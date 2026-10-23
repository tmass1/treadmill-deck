// Treadmill Deck site Worker. The static site in ./site (the project page at /, the web console at
// /console/) plus three private parts, each behind the deck key (the DECK_KEY secret, sent as the
// X-Deck-Key header or ?k=):
//
//   GET  /update/manifest.json, /update/<apk>   the app's update channel, from KV (upd:<name>);
//                                              release.sh puts them there. UPDATE_OPEN=1 lets it
//                                              through without the key (consoles older than 1.9.3).
//
// /diag/, the console's report box:
//
//   POST /diag/report   the console uploads a Diagnostics or motor-test report (text/plain,
//                       header X-Deck-Key, X-Deck-Kind: report|motor, X-Deck-Version)
//   GET  /diag/?k=KEY   a page listing the reports, newest first (for the Mac)
//   GET  /diag/latest?k=KEY, /diag/latest-<kind>?k=KEY (motor, belt, report), /diag/<id>?k=KEY   one report as text
//
//   POST /runs            the console uploads a finished run (JSON, header X-Deck-Key); kept for good
//   GET  /runs/pending?k=KEY   runs not yet in Apple Fitness, oldest first, shaped for the iPhone
//                              Shortcut (start as "October 22, 2026 at 9:30 PM", minutes, miles, kcal)
//   GET  /runs/<id>/logged?k=KEY   the Shortcut marks a run as logged
//   GET  /runs?k=KEY            every run (the history backup), newest first
//
// Reports live in KV (REPORTS) for 30 days. They hold what the console can see about its
// hardware and the iFit apps; nothing personal, but the key keeps them off the open web.

const TTL = 60 * 60 * 24 * 30;
const MAX = 512 * 1024;

export default {
  async fetch(req, env) {
    const url = new URL(req.url);
    if (url.pathname.startsWith('/update/')) return update(req, env, url);
    if (url.pathname === '/runs' || url.pathname.startsWith('/runs/')) return runs(req, env, url);
    if (url.pathname === '/console') return Response.redirect(url.origin + '/console/', 301);
    if (!url.pathname.startsWith('/diag')) return env.ASSETS.fetch(req);

    if (!keyed(req, env, url)) return new Response('Not found', { status: 404 });

    if (req.method === 'POST' && url.pathname === '/diag/report') {
      const text = await req.text();
      if (!text || text.length > MAX) return new Response('Too large', { status: 413 });
      const kind = (req.headers.get('x-deck-kind') || 'report').replace(/[^a-z]/g, '').slice(0, 12) || 'report';
      const version = (req.headers.get('x-deck-version') || '').slice(0, 20);
      const now = new Date();
      const id = now.toISOString().replace(/[:.]/g, '-') + '-' + kind;
      const meta = { kind, version, at: now.toISOString(), lines: text.split('\n').length };
      await env.REPORTS.put('r:' + id, text, { expirationTtl: TTL, metadata: meta });
      return Response.json({ ok: true, id });
    }

    if (req.method !== 'GET') return new Response('Method not allowed', { status: 405 });
    const rest = url.pathname.replace(/^\/diag\/?/, '');

    if (rest === 'latest' || /^latest-[a-z]+$/.test(rest)) {
      const want = rest === 'latest' ? null : rest.slice(7);
      const list = await newest(env, 50);
      const hit = list.find(k => !want || (k.metadata && k.metadata.kind === want));
      if (!hit) return new Response('No reports yet', { status: 404 });
      return text(await env.REPORTS.get(hit.name), hit);
    }
    if (rest) {
      const t = await env.REPORTS.getWithMetadata('r:' + rest);
      if (t.value == null) return new Response('Not found', { status: 404 });
      return text(t.value, { name: 'r:' + rest, metadata: t.metadata });
    }
    return page(await newest(env, 100), url.searchParams.get('k') || '');
  },
};

const keyed = (req, env, url) => {
  const key = req.headers.get('x-deck-key') || url.searchParams.get('k');
  return !!env.DECK_KEY && key === env.DECK_KEY;
};

// ---- the update channel, from KV ----
async function update(req, env, url) {
  if (!keyed(req, env, url) && env.UPDATE_OPEN !== '1') return new Response('Not found', { status: 404 });
  const name = url.pathname.slice('/update/'.length);
  if (!/^[\w.-]+$/.test(name)) return new Response('Not found', { status: 404 });
  const body = await env.REPORTS.get('upd:' + name, 'arrayBuffer');
  if (!body) return new Response('Not found', { status: 404 });
  const apk = name.endsWith('.apk');
  return new Response(body, { headers: {
    'content-type': apk ? 'application/vnd.android.package-archive' : 'application/json; charset=utf-8',
    'cache-control': apk ? 'private, max-age=31536000, immutable' : 'no-store',
  } });
}

// ---- runs: the console's history, and the hand-off to Apple Fitness ----
const runKey = id => 'run:' + String(id).padStart(15, '0');
const json = (o, status = 200) => new Response(JSON.stringify(o), { status, headers: { 'content-type': 'application/json', 'cache-control': 'no-store' } });

async function allRuns(env) {
  const keys = [];
  let cursor;
  do {
    const r = await env.REPORTS.list({ prefix: 'run:', cursor });
    keys.push(...r.keys);
    cursor = r.list_complete ? null : r.cursor;
  } while (cursor);
  return keys;
}

function forShortcut(run) {
  const startMs = run.at - run.secs * 1000;
  return {
    id: String(run.id),
    name: run.name,
    start: new Date(startMs).toLocaleString('en-US', { timeZone: 'America/New_York', dateStyle: 'long', timeStyle: 'short' }),
    startISO: new Date(startMs).toISOString(),
    minutes: Math.round(run.secs / 6) / 10,
    miles: Math.round(run.miles * 100) / 100,
    kcal: Math.round(run.kcal || 0),
    avgHr: run.hr || 0,
  };
}

