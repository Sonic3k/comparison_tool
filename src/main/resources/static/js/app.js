/* ═══════════════════════════════════════════════════════════════════════════
   Comparison Console — app.js
   Sections: helpers · api · state · derive · render(top/side/filter) ·
             render(table) · drawer · rail · feed · live engine · actions ·
             modals · boot
   ═══════════════════════════════════════════════════════════════════════════ */
'use strict';

/* ── helpers ──────────────────────────────────────────────────────────────── */
const $  = (sel, root) => (root || document).querySelector(sel);
const $$ = (sel, root) => Array.from((root || document).querySelectorAll(sel));

function esc(s) {
  return String(s == null ? '' : s)
    .replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;').replaceAll("'", '&#39;');
}
function attr(s) { return esc(s); }
function key(g, id) { return g + '::' + id; }
function splitKey(k) { const i = k.indexOf('::'); return [k.slice(0, i), k.slice(i + 2)]; }

function fmtDur(ms) {
  if (ms == null || ms < 0) return '';
  const s = Math.floor(ms / 1000);
  if (s < 60) return s + 's';
  const m = Math.floor(s / 60);
  if (m < 60) return m + 'm ' + (s % 60) + 's';
  return Math.floor(m / 60) + 'h ' + (m % 60) + 'm';
}
function prettyJson(raw) {
  if (raw == null || raw === '') return '';
  try { return JSON.stringify(JSON.parse(raw), null, 2); } catch { return String(raw); }
}
function debounce(fn, ms) {
  let t; return (...a) => { clearTimeout(t); t = setTimeout(() => fn(...a), ms); };
}
function toast(msg, kind) {
  const el = document.createElement('div');
  el.className = 'toast ' + (kind || '');
  el.textContent = msg;
  $('#toasts').appendChild(el);
  setTimeout(() => el.remove(), 5000);
}
function copyText(text, btn) {
  navigator.clipboard.writeText(text).then(() => {
    if (btn) { const o = btn.textContent; btn.textContent = 'Copied'; setTimeout(() => btn.textContent = o, 1200); }
  }).catch(() => toast('Copy failed — clipboard unavailable', 'err'));
}

/* ── api ──────────────────────────────────────────────────────────────────── */
async function api(method, url, body, isForm) {
  const opt = { method, headers: {} };
  if (body != null) {
    if (isForm) { opt.body = body; }
    else { opt.headers['Content-Type'] = 'application/json'; opt.body = JSON.stringify(body); }
  }
  let res, json;
  try { res = await fetch(url, opt); } catch { throw new Error('Network error — is the server up?'); }
  try { json = await res.json(); } catch { json = null; }
  if (!json) {
    if (!res.ok) throw new Error('HTTP ' + res.status);
    return null;
  }
  if (json.success === false) throw new Error(json.message || 'Request failed');
  return json;
}

/* ── state ────────────────────────────────────────────────────────────────── */
const S = {
  suite: null,
  ui: {
    group: '*',            // sidebar group filter ('*' = all)
    status: 'all',         // chip filter
    q: '', gq: '',         // test-case search / group search
    sel: new Set(),        // selected TC keys "group::tcId"
    open: new Set(),       // expanded TC keys
    drawer: null,          // { g, id } request open in drawer
    dwTab: 'request',
    dwEdit: false
  },
  live: {
    on: false, state: 'idle',
    plan: [],              // scopeKeys of current/last run, in execution order
    overlay: new Map(),    // reqKey -> queued|running|passed|failed|error
    seen: new Set(),       // recent-entry signatures already applied
    prog: null, timer: null, flash: []
  },
  viewIndex: new Map()     // reqKey -> tcKey (built at render time)
};

const groups = () => (S.suite && S.suite.testGroups) || [];
const findGroup = name => groups().find(g => g.name === name) || null;
const findReq = (gName, id) => {
  const g = findGroup(gName);
  return g ? (g.testRequests || []).find(r => r.id === id) || null : null;
};

/* ── derive: statuses, rollups, counts ────────────────────────────────────── */
function reqStatus(gName, r) {
  const o = S.live.overlay.get(key(gName, r.id));
  if (o) return o;
  const st = r.result && r.result.status;
  return st ? String(st).toLowerCase() : 'pending';
}
function rollup(statuses) {
  if (statuses.includes('running')) return 'running';
  if (statuses.includes('queued'))  return 'queued';
  if (statuses.includes('error'))   return 'error';
  if (statuses.includes('failed'))  return 'failed';
  if (statuses.length && statuses.every(s => s === 'passed')) return 'passed';
  return 'pending';
}

/** Test cases of a group in first-appearance (execution) order. */
function tcList(group) {
  const map = new Map();
  for (const r of group.testRequests || []) {
    const id = r.testCaseId || r.id;
    if (!map.has(id)) map.set(id, []);
    map.get(id).push(r);
  }
  const defs = new Map((group.testCaseDefs || []).map(d => [d.id, d]));
  return Array.from(map, ([id, reqs]) => ({
    id, reqs,
    def: defs.get(id) || { id, name: reqs.length === 1 ? (reqs[0].name || id) : id }
  }));
}
function tcStat(gName, tc) { return rollup(tc.reqs.map(r => reqStatus(gName, r))); }

function groupCounts(g) {
  const c = { passed: 0, failed: 0, error: 0, pending: 0, running: 0, tcs: 0, reqs: (g.testRequests || []).length };
  for (const tc of tcList(g)) {
    c.tcs++;
    const st = tcStat(g.name, tc);
    if (st === 'queued') c.pending++;
    else c[st] = (c[st] || 0) + 1;
  }
  return c;
}
function suiteCounts() {
  const t = { passed: 0, failed: 0, error: 0, pending: 0, running: 0, tcs: 0, reqs: 0 };
  for (const g of groups()) {
    const c = groupCounts(g);
    for (const k of Object.keys(t)) t[k] += c[k] || 0;
  }
  return t;
}
function tcLastRun(tc) {
  let last = '';
  for (const r of tc.reqs) {
    const at = r.result && r.result.executedAt;
    if (at && at > last) last = at;
  }
  return last;
}

/* ── render: topbar ───────────────────────────────────────────────────────── */
function renderTop() {
  const has = !!S.suite;
  $('#layout').hidden = !has;
  $('#empty').hidden  = has;
  $('#runWrap').hidden = !has || S.live.on;
  $('#btnStop').hidden = !S.live.on;
  $('#btnCfg').hidden = !has;
  $('#run-stats').hidden = !has;

  if (!has) { $('#suiteName').textContent = 'No suite loaded'; $('#envPair').hidden = true; return; }

  const st = S.suite.settings || {};
  $('#suiteName').textContent = st.suiteName || 'Untitled suite';
  const ec = st.executionConfig || {};
  $('#envPair').hidden = false;
  $('#envSrc').textContent = ec.sourceEnvironment || '?';
  $('#envTgt').textContent = ec.targetEnvironment || '?';
  renderStats();
}
function renderStats() {
  if (!S.suite) return;
  const c = suiteCounts();
  $('#run-stats').innerHTML =
    `<span class="big">${c.tcs}</span><span style="color:var(--faint)">test cases</span>` +
    `<span class="p">${c.passed} ✓</span><span class="f">${c.failed} ✗</span>` +
    (c.error ? `<span class="e">${c.error} !</span>` : '');
  $('#miRunSel').textContent = `☑ Run selected (${S.ui.sel.size})`;
  const cur = S.ui.group;
  $('#miRunGrp').textContent = cur === '*' ? '▶ Run current group' : `▶ Run group “${cur}”`;
  $('#miRunGrp').style.display = cur === '*' ? 'none' : '';
}

/* ── render: sidebar ──────────────────────────────────────────────────────── */
function renderSide() {
  const list = $('#group-list');
  if (!S.suite) { list.innerHTML = ''; return; }
  const q = S.ui.gq.toLowerCase();
  const total = suiteCounts();
  let html = grpRow('*', 'All groups', total, S.ui.group === '*', true);
  for (const g of groups()) {
    if (q && !g.name.toLowerCase().includes(q)) continue;
    html += grpRow(g.name, g.name, groupCounts(g), S.ui.group === g.name, false, g.enabled === false);
  }
  list.innerHTML = html;
}
function grpRow(id, name, c, active, isAll, disabled) {
  const total = Math.max(1, c.tcs);
  const seg = (n, v) => n ? `<i style="width:${(n / total * 100).toFixed(1)}%;background:var(--${v})"></i>` : '';
  return `<div class="grp ${active ? 'active' : ''} ${disabled ? 'disabled' : ''}" data-act="side-group" data-g="${attr(id)}">
    <div class="grp-cell">
      <div style="display:flex;align-items:center;gap:8px">
        <span class="g-name">${esc(name)}</span>
        <span class="g-count">${c.tcs}</span>
        ${isAll ? '' : `<button class="g-run" data-act="grp-menu" data-g="${attr(id)}" title="Group actions">⋯</button>
        <button class="g-run" data-act="run-group" data-g="${attr(id)}" title="Run group">▶</button>`}
      </div>
      <div class="g-bar">${seg(c.passed, 'pass')}${seg(c.failed, 'fail')}${seg(c.error, 'err')}${seg(c.running, 'run')}${seg(c.pending, 'pend')}</div>
    </div>
  </div>`;
}

