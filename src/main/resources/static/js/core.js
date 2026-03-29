// ─── Global State ─────────────────────────────────────────────────────────────
let suite = null;
let currentGroup = null;
let pollTimer = null;

// ─── API ──────────────────────────────────────────────────────────────────────
async function api(method, path, body) {
  const opts = { method, headers: { 'Content-Type': 'application/json' } };
  if (body !== undefined) opts.body = JSON.stringify(body);
  return (await fetch('/api' + path, opts)).json();
}

async function uploadFile(file) {
  const fd = new FormData();
  fd.append('file', file);
  return (await fetch('/api/suite/import', { method: 'POST', body: fd })).json();
}

// ─── Utils ────────────────────────────────────────────────────────────────────
function g(id)  { return (document.getElementById(id)?.value || '').trim(); }
function sv(id, val) { const el = document.getElementById(id); if (el) el.value = val ?? ''; }
function vis(id, show) { const el = document.getElementById(id); if (el) el.style.display = show ? '' : 'none'; }

function esc(s) {
  return String(s || '')
    .replace(/&/g, '&amp;').replace(/</g, '&lt;')
    .replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}

function toast(msg, isErr = false) {
  const el = document.createElement('div');
  el.className = 'toast ' + (isErr ? 'toast-err' : 'toast-ok');
  el.textContent = msg;
  document.body.appendChild(el);
  setTimeout(() => el.remove(), 2800);
}

function openModal(id)  { document.getElementById(id).classList.add('open'); }
function closeModal(id) { document.getElementById(id).classList.remove('open'); }

// ─── Navigation ───────────────────────────────────────────────────────────────
function showPage(id) {
  document.querySelectorAll('.page').forEach(p => p.classList.remove('active'));
  document.getElementById(id).classList.add('active');
}

function showPanel(id) {
  document.querySelectorAll('.panel').forEach(p => p.classList.remove('active'));
  document.getElementById('panel-' + id)?.classList.add('active');
  document.querySelectorAll('.nav-item').forEach(i => i.classList.remove('active'));
  document.getElementById('nav-' + (id === 'groupDetail' ? 'groups' : id))?.classList.add('active');
}

function showSuiteView() {
  showPage('suite');
  document.getElementById('btnClear').style.display = '';
  document.getElementById('exportWrap').style.display = '';
  showPanel('settings');
}

// ─── Export dropdown ──────────────────────────────────────────────────────────
function toggleExport() { document.getElementById('exportMenu').classList.toggle('open'); }
document.addEventListener('click', e => {
  if (!e.target.closest('.export-wrap')) document.getElementById('exportMenu')?.classList.remove('open');
});

// ─── Landing ──────────────────────────────────────────────────────────────────
async function importFile(input) {
  const file = input.files[0];
  if (!file) return;
  await importSuiteFile(file);
}

async function handleSuiteDrop(event) {
  // kept for backwards compat but logic now handled by document-level handler
}

async function importSuiteFile(file) {
  const res = await uploadFile(file);
  if (res.success) {
    suite = res.data;
    renderSuite();
    showSuiteView();
    toast('Imported: ' + (suite.settings?.suiteName || ''));
  } else {
    alert('Import failed: ' + res.message);
  }
}

// ─── Full-page drag & drop ────────────────────────────────────────────────────
(function () {
  const getOverlay = () => document.getElementById('dropOverlay');
  const onLanding  = () => !!document.getElementById('landing')?.classList.contains('active');

  // Show overlay when drag enters document — only on landing page
  document.addEventListener('dragenter', e => {
    if (!onLanding()) return;
    e.preventDefault();
    getOverlay().style.display = 'block';
  });

  // Keep browser from navigating/downloading on any drop outside overlay
  document.addEventListener('dragover', e => e.preventDefault());
  document.addEventListener('drop',     e => e.preventDefault());
})();

function handleOverlayLeave(e) {
  // Only hide if leaving to outside the overlay itself (relatedTarget outside)
  if (!e.relatedTarget || !document.getElementById('dropOverlay').contains(e.relatedTarget)) {
    document.getElementById('dropOverlay').style.display = 'none';
  }
}

