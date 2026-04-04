// ── State ─────────────────────────────────────────────────────────────────────
const S = {
  suites:  [],      // TestSuite[] in registry
  active:  null,    // active suiteId
  tasks:   [],      // ExecutionTask[] history
  page:    'suites',
  group:   null,    // current group name (testcases page)
};

// ── API ───────────────────────────────────────────────────────────────────────
async function api(method, path, body) {
  try {
    const res = await fetch('/api' + path, {
      method,
      headers: { 'Content-Type': 'application/json' },
      body: body !== undefined ? JSON.stringify(body) : undefined,
    });
    return await res.json();
  } catch (e) { return { success: false, message: e.message }; }
}

async function upload(path, fd) {
  try {
    const res = await fetch('/api' + path, { method: 'POST', body: fd });
    return await res.json();
  } catch (e) { return { success: false, message: e.message }; }
}

// ── Toast ─────────────────────────────────────────────────────────────────────
function toast(msg, type = 'ok', ms = 3000) {
  const el = document.createElement('div');
  el.className = `toast toast-${type}`;
  el.textContent = msg;
  document.getElementById('toasts').appendChild(el);
  setTimeout(() => el.remove(), ms);
}

// ── Router ────────────────────────────────────────────────────────────────────
const pages = {};
function reg(name, mod) { pages[name] = mod; }

let _cur = null;
function go(page, params = {}) {
  _cur?.destroy?.();
  S.page  = page;
  S.group = params.group || null;

  document.querySelectorAll('.sb-item').forEach(el =>
    el.classList.toggle('active', el.dataset.page === page));

  renderBreadcrumb(page, params);

  const main = document.getElementById('main');
  _cur = pages[page] || null;
  _cur ? pages[page].render(main, params)
       : (main.innerHTML = `<div class="page"><div class="empty"><div class="empty-title">404</div></div></div>`);
}

function renderBreadcrumb(page, params) {
  const suite = activeSuite();
  const sName = suite?.settings?.suiteName || '—';

  const trails = {
    suites:    [['Suites']],
    groups:    [[sName, ()=>go('groups')], ['Groups']],
    testcases: [[sName, ()=>go('groups')], ['Groups', ()=>go('groups')], [params.group||'']],
    results:   [[sName, ()=>go('groups')], ['Results']],
    config:    [[sName, ()=>go('groups')], ['Config']],
  };

  const trail = trails[page] || [[page]];
  document.getElementById('breadcrumb').innerHTML = trail.map((c, i) => {
    const isLast = i === trail.length - 1;
    if (isLast) return `<span class="hd-bc-cur">${esc(c[0])}</span>`;
    if (c[1])   return `<span class="hd-bc-link" onclick="(${c[1].toString()})()">${esc(c[0])}</span><span class="hd-bc-sep">/</span>`;
    return `<span>${esc(c[0])}</span><span class="hd-bc-sep">/</span>`;
  }).join('');
}

// ── Suite helpers ─────────────────────────────────────────────────────────────
function activeSuite() { return S.suites.find(s => s.id === S.active) || null; }

function setActive(id) {
  S.active = id;
  const suite = activeSuite();
  const el = document.getElementById('suiteSelName');
  if (el) el.textContent = suite?.settings?.suiteName || 'Select suite';
  renderBreadcrumb(S.page, { group: S.group });
}

async function refreshSuite(id) {
  const r = await api('GET', `/suites/${id}`);
  if (r.success) {
    const i = S.suites.findIndex(s => s.id === id);
    if (i >= 0) S.suites[i] = r.data; else S.suites.push(r.data);
  }
}

// ── Run ───────────────────────────────────────────────────────────────────────
async function runSuite(groups = []) {
  if (!S.active) { toast('Select a suite first', 'err'); return; }
  const r = await api('POST', '/tasks', { suiteId: S.active, groups });
  if (r.success) {
    toast(`Queued: ${r.data.suiteName}${groups.length ? ' ('+groups.join(', ')+')' : ''}`);
    S.tasks.unshift(r.data);
    renderMonitor();
    startPolling();
  } else toast(r.message || 'Failed', 'err');
}