/* ── render: filter bar + bulk bar ────────────────────────────────────────── */
function renderFilter() {
  if (!S.suite) return;
  const scoped = S.ui.group === '*' ? groups() : [findGroup(S.ui.group)].filter(Boolean);
  const c = { all: 0, passed: 0, failed: 0, error: 0, pending: 0 };
  for (const g of scoped) {
    const gc = groupCounts(g);
    c.all += gc.tcs; c.passed += gc.passed; c.failed += gc.failed; c.error += gc.error;
    c.pending += gc.pending + gc.running;
  }
  const chip = (id, label, n) =>
    `<button class="chip c-${id} ${S.ui.status === id ? 'on' : ''}" data-act="chip" data-status="${id}">${label} <b>${n}</b></button>`;
  $('#filterbar').innerHTML =
    chip('all', 'All', c.all) + chip('failed', 'Failed', c.failed) + chip('error', 'Error', c.error) +
    chip('passed', 'Passed', c.passed) + chip('pending', 'Pending', c.pending) +
    `<input type="search" id="tc-search" placeholder="Search id · name · endpoint…" value="${attr(S.ui.q)}" autocomplete="off"/>` +
    `<button class="btn sm" data-act="req-new" title="Add a request">＋ Request</button>`;
  const si = $('#tc-search');
  si.addEventListener('input', debounce(e => { S.ui.q = e.target.value.trim(); renderTable(); }, 160));
}
function renderBulk() {
  const bar = $('#bulkbar');
  const n = S.ui.sel.size;
  bar.classList.toggle('visible', n > 0);
  if (!n) { bar.innerHTML = ''; return; }
  bar.innerHTML =
    `<span class="n">${n}</span> test case${n > 1 ? 's' : ''} selected` +
    `<button class="btn sm primary" data-act="run-selected">▶ Run selected</button>` +
    `<button class="btn sm" data-act="bulk-enable">Enable</button>` +
    `<button class="btn sm" data-act="bulk-disable">Disable</button>` +
    `<button class="btn sm" data-act="bulk-move">Move to test case…</button>` +
    `<button class="btn sm ghost" data-act="bulk-clear">Clear selection</button>`;
}

/* ── render: test-case table ──────────────────────────────────────────────── */
function matchesFilter(gName, tc) {
  const st = tcStat(gName, tc);
  const f = S.ui.status;
  if (f !== 'all') {
    if (f === 'pending' && !(st === 'pending' || st === 'queued' || st === 'running')) return false;
    if (f !== 'pending' && st !== f) return false;
  }
  const q = S.ui.q.toLowerCase();
  if (q) {
    const hay = [tc.id, tc.def.name, ...tc.reqs.flatMap(r => [r.id, r.name, r.endpoint])]
      .filter(Boolean).join(' ').toLowerCase();
    if (!hay.includes(q)) return false;
  }
  return true;
}

function renderTable() {
  const body = $('#tct-body');
  if (!S.suite) { body.innerHTML = ''; return; }
  S.viewIndex.clear();
  const scoped = S.ui.group === '*' ? groups() : [findGroup(S.ui.group)].filter(Boolean);
  let html = '', shown = 0;

  for (const g of scoped) {
    const tcs = tcList(g).filter(tc => matchesFilter(g.name, tc));
    if (!tcs.length) continue;
    if (S.ui.group === '*') {
      html += `<tr><td colspan="6" style="padding:10px 12px 4px">
        <span style="font-size:10px;font-weight:800;letter-spacing:.14em;text-transform:uppercase;color:var(--faint)">${esc(g.name)}</span>
        <span class="badge-n" style="margin-left:8px">${tcs.length}</span></td></tr>`;
    }
    for (const tc of tcs) {
      shown++;
      html += tcRowHtml(g, tc);
      if (S.ui.open.has(key(g.name, tc.id))) {
        for (const r of tc.reqs) html += rqRowHtml(g, tc, r);
      }
      for (const r of tc.reqs) S.viewIndex.set(key(g.name, r.id), key(g.name, tc.id));
    }
  }
  body.innerHTML = shown ? html
    : `<tr><td colspan="6"><div class="empty-hint">No test cases match the current filter.</div></td></tr>`;
  syncSelAll();
}

function tcRowHtml(g, tc) {
  const tck = key(g.name, tc.id);
  const st = tcStat(g.name, tc);
  const open = S.ui.open.has(tck);
  const single = tc.reqs.length === 1 ? tc.reqs[0] : null;
  const phases = new Set(tc.reqs.map(r => r.phase || 'test'));
  const phaseTag = (phases.size === 1 && !phases.has('test'))
    ? `<span class="tag ${[...phases][0]}">${[...phases][0]}</span>` : '';
  return `<tr class="tc-row ${S.ui.sel.has(tck) ? 'selected' : ''}" data-tck="${attr(tck)}" data-act="tc-toggle" data-g="${attr(g.name)}" data-tc="${attr(tc.id)}">
    <td data-stop="1"><input type="checkbox" data-act="sel-tc" data-k="${attr(tck)}" ${S.ui.sel.has(tck) ? 'checked' : ''}></td>
    <td><span class="pill ${st}" data-pill><i class="dot ${st}"></i>${st}</span></td>
    <td>
      <div style="display:flex;align-items:center;gap:8px">
        <span class="exp-caret ${open ? 'open' : ''}" data-caret>▸</span>
        <span class="tc-id">${esc(tc.id)}</span>${phaseTag}
      </div>
      <div class="tc-name" style="padding-left:24px">${esc(tc.def.name && tc.def.name !== tc.id ? tc.def.name : (single ? single.name || '' : ''))}</div>
    </td>
    <td>${single
      ? `<span class="method ${attr(single.method || 'GET')}">${esc(single.method || 'GET')}</span> <span class="endpoint" title="${attr(single.endpoint)}">${esc(single.endpoint || '')}</span>`
      : `<span class="badge-n">${tc.reqs.length} requests</span>`}</td>
    <td><span class="mono" style="font-size:11px;color:var(--faint)" data-lastrun>${esc(tcLastRun(tc))}</span></td>
    <td data-stop="1"><div class="row-acts">
      <button class="icobtn run" data-act="run-tc" data-g="${attr(g.name)}" data-tc="${attr(tc.id)}" title="Run this test case (with setup)">▶</button>
      <button class="icobtn" data-act="tc-edit" data-g="${attr(g.name)}" data-tc="${attr(tc.id)}" title="Edit test case">✎</button>
    </div></td>
  </tr>`;
}

function rqRowHtml(g, tc, r) {
  const rk = key(g.name, r.id);
  const st = reqStatus(g.name, r);
  const res = r.result || {};
  const codes = [res.sourceStatus, res.targetStatus].filter(Boolean).join(' → ');
  return `<tr class="rq-row ${r.enabled === false ? 'disabled' : ''}" data-rk="${attr(rk)}" data-act="open-req" data-g="${attr(g.name)}" data-id="${attr(r.id)}">
    <td></td>
    <td><i class="dot ${st}" data-dot></i> <span class="mono" style="font-size:10.5px;color:var(--faint)" data-codes>${esc(codes)}</span></td>
    <td style="padding-left:34px">
      <span class="rq-id">${esc(r.id)}</span>
      ${(r.phase && r.phase !== 'test') ? `<span class="tag ${attr(r.phase)}">${esc(r.phase)}</span>` : ''}
      ${r.extractVariables ? `<span class="tag" title="extracts: ${attr(r.extractVariables)}">vars</span>` : ''}
    </td>
    <td><span class="method ${attr(r.method || 'GET')}">${esc(r.method || 'GET')}</span>
        <span class="endpoint" title="${attr(r.endpoint)}">${esc(r.endpoint || '')}</span></td>
    <td><span class="mono" style="font-size:11px;color:var(--faint)">${esc(res.executedAt || '')}</span></td>
    <td data-stop="1"><div class="row-acts">
      <button class="icobtn run" data-act="run-req" data-g="${attr(g.name)}" data-id="${attr(r.id)}" title="Re-run this request only (no setup, no variables)">⟳</button>
      <button class="icobtn" data-act="req-toggle" data-g="${attr(g.name)}" data-id="${attr(r.id)}" title="Enable / disable">⏻</button>
      <button class="icobtn" data-act="open-req" data-g="${attr(g.name)}" data-id="${attr(r.id)}" title="Details">⧉</button>
    </div></td>
  </tr>`;
}

function syncSelAll() {
  const boxes = $$('#tct-body [data-act="sel-tc"]');
  const all = boxes.length > 0 && boxes.every(b => b.checked);
  $('#selAll').checked = all;
}

/** Patch a single request row + its TC row in place (live ticks, no re-render). */
function patchKey(rk) {
  const [gName, id] = splitKey(rk);
  const r = findReq(gName, id);
  const st = r ? reqStatus(gName, r) : (S.live.overlay.get(rk) || 'pending');
  const rrow = $(`tr[data-rk="${CSS.escape(rk)}"]`);
  if (rrow) {
    const dot = $('[data-dot]', rrow);
    if (dot) dot.className = 'dot ' + st;
  }
  const tck = S.viewIndex.get(rk);
  if (!tck) return;
  const [, tcId] = splitKey(tck);
  const g = findGroup(gName);
  if (!g) return;
  const tc = tcList(g).find(t => t.id === tcId);
  if (!tc) return;
  const trow = $(`tr[data-tck="${CSS.escape(tck)}"]`);
  if (trow) {
    const tst = tcStat(gName, tc);
    const pill = $('[data-pill]', trow);
    if (pill) { pill.className = 'pill ' + tst; pill.innerHTML = `<i class="dot ${tst}"></i>${tst}`; }
    const lr = $('[data-lastrun]', trow);
    if (lr) lr.textContent = tcLastRun(tc);
  }
}

