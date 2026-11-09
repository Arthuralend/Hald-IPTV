// IPTV Pro Max — web server.
// Holds the playlist on the server, hides every provider URL from the browser
// (all stream addresses are AES-GCM encrypted and expire), and proxies HLS.
// No dependencies: Node 20+.
'use strict';
const http = require('http');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { Readable } = require('stream');

const PORT = +(process.env.PORT || 8090);
const PRESET_URL = (process.env.PRESET_URL || '').trim();          // http(s) URL or local file path
const SECRET = process.env.SECRET || crypto.randomBytes(32).toString('hex');
const SITE_PASSWORD = process.env.SITE_PASSWORD || '';              // empty = open site
const UPSTREAM_UA = process.env.USER_AGENT || 'VLC/3.0.21 LibVLC/3.0.21';
const DIRECT = process.env.DIRECT !== '0';                          // allow direct-from-provider playback
const REFRESH_HOURS = +(process.env.REFRESH_HOURS || 6);
const TOKEN_TTL = 4 * 3600 * 1000;                                  // proxied links live 4 h
const PUBLIC = path.join(__dirname, 'public');
const CACHE = path.join(__dirname, 'cache');
fs.mkdirSync(CACHE, { recursive: true });

const KEY = crypto.createHash('sha256').update(SECRET).digest();

// ───────────── encryption of upstream URLs ─────────────
function seal(obj) {
  const iv = crypto.randomBytes(12);
  const c = crypto.createCipheriv('aes-256-gcm', KEY, iv);
  const body = Buffer.concat([c.update(JSON.stringify(obj)), c.final()]);
  return Buffer.concat([iv, c.getAuthTag(), body]).toString('base64url');
}
function open(token) {
  try {
    const b = Buffer.from(token, 'base64url');
    const d = crypto.createDecipheriv('aes-256-gcm', KEY, b.subarray(0, 12));
    d.setAuthTag(b.subarray(12, 28));
    const o = JSON.parse(Buffer.concat([d.update(b.subarray(28)), d.final()]).toString());
    return o.e > Date.now() ? o : null;
  } catch { return null; }
}

// ───────────── playlist ─────────────
let channels = [];          // {id,name,group,days,url,logo}
let byId = new Map();
let loadedAt = 0;

function parseM3U(text) {
  const out = [];
  let attrs = {}, name = '', grp = null;
  for (const raw of text.replace(/\r/g, '').split('\n')) {
    const line = raw.trim();
    if (!line) continue;
    if (line.startsWith('#EXTINF')) {
      attrs = {};
      for (const m of line.matchAll(/([\w-]+)="([^"]*)"/g)) attrs[m[1].toLowerCase()] = m[2];
      let q = false, last = -1;
      for (let i = 0; i < line.length; i++) { if (line[i] === '"') q = !q; else if (line[i] === ',' && !q) last = i; }
      name = last >= 0 ? line.slice(last + 1).trim() : '';
      grp = null;
    } else if (line.startsWith('#EXTGRP:')) grp = line.slice(8).trim();
    else if (line.startsWith('#')) continue;
    else {
      const days = parseInt(attrs['catchup-days'] || attrs['tvg-rec'] || attrs['timeshift'] || '0', 10) || 0;
      out.push({
        id: crypto.createHash('sha1').update(line).digest('hex').slice(0, 12),
        name: name || attrs['tvg-name'] || 'Channel',
        group: attrs['group-title'] || grp || '',
        days,
        tvgId: attrs['tvg-id'] || '',
        logo: attrs['tvg-logo'] || '',
        url: line,
      });
      attrs = {}; name = ''; grp = null;
    }
  }
  return out;
}

async function upstream(url, init = {}) {
  return fetch(url, { ...init, redirect: 'follow', headers: { 'User-Agent': UPSTREAM_UA, ...(init.headers || {}) } });
}

