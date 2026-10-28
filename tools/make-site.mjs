// Builds the web version of the console, site/console/index.html, from c1250-deck.html, with the
// Home photos beside it. The project page (site/index.html) is hand-written.
// Run by `wrangler deploy` (see wrangler.jsonc).
import { readFileSync, writeFileSync, mkdirSync, readdirSync, copyFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const out = join(root, 'site', 'console');
const src = readFileSync(join(root, 'c1250-deck.html'), 'utf8');
const m = src.match(/<div class="app"[\s>]/);
if (!m) throw new Error('make-site: no <div class="app"> container in c1250-deck.html');
const head = '<!doctype html><html lang="en"><head><meta charset="utf-8">'
  + '<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">'
  + '<link rel="icon" href="/favicon.svg?v=2" type="image/svg+xml"><title>C1250 Deck · demo</title>'
  + '<style>:root{color-scheme:light}body{margin:0;font:14px system-ui}[hidden]{display:none!important}img{max-width:100%}</style>';
mkdirSync(join(out, 'bg'), { recursive: true });
writeFileSync(join(out, 'index.html'), head + src.slice(0, m.index) + '</head><body>' + src.slice(m.index) + '</body></html>');
// Home background photos (see bg/CREDITS.md).
for (const f of readdirSync(join(root, 'bg')).filter((f) => f.endsWith('.webp'))) copyFileSync(join(root, 'bg', f), join(out, 'bg', f));
console.log('site/console/index.html written');