/* ── drawer ───────────────────────────────────────────────────────────────── */
function openDrawer(gName, id, tab) {
  S.ui.drawer = { g: gName, id };
  S.ui.dwTab = tab || 'request';
  S.ui.dwEdit = false;
  renderDrawer();
}
function closeDrawer() { S.ui.drawer = null; $('#drawer').classList.remove('open'); }

function renderDrawer() {
  const dw = $('#drawer');
  if (!S.ui.drawer) { dw.classList.remove('open'); return; }
  const { g: gName, id } = S.ui.drawer;
  const r = findReq(gName, id);
  if (!r) { closeDrawer(); return; }
  const st = reqStatus(gName, r);
  const tabs = [['request', 'Request'], ['result', 'Result'], ['diff', 'Diff & assertions'], ['curl', 'cURL']];
  dw.classList.add('open');
  dw.innerHTML = `
    <div class="dw-head">
      <div class="dw-title">
        <span class="pill ${st}"><i class="dot ${st}"></i>${st}</span>
        <span class="rq-id">${esc(r.id)}</span>
        <span class="spacer" style="flex:1"></span>
        <button class="icobtn" data-act="drawer-close" title="Close (Esc)">✕</button>
      </div>
      <div class="dw-sub">${esc(gName)} · test case <b class="mono">${esc(r.testCaseId || r.id)}</b>${r.name ? ' · ' + esc(r.name) : ''}</div>
      <div class="dw-tabs">${tabs.map(([k, l]) =>
        `<button class="dw-tab ${S.ui.dwTab === k ? 'on' : ''}" data-act="dw-tab" data-tab="${k}">${l}</button>`).join('')}</div>
    </div>
    <div class="dw-body" id="dw-body"></div>
    <div class="dw-foot">
      <button class="btn sm primary" data-act="run-tc" data-g="${attr(gName)}" data-tc="${attr(r.testCaseId || r.id)}">▶ Run test case</button>
      <button class="btn sm" data-act="run-req" data-g="${attr(gName)}" data-id="${attr(r.id)}" title="No setup, no variables">⟳ Request only</button>
      <span style="flex:1"></span>
      ${S.ui.dwEdit ? '' : `<button class="btn sm" data-act="dw-edit">Edit</button>`}
      <button class="btn sm" data-act="req-move" data-g="${attr(gName)}" data-id="${attr(r.id)}">Move…</button>
      <button class="btn sm danger" data-act="req-delete" data-g="${attr(gName)}" data-id="${attr(r.id)}">Delete</button>
    </div>`;
  renderDrawerBody(gName, r);
}

function paramsToText(list) { return (list || []).map(p => `${p.key}=${p.value == null ? '' : p.value}`).join('&'); }
function textToParams(t) {
  if (!t || !t.trim()) return [];
  return t.split('&').map(s => s.trim()).filter(Boolean).map(s => {
    const i = s.indexOf('=');
    return i < 0 ? { key: s, value: '' } : { key: s.slice(0, i), value: s.slice(i + 1) };
  });
}
function httpCodeCls(c) {
  const n = parseInt(c, 10);
  if (!n) return '';
  return n < 300 ? 'ok' : n < 500 ? 'warn' : 'bad';
}

function renderDrawerBody(gName, r) {
  const box = $('#dw-body');
  const res = r.result || {};
  if (S.ui.dwEdit) { box.innerHTML = requestFormHtml(r, false); return; }

  if (S.ui.dwTab === 'request') {
    const kv = [
      ['Test case', `<span class="mono">${esc(r.testCaseId || r.id)}</span>`],
      ['Name', esc(r.name)],
      ['Description', esc(r.description)],
      ['Enabled', r.enabled === false ? 'no' : 'yes'],
      ['Mode / phase', `${esc(r.verificationMode || 'comparison')} · ${esc(r.phase || 'test')}`],
      ['Endpoint', `<span class="method ${attr(r.method || 'GET')}">${esc(r.method || 'GET')}</span> <span class="mono">${esc(r.endpoint || '')}</span>`],
      ['Query params', esc(paramsToText(r.queryParams))],
      ['Form params', esc(paramsToText(r.formParams))],
      ['Extract vars', `<span class="mono">${esc(r.extractVariables || '')}</span>`],
      ['Author', esc(r.author)]
    ].filter(([, v]) => v !== '' && v != null);
    box.innerHTML =
      `<dl class="kv">${kv.map(([k, v]) => `<dt>${k}</dt><dd>${v}</dd>`).join('')}</dl>` +
      (r.headers ? `<div style="margin-top:12px"><div class="side-label">HEADERS</div><div class="codebox">${esc(r.headers)}</div></div>` : '') +
      (r.jsonBody ? `<div style="margin-top:12px"><div class="side-label">JSON BODY</div><div class="codebox">${esc(prettyJson(r.jsonBody))}</div></div>` : '') +
      (r.comparisonConfig ? `<div style="margin-top:12px"><div class="side-label">COMPARISON OVERRIDE</div><div class="codebox">${esc(JSON.stringify(r.comparisonConfig, null, 2))}</div></div>` : '') +
      (r.automationConfig ? `<div style="margin-top:12px"><div class="side-label">ASSERTIONS</div><div class="codebox">${esc(JSON.stringify(r.automationConfig, null, 2))}</div></div>` : '');
  }
  else if (S.ui.dwTab === 'result') {
    if (!res.executedAt && !res.sourceStatus && !res.targetStatus) {
      box.innerHTML = `<div class="empty-hint">Not executed yet — run the test case to capture responses.</div>`;
      return;
    }
    const meta = `<div class="dw-sub" style="margin-bottom:10px">mode <b>${esc(res.modeRun || '')}</b> · executed <b class="mono">${esc(res.executedAt || '')}</b></div>`;
    const col = (side, code, bodyRaw) => `
      <div class="resp-col ${side}">
        <div class="side-label ${side}">${side === 'src' ? 'SOURCE' : 'TARGET'}
          <span class="http-code ${httpCodeCls(code)}">${esc(code || '—')}</span></div>
        <div class="codebox">${esc(prettyJson(bodyRaw) || '(empty body)')}
          <button class="copybtn" data-copy="${attr(bodyRaw || '')}">Copy</button></div>
      </div>`;
    const hasSrc = res.sourceStatus || res.sourceResponse;
    box.innerHTML = meta + (hasSrc
      ? `<div class="resp-grid">${col('src', res.sourceStatus, res.sourceResponse)}${col('tgt', res.targetStatus, res.targetResponse)}</div>`
      : `<div class="resp-grid" style="grid-template-columns:1fr">${col('tgt', res.targetStatus, res.targetResponse)}</div>`);
  }
  else if (S.ui.dwTab === 'diff') {
    const diffs = (res.comparisonResult || '').split('\n').map(s => s.trim()).filter(Boolean);
    let html = '';
    html += `<div class="side-label">COMPARISON</div>`;
    html += diffs.length
      ? diffs.map(d => `<div class="diff-line">${esc(d)}</div>`).join('')
      : `<div class="dw-sub">${res.executedAt ? 'No differences — responses match.' : 'Not executed yet.'}</div>`;
    if (res.assertionResult) {
      html += `<div class="side-label" style="margin-top:16px">ASSERTIONS</div><div class="codebox">${esc(res.assertionResult)}</div>`;
    }
    box.innerHTML = html;
  }
  else if (S.ui.dwTab === 'curl') {
    box.innerHTML = `<div class="dw-sub">Loading cURL…</div>`;
    api('GET', `/api/execute/case/curl?groupName=${encodeURIComponent(gName)}&caseId=${encodeURIComponent(r.id)}`)
      .then(j => {
        if (!S.ui.drawer || S.ui.drawer.id !== r.id || S.ui.dwTab !== 'curl') return;
        const d = j.data || {};
        box.innerHTML =
          `<div class="side-label src">SOURCE</div><div class="codebox">${esc(d.source || '')}<button class="copybtn" data-copy="${attr(d.source || '')}">Copy</button></div>` +
          `<div class="side-label tgt" style="margin-top:14px">TARGET</div><div class="codebox">${esc(d.target || '')}<button class="copybtn" data-copy="${attr(d.target || '')}">Copy</button></div>`;
      })
      .catch(e => { box.innerHTML = `<div class="dw-sub" style="color:var(--fail)">${esc(e.message)}</div>`; });
  }
}