async function loadPlaylist() {
  if (!PRESET_URL) { console.warn('PRESET_URL is not set'); return; }
  try {
    let text;
    if (/^https?:/i.test(PRESET_URL)) {
      const r = await upstream(PRESET_URL);
      if (!r.ok) throw new Error('HTTP ' + r.status);
      text = await r.text();
    } else text = fs.readFileSync(PRESET_URL, 'utf8');
    const list = parseM3U(text);
    if (!list.length) throw new Error('no channels');
    fs.writeFileSync(path.join(CACHE, 'playlist.m3u'), text);
    apply(list);
    console.log(`playlist: ${list.length} channels`);
  } catch (e) {
    console.error('playlist load failed:', e.message);
    const cached = path.join(CACHE, 'playlist.m3u');
    if (!channels.length && fs.existsSync(cached)) apply(parseM3U(fs.readFileSync(cached, 'utf8')));
  }
  loadedAt = Date.now();
}
function apply(list) {
  channels = list;
  byId = new Map(list.map(c => [c.id, c]));
  logos.attach();
}

// ───────────── logos from iptv-org (public) ─────────────
const logos = (() => {
  const nameIndex = new Map(); const idIndex = new Map(); const urls = new Map();
  let ready = false;
  const TR = { а:'a',б:'b',в:'v',г:'g',д:'d',е:'e',ё:'e',ж:'zh',з:'z',и:'i',й:'y',к:'k',л:'l',м:'m',н:'n',о:'o',п:'p',р:'r',с:'s',т:'t',у:'u',ф:'f',х:'kh',ц:'ts',ч:'ch',ш:'sh',щ:'shch',ъ:'',ы:'y',ь:'',э:'e',ю:'yu',я:'ya' };
  const norm = s => s.toLowerCase().replace(/\[[^\]]*\]|\([^)]*\)/g, ' ')
    .replace(/(^|[^\p{L}\p{N}])(hd|fhd|uhd|4k|sd|hevc|orig|original|int|international|\+\d+|-\d+|50|uk|us)(?=$|[^\p{L}\p{N}])/gu, ' ')
    .replace(/[^\p{L}\p{N}]/gu, '');
  const tr = s => [...s].map(ch => TR[ch] ?? ch).join('');
  async function get(file) {
    const local = path.join(CACHE, file);
    try { if (Date.now() - fs.statSync(local).mtimeMs < 7 * 864e5) return JSON.parse(fs.readFileSync(local)); } catch {}
    for (const m of ['https://iptv-org.github.io/api/', 'https://raw.githubusercontent.com/iptv-org/api/gh-pages/']) {
      try { const r = await fetch(m + file); if (r.ok) { const t = await r.text(); fs.writeFileSync(local, t); return JSON.parse(t); } } catch {}
    }
    try { return JSON.parse(fs.readFileSync(local)); } catch { return null; }
  }
  async function load() {
    const [ch, lg] = await Promise.all([get('channels.json'), get('logos.json')]);
    if (!ch || !lg) return;
    for (const l of lg) { if (l.in_use === false) continue; if (!l.feed || !urls.has(l.channel)) urls.set(l.channel, l.url); }
    for (const c of ch) {
      if (!urls.has(c.id)) continue;
      idIndex.set(c.id.toLowerCase(), c.id);
      for (const n of [c.name, ...(c.alt_names || [])]) {
        const k = norm(n); if (!k) continue;
        for (const key of new Set([k, tr(k)])) { if (!nameIndex.has(key)) nameIndex.set(key, []); nameIndex.get(key).push(c.id); }
      }
    }
    ready = true; attach();
  }
  function find(c) {
    if (c.tvgId) { const id = idIndex.get(c.tvgId.split('@')[0].toLowerCase()); if (id) return urls.get(id); }
    const k = norm(c.name); if (!k) return null;
    const ids = nameIndex.get(k) || nameIndex.get(tr(k)); if (!ids) return null;
    const cyr = /[Ѐ-ӿ]/.test(c.name);
    const pref = cyr ? ['.ru', '.am', '.by', '.kz', '.ua'] : ['.am', '.ru', '.us', '.uk'];
    for (const p of pref) { const id = ids.find(i => i.toLowerCase().endsWith(p)); if (id) return urls.get(id); }
    return urls.get(ids[0]);
  }
  function attach() { if (!ready) return; for (const c of channels) if (!c.logo) c.logo = find(c) || ''; }
  return { load, attach };
})();

