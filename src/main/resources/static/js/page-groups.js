// ── Groups Page ───────────────────────────────────────────────────────────────
registerPage('groups', {
  render(container) {
    const suite = state.suites.find(s => s.id === state.activeSuiteId);
    if (!suite) {
      container.innerHTML = `<div class="page"><div class="empty-state">
        <div class="empty-icon">📂</div>
        <div class="empty-title">No suite selected</div>
        <div class="empty-sub"><button class="btn btn-primary btn-sm" onclick="navigate('suites')">Go to Suites</button></div>
      </div></div>`;
      return;
    }
    const groups = suite.testGroups || [];
    const totalTCs = groups.reduce((a,g) => a + (g.testCases?.length||0), 0);
    const enabledTCs = groups.reduce((a,g) => a + (g.testCases?.filter(tc=>tc.enabled).length||0), 0);

    container.innerHTML = `
    <div class="page">
      <div class="page-header">
        <div>
          <div class="page-title">${esc(suite.settings?.suiteName||'Suite')}</div>
          <div class="page-subtitle">${groups.length} groups · ${enabledTCs} enabled TCs of ${totalTCs}</div>
        </div>
        <div class="page-actions">
          <div class="run-btn-group">
            <button class="run-btn-main" onclick="runSuite([])">▶ Run All</button>
            <button class="run-btn-drop" id="runDropBtn" onclick="toggleRunDropdown()">▾</button>
          </div>
          <div class="dropdown-wrap">
            <div class="dropdown-menu hidden" id="runDropMenu"></div>
          </div>
          <button class="btn btn-ghost btn-sm" onclick="showAddGroupModal()">+ Group</button>
          <button class="btn btn-ghost btn-sm" onclick="navigate('config')">⚙ Config</button>
        </div>
      </div>

      <div class="group-grid" id="groupGrid"></div>
    </div>

    <!-- Add Group Modal -->
    <div class="overlay hidden" id="addGroupModal">
      <div class="modal">
        <div class="modal-header">
          <div class="modal-title">New Group</div>
          <button class="modal-close" onclick="closeModal('addGroupModal')">✕</button>
        </div>
        <div class="modal-body">
          <div class="form-group mb-4">
            <label class="form-label">Group Name *</label>
            <input class="form-input" id="ng-name" placeholder="e.g. User APIs">
          </div>
          <div class="form-group mb-4">
            <label class="form-label">Description</label>
            <input class="form-input" id="ng-desc" placeholder="Short description">
          </div>
          <div class="form-group">
            <label class="form-label">Owner</label>
            <input class="form-input" id="ng-owner" placeholder="email or name">
          </div>
        </div>
        <div class="modal-footer">
          <button class="btn btn-ghost" onclick="closeModal('addGroupModal')">Cancel</button>
          <button class="btn btn-primary" onclick="createGroup()">Create</button>
        </div>
      </div>
    </div>`;

    renderGroupGrid(suite);
    populateRunDropdown(groups);
  },
  destroy() {}
});

