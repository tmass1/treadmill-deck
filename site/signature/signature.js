/* Tommy Massaro Signature bar · v1.0.4 · no dependencies or tracking. */
const assets = new URL('./assets/', import.meta.url);
const products = [
 ['tidy-ui', 'Tidy UI', 'The design system', 'https://tidy-ui.tommymassaro.com/#overview'],
 ['fritter', 'Fritter', 'A little order for your day', 'https://fritter-app.tommymassaro.com/'],
 ['tidy', 'Tidy App', 'Your words, a little clearer', 'https://tidy-app.tommymassaro.com/'],
 ['mark', 'Mark', 'Show your agent what you mean', 'https://mark.tommymassaro.com/'],
 ['burn', 'Burn', 'Know how long your AI plan lasts', 'https://burn.tommymassaro.com/'],
];
const external = 'target="_blank" rel="noopener noreferrer"';
class TommySignature extends HTMLElement {
 static observedAttributes = ['appearance', 'current-product'];
 constructor() {
  super();
  this.attachShadow({mode:'open'});
  this.shadowRoot.innerHTML = `<style>
  @font-face{font-family:SignatureInter;src:url('${new URL('inter.woff2',assets)}') format('woff2');font-weight:100 900;font-display:swap}
  :host{--bg:#fdfcff;--ink:#292731;--muted:#746e7e;--rule:#e5e1eb;--soft:#f3f0f8;--focus:#6a50bf;display:block;position:relative;z-index:30;font:400 12px/1.4 SignatureInter,Inter,system-ui,sans-serif;color:var(--ink);color-scheme:light}
  :host([data-appearance=dark]){--bg:#1b1b21;--ink:#f4f1f9;--muted:#b0aab9;--rule:#35333e;--soft:#28262f;--focus:#b7a4f1;color-scheme:dark}
  *{box-sizing:border-box}a{color:inherit;text-decoration:none}a:focus-visible,summary:focus-visible{outline:2px solid var(--focus);outline-offset:3px;border-radius:5px}a:hover{color:var(--focus)}
  footer{position:relative;min-height:calc(52px + env(safe-area-inset-bottom,0px));padding:0 32px env(safe-area-inset-bottom,0px);background:var(--bg);border-top:1px solid var(--rule);display:flex;align-items:center;justify-content:space-between;gap:16px}
  .credit,.right{display:flex;align-items:center;gap:12px}.credit>span{color:var(--muted)}.wordmark{display:inline-flex;align-items:baseline;gap:2px;font-size:17px;line-height:1;letter-spacing:-.65px;white-space:nowrap}.wordmark strong{font-weight:750}.wordmark span{font-weight:400}.wordmark sup{color:#f47778;font-size:12px;position:relative;top:-2px;margin-left:2px}
  .right{gap:20px}.rule{height:16px;width:1px;background:var(--rule)}.portfolio{display:flex;align-items:center;gap:10px;min-height:44px;font-size:11px}.arrow{font-size:16px;line-height:1}
  details{position:relative}summary{min-height:44px;cursor:pointer;list-style:none;display:flex;align-items:center;gap:9px;font-size:11px;user-select:none}summary::-webkit-details-marker{display:none}summary svg{width:12px;height:12px;transition:transform .18s ease}details[open] summary svg{transform:rotate(180deg)}
  .menu{position:absolute;bottom:calc(100% + 9px);right:0;width:294px;max-width:calc(100vw - 24px);padding:8px;background:var(--bg);border:1px solid var(--rule);border-radius:13px;box-shadow:0 14px 45px #09051b25,0 2px 5px #09051b08;animation:appear .18s ease-out;max-height:calc(100dvh - 84px - env(safe-area-inset-bottom,0px));overflow:auto}
  .caption{display:flex;justify-content:space-between;gap:8px;padding:9px 10px 12px;color:var(--muted);font-size:10px}.product{display:flex;align-items:center;gap:11px;padding:9px 10px;border-radius:8px;min-height:55px}.product:hover{background:var(--soft);color:var(--ink)}.product>span:nth-child(2){flex:1;min-width:0}.product b{font-size:13px;font-weight:550;display:block}.product small{display:block;font-size:11px;color:var(--muted);margin-top:1px}.product>.arrow{color:var(--muted)}
  .icon{display:block;width:32px;height:32px;flex-shrink:0;position:relative;overflow:hidden;border-radius:7px}.icon img{position:absolute;left:50%;top:50%;width:100%;height:100%;max-width:none;transform:translate(-50%,-50%);object-fit:fill}.icon-burn img,.icon-tidy img{width:106.667%;height:106.667%}.icon-fritter img{width:124.272%;height:124.272%}
  @keyframes appear{from{opacity:0;transform:translateY(5px)}to{opacity:1;transform:translateY(0)}}
  @media(max-width:600px){footer{padding-inline:16px;gap:10px}.credit>span,.rule,.portfolio{display:none}.wordmark{font-size:15px}.right{gap:0}.menu{right:0}summary{font-size:11px}}
  @media(prefers-reduced-motion:reduce){*,*::before,*::after{animation:none!important;transition:none!important}}
  </style><footer aria-label="Designed by Tommy Massaro"><div class="credit"><span>Designed &amp; built by</span><a class="wordmark" href="https://tommymassaro.com/" ${external} aria-label="Tommy Massaro — portfolio (opens in a new tab)"><strong>tommy</strong><span>massaro</span><sup aria-hidden="true">*</sup></a></div><div class="right"><details><summary>More by Tommy <svg viewBox="0 0 12 12" fill="none" aria-hidden="true"><path d="m3 7 3-3 3 3" stroke="currentColor" stroke-linecap="round" stroke-linejoin="round"/></svg></summary><nav class="menu" aria-label="More products by Tommy Massaro"><div class="caption"><span>More by Tommy Massaro</span><span class="product-count">5 products</span></div><div class="products"></div></nav></details><span class="rule" aria-hidden="true"></span><a class="portfolio" href="https://tommymassaro.com/" ${external}>View my work <span class="arrow" aria-hidden="true">↗</span></a></div></footer>`;
  this.details=this.shadowRoot.querySelector('details');
  this.closeOutside=e=>{if(!e.composedPath().includes(this))this.details.open=false};
  this.escape=e=>{if(e.key==='Escape'&&this.details.open){e.preventDefault();this.details.open=false;this.shadowRoot.querySelector('summary').focus()}};
  this.syncTheme=()=>{const root=document.documentElement;const appearance=this.getAttribute('appearance')||'auto';this.dataset.appearance=appearance==='auto'?(root.dataset.tidyTheme||root.dataset.theme||(root.classList.contains('dark')?'dark':this.scheme.matches?'dark':'light')):appearance};
 }
 renderProducts(){
  const current=this.getAttribute('current-product')||products.find(product=>new URL(product[3]).hostname===location.hostname)?.[0];
  const visible=products.filter(product=>product[0]!==current);
  if(current==='tidy-ui')visible.sort((a,b)=>Number(b[0]==='tidy')-Number(a[0]==='tidy'));
  this.shadowRoot.querySelector('.product-count').textContent=`${visible.length} products`;
  this.shadowRoot.querySelector('.products').innerHTML=visible.map(([id,name,description,url])=>`<a class="product" href="${url}" ${external} aria-label="${name} — ${description} (opens in a new tab)"><span class="icon icon-${id}" aria-hidden="true"><img src="${new URL(`icon-${id}.${(id==='burn'||id==='tidy')?'svg':'png'}`,assets)}" alt="" width="32" height="32"></span><span><b>${name}</b><small>${description}</small></span><span class="arrow" aria-hidden="true">↗</span></a>`).join('');
 }
 connectedCallback(){
  this.renderProducts();
  this.scheme=matchMedia('(prefers-color-scheme: dark)');
  this.observer=new MutationObserver(this.syncTheme);
  this.observer.observe(document.documentElement,{attributes:true,attributeFilter:['class','data-theme','data-tidy-theme']});
  this.scheme.addEventListener('change',this.syncTheme);this.syncTheme();
  document.addEventListener('pointerdown',this.closeOutside);document.addEventListener('keydown',this.escape);
 }
 disconnectedCallback(){this.observer?.disconnect();this.scheme?.removeEventListener('change',this.syncTheme);document.removeEventListener('pointerdown',this.closeOutside);document.removeEventListener('keydown',this.escape)}
 attributeChangedCallback(name){if(this.isConnected){if(name==='current-product')this.renderProducts();else if(this.scheme)this.syncTheme()}}
}
if(!customElements.get('tommy-signature'))customElements.define('tommy-signature',TommySignature);