// ───────────── HLS proxy (fast start) ─────────────
// 1) remembers where the provider redirects a channel, so the next start skips the redirect;
// 2) when a media playlist is served, the newest segments are pre-fetched into memory,
//    so by the time the player asks for them they are already on the server;
// 3) playlists are rewritten with encrypted, expiring links (encryption costs microseconds).
const redirects = new Map();          // original url -> { url, t }
const REDIRECT_TTL = 10 * 60e3;
const segCache = new Map();           // upstream url -> { p: Promise<{buf,ct}>, t, size }
const SEG_TTL = 90e3;
const SEG_MAX_BYTES = 256 * 1024 * 1024;
const PREFETCH = 3;
let segBytes = 0;

function isPlaylistUrl(u) { try { return /\.m3u8?$/i.test(new URL(u).pathname); } catch { return false; } }

function cacheSweep() {
  const now = Date.now();
  for (const [k, v] of segCache) {
    if (now - v.t > SEG_TTL || segBytes > SEG_MAX_BYTES) { segCache.delete(k); segBytes -= v.size; }
    else if (segBytes <= SEG_MAX_BYTES) break;   // Map keeps insertion order: oldest first
  }
}
setInterval(cacheSweep, 15e3).unref();

function prefetch(url) {
  if (segCache.has(url)) return segCache.get(url).p;
  const entry = { t: Date.now(), size: 0, p: null };
  entry.p = upstream(url).then(async r => {
    if (!r.ok) throw new Error('HTTP ' + r.status);
    const buf = Buffer.from(await r.arrayBuffer());
    entry.size = buf.length; segBytes += buf.length;
    return { buf, ct: r.headers.get('content-type') || 'video/mp2t' };
  });
  entry.p.catch(() => { segCache.delete(url); });
  segCache.set(url, entry);
  cacheSweep();
  return entry.p;
}

async function fetchPlaylist(url) {
  const known = redirects.get(url);
  if (known && Date.now() - known.t < REDIRECT_TTL) {
    try {
      const r = await upstream(known.url);
      if (r.ok) return r;
    } catch {}
    redirects.delete(url);
  }
  const r = await upstream(url);
  if (r.ok && r.url && r.url !== url) redirects.set(url, { url: r.url, t: Date.now() });
  return r;
}

function rewrite(text, base) {
  const exp = Date.now() + TOKEN_TTL;
  const segments = [];
  const wrap = u => {
    const abs = new URL(u, base).toString();
    return '/x/' + seal({ u: abs, e: exp });
  };
  const out = text.split('\n').map(line => {
    const l = line.trim();
    if (!l) return line;
    if (l.startsWith('#')) return line.replace(/URI="([^"]+)"/g, (_, u) => `URI="${wrap(u)}"`);
    const abs = new URL(l, base).toString();
    if (!isPlaylistUrl(abs)) segments.push(abs);
    return wrap(l);
  }).join('\n');
  // newest segments are what a live player starts with
  for (const u of segments.slice(-PREFETCH)) prefetch(u).catch(() => {});
  return out;
}

async function sendPlaylist(res, r) {
  const body = rewrite(await r.text(), r.url);
  res.writeHead(200, { 'Content-Type': 'application/vnd.apple.mpegurl', 'Cache-Control': 'no-store' });
  res.end(body);
}

async function proxy(res, url, { playlist = false } = {}) {
  // segment already pre-fetched (or being fetched) -> serve from memory
  const cached = segCache.get(url);
  if (cached) {
    try {
      const { buf, ct } = await cached.p;
      res.writeHead(200, { 'Content-Type': ct, 'Content-Length': buf.length, 'Cache-Control': 'no-store' });
      return res.end(buf);
    } catch { /* fall through to a direct fetch */ }
  }
  let r;
  try { r = playlist || isPlaylistUrl(url) ? await fetchPlaylist(url) : await upstream(url); }
  catch { return send(res, 502, 'upstream error'); }
  if (!r.ok) return send(res, r.status, 'upstream ' + r.status);
  const ct = r.headers.get('content-type') || '';
  if (/mpegurl/i.test(ct) || isPlaylistUrl(r.url)) return sendPlaylist(res, r);
  res.writeHead(200, {
    'Content-Type': ct || 'video/mp2t',
    ...(r.headers.get('content-length') ? { 'Content-Length': r.headers.get('content-length') } : {}),
    'Cache-Control': 'no-store',
  });
  Readable.fromWeb(r.body).on('error', () => res.destroy()).pipe(res);
}

