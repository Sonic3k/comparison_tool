// ── Global State ──────────────────────────────────────────────────────────────
const state = {
  suites:        [],     // TestSuite[] loaded in registry
  activeSuiteId: null,   // currently selected suite
  tasks:         [],     // ExecutionTask[] (recent 10)
  currentPage:   'suites',
  currentGroup:  null,   // group name when on testcases page
  activeVm:      'all',  // Verification Mode filter for this UI session
};

// ── API Client ────────────────────────────────────────────────────────────────
async function api(method, path, body) {
  const opts = {
    method,
    headers: { 'Content-Type': 'application/json' },
  };
  if (body !== undefined) opts.body = JSON.stringify(body);
  try {
    const res = await fetch('/api' + path, opts);
    const json = await res.json();
    return json;
  } catch (e) {
    return { success: false, message: e.message };
  }
}

async function apiUpload(path, formData) {
  try {
    const res = await fetch('/api' + path, { method: 'POST', body: formData });
    return await res.json();
  } catch (e) {
    return { success: false, message: e.message };
  }
}

// ── Toast ─────────────────────────────────────────────────────────────────────
function toast(msg, type = 'success', duration = 3000) {
  const el = document.createElement('div');
  el.className = `toast toast-${type}`;
  el.textContent = msg;
  document.getElementById('toast-container').appendChild(el);
  setTimeout(() => el.remove(), duration);
}

// ── Router ────────────────────────────────────────────────────────────────────
const pages = {};
function registerPage(name, module) { pages[name] = module; }

let currentPageInstance = null;
function navigate(page, params = {}) {
  if (currentPageInstance?.destroy) currentPageInstance.destroy();

  state.currentPage = page;
  state.currentGroup = params.group || null;

  // Update sidebar active
  document.querySelectorAll('.nav-item').forEach(el => {
    el.classList.toggle('active', el.dataset.page === page);
  });

  // Update breadcrumb
  renderBreadcrumb(page, params);

  // Render page
  const main = document.getElementById('main');
  if (pages[page]) {
    currentPageInstance = pages[page];
    pages[page].render(main, params);
  } else {
    main.innerHTML = `<div class="page"><div class="empty-state"><div class="empty-icon">🚧</div><div class="empty-title">Page not found: ${page}</div></div></div>`;
  }
}

function renderBreadcrumb(page, params) {
  const bc = document.getElementById('breadcrumb');
  const suite = state.suites.find(s => s.id === state.activeSuiteId);
  const suiteName = suite?.settings?.suiteName || 'No suite';
  const crumbs = {
    suites:    [['Suites']],
    groups:    [[suiteName, () => navigate('groups')], ['Groups']],
    testcases: [[suiteName, () => navigate('groups')], ['Groups'], [params.group || '']],
    results:   [['Results']],
    config:    [[suiteName, () => navigate('groups')], ['Config']],
  };
  const trail = crumbs[page] || [[page]];
  bc.innerHTML = trail.map((c, i) => {
    const isLast = i === trail.length - 1;
    const label = c[0];
    const fn = c[1];
    if (isLast) return `<span class="breadcrumb-current">${esc(label)}</span>`;
    if (fn) return `<span class="breadcrumb-link" onclick="(${fn.toString()})()" style="cursor:pointer;color:var(--c-primary)">${esc(label)}</span><span class="breadcrumb-sep">/</span>`;
    return `<span>${esc(label)}</span><span class="breadcrumb-sep">/</span>`;
  }).join('');
}

// ── Suite Selector ────────────────────────────────────────────────────────────
function renderSuiteSelector() {
  const suite = state.suites.find(s => s.id === state.activeSuiteId);
  const el = document.getElementById('suiteSelectorLabel');
  if (el) el.textContent = suite?.settings?.suiteName || 'Select suite';
}

function setActiveSuite(id) {
  state.activeSuiteId = id;
  renderSuiteSelector();
  renderBreadcrumb(state.currentPage, { group: state.currentGroup });
}

// ── Verification Mode Bar ─────────────────────────────────────────────────────
function setVm(vm) {
  state.activeVm = vm;
  document.querySelectorAll('.vm-btn').forEach(btn => {
    btn.classList.toggle('active', btn.dataset.vm === vm);
  });
}