/* Shared request form (drawer edit + create modal). isNew adds id/group fields. */
function requestFormHtml(r, isNew, groupOptions) {
  const sel = (name, opts, cur) =>
    `<select name="${name}">${opts.map(o => `<option value="${attr(o)}" ${o === cur ? 'selected' : ''}>${esc(o || '(default)')}</option>`).join('')}</select>`;
  return `<form id="req-form" class="form-grid" onsubmit="return false">
    ${isNew ? `<div class="fld"><label>Group</label>${sel('group', groupOptions, groupOptions[0])}</div>
               <div class="fld"><label>Request ID *</label><input name="id" required value="${attr(r.id || '')}"></div>` : ''}
    <div class="fld"><label>Test case ID</label><input name="testCaseId" value="${attr(r.testCaseId || '')}" placeholder="blank = own id"></div>
    <div class="fld"><label>Name</label><input name="name" value="${attr(r.name || '')}"></div>
    <div class="fld full"><label>Description</label><input name="description" value="${attr(r.description || '')}"></div>
    <div class="fld"><label>Method</label>${sel('method', ['GET', 'POST', 'PUT', 'PATCH', 'DELETE'], r.method || 'GET')}</div>
    <div class="fld"><label>Enabled</label>${sel('enabled', ['true', 'false'], String(r.enabled !== false))}</div>
    <div class="fld full"><label>Endpoint (path only)</label><input name="endpoint" class="mono" value="${attr(r.endpoint || '')}"></div>
    <div class="fld"><label>Verification mode</label>${sel('verificationMode', ['comparison', 'automation', 'both', 'none'], r.verificationMode || 'comparison')}</div>
    <div class="fld"><label>Phase</label>${sel('phase', ['test', 'setup', 'teardown'], r.phase || 'test')}</div>
    <div class="fld full"><label>Query params (a=1&b=2)</label><input name="queryParams" class="mono" value="${attr(paramsToText(r.queryParams))}"></div>
    <div class="fld full"><label>Form params (a=1&b=2)</label><input name="formParams" class="mono" value="${attr(paramsToText(r.formParams))}"></div>
    <div class="fld full"><label>Headers (Key: Value per line)</label><textarea name="headers">${esc(r.headers || '')}</textarea></div>
    <div class="fld full"><label>JSON body</label><textarea name="jsonBody" rows="5">${esc(r.jsonBody || '')}</textarea></div>
    <div class="fld"><label>Extract variables</label><input name="extractVariables" class="mono" value="${attr(r.extractVariables || '')}" placeholder="var=$.json.path"></div>
    <div class="fld"><label>Author</label><input name="author" value="${attr(r.author || '')}"></div>
    ${isNew ? '' : `<div class="fld full" style="display:flex;gap:8px;justify-content:flex-end;margin-top:4px">
      <button class="btn sm ghost" data-act="dw-cancel">Cancel</button>
      <button class="btn sm primary" data-act="dw-save">Save changes</button></div>`}
  </form>`;
}
function readRequestForm(form, base) {
  const f = new FormData(form);
  const v = n => (f.get(n) == null ? '' : String(f.get(n)));
  const out = Object.assign({}, base);
  if (f.has('id')) out.id = v('id').trim();
  out.testCaseId = v('testCaseId').trim() || null;
  out.name = v('name'); out.description = v('description');
  out.enabled = v('enabled') === 'true';
  out.method = v('method');
  out.endpoint = v('endpoint').trim();
  out.verificationMode = v('verificationMode');
  out.phase = v('phase');
  out.queryParams = textToParams(v('queryParams'));
  out.formParams = textToParams(v('formParams'));
  out.headers = v('headers'); out.jsonBody = v('jsonBody');
  out.extractVariables = v('extractVariables'); out.author = v('author');
  return out;
}

/* ── run rail (canvas) ────────────────────────────────────────────────────── */
const RAIL_COLORS = {
  running: '#5C9DFF', passed: '#34C77B',
  failed: '#F25757', error: '#B583F5', pending: '#2A3A5C'
};
RAIL_COLORS.queued = '#2F4A7A';
function railStatus(rk) {
  const o = S.live.overlay.get(rk);
  if (o) return o;
  const [g, id] = splitKey(rk);
  const r = findReq(g, id);
  return r ? reqStatus(g, r) : 'pending';
}
function drawRail() {
  const wrap = $('#rail-wrap');
  const plan = S.live.plan;
  if (!plan.length) { wrap.classList.remove('visible'); return; }
  wrap.classList.add('visible');
  const cv = $('#rail');
  const dpr = window.devicePixelRatio || 1;
  const W = cv.clientWidth * dpr, H = 22 * dpr;
  if (cv.width !== W) cv.width = W;
  if (cv.height !== H) cv.height = H;
  const ctx = cv.getContext('2d');
  ctx.clearRect(0, 0, W, H);
  const n = plan.length;
  const tick = Math.max(1, Math.floor(W / n));
  const gap = tick >= 3 * dpr ? dpr : 0;
  for (let i = 0; i < n; i++) {
    ctx.fillStyle = RAIL_COLORS[railStatus(plan[i])] || RAIL_COLORS.pending;
    ctx.fillRect(i * tick, 0, Math.max(dpr, tick - gap), H);
  }
  const p = S.live.prog || {};
  const state = S.live.state;
  $('#rail-meta').innerHTML =
    `<span class="state-${state}">● ${state}</span>` +
    `<span>${p.done ?? 0} / ${p.total ?? n}</span>` +
    `<span style="color:var(--pass)">${p.passed ?? 0} passed</span>` +
    `<span style="color:var(--fail)">${p.failed ?? 0} failed</span>` +
    (p.errorCount ? `<span style="color:var(--err)">${p.errorCount} error</span>` : '') +
    `<span>${fmtDur(p.elapsedMs)}</span>` +
    ((state === 'running' || state === 'stopping') && p.active && p.active.length
      ? `<span style="color:var(--run)">now: ${esc(p.active.slice(0, 3).map(k => splitKey(k)[1]).join(' · '))}${p.active.length > 3 ? ' +' + (p.active.length - 3) : ''}</span>`
      : '');
}

/* ── live feed ────────────────────────────────────────────────────────────── */
function renderFeed() {
  const feed = $('#feed');
  const items = S.live.flash;
  feed.classList.toggle('visible', items.length > 0);
  if (!items.length) return;
  const ico = { passed: '✓', failed: '✗', error: '!', };
  feed.innerHTML = `<span class="fd-label">LIVE</span>` + items.slice(-24).reverse().map(e =>
    `<button class="fd" data-act="feed-open" data-g="${attr(e.group)}" data-id="${attr(e.requestId)}">
      <i class="dot ${attr(e.status)}"></i>${esc(e.requestId)} ${ico[e.status] || ''}</button>`).join('');
}

/* ── live engine ──────────────────────────────────────────────────────────── */
function optSetup() { const el = $('#optSetup'); return el ? el.checked : true; }

async function startRun(payload, label) {
  if (S.live.on) { toast('Execution already running — Stop it first', 'err'); return; }
  try { await api('POST', '/api/execute', payload || {}); }
  catch (e) { toast(e.message, 'err'); return; }
  $$('.menu.open').forEach(m => m.classList.remove('open'));
  S.live.on = true; S.live.state = 'running'; S.live.prog = null;
  S.live.plan = []; S.live.overlay.clear(); S.live.seen.clear(); S.live.flash = [];
  renderTop(); renderFeed(); drawRail();
  toast('Started: ' + (label || 'run'));
  poll();
  S.live.timer = setInterval(poll, 1000);
}

async function poll() {
  if (!S.live.on) return;
  let j; try { j = await api('GET', '/api/execute/progress'); } catch { return; }
  if (!S.live.on) return;
  const p = j.data || {};
  S.live.prog = p;
  S.live.state = p.state || 'idle';

  if (!S.live.plan.length && p.scopeKeys && p.scopeKeys.length) {
    S.live.plan = p.scopeKeys;
    for (const k of S.live.plan) if (!S.live.overlay.has(k)) S.live.overlay.set(k, 'queued');
    renderTable();
  }

  const changed = new Set();
  for (const e of p.recent || []) {
    const sig = e.group + '::' + e.requestId + '@' + e.at;
    if (S.live.seen.has(sig)) continue;
    S.live.seen.add(sig);
    const k = key(e.group, e.requestId);
    S.live.overlay.set(k, e.status);
    const r = findReq(e.group, e.requestId);
    if (r) { r.result = r.result || {}; r.result.status = e.status; }
    S.live.flash.push(e);
    changed.add(k);
  }
  for (const k of p.active || []) {
    if (S.live.overlay.get(k) !== 'running') { S.live.overlay.set(k, 'running'); changed.add(k); }
  }

  for (const k of changed) patchKey(k);
  if (changed.size) { refreshCounts(); renderFeed(); if (S.ui.drawer) { const dk = key(S.ui.drawer.g, S.ui.drawer.id); if (changed.has(dk)) renderDrawer(); } }
  drawRail();

  if (S.live.state === 'done' || S.live.state === 'stopped' || S.live.state === 'aborted') {
    await finishRun(p);
  }
}

async function finishRun(p) {
  if (S.live.timer) { clearInterval(S.live.timer); S.live.timer = null; }
  S.live.on = false;
  try {
    const j = await api('GET', '/api/suite');
    if (j && j.data) S.suite = j.data;
  } catch { /* keep local model */ }
  S.live.overlay.clear();   // rail now colors from persisted results
  renderAll();
  drawRail();
  const summary = `${p.passed ?? 0} passed · ${p.failed ?? 0} failed` + (p.errorCount ? ` · ${p.errorCount} error` : '') + ` (${fmtDur(p.elapsedMs)})`;
  if (S.live.state === 'aborted') toast('Run aborted: ' + (p.error || 'unknown error'), 'err');
  else if (S.live.state === 'stopped') toast('Run stopped — ' + summary);
  else toast('Run done — ' + summary, 'ok');
}

