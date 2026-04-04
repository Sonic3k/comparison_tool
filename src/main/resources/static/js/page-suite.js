reg('suites', {
  render(el) {
    el.innerHTML = `
    <div class="page">
      <div class="page-hd">
        <div><div class="page-title">Test Suites</div><div class="page-sub">${S.suites.length} suite(s) loaded</div></div>
        <div class="page-acts">
          <button class="btn btn-ghost btn-sm" onclick="window.location.href='/api/export/template/excel'">⬇ Template</button>
        </div>
      </div>

      <div class="drop-zone" id="dropZone">
        <div class="drop-icon">📂</div>
        <div class="drop-title">Import Test Suite</div>
        <div class="drop-sub">Drag & drop .xlsx or .xml, or click to browse</div>
        <input type="file" id="fileInput" accept=".xlsx,.xml" style="display:none" onchange="handleFile(this.files[0])">
      </div>

      <div style="margin-top:24px">
        <div class="sec-hd"><span class="sec-title">Loaded Suites</span></div>
        <div id="suiteList"></div>
      </div>
    </div>`;

    const dz = document.getElementById('dropZone');
    dz.onclick = () => document.getElementById('fileInput').click();
    dz.ondragover = e => { e.preventDefault(); dz.classList.add('drag-over'); };
    dz.ondragleave = () => dz.classList.remove('drag-over');
    dz.ondrop = e => { e.preventDefault(); dz.classList.remove('drag-over'); handleFile(e.dataTransfer.files[0]); };

    renderSuiteList();
  },
  destroy() {}
});

function renderSuiteList() {
  const el = document.getElementById('suiteList');
  if (!el) return;
  if (!S.suites.length) {
    el.innerHTML = `<div class="empty"><div class="empty-icon">📋</div><div class="empty-title">No suites loaded</div><div class="empty-sub">Import an Excel or XML file above</div></div>`;
    return;
  }
  el.innerHTML = S.suites.map(s => {
    const sets = s.settings || {};
    const groups = s.testGroups || [];
    const total = groups.reduce((a,g) => a + (g.testCases?.length||0), 0);
    const enabled = groups.reduce((a,g) => a + (g.testCases?.filter(tc=>tc.enabled).length||0), 0);
    const isActive = s.id === S.active;

    // Check if this suite has a running task
    const runningTask = S.tasks.find(t => t.suiteId === s.id && t.status === 'in_progress');
    const pendingTask = S.tasks.find(t => t.suiteId === s.id && t.status === 'pending');

    return `
    <div class="suite-item ${isActive?'si-active':''}" onclick="pickSuiteAndGo('${s.id}')">
      <div class="si-icon">📋</div>
      <div class="si-info">
        <div class="si-name">${esc(sets.suiteName||'Unnamed Suite')}</div>
        <div class="si-meta">${groups.length} groups · ${enabled}/${total} TCs enabled${sets.version?' · v'+esc(sets.version):''}${sets.lastUpdatedBy?' · '+esc(sets.lastUpdatedBy):''}</div>
      </div>
      ${runningTask ? `<div class="si-running"><span class="t-dot t-dot-in_progress"></span>${runningTask.percent}%</div>` :
        pendingTask ? `<div class="si-running" style="color:var(--muted)"><span class="t-dot t-dot-pending"></span>pending</div>` : ''}
      <div class="si-acts" onclick="event.stopPropagation()">
        ${isActive ? `<span class="badge b-cmp" style="font-size:10px">active</span>` : ''}
        <button class="btn btn-ghost btn-xs" onclick="exportSuite('${s.id}','excel')">⬇ xlsx</button>
        <button class="btn btn-ghost btn-xs" onclick="exportSuite('${s.id}','xml')">⬇ xml</button>
        <button class="btn btn-ghost btn-xs" onclick="quickRun('${s.id}')">▶ Run</button>
        <button class="btn btn-ghost btn-xs" style="color:var(--fail)" onclick="removeSuite('${s.id}')">✕</button>
      </div>
    </div>`;
  }).join('');
}

async function handleFile(file) {
  if (!file) return;
  const fd = new FormData(); fd.append('file', file);
  const r = await upload('/suites/import', fd);
  if (r.success) {
    const suite = r.data;
    const i = S.suites.findIndex(s => s.id === suite.id);
    if (i >= 0) S.suites[i] = suite; else S.suites.push(suite);
    setActive(suite.id);
    toast(`Imported: ${suite.settings?.suiteName}`);
    renderSuiteList();
    document.querySelector('.page-sub').textContent = `${S.suites.length} suite(s) loaded`;
  } else toast(r.message || 'Import failed', 'err');
}

function pickSuiteAndGo(id) { setActive(id); go('groups'); }

async function removeSuite(id) {
  if (!confirm('Remove this suite?')) return;
  const r = await api('DELETE', `/suites/${id}`);
  if (r.success) {
    S.suites = S.suites.filter(s => s.id !== id);
    if (S.active === id) setActive(S.suites[0]?.id || null);
    toast('Suite removed'); renderSuiteList();
  } else toast(r.message, 'err');
}

async function quickRun(suiteId) {
  setActive(suiteId);
  await runSuite([]);
  go('results');
}

function exportSuite(id, fmt) { window.location.href = `/api/suites/${id}/export/${fmt}`; }