async function handleOverlayDrop(e) {
  e.preventDefault();
  document.getElementById('dropOverlay').style.display = 'none';

  // Try dataTransfer.files first, fall back to items (Chrome download bar uses items)
  let file = e.dataTransfer?.files?.[0];
  if (!file && e.dataTransfer?.items?.length > 0) {
    for (const item of e.dataTransfer.items) {
      if (item.kind === 'file') { file = item.getAsFile(); break; }
    }
  }

  if (!file) { toast('No file detected', true); return; }
  const ext = file.name.split('.').pop().toLowerCase();
  if (!['xlsx', 'xls', 'xml'].includes(ext)) {
    toast('Please drop an Excel (.xlsx) or XML file', true);
    return;
  }
  await importSuiteFile(file);
}

async function createSuite() {
  const name = g('cm-name');
  if (!name) { alert('Suite name required'); return; }
  const now = new Date().toISOString().split('T')[0];
  const res = await api('POST', '/suite/new', {
    settings: {
      suiteName: name, description: g('cm-desc'),
      version: g('cm-version') || '1.0', createdBy: g('cm-author'), createdDate: now,
      executionConfig: { mode: 'PARALLEL', timeout: 30, parallelLimit: 10, delayBetweenRequests: 100, retries: 2 },
      comparisonConfig: { ignoreFieldsRaw: '', caseSensitive: true, ignoreArrayOrder: false, numericTolerance: 0.001, compareErrorResponses: false }
    },
    environments: [], authProfiles: [], testGroups: []
  });
  if (res.success) {
    suite = res.data;
    closeModal('createModal');
    renderSuite();
    showSuiteView();
  }
}

async function clearSuite() {
  if (!confirm('Clear current session?')) return;
  await api('DELETE', '/suite');
  suite = null; currentGroup = null; stopPolling();
  document.getElementById('btnClear').style.display = 'none';
  document.getElementById('exportWrap').style.display = 'none';
  document.getElementById('suiteBadge').textContent = 'No suite loaded';
  document.getElementById('suiteBadge').classList.remove('loaded');
  showPage('landing');
  document.getElementById('landing').classList.add('active');
}

// ─── Render suite ─────────────────────────────────────────────────────────────
function renderSuite() {
  if (!suite) return;
  const s = suite.settings || {}, ec = s.executionConfig || {}, cc = s.comparisonConfig || {};

  document.getElementById('suiteBadge').textContent = s.suiteName || 'Unnamed Suite';
  document.getElementById('suiteBadge').classList.add('loaded');

  // Settings panel
  sv('s-name', s.suiteName);      sv('s-version', s.version);
  sv('s-desc', s.description);    sv('s-createdBy', s.createdBy);
  sv('s-createdDate', s.createdDate); sv('s-updatedBy', s.lastUpdatedBy);
  sv('s-updatedDate', s.lastUpdatedDate);
  sv('s-mode', ec.mode || 'PARALLEL'); sv('s-timeout', ec.timeout);
  sv('s-parallelLimit', ec.parallelLimit); sv('s-delay', ec.delayBetweenRequests);
  sv('s-retries', ec.retries);
  sv('s-ignoreFields', cc.ignoreFieldsRaw);
  sv('s-caseSensitive', String(cc.caseSensitive !== false));
  sv('s-ignoreArrayOrder', String(!!cc.ignoreArrayOrder));
  sv('s-tolerance', cc.numericTolerance);
  sv('s-compareErrorResponses', String(!!cc.compareErrorResponses));

  // Config panels
  renderEnvTable(suite.environments || []);
  populateEnvSelects(suite.environments || []);
  sv('s-sourceEnv', ec.sourceEnvironment || '');
  sv('s-targetEnv', ec.targetEnvironment || '');
  renderAuthProfiles(suite.authProfiles || []);

  // Groups
  renderGroupGrid(suite.testGroups || []);
  if (typeof renderResultsPanel === 'function') renderResultsPanel();
}

// ─── Modal close on backdrop ──────────────────────────────────────────────────
document.addEventListener('DOMContentLoaded', () => {
  document.querySelectorAll('.overlay').forEach(o =>
    o.addEventListener('click', e => { if (e.target === o) o.classList.remove('open'); })
  );
});

// ─── Init ─────────────────────────────────────────────────────────────────────
(async () => {
  const res = await api('GET', '/suite');
  if (res.success && res.data) {
    suite = res.data;
    renderSuite();
    showSuiteView();
  }
})();