function refreshCounts() {
  renderSide(); renderStats();
  // chip counts in place — keeps the search input focused during a run
  const scoped = S.ui.group === '*' ? groups() : [findGroup(S.ui.group)].filter(Boolean);
  const c = { all: 0, passed: 0, failed: 0, error: 0, pending: 0 };
  for (const g of scoped) {
    const gc = groupCounts(g);
    c.all += gc.tcs; c.passed += gc.passed; c.failed += gc.failed; c.error += gc.error;
    c.pending += gc.pending + gc.running;
  }
  const chips = $$('#filterbar .chip');
  const order = ['all', 'failed', 'error', 'passed', 'pending'];
  chips.forEach((ch, i) => { const b = $('b', ch); if (b && order[i] != null) b.textContent = c[order[i]]; });
}

/* ── actions ──────────────────────────────────────────────────────────────── */
const runAll      = () => startRun({}, 'all groups');
const runFailed   = () => startRun({ scope: 'failed', includeSetup: optSetup() }, 'failed test cases');
const runGroup    = g  => startRun({ groups: [g] }, 'group ' + g);
const runTC       = (g, tc) => startRun({ scope: 'testcases', includeSetup: optSetup(),
                        testCases: [{ groupName: g, testCaseId: tc }] }, tc);
function runSelected() {
  if (!S.ui.sel.size) { toast('No test cases selected'); return; }
  const refs = [...S.ui.sel].map(k => { const [g, tc] = splitKey(k); return { groupName: g, testCaseId: tc }; });
  startRun({ scope: 'testcases', includeSetup: optSetup(), testCases: refs }, refs.length + ' selected test case(s)');
}
async function stopRun() {
  try {
    await api('POST', '/api/execute/stop');
    S.live.state = 'stopping';
    toast('Stopping — in-flight requests finish, teardown still runs');
    drawRail();
  } catch (e) { toast(e.message, 'err'); }
}

async function runReqSync(g, id) {
  if (S.live.on) { toast('Execution running — Stop it first', 'err'); return; }
  const k = key(g, id);
  S.live.overlay.set(k, 'running'); patchKey(k);
  if (S.ui.drawer && S.ui.drawer.id === id) renderDrawer();
  try {
    const j = await api('POST', '/api/execute/case', { groupName: g, caseId: id });
    const grp = findGroup(g);
    const i = grp ? grp.testRequests.findIndex(r => r.id === id) : -1;
    if (i >= 0) grp.testRequests[i] = j.data;
    toast(`${id} → ${String((j.data.result || {}).status || '').toLowerCase() || 'done'}`);
  } catch (e) { toast(e.message, 'err'); }
  S.live.overlay.delete(k);
  renderTable(); refreshCounts();
  if (S.ui.drawer && S.ui.drawer.id === id) renderDrawer();
}

async function toggleReq(g, id) {
  try {
    const j = await api('PATCH', `/api/groups/${encodeURIComponent(g)}/cases/${encodeURIComponent(id)}/toggle`);
    const grp = findGroup(g);
    const i = grp.testRequests.findIndex(r => r.id === id);
    if (i >= 0) grp.testRequests[i] = j.data;
    renderTable(); refreshCounts();
    if (S.ui.drawer && S.ui.drawer.id === id) renderDrawer();
  } catch (e) { toast(e.message, 'err'); }
}

async function bulkSetEnabled(enabled) {
  const jobs = [];
  for (const tck of S.ui.sel) {
    const [gName, tcId] = splitKey(tck);
    const g = findGroup(gName); if (!g) continue;
    for (const r of g.testRequests) {
      if ((r.testCaseId || r.id) === tcId && (r.enabled !== false) !== enabled) {
        jobs.push({ g: gName, id: r.id });
      }
    }
  }
  if (!jobs.length) { toast('Nothing to change'); return; }
  for (let i = 0; i < jobs.length; i += 8) {
    await Promise.all(jobs.slice(i, i + 8).map(({ g, id }) =>
      api('PATCH', `/api/groups/${encodeURIComponent(g)}/cases/${encodeURIComponent(id)}/toggle`)
        .then(j => {
          const grp = findGroup(g);
          const ix = grp.testRequests.findIndex(r => r.id === id);
          if (ix >= 0) grp.testRequests[ix] = j.data;
        }).catch(() => {})));
  }
  toast(`${jobs.length} request(s) ${enabled ? 'enabled' : 'disabled'}`);
  renderTable(); refreshCounts();
}

async function reloadSuite() {
  try {
    const j = await api('GET', '/api/suite');
    S.suite = j && j.data ? j.data : null;
  } catch { S.suite = null; }
  renderAll();
}

function revealReq(gName, id) {
  const r = findReq(gName, id);
  if (!r) return;
  const tcId = r.testCaseId || r.id;
  if (S.ui.group !== '*' && S.ui.group !== gName) S.ui.group = gName;
  S.ui.status = 'all';
  S.ui.open.add(key(gName, tcId));
  renderSide(); renderFilter(); renderTable();
  openDrawer(gName, id, 'result');
  const row = $(`tr[data-rk="${CSS.escape(key(gName, id))}"]`);
  if (row) { row.scrollIntoView({ block: 'center' }); row.classList.add('spot'); setTimeout(() => row.classList.remove('spot'), 1700); }
}

/* ── modals ───────────────────────────────────────────────────────────────── */
function modal(opt) {
  const veil = document.createElement('div');
  veil.className = 'modal-veil';
  veil.innerHTML = `<div class="modal ${opt.wide ? 'wide' : ''}">
    <div class="md-head"><h3>${esc(opt.title)}</h3><button class="icobtn" data-md-close>✕</button></div>
    <div class="md-body">${opt.body || ''}</div>
    <div class="md-foot"></div></div>`;
  const close = () => veil.remove();
  const foot = $('.md-foot', veil);
  for (const a of (opt.actions || [])) {
    const b = document.createElement('button');
    b.className = 'btn sm ' + (a.cls || '');
    b.textContent = a.label;
    b.addEventListener('click', () => a.onClick ? a.onClick(close, veil) : close());
    foot.appendChild(b);
  }
  veil.addEventListener('mousedown', e => { if (e.target === veil) close(); });
  veil.addEventListener('click', e => { if (e.target.closest('[data-md-close]')) close(); });
  $('#modal-root').appendChild(veil);
  return { veil, close };
}
function confirmModal(title, text, onOk, okLabel) {
  modal({
    title, body: `<p style="color:var(--muted)">${esc(text)}</p>`,
    actions: [
      { label: 'Cancel', cls: 'ghost' },
      { label: okLabel || 'Confirm', cls: 'danger', onClick: c => { c(); onOk(); } }
    ]
  });
}

function moveModal(gName, requestIds) {
  const g = findGroup(gName); if (!g) return;
  const defs = (g.testCaseDefs || []).map(d => d.id);
  modal({
    title: `Move ${requestIds.length} request(s) → test case`,
    body: `<div class="fld"><label>Group</label><input value="${attr(gName)}" disabled></div>
      <div class="fld" style="margin-top:10px"><label>Target test case ID (existing or new)</label>
      <input id="mv-target" class="mono" list="mv-defs" placeholder="e.g. AM_TC1" autocomplete="off">
      <datalist id="mv-defs">${defs.map(d => `<option value="${attr(d)}">`).join('')}</datalist></div>`,
    actions: [
      { label: 'Cancel', cls: 'ghost' },
      { label: 'Move', cls: 'primary', onClick: async (close, veil) => {
          const target = $('#mv-target', veil).value.trim();
          if (!target) { toast('Enter a target test case ID', 'err'); return; }
          try {
            const j = await api('POST', `/api/groups/${encodeURIComponent(gName)}/testcases/${encodeURIComponent(target)}/assign`, { requestIds });
            close(); toast(j.message || 'Moved', 'ok');
            S.ui.sel.clear();
            await reloadSuite();
          } catch (e) { toast(e.message, 'err'); }
        } }
    ]
  });
}

function tcEditModal(gName, tcId) {
  const g = findGroup(gName); if (!g) return;
  const def = (g.testCaseDefs || []).find(d => d.id === tcId) || { id: tcId, name: tcId, description: '' };
  modal({
    title: 'Edit test case — ' + tcId,
    body: `<div class="form-grid">
      <div class="fld"><label>ID (rename cascades to member requests)</label><input id="td-id" class="mono" value="${attr(def.id)}"></div>
      <div class="fld"><label>Name</label><input id="td-name" value="${attr(def.name || '')}"></div>
      <div class="fld full"><label>Description</label><textarea id="td-desc" rows="2">${esc(def.description || '')}</textarea></div>
    </div>`,
    actions: [
      { label: 'Delete test case', cls: 'danger', onClick: (close) => {
          confirmModal('Delete test case?', `Member requests of “${tcId}” revert to one-request test cases. Requests themselves are kept.`, async () => {
            try {
              await api('DELETE', `/api/groups/${encodeURIComponent(gName)}/testcases/${encodeURIComponent(tcId)}`);
              close(); toast('Test case deleted'); await reloadSuite();
            } catch (e) { toast(e.message, 'err'); }
          }, 'Delete');
        } },
      { label: 'Cancel', cls: 'ghost' },
      { label: 'Save', cls: 'primary', onClick: async (close, veil) => {
          const body = { id: $('#td-id', veil).value.trim(), name: $('#td-name', veil).value, description: $('#td-desc', veil).value };
          try {
            await api('PUT', `/api/groups/${encodeURIComponent(gName)}/testcases/${encodeURIComponent(tcId)}`, body);
            close(); toast('Test case saved', 'ok'); await reloadSuite();
          } catch (e) { toast(e.message, 'err'); }
        } }
    ]
  });
}