// ── Suite dropdown ─────────────────────────────────────────────────────────────
function toggleSuiteDd() {
  const menu = document.getElementById('suiteDdMenu');
  menu.classList.toggle('hidden');
  menu.innerHTML = S.suites.map(s =>
    `<div class="dd-item ${s.id===S.active?'active':''}" onclick="pickSuite('${s.id}')">
      ${s.id===S.active?'✓ ':''}${esc(s.settings?.suiteName||s.id)}
    </div>`
  ).join('') +
  `<div class="dd-sep"></div>
   <div class="dd-item" onclick="go('suites');closeDd('suiteDdMenu')">📂 Manage suites…</div>`;
}

function pickSuite(id) {
  setActive(id); closeDd('suiteDdMenu'); go('groups');
}

// Run dropdown (groups)
function toggleRunDd() {
  const menu = document.getElementById('runDdMenu');
  menu.classList.toggle('hidden');
  const suite = activeSuite();
  const groups = suite?.testGroups || [];
  menu.innerHTML =
    `<div class="dd-item" onclick="runSuite([]);closeDd('runDdMenu')">▶ Run All</div>` +
    (groups.length ? '<div class="dd-sep"></div>' : '') +
    groups.map(g =>
      `<div class="dd-item" onclick="runSuite(['${esc(g.name)}']);closeDd('runDdMenu')">▶ ${esc(g.name)}</div>`
    ).join('');
}

function closeDd(id) { document.getElementById(id)?.classList.add('hidden'); }

// ── Polling ───────────────────────────────────────────────────────────────────
let _poll = null;

function startPolling() {
  if (_poll) return;
  _poll = setInterval(pollTasks, 1500);
}

function stopPolling() { clearInterval(_poll); _poll = null; }

async function pollTasks() {
  const r = await api('GET', '/tasks');
  if (!r.success) return;

  const prev = S.tasks;
  S.tasks = r.data || [];

  // Detect tasks that just completed → refresh suite to pull updated tc.result
  for (const t of S.tasks) {
    if (t.status === 'completed' || t.status === 'failed') {
      const was = prev.find(p => p.taskId === t.taskId);
      if (was && was.status === 'in_progress') {
        if (t.suiteId) await refreshSuite(t.suiteId);
      }
    }
  }

  renderMonitor();

  const active = S.tasks.filter(t => ['pending','in_progress'].includes(t.status));
  const badge = document.getElementById('taskBadge');
  if (badge) { badge.textContent = active.length; badge.style.display = active.length ? '' : 'none'; }

  // Refresh current page if relevant
  if (S.page === 'results') pages.results?.refresh?.();
  if (S.page === 'groups') {
    const suite = activeSuite();
    const el = document.getElementById('groupGrid');
    if (suite && el) renderGroupGrid(suite);
  }

  if (!active.length) stopPolling();
}

// ── Monitor ───────────────────────────────────────────────────────────────────
let _monOpen = false;

function renderMonitor() {
  const active = S.tasks.filter(t => ['pending','in_progress'].includes(t.status));

  // Label
  const lbl = document.getElementById('monLabel');
  if (lbl) {
    const run = active.filter(t => t.status === 'in_progress').length;
    const pend = active.filter(t => t.status === 'pending').length;
    lbl.textContent = active.length
      ? `${run ? run+' running' : ''}${run&&pend?', ':''}${pend ? pend+' pending' : ''}`
      : 'No active tasks';
  }

  // Chips
  const chips = document.getElementById('monChips');
  if (chips) {
    chips.innerHTML = active.slice(0,3).map(t => `
      <div class="mon-chip">
        <span class="t-dot t-dot-${t.status}"></span>
        <span class="mon-chip-name">${esc(t.suiteName)}</span>
        <div class="mon-chip-bar"><div class="mon-chip-fill t-fill-${t.status}" style="width:${t.percent}%"></div></div>
        <span style="font-size:10px;color:#64748b">${t.percent}%</span>
      </div>`).join('');
  }

  // Expanded body
  const body = document.getElementById('monBody');
  if (body) {
    body.innerHTML = S.tasks.slice(0,10).map(t => `
      <div class="task-row" onclick="go('results')">
        <span class="t-dot t-dot-${t.status}"></span>
        <span class="t-name">${esc(t.suiteName)}</span>
        <span class="t-groups">${t.groupFilter?.length ? t.groupFilter.join(', ') : 'all groups'}</span>
        <div class="t-bar"><div class="t-bar-fill t-fill-${t.status}" style="width:${t.percent}%"></div></div>
        <span class="t-pct">${t.percent}%</span>
        <span class="t-stat">${t.status === 'in_progress' ? t.done+'/'+t.total : t.status}</span>
        ${t.status === 'pending'
          ? `<button class="t-cancel" onclick="event.stopPropagation();cancelTask('${t.taskId}')">✕</button>`
          : ''}
      </div>`).join('') ||
      `<div style="text-align:center;padding:16px;font-size:12px;color:#475569">No tasks yet</div>`;
  }
}

