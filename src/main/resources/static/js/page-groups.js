reg('groups', {
  render(el) {
    const suite = activeSuite();
    if (!suite) { el.innerHTML = noSuite(); return; }

    const groups = suite.testGroups || [];
    const totalTCs   = groups.reduce((a,g) => a+(g.testCases?.length||0), 0);
    const enabledTCs = groups.reduce((a,g) => a+(g.testCases?.filter(tc=>tc.enabled).length||0), 0);

    el.innerHTML = `
    <div class="page">
      <div class="page-hd">
        <div>
          <div class="page-title">${esc(suite.settings?.suiteName||'Suite')}</div>
          <div class="page-sub">${groups.length} groups · ${enabledTCs} enabled / ${totalTCs} TCs</div>
        </div>
        <div class="page-acts">
          <button class="btn btn-ghost btn-sm" onclick="go('config')">⚙ Config</button>
          <button class="btn btn-ghost btn-sm" onclick="go('results')">📊 Results</button>
          <button class="btn btn-ghost btn-sm" onclick="showAddGroup()">+ Group</button>
          <div class="dd-wrap">
            <div class="run-group">
              <button class="run-main" onclick="runSuite([]);go('results')">▶ Run All</button>
              <button class="run-caret" onclick="toggleRunDd()">▾</button>
            </div>
            <div class="dd-menu hidden" id="runDdMenu"></div>
          </div>
        </div>
      </div>

      <div class="group-grid" id="groupGrid"></div>
    </div>

    <!-- Add Group Modal -->
    <div class="overlay hidden" id="addGroupModal">
      <div class="modal">
        <div class="modal-hd"><span class="modal-title">New Group</span><span class="modal-x" onclick="closeModal('addGroupModal')">✕</span></div>
        <div class="modal-body">
          <div class="form-group" style="margin-bottom:12px"><label class="form-label">Name *</label><input class="input" id="ng-name" placeholder="e.g. User APIs"></div>
          <div class="form-group" style="margin-bottom:12px"><label class="form-label">Description</label><input class="input" id="ng-desc"></div>
          <div class="form-group"><label class="form-label">Owner</label><input class="input" id="ng-owner"></div>
        </div>
        <div class="modal-ft">
          <button class="btn btn-ghost" onclick="closeModal('addGroupModal')">Cancel</button>
          <button class="btn btn-primary" onclick="createGroup()">Create</button>
        </div>
      </div>
    </div>`;

    renderGroupGrid(suite);
  },
  destroy() {}
});

function renderGroupGrid(suite) {
  const el = document.getElementById('groupGrid');
  if (!el) return;
  const groups = suite.testGroups || [];

  el.innerHTML = groups.map(g => {
    const tcs = g.testCases || [];
    const enabled = tcs.filter(t => t.enabled);
    const withResult = enabled.filter(t => t.result && t.result.status && t.result.status !== 'pending');
    const passed  = withResult.filter(t => t.result?.status === 'passed').length;
    const failed  = withResult.filter(t => t.result?.status === 'failed').length;
    const errors  = withResult.filter(t => t.result?.status === 'error').length;
    const pending = enabled.length - withResult.length;
    const hasResults = withResult.length > 0;

    return `
    <div class="group-card" onclick="go('testcases',{group:'${esc(g.name)}'})">
      <div style="display:flex;align-items:flex-start;justify-content:space-between;gap:8px">
        <div>
          <div class="gc-name">${esc(g.name)}</div>
          ${g.owner ? `<div class="gc-owner">${esc(g.owner)}</div>` : ''}
        </div>
        <div style="display:flex;gap:4px" onclick="event.stopPropagation()">
          <button class="btn btn-ghost btn-xs" onclick="runGroup('${esc(g.name)}')" title="Run this group">▶</button>
          <button class="btn btn-ghost btn-xs" style="color:var(--fail)" onclick="deleteGroup('${esc(g.name)}')">✕</button>
        </div>
      </div>
      ${g.description ? `<div class="gc-desc">${esc(g.description)}</div>` : ''}
      <div class="gc-stats">
        ${hasResults ? `
          <div class="gc-stat s-pass"><div class="n">${passed}</div><div class="l">pass</div></div>
          <div class="gc-stat s-fail"><div class="n">${failed+errors}</div><div class="l">fail</div></div>
        ` : ''}
        <div class="gc-stat s-total"><div class="n">${enabled.length}</div><div class="l">enabled</div></div>
        ${pending && hasResults ? `<div class="gc-stat s-pend"><div class="n">${pending}</div><div class="l">pending</div></div>` : ''}
      </div>
    </div>`;
  }).join('') + `
    <div class="add-card" onclick="showAddGroup()">
      <div class="plus">+</div><div>Add Group</div>
    </div>`;
}

async function runGroup(name) {
  await runSuite([name]);
  go('results');
}

function showAddGroup() {
  ['ng-name','ng-desc','ng-owner'].forEach(id => { const el=document.getElementById(id); if(el) el.value=''; });
  openModal('addGroupModal');
}

async function createGroup() {
  const name = document.getElementById('ng-name')?.value?.trim();
  if (!name) { toast('Name required','err'); return; }
  const r = await api('POST', `/suites/${S.active}/groups`, {
    name, description: document.getElementById('ng-desc')?.value?.trim()||'',
    owner: document.getElementById('ng-owner')?.value?.trim()||'', testCases: []
  });
  if (r.success) {
    await refreshSuite(S.active);
    closeModal('addGroupModal'); toast('Group created'); go('groups');
  } else toast(r.message, 'err');
}

async function deleteGroup(name) {
  if (!confirm(`Delete "${name}" and all its test cases?`)) return;
  const r = await api('DELETE', `/suites/${S.active}/groups/${encodeURIComponent(name)}`);
  if (r.success) { await refreshSuite(S.active); toast('Group deleted'); go('groups'); }
  else toast(r.message, 'err');
}

function noSuite() {
  return `<div class="page"><div class="empty"><div class="empty-icon">📂</div>
    <div class="empty-title">No suite selected</div>
    <button class="btn btn-primary btn-sm" style="margin-top:12px" onclick="go('suites')">Go to Suites</button>
  </div></div>`;
}