function archiveUrl(c, start) {
  const now = Math.floor(Date.now() / 1000);
  return c.url + (c.url.includes('?') ? '&' : '?') + `utc=${start}&lutc=${now}`;
}

// ───────────── HTTP ─────────────
const MIME = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8', '.png': 'image/png', '.svg': 'image/svg+xml', '.json': 'application/json' };

function send(res, code, body, type = 'text/plain; charset=utf-8') {
  res.writeHead(code, { 'Content-Type': type });
  res.end(body);
}

function authorized(req) {
  if (!SITE_PASSWORD) return true;
  const cookie = (req.headers.cookie || '').split(/;\s*/).find(s => s.startsWith('ipm='));
  if (!cookie) return false;
  const want = crypto.createHmac('sha256', KEY).update(SITE_PASSWORD).digest('hex');
  return cookie.slice(4) === want;
}

const server = http.createServer(async (req, res) => {
  const u = new URL(req.url, 'http://x');
  const p = u.pathname;
  res.setHeader('X-Content-Type-Options', 'nosniff');
  res.setHeader('Referrer-Policy', 'no-referrer');

  if (p === '/api/login' && req.method === 'POST') {
    let body = ''; req.on('data', d => { body += d; if (body.length > 1e4) req.destroy(); });
    req.on('end', () => {
      let pw = ''; try { pw = JSON.parse(body).password || ''; } catch {}
      if (!SITE_PASSWORD || pw === SITE_PASSWORD) {
        const v = crypto.createHmac('sha256', KEY).update(SITE_PASSWORD).digest('hex');
        res.setHeader('Set-Cookie', `ipm=${v}; Path=/; HttpOnly; SameSite=Lax; Max-Age=31536000`);
        return send(res, 200, '{"ok":true}', 'application/json');
      }
      send(res, 401, '{"ok":false}', 'application/json');
    });
    return;
  }

  if (p.startsWith('/api/') || p.startsWith('/play/') || p.startsWith('/x/')) {
    if (!authorized(req)) return send(res, 401, '{"auth":true}', 'application/json');
  }

  if (p === '/api/channels') {
    if (Date.now() - loadedAt > REFRESH_HOURS * 3600e3) loadPlaylist();
    const list = channels.map(({ id, name, group, days, logo }) => ({ id, name, group, days, logo }));
    res.writeHead(200, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
    return res.end(JSON.stringify({ channels: list, password: !!SITE_PASSWORD, direct: DIRECT }));
  }

  let m;
  if ((m = p.match(/^\/play\/([0-9a-f]{12})\.m3u8$/))) {
    const c = byId.get(m[1]);
    if (!c) return send(res, 404, 'not found');
    const start = parseInt(u.searchParams.get('start') || '0', 10);
    const url = start > 0 && c.days > 0 ? archiveUrl(c, start) : c.url;
    if (u.searchParams.get('direct') === '1' && DIRECT) {
      // direct mode: the player goes straight to the provider (no video through this server)
      const known = redirects.get(url);
      res.writeHead(302, { Location: known && Date.now() - known.t < REDIRECT_TTL ? known.url : url, 'Cache-Control': 'no-store' });
      return res.end();
    }
    return proxy(res, url, { playlist: true });
  }

  if ((m = p.match(/^\/x\/([\w-]+)$/))) {
    const o = open(m[1]);
    if (!o) return send(res, 410, 'expired');
    return proxy(res, o.u);
  }

  // static files
  let file = path.normalize(path.join(PUBLIC, p === '/' ? 'index.html' : p));
  if (!file.startsWith(PUBLIC)) return send(res, 403, 'forbidden');
  fs.readFile(file, (err, data) => {
    if (err) return send(res, 404, 'not found');
    res.writeHead(200, { 'Content-Type': MIME[path.extname(file)] || 'application/octet-stream', 'Cache-Control': 'no-cache' });
    res.end(data);
  });
});

loadPlaylist().then(() => logos.load().catch(() => {}));
setInterval(loadPlaylist, REFRESH_HOURS * 3600e3);
server.listen(PORT, () => console.log(`IPTV Pro Max web on :${PORT}`));