function groupModal(gName) {
  const g = findGroup(gName); if (!g) return;
  modal({
    title: 'Group — ' + gName,
    body: `<div class="form-grid">
      <div class="fld"><label>Name</label><input id="gm-name" value="${attr(g.name)}"></div>
      <div class="fld"><label>Owner</label><input id="gm-owner" value="${attr(g.owner || '')}"></div>
      <div class="fld full"><label>Description</label><textarea id="gm-desc" rows="2">${esc(g.description || '')}</textarea></div>
      <div class="fld full" style="display:flex;gap:8px;flex-wrap:wrap;margin-top:2px">
        <button class="btn sm" id="gm-toggle">${g.enabled === false ? 'Enable group' : 'Disable group'}</button>
        <button class="btn sm" id="gm-newtc">New test case…</button>
        <a class="btn sm" href="/api/groups/${encodeURIComponent(gName)}/export/xml">Export XML</a>
        <a class="btn sm" href="/api/groups/${encodeURIComponent(gName)}/export/postman?mode=both">Export Postman</a>
      </div>
    </div>`,
    actions: [
      { label: 'Delete group', cls: 'danger', onClick: (close) => {
          confirmModal('Delete group?', `“${gName}” and all its requests will be removed from the session.`, async () => {
            try {
              await api('DELETE', `/api/groups/${encodeURIComponent(gName)}`);
              if (S.ui.group === gName) S.ui.group = '*';
              close(); toast('Group deleted'); await reloadSuite();
            } catch (e) { toast(e.message, 'err'); }
          }, 'Delete');
        } },
      { label: 'Cancel', cls: 'ghost' },
      { label: 'Save', cls: 'primary', onClick: async (close, veil) => {
          const body = { name: $('#gm-name', veil).value.trim(), owner: $('#gm-owner', veil).value, description: $('#gm-desc', veil).value };
          try {
            await api('PUT', `/api/groups/${encodeURIComponent(gName)}`, body);
            if (S.ui.group === gName) S.ui.group = body.name;
            close(); toast('Group saved', 'ok'); await reloadSuite();
          } catch (e) { toast(e.message, 'err'); }
        } }
    ]
  });
  // secondary buttons inside body
  const veil = $('#modal-root .modal-veil:last-child');
  $('#gm-toggle', veil).addEventListener('click', async () => {
    try { await api('PATCH', `/api/groups/${encodeURIComponent(gName)}/toggle`); toast('Group toggled'); await reloadSuite(); }
    catch (e) { toast(e.message, 'err'); }
  });
  $('#gm-newtc', veil).addEventListener('click', () => {
    modal({
      title: 'New test case in ' + gName,
      body: `<div class="form-grid">
        <div class="fld"><label>ID *</label><input id="ntc-id" class="mono"></div>
        <div class="fld"><label>Name</label><input id="ntc-name"></div>
        <div class="fld full"><label>Description</label><input id="ntc-desc"></div></div>`,
      actions: [
        { label: 'Cancel', cls: 'ghost' },
        { label: 'Create', cls: 'primary', onClick: async (close, v) => {
            const body = { id: $('#ntc-id', v).value.trim(), name: $('#ntc-name', v).value, description: $('#ntc-desc', v).value };
            if (!body.id) { toast('ID is required', 'err'); return; }
            try {
              await api('POST', `/api/groups/${encodeURIComponent(gName)}/testcases`, body);
              close(); toast('Test case created', 'ok'); await reloadSuite();
            } catch (e) { toast(e.message, 'err'); }
          } }
      ]
    });
  });
}

function newGroupModal() {
  modal({
    title: 'New group',
    body: `<div class="form-grid">
      <div class="fld"><label>Name *</label><input id="ng-name"></div>
      <div class="fld"><label>Owner</label><input id="ng-owner"></div>
      <div class="fld full"><label>Description</label><input id="ng-desc"></div></div>`,
    actions: [
      { label: 'Cancel', cls: 'ghost' },
      { label: 'Create', cls: 'primary', onClick: async (close, v) => {
          const body = { name: $('#ng-name', v).value.trim(), owner: $('#ng-owner', v).value, description: $('#ng-desc', v).value, enabled: true, testRequests: [], testCaseDefs: [] };
          if (!body.name) { toast('Name is required', 'err'); return; }
          try { await api('POST', '/api/groups', body); close(); toast('Group created', 'ok'); await reloadSuite(); }
          catch (e) { toast(e.message, 'err'); }
        } }
    ]
  });
}

function newRequestModal() {
  const names = groups().map(g => g.name);
  if (!names.length) { toast('Create a group first'); return; }
  const preferred = S.ui.group !== '*' ? [S.ui.group, ...names.filter(n => n !== S.ui.group)] : names;
  modal({
    title: 'New request',
    wide: true,
    body: requestFormHtml({}, true, preferred),
    actions: [
      { label: 'Cancel', cls: 'ghost' },
      { label: 'Create', cls: 'primary', onClick: async (close, v) => {
          const form = $('#req-form', v);
          const body = readRequestForm(form, { result: null });
          const gName = new FormData(form).get('group');
          if (!body.id) { toast('Request ID is required', 'err'); return; }
          try {
            await api('POST', `/api/groups/${encodeURIComponent(gName)}/cases`, body);
            close(); toast('Request created', 'ok'); await reloadSuite();
          } catch (e) { toast(e.message, 'err'); }
        } }
    ]
  });
}

/* ── config modal ─────────────────────────────────────────────────────────── */
function headersToText(list) { return (list || []).map(p => `${p.key}: ${p.value == null ? '' : p.value}`).join('\n'); }
function textToHeaders(t) {
  return String(t || '').split('\n').map(s => s.trim()).filter(Boolean).map(s => {
    const i = s.indexOf(':');
    return i < 0 ? { key: s, value: '' } : { key: s.slice(0, i).trim(), value: s.slice(i + 1).trim() };
  });
}