function toggleMonitor() {
  _monOpen = !_monOpen;
  document.getElementById('app').classList.toggle('mon-open', _monOpen);
}

async function cancelTask(id) {
  const r = await api('DELETE', `/tasks/${id}`);
  r.success ? (toast('Task cancelled'), pollTasks()) : toast(r.message, 'err');
}

// ── Shared modal helpers ───────────────────────────────────────────────────────
function openModal(id)  { document.getElementById(id)?.classList.remove('hidden'); }
function closeModal(id) { document.getElementById(id)?.classList.add('hidden'); }

// ── Init ──────────────────────────────────────────────────────────────────────
async function init() {
  // Close dropdowns on outside click
  document.addEventListener('click', e => {
    if (!e.target.closest('.dd-wrap'))
      document.querySelectorAll('.dd-menu').forEach(m => m.classList.add('hidden'));
  });

  // Load suites
  const sr = await api('GET', '/suites');
  if (sr.success) {
    S.suites = Array.isArray(sr.data) ? sr.data : Object.values(sr.data || {});
    if (S.suites.length) setActive(S.suites[0].id);
  }

  // Load tasks
  const tr = await api('GET', '/tasks');
  if (tr.success) {
    S.tasks = tr.data || [];
    renderMonitor();
    const active = S.tasks.filter(t => ['pending','in_progress'].includes(t.status));
    if (active.length) {
      startPolling();
      const badge = document.getElementById('taskBadge');
      if (badge) { badge.textContent = active.length; badge.style.display = ''; }
    }
  }

  go('suites');
}

// ── Utils ─────────────────────────────────────────────────────────────────────
function esc(s) {
  return String(s??'').replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;');
}

function vmBadge(vm) {
  const m = { comparison:['b-cmp','CMP'], automation:['b-auto','AUTO'], both:['b-both','BOTH'] };
  const [cls, lbl] = m[vm] || ['b-cmp','CMP'];
  return `<span class="badge ${cls}">${lbl}</span>`;
}

function statusBadge(s) {
  const m = { passed:'b-pass', failed:'b-fail', error:'b-error', pending:'b-pending' };
  return `<span class="badge ${m[s]||'b-pending'}">${s||'pending'}</span>`;
}

function methodBadge(m) {
  const colors = {GET:'#0369a1',POST:'#059669',PUT:'#d97706',PATCH:'#7c3aed',DELETE:'#dc2626'};
  return `<span class="badge b-method" style="color:${colors[m]||'#475569'}">${m||'GET'}</span>`;
}

function passRate(p, t) { return t ? Math.round(p / t * 100) : 0; }

function ringSvg(rate) {
  const r = 26, c = 2 * Math.PI * r;
  const offset = c - c * rate / 100;
  return `<svg width="64" height="64" viewBox="0 0 64 64">
    <circle class="ring-track" cx="32" cy="32" r="${r}"/>
    <circle class="ring-fill"  cx="32" cy="32" r="${r}"
      stroke-dasharray="${c}" stroke-dashoffset="${offset}"
      stroke="${rate>=80?'var(--pass)':rate>=50?'var(--error)':'var(--fail)'}"/>
  </svg>`;
}