function renderGroupGrid(suite) {
  const el = document.getElementById('groupGrid');
  if (!el) return;
  const groups = suite.testGroups || [];

  const cards = groups.map(g => {
    const tcs = g.testCases || [];
    const enabled = tcs.filter(tc => tc.enabled);
    const cmp  = enabled.filter(tc => tc.verificationMode === 'comparison' || !tc.verificationMode).length;
    const auto = enabled.filter(tc => tc.verificationMode === 'automation').length;
    const both = enabled.filter(tc => tc.verificationMode === 'both').length;

    return `
    <div class="group-card" onclick="navigate('testcases',{group:'${esc(g.name)}'})">
      <div class="group-card-header">
        <div>
          <div class="group-card-name">${esc(g.name)}</div>
          ${g.owner ? `<div class="group-card-owner">${esc(g.owner)}</div>` : ''}
        </div>
        <div style="display:flex;gap:6px;align-items:center" onclick="event.stopPropagation()">
          <button class="btn btn-ghost btn-xs" onclick="runGroup('${esc(g.name)}')" title="Run this group">▶</button>
          <button class="btn btn-ghost btn-xs" style="color:var(--c-fail)" onclick="deleteGroup('${esc(g.name)}')" title="Delete">✕</button>
        </div>
      </div>
      ${g.description ? `<div class="group-card-desc">${esc(g.description)}</div>` : ''}
      <div class="group-card-stats">
        <div class="mini-stat s-total"><div class="mn">${enabled.length}</div><div class="ml">Enabled</div></div>
        ${cmp  ? `<div class="mini-stat" style="background:var(--c-comparison-lt)"><div class="mn" style="color:var(--c-comparison)">${cmp}</div><div class="ml">CMP</div></div>` : ''}
        ${auto ? `<div class="mini-stat" style="background:var(--c-automation-lt)"><div class="mn" style="color:var(--c-automation)">${auto}</div><div class="ml">AUTO</div></div>` : ''}
        ${both ? `<div class="mini-stat" style="background:var(--c-both-lt)"><div class="mn" style="color:var(--c-both)">${both}</div><div class="ml">BOTH</div></div>` : ''}
        ${tcs.length - enabled.length > 0 ? `<div class="mini-stat s-pend"><div class="mn">${tcs.length - enabled.length}</div><div class="ml">Off</div></div>` : ''}
      </div>
    </div>`;
  }).join('');

  el.innerHTML = cards + `
    <div class="add-group-card" onclick="showAddGroupModal()">
      <div class="plus">+</div>
      <div>Add Group</div>
    </div>`;
}

function populateRunDropdown(groups) {
  const menu = document.getElementById('runDropMenu');
  if (!menu) return;
  menu.innerHTML = groups.map(g => `
    <div class="dropdown-item" onclick="runGroup('${esc(g.name)}');toggleRunDropdown()">
      ▶ ${esc(g.name)}
    </div>`).join('');
}

function toggleRunDropdown() {
  document.getElementById('runDropMenu')?.classList.toggle('hidden');
}

async function runGroup(groupName) {
  if (!state.activeSuiteId) { toast('No suite selected', 'error'); return; }
  const res = await api('POST', '/tasks', {
    suiteId: state.activeSuiteId,
    groups: [groupName],
    verificationMode: getVmPayload(),
  });
  if (res.success) {
    state.tasks.unshift(res.data);
    renderMonitor();
    startTaskPolling();
    toast(`Running: ${groupName}`);
    navigate('results');
  } else toast(res.message, 'error');
}

function showAddGroupModal() {
  ['ng-name','ng-desc','ng-owner'].forEach(id => {
    const el = document.getElementById(id);
    if (el) el.value = '';
  });
  openModal('addGroupModal');
}

async function createGroup() {
  const name = document.getElementById('ng-name')?.value?.trim();
  if (!name) { toast('Group name is required', 'error'); return; }
  const res = await api('POST', `/suites/${state.activeSuiteId}/groups`, {
    name,
    description: document.getElementById('ng-desc')?.value?.trim() || '',
    owner: document.getElementById('ng-owner')?.value?.trim() || '',
    testCases: [],
  });
  if (res.success) {
    // Refresh suite data
    const sr = await api('GET', `/suites/${state.activeSuiteId}`);
    if (sr.success) {
      const idx = state.suites.findIndex(s => s.id === state.activeSuiteId);
      if (idx >= 0) state.suites[idx] = sr.data;
    }
    closeModal('addGroupModal');
    toast(`Group created: ${name}`);
    navigate('groups');
  } else toast(res.message, 'error');
}

async function deleteGroup(name) {
  if (!confirm(`Delete group "${name}" and all its test cases?`)) return;
  const res = await api('DELETE', `/suites/${state.activeSuiteId}/groups/${encodeURIComponent(name)}`);
  if (res.success) {
    const sr = await api('GET', `/suites/${state.activeSuiteId}`);
    if (sr.success) {
      const idx = state.suites.findIndex(s => s.id === state.activeSuiteId);
      if (idx >= 0) state.suites[idx] = sr.data;
    }
    toast(`Group deleted: ${name}`);
    navigate('groups');
  } else toast(res.message, 'error');
}

// ── Shared modal helpers ───────────────────────────────────────────────────────
function openModal(id) { document.getElementById(id)?.classList.remove('hidden'); }
function closeModal(id) { document.getElementById(id)?.classList.add('hidden'); }