async function runs(req, env, url) {
  if (!keyed(req, env, url)) return new Response('Not found', { status: 404 });
  const parts = url.pathname.split('/').filter(Boolean);   // ['runs', ...]

  if (req.method === 'POST' && parts.length === 1) {
    const body = await req.text();
    if (!body || body.length > MAX) return new Response('Too large', { status: 413 });
    let run;
    try { run = JSON.parse(body); } catch (_) { return new Response('Not JSON', { status: 400 }); }
    if (!run || !Number.isFinite(run.id) || !Number.isFinite(run.at) || !Number.isFinite(run.secs)) return new Response('Not a run', { status: 400 });
    const old = await env.REPORTS.getWithMetadata(runKey(run.id));
    const logged = !!(old.metadata && old.metadata.logged);
    await env.REPORTS.put(runKey(run.id), JSON.stringify(run), { metadata: { at: run.at, logged } });
    return json({ ok: true, id: run.id, logged });
  }
  if (parts[1] === 'pending' && parts.length === 2) {
    // KV's list lags a write by up to a minute, so check each candidate's own metadata too.
    const keys = (await allRuns(env)).filter(k => !(k.metadata && k.metadata.logged)).sort((a, b) => (a.name < b.name ? -1 : 1));
    const out = [];
    for (const k of keys.slice(0, 20)) {
      const v = await env.REPORTS.getWithMetadata(k.name, 'json');
      if (v.value && !(v.metadata && v.metadata.logged)) out.push(forShortcut(v.value));
    }
    return json(out);
  }
  if (parts.length === 3 && parts[2] === 'logged') {
    const k = runKey(parts[1]);
    const v = await env.REPORTS.getWithMetadata(k);
    if (v.value == null) return json({ ok: false, error: 'No such run' }, 404);
    await env.REPORTS.put(k, v.value, { metadata: { ...(v.metadata || {}), logged: true } });
    return json({ ok: true });
  }
  if (req.method === 'DELETE' && parts.length === 2) {
    await env.REPORTS.delete(runKey(parts[1]));
    return json({ ok: true });
  }
  if (parts.length === 1) {
    const keys = (await allRuns(env)).sort((a, b) => (a.name < b.name ? 1 : -1));
    const out = [];
    for (const k of keys) { const v = await env.REPORTS.get(k.name, 'json'); if (v) out.push({ ...v, logged: !!(k.metadata && k.metadata.logged) }); }
    return json(out);
  }
  return new Response('Not found', { status: 404 });
}

async function newest(env, n) {
  const keys = [];
  let cursor;
  do {
    const r = await env.REPORTS.list({ prefix: 'r:', cursor });
    keys.push(...r.keys);
    cursor = r.list_complete ? null : r.cursor;
  } while (cursor);
  return keys.sort((a, b) => (a.name < b.name ? 1 : -1)).slice(0, n);
}

function text(body, k) {
  const m = k.metadata || {};
  return new Response(body, {
    headers: {
      'content-type': 'text/plain; charset=utf-8',
      'cache-control': 'no-store',
      'x-deck-report': k.name.slice(2),
      'x-deck-kind': m.kind || '',
      'x-deck-version': m.version || '',
    },
  });
}

const esc = s => String(s).replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));

function page(list, key) {
  const label = { report: 'Diagnostics', motor: 'Motor link test' };
  const rows = list.map(k => {
    const m = k.metadata || {};
    const id = k.name.slice(2);
    const when = m.at ? new Date(m.at).toLocaleString('en-US', { timeZone: 'America/New_York', dateStyle: 'medium', timeStyle: 'short' }) : id;
    return `<li><a href="/diag/${esc(id)}?k=${esc(key)}"><b>${esc(label[m.kind] || m.kind || 'Report')}</b>`
      + `<span>${esc(when)}${m.version ? ' · C1250 Deck ' + esc(m.version) : ''} · ${m.lines || '?'} lines</span></a></li>`;
  }).join('');
  const html = `<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="robots" content="noindex"><title>Console reports</title>
<style>
:root{--bg:#f6f7f9;--fg:#0e1116;--muted:#5b6370;--card:#fff;--line:#e3e6ea;--accent:#1677d2}
@media (prefers-color-scheme:dark){:root{--bg:#0b0d10;--fg:#e8ebef;--muted:#98a1ad;--card:#15191e;--line:#262c33;--accent:#4aa8ff}}
body{margin:0;background:var(--bg);color:var(--fg);font:15px/1.45 -apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif}
main{max-width:720px;margin:0 auto;padding:32px 16px}
h1{font-size:22px;margin:0 0 4px}p{color:var(--muted);margin:0 0 20px}
ul{list-style:none;margin:0;padding:0;border:1px solid var(--line);border-radius:12px;background:var(--card);overflow:hidden}
li+li{border-top:1px solid var(--line)}
a{display:flex;justify-content:space-between;gap:16px;padding:14px 16px;color:inherit;text-decoration:none}
a:hover{background:var(--line)}a b{color:var(--accent);font-weight:600}span{color:var(--muted);text-align:right}
</style><main><h1>Console reports</h1><p>Sent by C1250 Deck on the treadmill. Kept 30 days.</p>
${rows ? `<ul>${rows}</ul>` : '<p>Nothing yet. Run Diagnostics on the console and it lands here.</p>'}</main>`;
  return new Response(html, { headers: { 'content-type': 'text/html; charset=utf-8', 'cache-control': 'no-store' } });
}
