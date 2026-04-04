// ── Suite Page ────────────────────────────────────────────────────────────────
registerPage('suites', {
  render(container) {
    container.innerHTML = `
    <div class="page">
      <div class="page-header">
        <div>
          <div class="page-title">Test Suites</div>
          <div class="page-subtitle">${state.suites.length} suite${state.suites.length !== 1 ? 's' : ''} loaded</div>
        </div>
        <div class="page-actions">
          <button class="btn btn-ghost btn-sm" onclick="downloadTemplate()">⬇ Template</button>
        </div>
      </div>

      <!-- Drop zone -->
      <div class="drop-zone" id="suiteDropZone">
        <div class="drop-icon">📂</div>
        <div class="drop-title">Import Test Suite</div>
        <div class="drop-sub">Drag & drop Excel (.xlsx) or XML file, or click to browse</div>
        <input type="file" id="suiteFileInput" accept=".xlsx,.xml" style="display:none" onchange="handleSuiteFile(this.files[0])">
      </div>

      <!-- Suite list -->
      <div style="margin-top:var(--sp-6)">
        <div class="section-header">
          <div class="section-title">Loaded Suites</div>
        </div>
        <div id="suiteList"></div>
      </div>
    </div>`;

    // Drop zone events
    const dz = document.getElementById('suiteDropZone');
    dz.onclick = () => document.getElementById('suiteFileInput').click();
    dz.ondragover = e => { e.preventDefault(); dz.classList.add('drag-over'); };
    dz.ondragleave = () => dz.classList.remove('drag-over');
    dz.ondrop = e => {
      e.preventDefault(); dz.classList.remove('drag-over');
      const file = e.dataTransfer.files[0];
      if (file) handleSuiteFile(file);
    };

    renderSuiteList();
  },
  destroy() {}
});

function renderSuiteList() {
  const el = document.getElementById('suiteList');
  if (!el) return;
  if (!state.suites.length) {
    el.innerHTML = `<div class="empty-state">
      <div class="empty-icon">📋</div>
      <div class="empty-title">No suites loaded</div>
      <div class="empty-sub">Import an Excel or XML file above to get started</div>
    </div>`;
    return;
  }
  el.innerHTML = state.suites.map(s => {
    const sets = s.settings || {};
    const groups = s.testGroups || [];
    const totalTCs = groups.reduce((a,g) => a + (g.testCases?.length || 0), 0);
    const isActive = s.id === state.activeSuiteId;
    return `
    <div class="suite-item ${isActive ? 'active' : ''}" onclick="selectAndGoToGroups('${s.id}')">
      <div class="suite-item-icon">📋</div>
      <div class="suite-item-info">
        <div class="suite-item-name">${esc(sets.suiteName || 'Unnamed Suite')}</div>
        <div class="suite-item-meta">
          ${groups.length} groups · ${totalTCs} TCs
          ${sets.version ? `· v${esc(sets.version)}` : ''}
          ${sets.lastUpdatedBy ? `· ${esc(sets.lastUpdatedBy)}` : ''}
        </div>
      </div>
      <div class="suite-item-actions" onclick="event.stopPropagation()">
        ${isActive ? '<span class="badge badge-comparison" style="font-size:11px">Active</span>' : ''}
        <button class="btn btn-ghost btn-xs" onclick="exportSuite('${s.id}','excel')" title="Export suite">⬇ Excel</button>
        <button class="btn btn-ghost btn-xs" onclick="exportSuite('${s.id}','xml')" title="Export XML">⬇ XML</button>
        <button class="btn btn-ghost btn-xs" onclick="runSuiteFromList('${s.id}')" title="Run suite">▶ Run</button>
        <button class="btn btn-ghost btn-xs" style="color:var(--c-fail)" onclick="removeSuite('${s.id}')" title="Remove">✕</button>
      </div>
    </div>`;
  }).join('');
}

async function handleSuiteFile(file) {
  if (!file) return;
  const fd = new FormData();
  fd.append('file', file);
  const res = await apiUpload('/suites/import', fd);
  if (res.success) {
    const suite = res.data;
    // Update or add to state
    const idx = state.suites.findIndex(s => s.id === suite.id);
    if (idx >= 0) state.suites[idx] = suite;
    else state.suites.push(suite);
    setActiveSuite(suite.id);
    toast(`Imported: ${suite.settings?.suiteName || suite.id}`, 'success');
    renderSuiteList();
  } else {
    toast(res.message || 'Import failed', 'error');
  }
}

function selectAndGoToGroups(id) {
  setActiveSuite(id);
  navigate('groups');
}

async function removeSuite(id) {
  if (!confirm('Remove this suite from the session?')) return;
  const res = await api('DELETE', `/suites/${id}`);
  if (res.success) {
    state.suites = state.suites.filter(s => s.id !== id);
    if (state.activeSuiteId === id)
      setActiveSuite(state.suites[0]?.id || null);
    toast('Suite removed');
    renderSuiteList();
  } else toast(res.message, 'error');
}

async function runSuiteFromList(suiteId) {
  setActiveSuite(suiteId);
  const res = await api('POST', '/tasks', {
    suiteId, groups: [], verificationMode: getVmPayload()
  });
  if (res.success) {
    state.tasks.unshift(res.data);
    renderMonitor();
    startTaskPolling();
    toast(`Task queued for ${res.data.suiteName}`);
    navigate('results');
  } else toast(res.message, 'error');
}

function exportSuite(id, fmt) {
  window.location.href = `/api/suites/${id}/export/${fmt}`;
}

function downloadTemplate() {
  window.location.href = '/api/export/template/excel';
}