function cfgModal() {
  if (!S.suite) return;
  const work = {
    settings: structuredClone(S.suite.settings || {}),
    envs: structuredClone(S.suite.environments || []),
    auths: structuredClone(S.suite.authProfiles || [])
  };
  work.settings.executionConfig = work.settings.executionConfig || {};
  work.settings.comparisonConfig = work.settings.comparisonConfig || {};
  let tab = 'settings';

  const m = modal({
    title: 'Suite configuration', wide: true, body: '',
    actions: [
      { label: 'Close', cls: 'ghost' },
      { label: 'Save', cls: 'primary', onClick: async (close, veil) => {
          collect(veil);
          try {
            if (tab === 'settings') {
              await api('PUT', '/api/suite/settings', work.settings);
              S.suite.settings = structuredClone(work.settings);
            } else if (tab === 'envs') {
              await api('PUT', '/api/suite/environment', work.envs);
              S.suite.environments = structuredClone(work.envs);
            } else {
              await api('PUT', '/api/suite/auth-profiles', work.auths);
              S.suite.authProfiles = structuredClone(work.auths);
            }
            toast('Configuration saved', 'ok');
            renderTop();
          } catch (e) { toast(e.message, 'err'); }
        } }
    ]
  });
  const body = $('.md-body', m.veil);

  const boolSel = (name, val) =>
    `<select name="${name}"><option value="true" ${val ? 'selected' : ''}>true</option><option value="false" ${!val ? 'selected' : ''}>false</option></select>`;
  const envOpts  = cur => work.envs.map(e2 => `<option ${e2.name === cur ? 'selected' : ''}>${esc(e2.name)}</option>`).join('');
  const authOpts = cur => `<option value="" ${!cur ? 'selected' : ''}>(none)</option>` +
    work.auths.map(a => `<option ${a.name === cur ? 'selected' : ''}>${esc(a.name)}</option>`).join('');

  function tabHead() {
    const tabs = [['settings', 'Settings'], ['envs', 'Environments'], ['auths', 'Auth profiles']];
    return `<div class="cfg-tabs">${tabs.map(([k, l]) =>
      `<button class="dw-tab ${tab === k ? 'on' : ''}" data-cfg-tab="${k}">${l}</button>`).join('')}</div>`;
  }
  function settingsHtml() {
    const s = work.settings, ec = s.executionConfig, cc = s.comparisonConfig;
    const vm = ec.verificationMode || '';
    return `<div class="form-grid">
      <div class="fld"><label>Suite name</label><input name="suiteName" value="${attr(s.suiteName || '')}"></div>
      <div class="fld"><label>Version</label><input name="version" value="${attr(s.version || '')}"></div>
      <div class="fld full"><label>Description</label><input name="description" value="${attr(s.description || '')}"></div>
      <div class="fld"><label>Execution mode</label>
        <select name="mode"><option ${ec.mode === 'parallel' ? 'selected' : ''}>parallel</option><option ${ec.mode === 'source_first' ? 'selected' : ''}>source_first</option></select></div>
      <div class="fld"><label>Verification mode filter</label>
        <select name="verificationMode"><option value="" ${!vm ? 'selected' : ''}>(all — no filter)</option>
        ${['comparison', 'automation', 'both', 'none'].map(v => `<option ${vm === v ? 'selected' : ''}>${v}</option>`).join('')}</select></div>
      <div class="fld"><label>Source environment</label><select name="sourceEnvironment">${envOpts(ec.sourceEnvironment)}</select></div>
      <div class="fld"><label>Target environment</label><select name="targetEnvironment">${envOpts(ec.targetEnvironment)}</select></div>
      <div class="fld"><label>Timeout (ms)</label><input name="timeout" type="number" value="${attr(ec.timeout ?? 20000)}"></div>
      <div class="fld"><label>Parallel limit</label><input name="parallelLimit" type="number" value="${attr(ec.parallelLimit ?? 5)}"></div>
      <div class="fld"><label>Delay between requests (ms)</label><input name="delayBetweenRequests" type="number" value="${attr(ec.delayBetweenRequests ?? 0)}"></div>
      <div class="fld"><label>Retries</label><input name="retries" type="number" value="${attr(ec.retries ?? 0)}"></div>
      <div class="fld full" style="margin-top:6px"><label style="color:var(--tgt)">Comparison defaults</label></div>
      <div class="fld full"><label>Ignore fields</label><textarea name="ignoreFieldsRaw" rows="3">${esc(cc.ignoreFieldsRaw || '')}</textarea></div>
      <div class="fld"><label>Ignore array order</label>${boolSel('ignoreArrayOrder', cc.ignoreArrayOrder)}</div>
      <div class="fld"><label>Case sensitive</label>${boolSel('caseSensitive', cc.caseSensitive)}</div>
      <div class="fld"><label>Compare error responses</label>${boolSel('compareErrorResponses', cc.compareErrorResponses)}</div>
      <div class="fld"><label>Numeric tolerance</label><input name="numericTolerance" type="number" step="0.0001" value="${attr(cc.numericTolerance ?? 0.001)}"></div>
    </div>`;
  }
  function envsHtml() {
    return work.envs.map((e2, i) => `<div class="edit-card" data-i="${i}">
      <button class="btn sm ghost rm" data-rm-env="${i}">✕ Remove</button>
      <div class="form-grid">
        <div class="fld"><label>Name</label><input name="name" value="${attr(e2.name || '')}"></div>
        <div class="fld"><label>Auth profile</label><select name="authProfile">${authOpts(e2.authProfile)}</select></div>
        <div class="fld full"><label>Base URL</label><input name="url" class="mono" value="${attr(e2.url || '')}"></div>
        <div class="fld full"><label>Headers (Key: Value per line)</label><textarea name="headers" rows="2">${esc(headersToText(e2.headers))}</textarea></div>
      </div></div>`).join('') +
      `<button class="btn sm" data-add-env>＋ Add environment</button>`;
  }
  function authsHtml() {
    return work.auths.map((a, i) => `<div class="edit-card" data-i="${i}">
      <button class="btn sm ghost rm" data-rm-auth="${i}">✕ Remove</button>
      <div class="form-grid">
        <div class="fld"><label>Name</label><input name="name" value="${attr(a.name || '')}"></div>
        <div class="fld"><label>Type</label>
          <select name="type">${['none', 'basic', 'bearer', 'client_credentials', 'saml'].map(v =>
            `<option ${String(a.type || 'none') === v ? 'selected' : ''}>${v}</option>`).join('')}</select></div>
        <div class="fld full"><label>Token URL</label><input name="tokenUrl" class="mono" value="${attr(a.tokenUrl || '')}"></div>
        <div class="fld"><label>Username</label><input name="username" value="${attr(a.username || '')}"></div>
        <div class="fld"><label>Password</label><input name="password" type="password" value="${attr(a.password || '')}"></div>
        <div class="fld"><label>Client ID</label><input name="clientId" value="${attr(a.clientId || '')}"></div>
        <div class="fld"><label>Client secret</label><input name="clientSecret" type="password" value="${attr(a.clientSecret || '')}"></div>
        <div class="fld"><label>Scope</label><input name="scope" value="${attr(a.scope || '')}"></div>
        <div class="fld"><label>Entity ID</label><input name="entityId" value="${attr(a.entityId || '')}"></div>
        <div class="fld full"><label>Static token (bearer paste-and-run)</label><textarea name="token" rows="2" class="mono">${esc(a.token || '')}</textarea></div>
      </div></div>`).join('') +
      `<button class="btn sm" data-add-auth>＋ Add auth profile</button>`;
  }

  function collect(veil) {
    if (tab === 'settings') {
      const form = $('#cfg-form', veil);
      if (!form) return;
      const f = new FormData(form);
      const v = n => String(f.get(n) ?? '');
      const s = work.settings, ec = s.executionConfig, cc = s.comparisonConfig;
      s.suiteName = v('suiteName'); s.version = v('version'); s.description = v('description');
      ec.mode = v('mode');
      ec.verificationMode = v('verificationMode') || null;
      ec.sourceEnvironment = v('sourceEnvironment'); ec.targetEnvironment = v('targetEnvironment');
      ec.timeout = parseInt(v('timeout'), 10) || 0;
      ec.parallelLimit = parseInt(v('parallelLimit'), 10) || 0;
      ec.delayBetweenRequests = parseInt(v('delayBetweenRequests'), 10) || 0;
      ec.retries = parseInt(v('retries'), 10) || 0;
      cc.ignoreFieldsRaw = v('ignoreFieldsRaw');
      cc.ignoreArrayOrder = v('ignoreArrayOrder') === 'true';
      cc.caseSensitive = v('caseSensitive') === 'true';
      cc.compareErrorResponses = v('compareErrorResponses') === 'true';
      cc.numericTolerance = parseFloat(v('numericTolerance')) || 0;
    } else if (tab === 'envs') {
      work.envs = $$('.edit-card', veil).map(card => ({
        name: $('[name=name]', card).value.trim(),
        url: $('[name=url]', card).value.trim(),
        authProfile: $('[name=authProfile]', card).value || null,
        headers: textToHeaders($('[name=headers]', card).value)
      }));
    } else {
      work.auths = $$('.edit-card', veil).map(card => ({
        name: $('[name=name]', card).value.trim(),
        type: $('[name=type]', card).value,
        tokenUrl: $('[name=tokenUrl]', card).value.trim(),
        username: $('[name=username]', card).value,
        password: $('[name=password]', card).value,
        clientId: $('[name=clientId]', card).value,
        clientSecret: $('[name=clientSecret]', card).value,
        scope: $('[name=scope]', card).value,
        entityId: $('[name=entityId]', card).value,
        token: $('[name=token]', card).value.trim()
      }));
    }
  }

  function render() {
    body.innerHTML = tabHead() + (tab === 'settings'
      ? `<form id="cfg-form" onsubmit="return false">${settingsHtml()}</form>`
      : tab === 'envs' ? envsHtml() : authsHtml());
  }

  body.addEventListener('click', e => {
    const tb = e.target.closest('[data-cfg-tab]');
    if (tb) { collect(m.veil); tab = tb.dataset.cfgTab; render(); return; }
    if (e.target.closest('[data-add-env]')) {
      collect(m.veil); work.envs.push({ name: 'env' + (work.envs.length + 1), url: '', authProfile: null, headers: [] }); render(); return;
    }
    if (e.target.closest('[data-add-auth]')) {
      collect(m.veil); work.auths.push({ name: 'profile' + (work.auths.length + 1), type: 'none' }); render(); return;
    }
    const re = e.target.closest('[data-rm-env]');
    if (re) { collect(m.veil); work.envs.splice(+re.dataset.rmEnv, 1); render(); return; }
    const ra = e.target.closest('[data-rm-auth]');
    if (ra) { collect(m.veil); work.auths.splice(+ra.dataset.rmAuth, 1); render(); return; }
  });

  render();
}

/* ── import / clear ───────────────────────────────────────────────────────── */
async function uploadSuite(file) {
  if (!file) return;
  const fd = new FormData(); fd.append('file', file);
  try {
    const j = await api('POST', '/api/suite/import', fd, true);
    toast((j && j.message) || 'Suite imported', 'ok');
    S.ui.group = '*'; S.ui.q = ''; S.ui.sel.clear(); S.ui.open.clear(); S.ui.drawer = null;
    S.live.plan = []; S.live.overlay.clear(); S.live.prog = null; S.live.state = 'idle'; S.live.flash = [];
    await reloadSuite();
    drawRail();
  } catch (e) { toast(e.message, 'err'); }
}

function importGroupModal(file) {
  const ext = file.name.toLowerCase().endsWith('.xml') ? 'xml' : 'json';
  modal({
    title: 'Import group — ' + file.name,
    body: `<div class="fld"><label>If a group with the same name exists</label>
      <select id="ig-mode">
        <option value="new">Fail — import as new group only</option>
        <option value="replace" selected>Replace the existing group</option>
      </select></div>`,
    actions: [
      { label: 'Cancel', cls: 'ghost' },
      { label: 'Import', cls: 'primary', onClick: async (close, v) => {
          const mode = $('#ig-mode', v).value;
          const fd = new FormData(); fd.append('file', file);
          try {
            const j = await api('POST', `/api/groups/import/${ext}?mode=${mode}`, fd, true);
            close(); toast((j && j.message) || 'Group imported', 'ok');
            await reloadSuite();
          } catch (e) { toast(e.message, 'err'); }
        } }
    ]
  });
}