function getVmPayload() {
  return state.activeVm === 'all' ? null : state.activeVm;
}

// ── Run Controls ──────────────────────────────────────────────────────────────
async function runSuite(groupFilter = []) {
  if (!state.activeSuiteId) { toast('Select a suite first', 'error'); return; }
  const res = await api('POST', '/tasks', {
    suiteId: state.activeSuiteId,
    groups: groupFilter,
    verificationMode: getVmPayload(),
  });
  if (res.success) {
    toast(`Task queued: ${res.data.suiteName}`, 'success');
    state.tasks.unshift(res.data);
    renderMonitor();
    startTaskPolling();
  } else {
    toast(res.message || 'Failed to queue task', 'error');
  }
}

// ── Task Polling ──────────────────────────────────────────────────────────────
let pollTimer = null;
function startTaskPolling() {
  if (pollTimer) return;
  pollTimer = setInterval(pollTasks, 1500);
}
function stopTaskPolling() {
  clearInterval(pollTimer);
  pollTimer = null;
}

async function pollTasks() {
  const res = await api('GET', '/tasks');
  if (!res.success) return;
  const prev = state.tasks;
  state.tasks = res.data || [];
  renderMonitor();

  // Update nav badge
  const active = state.tasks.filter(t => t.status === 'pending' || t.status === 'in_progress');
  const badge = document.getElementById('tasksBadge');
  if (badge) {
    badge.textContent = active.length;
    badge.style.display = active.length ? '' : 'none';
  }

  // Stop polling when nothing active
  if (active.length === 0) stopTaskPolling();

  // If on results page, refresh
  if (state.currentPage === 'results' && pages.results?.refresh) {
    pages.results.refresh();
  }
}

// ── Monitor Bar ───────────────────────────────────────────────────────────────
let monitorExpanded = false;

function renderMonitor() {
  const active = state.tasks.filter(t => ['pending','in_progress'].includes(t.status));
  const bar = document.getElementById('monitorBar');
  const label = document.getElementById('monitorLabel');
  const chips = document.getElementById('monitorChips');
  const expandedArea = document.getElementById('monitorExpandedArea');

  if (label) {
    const run = active.filter(t => t.status === 'in_progress').length;
    const pend = active.filter(t => t.status === 'pending').length;
    label.textContent = active.length
      ? `${run} running${pend ? `, ${pend} pending` : ''}`
      : 'No active tasks';
  }

  // Chips (compact view)
  if (chips) {
    chips.innerHTML = active.slice(0, 4).map(t => `
      <div class="monitor-chip">
        <div class="task-status-dot dot-${t.status}"></div>
        <span class="monitor-chip-name">${esc(t.suiteName)}</span>
        <div class="monitor-chip-prog">
          <div class="monitor-chip-prog-fill fill-${t.status}" style="width:${t.percent}%"></div>
        </div>
        <span style="font-size:10px;color:#64748b">${t.percent}%</span>
      </div>`).join('');
  }

  // Expanded list
  if (expandedArea) {
    expandedArea.innerHTML = state.tasks.slice(0, 10).map(t => `
      <div class="task-row" onclick="navigate('results', {taskId:'${t.taskId}'})">
        <div class="task-status-dot dot-${t.status}"></div>
        <div class="task-row-name">${esc(t.suiteName)}</div>
        <span class="task-row-mode">${t.verificationMode || 'all'}</span>
        <div class="task-row-progress">
          <div class="task-row-progress-fill fill-${t.status}" style="width:${t.percent}%"></div>
        </div>
        <span class="task-row-pct">${t.percent}%</span>
        ${t.status === 'in_progress'
          ? `<span style="font-size:11px;color:#94a3b8">${t.done}/${t.total}</span>`
          : `<span style="font-size:11px;color:#94a3b8">${t.status}</span>`}
        ${t.status === 'pending'
          ? `<button class="task-row-cancel" onclick="event.stopPropagation();cancelTask('${t.taskId}')">✕</button>`
          : ''}
      </div>`).join('') || '<div style="text-align:center;padding:16px;color:#475569;font-size:12px">No tasks yet</div>';
  }
}

