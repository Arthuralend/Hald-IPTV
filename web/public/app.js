'use strict';
(() => {
  // ───────── i18n ─────────
  const RU = /^(ru|be|uk|kk)/i.test(navigator.language || '');
  const EN = {
    'Все каналы': 'All Channels', 'Избранное': 'Favorites', 'Недавние': 'Recent', 'Группы': 'Groups',
    'Без группы': 'Ungrouped', 'Поиск канала': 'Search channels', 'каналов': 'channels', 'Нет каналов': 'No channels',
    'Выберите канал': 'Select a channel', 'Подключение…': 'Connecting…', 'Ошибка воспроизведения': 'Playback error',
    'ЭФИР': 'LIVE', 'Архив недоступен для этого канала': 'Archive is not available for this channel',
    'Очистить недавние': 'Clear recent', 'Пароль': 'Password', 'Войти': 'Sign in', 'Неверный пароль': 'Wrong password',
    'архив': 'archive', 'дн.': 'd', 'Нажмите, чтобы включить звук': 'Tap to unmute',
  };
  const T = s => RU ? s : (EN[s] || s);
  document.documentElement.lang = RU ? 'ru' : 'en';

  // ───────── state ─────────
  const $ = id => document.getElementById(id);
  const store = {
    get(k, d) { try { return JSON.parse(localStorage.getItem('ipm.' + k)) ?? d; } catch { return d; } },
    set(k, v) { try { localStorage.setItem('ipm.' + k, JSON.stringify(v)); } catch {} },
  };
  let channels = [];
  let byId = new Map();
  let favorites = new Set(store.get('fav', []));
  let recents = store.get('recent', []);
  let section = { kind: 'all' };
  let grid = store.get('grid', false);
  let current = null;
  let archiveStart = null;   // unix seconds; null = live
  let hls = null;
  const video = $('video');

  // ───────── helpers ─────────
  const PALETTE = ['#F2800A', '#D96200', '#5C9EBF', '#6B6E75'];
  function colorFor(name) { let h = 0; for (const c of name) h = (h * 31 + c.charCodeAt(0)) & 0xffff; return PALETTE[h % PALETTE.length]; }
  function initials(name) {
    const words = name.replace(/\([^)]*\)|\[[^\]]*\]/g, ' ').split(/\s+/)
      .filter(w => /^[\p{L}\p{N}]/u.test(w) && !/^(u?hd|fhd|4k|sd|orig|\+?\d{2,})$/i.test(w));
    return words.slice(0, 2).map(w => w[0].toUpperCase()).join('') || '•';
  }
  function logoEl(c, cls = 'logo') {
    const d = document.createElement('div');
    d.className = cls;
    if (c.logo) {
      d.classList.add('has');
      const img = new Image();
      img.referrerPolicy = 'no-referrer';
      img.onload = () => { d.style.backgroundImage = `url("${c.logo}")`; d.textContent = ''; };
      img.onerror = () => { d.classList.remove('has'); d.style.background = colorFor(c.name); d.textContent = initials(c.name); };
      img.src = c.logo;
      d.style.background = colorFor(c.name); d.textContent = initials(c.name);
    } else { d.style.background = colorFor(c.name); d.textContent = initials(c.name); }
    return d;
  }
  let toastTimer;
  function toast(text) {
    $('toast').textContent = text; $('toast').classList.remove('hidden');
    clearTimeout(toastTimer); toastTimer = setTimeout(() => $('toast').classList.add('hidden'), 3000);
  }
  function status(text) { $('status').textContent = text || ''; $('status').classList.toggle('hidden', !text); }

  // ───────── loading ─────────
  async function load() {
    const r = await fetch('/api/channels', { cache: 'no-store' });
    if (r.status === 401) return showLogin();
    const data = await r.json();
    channels = data.channels.map(c => ({ ...c, group: c.group || T('Без группы') }));
    byId = new Map(channels.map(c => [c.id, c]));
    renderSections(); renderList();
    const last = recents[0] && byId.get(recents[0]);
    if (last) select(last, false);
  }

  function showLogin() {
    $('login').classList.remove('hidden');
    $('password').placeholder = T('Пароль');
    $('loginBtn').textContent = T('Войти');
    $('loginForm').onsubmit = async e => {
      e.preventDefault();
      const r = await fetch('/api/login', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ password: $('password').value }) });
      if (r.ok) { $('login').classList.add('hidden'); load(); }
      else { $('loginErr').textContent = T('Неверный пароль'); $('loginErr').classList.remove('hidden'); }
    };
  }

  // ───────── sidebar ─────────
  function renderSections() {
    const nav = $('sections'); nav.innerHTML = '';
    const add = (kind, name, ico, count, group, main) => {
      const d = document.createElement('div');
      d.className = 'item' + (main ? ' main' : '') + (section.kind === kind && section.group === group ? ' active' : '');
      d.innerHTML = `<span class="ico">${ico}</span><span class="name"></span><span class="count">${count || ''}</span>`;
      d.querySelector('.name').textContent = name;
      d.onclick = () => { section = { kind, group }; $('sidebar').classList.remove('open'); renderSections(); renderList(); };
      nav.appendChild(d);
    };
    add('all', T('Все каналы'), '▣', channels.length, undefined, true);
    add('fav', T('Избранное'), '★', channels.filter(c => favorites.has(c.id)).length, undefined, true);
    add('recent', T('Недавние'), '◷', recents.filter(id => byId.has(id)).length, undefined, true);
    const sep = document.createElement('div'); sep.className = 'sep'; sep.textContent = T('Группы'); nav.appendChild(sep);
    const groups = new Map();
    for (const c of channels) groups.set(c.group, (groups.get(c.group) || 0) + 1);
    for (const [g, n] of groups) add('group', g, '▸', n, g, false);
  }

  function sectionChannels() {
    switch (section.kind) {
      case 'fav': return channels.filter(c => favorites.has(c.id));
      case 'recent': return recents.map(id => byId.get(id)).filter(Boolean);
      case 'group': return channels.filter(c => c.group === section.group);
      default: return channels;
    }
  }
  function sectionTitle() {
    return { all: T('Все каналы'), fav: T('Избранное'), recent: T('Недавние') }[section.kind] || section.group;
  }

  // ───────── channel list (virtualized for big lists) ─────────
  let visible = [];
  function renderList() {
    const q = $('search').value.trim().toLowerCase();
    visible = sectionChannels().filter(c => !q || c.name.toLowerCase().includes(q));
    $('sectionTitle').textContent = sectionTitle();
    $('sectionCount').textContent = `${visible.length} ${T('каналов')}`;
    $('clearRecentsBtn').classList.toggle('hidden', !(section.kind === 'recent' && recents.length));
    $('listModeBtn').classList.toggle('on', !grid);
    $('gridModeBtn').classList.toggle('on', grid);
    const box = $('channels');
    box.className = grid ? 'grid' : '';
    box.innerHTML = '';
    if (!visible.length) { box.innerHTML = `<div class="empty">${T('Нет каналов')}</div>`; return; }
    renderChunk(0);
  }
  const CHUNK = 150;
  function renderChunk(from) {
    const box = $('channels');
    const frag = document.createDocumentFragment();
    for (const c of visible.slice(from, from + CHUNK)) frag.appendChild(row(c));
    box.appendChild(frag);
    if (from + CHUNK < visible.length) {
      const sentinel = document.createElement('div');
      sentinel.style.height = '1px';
      box.appendChild(sentinel);
      new IntersectionObserver((ents, obs) => {
        if (ents[0].isIntersecting) { obs.disconnect(); sentinel.remove(); renderChunk(from + CHUNK); }
      }, { root: box, rootMargin: '400px' }).observe(sentinel);
    }
  }
  function row(c) {
    const d = document.createElement('div');
    d.className = 'ch' + (current && current.id === c.id ? ' current' : '');
    d.dataset.id = c.id;
    d.appendChild(logoEl(c));
    const nm = document.createElement('div'); nm.className = 'nm'; nm.textContent = c.name; d.appendChild(nm);
    if (favorites.has(c.id)) { const s = document.createElement('span'); s.className = 'star'; s.textContent = '★'; d.appendChild(s); }
    if (c.days > 0) { const a = document.createElement('span'); a.className = 'arch'; a.title = T('архив'); a.textContent = `⟲${c.days}${T('дн.')}`; d.appendChild(a); }
    d.onclick = () => select(c, true);
    return d;
  }
  function markCurrent() {
    document.querySelectorAll('#channels .ch.current').forEach(e => e.classList.remove('current'));
    if (current) document.querySelector(`#channels .ch[data-id="${current.id}"]`)?.classList.add('current');
  }

  // ───────── playback ─────────
  function select(c, autoplay) {
    current = c;
    archiveStart = null;
    recents = [c.id, ...recents.filter(id => id !== c.id)].slice(0, 50);
    store.set('recent', recents);
    $('placeholder').classList.add('hidden');
    const cl = $('curLogo'); cl.replaceWith(Object.assign(logoEl(c), { id: 'curLogo' }));
    $('curName').textContent = c.name;
    $('curGroup').textContent = c.group;
    updateFav(); markCurrent(); showControls();
    load_(autoplay);
    if (section.kind === 'recent') renderSections();
  }

  function streamUrl() {
    return `/play/${current.id}.m3u8` + (archiveStart ? `?start=${archiveStart}` : '');
  }

  function load_(autoplay = true) {
    if (!current) return;
    status(T('Подключение…'));
    const src = streamUrl();
    if (hls) { hls.destroy(); hls = null; }
    if (window.Hls && Hls.isSupported()) {
      hls = new Hls({ liveSyncDurationCount: 3, maxBufferLength: 30, enableWorker: true });
      hls.loadSource(src);
      hls.attachMedia(video);
      hls.on(Hls.Events.MANIFEST_PARSED, () => { if (autoplay) play(); });
      hls.on(Hls.Events.ERROR, (_, d) => {
        if (!d.fatal) return;
        if (d.type === Hls.ErrorTypes.NETWORK_ERROR) { setTimeout(() => hls && hls.startLoad(), 1500); }
        else if (d.type === Hls.ErrorTypes.MEDIA_ERROR) hls.recoverMediaError();
        else status(T('Ошибка воспроизведения'));
      });
    } else {
      video.src = src;                       // Safari / iOS play HLS natively
      if (autoplay) play();
    }
  }

  async function play() {
    try { await video.play(); }
    catch {
      video.muted = true; $('muteBtn').textContent = '🔇';
      try { await video.play(); toast(T('Нажмите, чтобы включить звук')); } catch {}
    }
  }

  video.addEventListener('playing', () => { status(null); $('playBtn').textContent = '⏸'; });
  video.addEventListener('pause', () => { $('playBtn').textContent = '▶'; });
  video.addEventListener('waiting', () => status(T('Подключение…')));
  video.addEventListener('error', () => status(T('Ошибка воспроизведения')));

  function step(d) {
    const list = visible.length ? visible : channels;
    if (!list.length) return;
    let i = current ? list.findIndex(c => c.id === current.id) : -1;
    if (i < 0) i = d > 0 ? -1 : 0;
    select(list[(i + d + list.length) % list.length], true);
  }

  // archive / rewind
  const nowSec = () => Math.floor(Date.now() / 1000);
  function positionSec() { return archiveStart ? archiveStart + Math.floor(video.currentTime || 0) : nowSec(); }
  function seekTo(target) {
    if (!current) return;
    if (!current.days) {
      // live window of the stream itself (some HLS streams keep a few minutes)
      if (video.seekable.length) {
        const s = video.seekable.start(0), e = video.seekable.end(video.seekable.length - 1);
        const t = video.currentTime + (target - positionSec());
        if (t >= s) { video.currentTime = Math.min(t, e); return; }
      }
      return toast(T('Архив недоступен для этого канала'));
    }
    const limit = nowSec() - current.days * 86400;
    if (target >= nowSec() - 15) { archiveStart = null; }
    else archiveStart = Math.max(target, limit);
    load_(true);
  }
  const rewind = s => seekTo(positionSec() - s);
  const forward = s => { if (archiveStart || video.seekable.length) seekTo(positionSec() + s); };
  function goLive() { if (current) { archiveStart = null; load_(true); } }

  function updateFav() {
    const on = current && favorites.has(current.id);
    $('favBtn').textContent = on ? '★' : '☆';
    $('favBtn').classList.toggle('on', !!on);
  }
  function toggleFav() {
    if (!current) return;
    favorites.has(current.id) ? favorites.delete(current.id) : favorites.add(current.id);
    store.set('fav', [...favorites]);
    updateFav(); renderSections();
    if (section.kind === 'fav') renderList(); else {
      const el = document.querySelector(`#channels .ch[data-id="${current.id}"]`);
      if (el) el.replaceWith(row(current));
    }
  }

  // full screen: only the video
  function toggleFull() {
    const stage = $('stage');
    if (document.fullscreenElement) return document.exitFullscreen();
    if (stage.requestFullscreen) stage.requestFullscreen().catch(() => {});
    else if (video.webkitEnterFullscreen) video.webkitEnterFullscreen();   // iPhone
  }
  document.addEventListener('fullscreenchange', () => { $('fullBtn').textContent = document.fullscreenElement ? '🗗' : '⛶'; showControls(); });

  // controls auto-hide
  let lastMove = Date.now();
  function showControls() {
    lastMove = Date.now();
    if (current) $('controls').classList.remove('hidden');
    $('stage').classList.remove('idle');
  }
  setInterval(() => {
    if (current) {
      const idle = Date.now() - lastMove > 3000 && !video.paused && !$('controls').matches(':hover');
      $('controls').classList.toggle('hidden', idle);
      $('stage').classList.toggle('idle', idle && !!document.fullscreenElement);
      const behind = archiveStart ? Math.max(0, nowSec() - positionSec()) : 0;
      $('liveBtn').classList.toggle('behind', !!archiveStart);
      if (archiveStart) {
        const h = Math.floor(behind / 3600), m = Math.floor(behind / 60) % 60, s = behind % 60;
        $('liveText').textContent = '−' + (h ? `${h}:` : '') + String(m).padStart(2, '0') + ':' + String(s).padStart(2, '0');
        $('posText').textContent = new Date(positionSec() * 1000).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
      } else { $('liveText').textContent = T('ЭФИР'); $('posText').textContent = ''; }
      document.querySelectorAll('[data-act=fwd30],[data-act=fwd300]').forEach(b => b.disabled = !archiveStart);
    }
  }, 1000);
  ['mousemove', 'touchstart'].forEach(ev => $('stage').addEventListener(ev, showControls, { passive: true }));
  $('stage').addEventListener('dblclick', toggleFull);

  // buttons
  const actions = {
    back300: () => rewind(300), back30: () => rewind(30), fwd30: () => forward(30), fwd300: () => forward(300),
    live: goLive, prev: () => step(-1), next: () => step(1), fav: toggleFav, full: toggleFull,
    play: () => { if (!current) return; video.paused ? play() : video.pause(); },
    mute: () => { video.muted = !video.muted; $('muteBtn').textContent = video.muted ? '🔇' : '🔊'; },
  };
  $('controls').addEventListener('click', e => {
    const b = e.target.closest('button[data-act]');
    if (b) { e.stopPropagation(); actions[b.dataset.act](); showControls(); }
  });
  $('volume').value = store.get('vol', 0.8);
  video.volume = +$('volume').value;
  $('volume').oninput = e => { video.volume = +e.target.value; video.muted = false; $('muteBtn').textContent = '🔊'; store.set('vol', video.volume); };

  // list / search
  $('search').placeholder = T('Поиск канала');
  $('search').oninput = renderList;
  $('listModeBtn').onclick = () => { grid = false; store.set('grid', grid); renderList(); };
  $('gridModeBtn').onclick = () => { grid = true; store.set('grid', grid); renderList(); };
  $('clearRecentsBtn').title = T('Очистить недавние');
  $('clearRecentsBtn').onclick = () => { recents = []; store.set('recent', recents); renderSections(); renderList(); };
  $('menuBtn').onclick = () => $('sidebar').classList.toggle('open');
  $('placeholderText').textContent = T('Выберите канал');

  // keyboard
  document.addEventListener('keydown', e => {
    if (e.target.tagName === 'INPUT') return;
    const k = e.key;
    if (k === ' ') actions.play();
    else if (k === 'f' || k === 'F' || k === 'Enter') toggleFull();
    else if (k === 'm' || k === 'M') actions.mute();
    else if (k === 'ArrowUp' && (e.ctrlKey || e.metaKey)) step(-1);
    else if (k === 'ArrowDown' && (e.ctrlKey || e.metaKey)) step(1);
    else if (k === 'ArrowLeft') rewind(e.shiftKey ? 300 : 30);
    else if (k === 'ArrowRight') forward(e.shiftKey ? 300 : 30);
    else if (k === 'l' || k === 'L') goLive();
    else return;
    e.preventDefault(); showControls();
  });

  load().catch(() => status(T('Ошибка воспроизведения')));
})();