function clearSuiteConfirm() {
  confirmModal('Clear suite?',
    'The current suite (including results) is removed from the session. Export first if you need it.',
    async () => {
      try {
        await api('DELETE', '/api/suite');
        S.suite = null;
        S.ui.group = '*'; S.ui.q = ''; S.ui.sel.clear(); S.ui.open.clear(); S.ui.drawer = null;
        S.live.plan = []; S.live.overlay.clear(); S.live.prog = null; S.live.state = 'idle'; S.live.flash = [];
        renderAll(); drawRail();
        toast('Suite cleared');
      } catch (e) { toast(e.message, 'err'); }
    }, 'Clear');
}

/* ── drawer save ──────────────────────────────────────────────────────────── */
async function saveDrawerEdit() {
  if (!S.ui.drawer) return;
  const { g, id } = S.ui.drawer;
  const form = $('#req-form', $('#drawer'));
  const base = findReq(g, id);
  if (!form || !base) return;
  const bodyObj = readRequestForm(form, structuredClone(base));
  try {
    await api('PUT', `/api/groups/${encodeURIComponent(g)}/cases/${encodeURIComponent(id)}`, bodyObj);
    S.ui.dwEdit = false;
    toast('Request saved', 'ok');
    await reloadSuite();   // testCaseId changes re-normalize defs server-side
  } catch (e) { toast(e.message, 'err'); }
}

/* ── event wiring ─────────────────────────────────────────────────────────── */
function renderAll() {
  renderTop(); renderSide(); renderFilter(); renderBulk(); renderTable(); renderFeed();
  if (S.ui.drawer) renderDrawer(); else $('#drawer').classList.remove('open');
}

function onDocClick(e) {
  const cp = e.target.closest('[data-copy]');
  if (cp) { copyText(cp.getAttribute('data-copy'), cp); return; }

  const menuBtn = e.target.closest('[data-act="menu"]');
  const insideMenu = e.target.closest('.menu');
  $$('.menu.open').forEach(mn => {
    if (menuBtn && $('#' + menuBtn.dataset.menu) === mn) return;
    if (mn === insideMenu) return;
    mn.classList.remove('open');
  });
  if (menuBtn) { const mn = $('#' + menuBtn.dataset.menu); if (mn) mn.classList.toggle('open'); return; }

  const t = e.target.closest('[data-act]');
  if (!t) return;
  const act = t.dataset.act;
  if (act === 'menu') return;
  if (t.tagName === 'TR' && e.target.closest('[data-stop]')) return;

  const g = t.dataset.g, id = t.dataset.id, tc = t.dataset.tc;

  switch (act) {
    case 'run-all': runAll(); break;
    case 'run-failed': runFailed(); break;
    case 'run-selected': runSelected(); break;
    case 'run-group-current': if (S.ui.group !== '*') runGroup(S.ui.group); break;
    case 'run-group': runGroup(g); break;
    case 'run-tc': runTC(g, tc); break;
    case 'run-req': runReqSync(g, id); break;
    case 'stop': stopRun(); break;

    case 'chip': S.ui.status = t.dataset.status; renderFilter(); renderTable(); break;
    case 'side-group': S.ui.group = g; renderSide(); renderFilter(); renderTable(); renderStats(); break;
    case 'grp-menu': groupModal(g); break;

    case 'tc-toggle': {
      const k = key(g, tc);
      if (S.ui.open.has(k)) S.ui.open.delete(k); else S.ui.open.add(k);
      renderTable(); break;
    }
    case 'sel-tc': {
      const k = t.dataset.k;
      if (t.checked) S.ui.sel.add(k); else S.ui.sel.delete(k);
      const row = t.closest('tr'); if (row) row.classList.toggle('selected', t.checked);
      renderBulk(); renderStats(); syncSelAll(); break;
    }
    case 'open-req': openDrawer(g, id); break;
    case 'drawer-close': closeDrawer(); break;
    case 'dw-tab': S.ui.dwTab = t.dataset.tab; S.ui.dwEdit = false; renderDrawer(); break;
    case 'dw-edit': S.ui.dwEdit = true; renderDrawer(); break;
    case 'dw-cancel': S.ui.dwEdit = false; renderDrawer(); break;
    case 'dw-save': saveDrawerEdit(); break;
    case 'req-move': moveModal(g, [id]); break;
    case 'req-toggle': toggleReq(g, id); break;
    case 'req-delete':
      confirmModal('Delete request?', `Request “${id}” will be removed from ${g}.`, async () => {
        try {
          await api('DELETE', `/api/groups/${encodeURIComponent(g)}/cases/${encodeURIComponent(id)}`);
          toast('Request deleted');
          if (S.ui.drawer && S.ui.drawer.id === id) closeDrawer();
          await reloadSuite();
        } catch (er) { toast(er.message, 'err'); }
      }, 'Delete');
      break;
    case 'tc-edit': tcEditModal(g, tc); break;

    case 'bulk-enable': bulkSetEnabled(true); break;
    case 'bulk-disable': bulkSetEnabled(false); break;
    case 'bulk-move': bulkMove(); break;
    case 'bulk-clear': S.ui.sel.clear(); renderTable(); renderBulk(); renderStats(); break;

    case 'feed-open': revealReq(g, id); break;
    case 'req-new': newRequestModal(); break;
    case 'grp-new': newGroupModal(); break;
    case 'cfg-open': cfgModal(); break;
    case 'import-suite': $('#fileSuite').click(); break;
    case 'import-group': $('#fileGroup').click(); break;
    case 'clear-suite': clearSuiteConfirm(); break;
  }

  if (t.closest('.menu')) $$('.menu.open').forEach(mn => mn.classList.remove('open'));
}

function bulkMove() {
  if (!S.ui.sel.size) return;
  const gNames = new Set([...S.ui.sel].map(k => splitKey(k)[0]));
  if (gNames.size > 1) { toast('Move works within one group — select test cases from a single group', 'err'); return; }
  const gName = [...gNames][0];
  const g = findGroup(gName); if (!g) return;
  const tcIds = new Set([...S.ui.sel].map(k => splitKey(k)[1]));
  const requestIds = g.testRequests.filter(r => tcIds.has(r.testCaseId || r.id)).map(r => r.id);
  moveModal(gName, requestIds);
}

function bindStatic() {
  document.addEventListener('click', onDocClick);

  document.addEventListener('keydown', e => {
    if (e.key !== 'Escape') return;
    const veils = $$('#modal-root .modal-veil');
    if (veils.length) { veils[veils.length - 1].remove(); return; }
    if (S.ui.drawer) { closeDrawer(); S.ui.drawer = null; return; }
    $$('.menu.open').forEach(mn => mn.classList.remove('open'));
  });

  window.addEventListener('resize', debounce(drawRail, 150));

  $('#selAll').addEventListener('change', e => {
    const boxes = $$('#tct-body [data-act="sel-tc"]');
    for (const b of boxes) {
      b.checked = e.target.checked;
      const k = b.dataset.k;
      if (e.target.checked) S.ui.sel.add(k); else S.ui.sel.delete(k);
      const row = b.closest('tr'); if (row) row.classList.toggle('selected', b.checked);
    }
    renderBulk(); renderStats();
  });

  $('#grp-search').addEventListener('input', debounce(e => {
    S.ui.gq = e.target.value.trim(); renderSide();
  }, 150));

  $('#fileSuite').addEventListener('change', e => {
    const f = e.target.files[0]; e.target.value = '';
    uploadSuite(f);
  });
  $('#fileGroup').addEventListener('change', e => {
    const f = e.target.files[0]; e.target.value = '';
    if (f) importGroupModal(f);
  });

  const dz = $('#dropzone');
  dz.addEventListener('click', () => $('#fileSuite').click());
  dz.addEventListener('keydown', e => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); $('#fileSuite').click(); } });
  dz.addEventListener('dragover', e => { e.preventDefault(); dz.classList.add('over'); });
  dz.addEventListener('dragleave', () => dz.classList.remove('over'));
  dz.addEventListener('drop', e => { e.preventDefault(); dz.classList.remove('over'); uploadSuite(e.dataTransfer.files[0]); });
}

/* ── boot ─────────────────────────────────────────────────────────────────── */
async function boot() {
  bindStatic();
  try {
    const j = await api('GET', '/api/suite');
    S.suite = (j && j.data) || null;
  } catch { S.suite = null; }

  try {
    const j = await api('GET', '/api/execute/progress');
    const p = (j && j.data) || {};
    if (p.state === 'running' || p.state === 'stopping') {
      // resume live tracking after a page reload
      S.live.on = true; S.live.state = p.state; S.live.prog = p;
      S.live.plan = p.scopeKeys || [];
      for (const k of S.live.plan) S.live.overlay.set(k, 'queued');
      for (const en of p.recent || []) {
        S.live.seen.add(en.group + '::' + en.requestId + '@' + en.at);
        S.live.overlay.set(key(en.group, en.requestId), en.status);
        S.live.flash.push(en);
      }
      for (const k of p.active || []) S.live.overlay.set(k, 'running');
      S.live.timer = setInterval(poll, 1000);
    } else if ((p.scopeKeys || []).length) {
      // show the last run's rail, colored from persisted results
      S.live.plan = p.scopeKeys; S.live.prog = p; S.live.state = p.state || 'idle';
    }
  } catch { /* no progress info */ }

  renderAll();
  drawRail();
}

document.addEventListener('DOMContentLoaded', boot);