function toggleMonitor() {
  monitorExpanded = !monitorExpanded;
  document.getElementById('app').classList.toggle('monitor-expanded', monitorExpanded);
  const btn = document.getElementById('monitorToggle');
  if (btn) btn.textContent = monitorExpanded ? '▲' : '▲';
}

async function cancelTask(taskId) {
  const res = await api('DELETE', `/tasks/${taskId}`);
  if (res.success) { toast('Task cancelled'); pollTasks(); }
  else toast(res.message, 'error');
}

// ── Suite Dropdown ────────────────────────────────────────────────────────────
function toggleSuiteDropdown() {
  const menu = document.getElementById('suiteDropdownMenu');
  menu.classList.toggle('hidden');
  // Re-render options
  menu.innerHTML = state.suites.map(s => `
    <div class="dropdown-item ${s.id === state.activeSuiteId ? 'font-bold' : ''}"
         onclick="selectSuite('${s.id}')">
      ${s.id === state.activeSuiteId ? '✓ ' : ''}${esc(s.settings?.suiteName || s.id)}
    </div>`).join('') +
    `<div class="dropdown-divider"></div>
     <div class="dropdown-item" onclick="navigate('suites')">📂 Manage suites…</div>`;
}

function selectSuite(id) {
  setActiveSuite(id);
  document.getElementById('suiteDropdownMenu').classList.add('hidden');
  navigate('groups');
}

// ── Init ──────────────────────────────────────────────────────────────────────
async function initApp() {
  // Close dropdowns on outside click
  document.addEventListener('click', e => {
    if (!e.target.closest('.dropdown-wrap'))
      document.querySelectorAll('.dropdown-menu').forEach(m => m.classList.add('hidden'));
  });

  // Load suites
  const res = await api('GET', '/suites');
  if (res.success && res.data) {
    state.suites = Array.isArray(res.data) ? res.data : Object.values(res.data);
    if (state.suites.length) setActiveSuite(state.suites[0].id);
  }

  // Load tasks
  const tr = await api('GET', '/tasks');
  if (tr.success) {
    state.tasks = tr.data || [];
    renderMonitor();
    const hasActive = state.tasks.some(t => ['pending','in_progress'].includes(t.status));
    if (hasActive) startTaskPolling();
    // Update badge
    const active = state.tasks.filter(t => ['pending','in_progress'].includes(t.status));
    const badge = document.getElementById('tasksBadge');
    if (badge && active.length) { badge.textContent = active.length; badge.style.display = ''; }
  }

  renderSuiteSelector();
  navigate('suites');
}

// ── Utils ─────────────────────────────────────────────────────────────────────
function esc(s) {
  if (s == null) return '';
  return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;')
    .replace(/"/g,'&quot;').replace(/'/g,'&#39;');
}

function vmBadge(vm) {
  const map = {
    comparison: ['badge-comparison','CMP'],
    automation: ['badge-automation','AUTO'],
    both: ['badge-both','BOTH'],
  };
  const [cls, label] = map[vm] || ['badge-comparison','CMP'];
  return `<span class="badge ${cls}">${label}</span>`;
}

function statusBadge(s) {
  const map = {
    passed:  'badge-pass',  failed: 'badge-fail',
    error:   'badge-error', pending: 'badge-pending',
  };
  return `<span class="badge ${map[s] || 'badge-pending'}">${s || 'pending'}</span>`;
}

function taskStatusBadge(s) {
  const map = {
    in_progress: 'badge-inprogress',
    completed:   'badge-completed',
    failed:      'badge-fail',
    pending:     'badge-pending',
    cancelled:   'badge-cancelled',
  };
  return `<span class="badge ${map[s] || 'badge-pending'}">${(s||'').replace('_',' ')}</span>`;
}

function fmt(n, d=0) { return (n||0).toFixed(d); }

function passRate(p, t) { return t ? Math.round(p/t*100) : 0; }

function passRingSvg(rate) {
  const r = 28, c = 2*Math.PI*r;
  const fill = c - (c * rate / 100);
  return `<svg width="72" height="72" viewBox="0 0 72 72">
    <circle class="pass-ring-track" cx="36" cy="36" r="${r}"/>
    <circle class="pass-ring-fill" cx="36" cy="36" r="${r}"
      stroke-dasharray="${c}" stroke-dashoffset="${fill}"/>
  </svg>`;
}
