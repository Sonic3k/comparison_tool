// ── Test Cases Page ───────────────────────────────────────────────────────────
registerPage('testcases', {
  render(container, params) {
    const suite = state.suites.find(s => s.id === state.activeSuiteId);
    const group = suite?.testGroups?.find(g => g.name === params.group);
    if (!suite || !group) {
      container.innerHTML = `<div class="page"><div class="empty-state">
        <div class="empty-icon">⚠️</div><div class="empty-title">Group not found</div>
        <div class="empty-sub"><button class="btn btn-ghost btn-sm" onclick="navigate('groups')">Back to Groups</button></div>
      </div></div>`;
      return;
    }

    container.innerHTML = `
    <div class="page">
      <div class="page-header">
        <div>
          <div class="page-title">${esc(group.name)}</div>
          <div class="page-subtitle">${group.testCases?.length||0} test cases${group.description ? ' · ' + esc(group.description) : ''}</div>
        </div>
        <div class="page-actions">
          <button class="btn btn-ghost btn-sm" onclick="runGroup('${esc(group.name)}')">▶ Run Group</button>
          <button class="btn btn-primary btn-sm" onclick="showTcModal()">+ New TC</button>
        </div>
      </div>

      <!-- Filter bar -->
      <div class="filter-bar">
        <div class="search-input-wrap">
          <span class="search-icon">🔍</span>
          <input class="search-input" id="tcSearch" placeholder="Search ID, name, endpoint…" oninput="filterTcTable()">
        </div>
        <button class="filter-chip active" data-filter="all" onclick="setTcFilter('all',this)">All</button>
        <button class="filter-chip" data-filter="comparison" onclick="setTcFilter('comparison',this)">Comparison</button>
        <button class="filter-chip" data-filter="automation" onclick="setTcFilter('automation',this)">Automation</button>
        <button class="filter-chip" data-filter="both" onclick="setTcFilter('both',this)">Both</button>
        <button class="filter-chip" data-filter="disabled" onclick="setTcFilter('disabled',this)">Disabled</button>
      </div>

      <!-- Table -->
      <div class="table-wrap">
        <table id="tcTable">
          <thead>
            <tr>
              <th style="width:32px"></th>
              <th>ID</th>
              <th>Name</th>
              <th>Method</th>
              <th>Endpoint</th>
              <th>Mode</th>
              <th>Enabled</th>
              <th style="width:80px"></th>
            </tr>
          </thead>
          <tbody id="tcTableBody"></tbody>
        </table>
      </div>
    </div>

    <!-- TC Modal -->
    <div class="overlay hidden" id="tcModal">
      <div class="modal modal-lg">
        <div class="modal-header">
          <div class="modal-title" id="tcModalTitle">New Test Case</div>
          <button class="modal-close" onclick="closeModal('tcModal')">✕</button>
        </div>
        <div class="modal-body">
          <input type="hidden" id="tc-isEdit" value="false">
          <input type="hidden" id="tc-origId" value="">

          <div class="form-grid mb-4">
            <div class="form-group">
              <label class="form-label">ID *</label>
              <input class="form-input" id="tc-id" placeholder="TC-001">
            </div>
            <div class="form-group">
              <label class="form-label">Name *</label>
              <input class="form-input" id="tc-name" placeholder="Get User Profile">
            </div>
            <div class="form-group form-full">
              <label class="form-label">Description</label>
              <input class="form-input" id="tc-desc" placeholder="What this test case verifies">
            </div>
            <div class="form-group">
              <label class="form-label">Verification Mode</label>
              <select class="form-input" id="tc-vm" onchange="updateTcModalSections()">
                <option value="comparison">Comparison — compare source vs target</option>
                <option value="automation">Automation — assert target only</option>
                <option value="both">Both — compare + assert</option>
              </select>
            </div>
            <div class="form-group">
              <label class="form-label">Enabled</label>
              <select class="form-input" id="tc-enabled">
                <option value="true">Yes</option>
                <option value="false">No</option>
              </select>
            </div>
            <div class="form-group">
              <label class="form-label">Method</label>
              <select class="form-input" id="tc-method">
                <option>GET</option><option>POST</option><option>PUT</option>
                <option>PATCH</option><option>DELETE</option>
              </select>
            </div>
            <div class="form-group form-full">
              <label class="form-label">Endpoint</label>
              <input class="form-input" id="tc-endpoint" placeholder="/api/users/123">
            </div>
            <div class="form-group form-full">
              <label class="form-label">Query Params <span class="form-hint">— key=value&key2=value2</span></label>
              <input class="form-input" id="tc-query" placeholder="page=1&limit=10">
            </div>
            <div class="form-group form-full">
              <label class="form-label">JSON Body</label>
              <textarea class="form-input" id="tc-body" rows="3" style="font-family:var(--font-mono);font-size:12px" placeholder='{"key":"value"}'></textarea>
            </div>
            <div class="form-group form-full">
              <label class="form-label">Headers <span class="form-hint">— Key: Value, one per line</span></label>
              <textarea class="form-input" id="tc-headers" rows="2" placeholder="Authorization: Bearer token"></textarea>
            </div>
            <div class="form-group">
              <label class="form-label">Author</label>
              <input class="form-input" id="tc-author">
            </div>
          </div>

          <!-- Comparison section -->
          <div id="cmpSection">
            <div style="font-size:13px;font-weight:600;color:var(--c-comparison);margin-bottom:12px;padding-top:12px;border-top:1px solid var(--c-border)">
              ⚖ Comparison Overrides <span style="font-weight:400;color:var(--t-muted);font-size:12px">— leave blank to use suite defaults</span>
            </div>
            <div class="form-grid mb-4">
              <div class="form-group">
                <label class="form-label">Ignore Fields</label>
                <input class="form-input" id="tc-ignoreFields" placeholder="timestamp,requestId">
              </div>
              <div class="form-group">
                <label class="form-label">Ignore Array Order</label>
                <select class="form-input" id="tc-ignoreArrayOrder">
                  <option value="">— use suite default</option>
                  <option value="true">true</option>
                  <option value="false">false</option>
                </select>
              </div>
              <div class="form-group">
                <label class="form-label">Compare Error Responses</label>
                <select class="form-input" id="tc-compareErr">
                  <option value="">— use suite default</option>
                  <option value="true">true</option>
                  <option value="false">false</option>
                </select>
              </div>
            </div>
          </div>

          <!-- Automation section -->
          <div id="autoSection" class="hidden">
            <div style="font-size:13px;font-weight:600;color:var(--c-automation);margin-bottom:12px;padding-top:12px;border-top:1px solid var(--c-border)">
              🤖 Automation Assertions
            </div>
            <div class="form-grid">
              <div class="form-group">
                <label class="form-label">Expected Status</label>
                <input class="form-input" id="tc-expStatus" placeholder="200 or 2xx">
              </div>
              <div class="form-group">
                <label class="form-label">Max Response Time (ms)</label>
                <input class="form-input" id="tc-maxRt" type="number" placeholder="2000">
              </div>
              <div class="form-group form-full">
                <label class="form-label">Expected Body Assertions <span class="form-hint">— one per line, e.g. $.user.name == "John"</span></label>
                <textarea class="form-input" id="tc-expBody" rows="5" style="font-family:var(--font-mono);font-size:12px"></textarea>
              </div>
            </div>
          </div>
        </div>
        <div class="modal-footer">
          <button class="btn btn-ghost" onclick="closeModal('tcModal')">Cancel</button>
          <button class="btn btn-primary" onclick="saveTc()">Save</button>
        </div>
      </div>
    </div>`;

    this._group = group;
    this._filter = 'all';
    renderTcTable(group);
  },

  destroy() { this._group = null; }
});

// ── TC Table ──────────────────────────────────────────────────────────────────
let _tcFilter = 'all';
let _tcSearch = '';

function renderTcTable(group) {
  const tbody = document.getElementById('tcTableBody');
  if (!tbody) return;
  const tcs = group?.testCases || [];

  const filtered = tcs.filter(tc => {
    const vm = tc.verificationMode || 'comparison';
    const matchFilter =
      _tcFilter === 'all' ? true :
      _tcFilter === 'disabled' ? !tc.enabled :
      vm === _tcFilter;
    const q = _tcSearch.toLowerCase();
    const matchSearch = !q ||
      (tc.id||'').toLowerCase().includes(q) ||
      (tc.name||'').toLowerCase().includes(q) ||
      (tc.endpoint||'').toLowerCase().includes(q);
    return matchFilter && matchSearch;
  });

  if (!filtered.length) {
    tbody.innerHTML = `<tr><td colspan="8"><div class="empty-state" style="padding:32px">
      <div class="empty-icon" style="font-size:32px">🔍</div>
      <div class="empty-title">No test cases match</div>
    </div></td></tr>`;
    return;
  }

  tbody.innerHTML = filtered.map(tc => {
    const vm = tc.verificationMode || 'comparison';
    const methodColors = {GET:'#0369a1',POST:'#059669',PUT:'#d97706',PATCH:'#7c3aed',DELETE:'#dc2626'};
    const mc = methodColors[tc.method] || '#475569';
    return `
    <tr class="row-clickable ${!tc.enabled ? 'text-muted' : ''}" onclick="toggleTcExpand('${esc(tc.id)}')">
      <td style="text-align:center;color:var(--t-muted);font-size:12px" id="exp-arrow-${esc(tc.id)}">▶</td>
      <td><code style="font-size:12px;color:var(--t-secondary)">${esc(tc.id)}</code></td>
      <td style="font-weight:500;color:var(--t-primary)">${esc(tc.name)}</td>
      <td><span class="badge badge-method" style="color:${mc}">${esc(tc.method||'GET')}</span></td>
      <td><code style="font-size:11px;color:var(--t-secondary)">${esc(tc.endpoint)}</code></td>
      <td>${vmBadge(vm)}</td>
      <td>
        <div class="toggle ${tc.enabled ? 'on' : ''}" onclick="event.stopPropagation();toggleTc('${esc(tc.id)}')"></div>
      </td>
      <td onclick="event.stopPropagation()">
        <div style="display:flex;gap:4px">
          <button class="btn btn-ghost btn-xs" onclick="showTcModal('${esc(tc.id)}')">Edit</button>
          <button class="btn btn-ghost btn-xs" style="color:var(--c-fail)" onclick="deleteTc('${esc(tc.id)}')">✕</button>
        </div>
      </td>
    </tr>
    <tr class="row-expand-area hidden" id="exp-row-${esc(tc.id)}">
      <td colspan="8">
        <div class="expand-content">
          ${renderTcDetail(tc)}
        </div>
      </td>
    </tr>`;
  }).join('');
}

function renderTcDetail(tc) {
  const auto = tc.automationConfig || {};
  const cmp  = tc.comparisonConfig || {};
  const vm   = tc.verificationMode || 'comparison';
  const parts = [];

  if (tc.queryParams?.length) parts.push(`<div class="form-group"><label class="form-label">Query Params</label><code style="font-size:12px">${esc(tc.queryParams.map(p=>p.key+'='+p.value).join('&'))}</code></div>`);
  if (tc.jsonBody) parts.push(`<div class="form-group"><label class="form-label">Body</label><pre style="font-size:11px;background:var(--c-bg);padding:8px;border-radius:4px;overflow:auto">${esc(tc.jsonBody)}</pre></div>`);
  if (tc.headers) parts.push(`<div class="form-group"><label class="form-label">Headers</label><code style="font-size:12px">${esc(tc.headers)}</code></div>`);

  if ((vm === 'comparison' || vm === 'both') && (cmp.ignoreFieldsRaw || cmp.compareErrorResponses != null)) {
    parts.push(`<div style="font-size:12px;color:var(--c-comparison);font-weight:600;margin-top:8px">Comparison Overrides</div>`);
    if (cmp.ignoreFieldsRaw) parts.push(`<div style="font-size:12px">Ignore fields: <code>${esc(cmp.ignoreFieldsRaw)}</code></div>`);
  }

  if (vm === 'automation' || vm === 'both') {
    parts.push(`<div style="font-size:12px;color:var(--c-automation);font-weight:600;margin-top:8px">Automation Assertions</div>`);
    if (auto.expectedStatus) parts.push(`<div style="font-size:12px">Expected status: <code>${esc(auto.expectedStatus)}</code></div>`);
    if (auto.expectedBody) parts.push(`<div class="form-group"><label class="form-label">Body assertions</label><pre class="assertion-output">${esc(auto.expectedBody)}</pre></div>`);
  }

  return `<div style="display:flex;flex-direction:column;gap:8px">${parts.join('') || '<span style="color:var(--t-muted);font-size:12px">No additional details</span>'}</div>`;
}

let _expandedTcId = null;
function toggleTcExpand(id) {
  const row = document.getElementById(`exp-row-${id}`);
  const arrow = document.getElementById(`exp-arrow-${id}`);
  if (!row) return;
  const isOpen = !row.classList.contains('hidden');
  // Close previous
  if (_expandedTcId && _expandedTcId !== id) {
    document.getElementById(`exp-row-${_expandedTcId}`)?.classList.add('hidden');
    const a = document.getElementById(`exp-arrow-${_expandedTcId}`);
    if (a) a.textContent = '▶';
  }
  row.classList.toggle('hidden', isOpen);
  arrow.textContent = isOpen ? '▶' : '▼';
  _expandedTcId = isOpen ? null : id;
}

function filterTcTable() {
  _tcSearch = document.getElementById('tcSearch')?.value || '';
  const suite = state.suites.find(s => s.id === state.activeSuiteId);
  const group = suite?.testGroups?.find(g => g.name === state.currentGroup);
  renderTcTable(group);
}

function setTcFilter(filter, btn) {
  _tcFilter = filter;
  document.querySelectorAll('[data-filter]').forEach(b => b.classList.remove('active'));
  btn.classList.add('active');
  filterTcTable();
}

// ── TC Modal ──────────────────────────────────────────────────────────────────
function updateTcModalSections() {
  const vm = document.getElementById('tc-vm')?.value;
  document.getElementById('cmpSection')?.classList.toggle('hidden', vm === 'automation');
  document.getElementById('autoSection')?.classList.toggle('hidden', vm === 'comparison');
}

function showTcModal(tcId = null) {
  const suite = state.suites.find(s => s.id === state.activeSuiteId);
  const group = suite?.testGroups?.find(g => g.name === state.currentGroup);
  const tc = tcId ? group?.testCases?.find(t => t.id === tcId) : null;

  document.getElementById('tcModalTitle').textContent = tc ? 'Edit Test Case' : 'New Test Case';
  document.getElementById('tc-isEdit').value = tc ? 'true' : 'false';
  document.getElementById('tc-origId').value = tc?.id || '';

  const sv = (id, v) => { const el = document.getElementById(id); if (el) el.value = v ?? ''; };
  if (tc) {
    const cmp  = tc.comparisonConfig || {};
    const auto = tc.automationConfig || {};
    sv('tc-id', tc.id);
    sv('tc-name', tc.name);
    sv('tc-desc', tc.description);
    sv('tc-vm', tc.verificationMode || 'comparison');
    sv('tc-enabled', String(tc.enabled !== false));
    sv('tc-method', tc.method);
    sv('tc-endpoint', tc.endpoint);
    sv('tc-query', tc.queryParams?.map(p=>p.key+'='+p.value).join('&')||'');
    sv('tc-body', tc.jsonBody);
    sv('tc-headers', tc.headers);
    sv('tc-author', tc.author);
    sv('tc-ignoreFields', cmp.ignoreFieldsRaw);
    sv('tc-ignoreArrayOrder', cmp.ignoreArrayOrder != null ? String(cmp.ignoreArrayOrder) : '');
    sv('tc-compareErr', cmp.compareErrorResponses != null ? String(cmp.compareErrorResponses) : '');
    sv('tc-expStatus', auto.expectedStatus);
    sv('tc-expBody', auto.expectedBody);
    sv('tc-maxRt', auto.maxResponseTime || '');
  } else {
    ['tc-id','tc-name','tc-desc','tc-query','tc-body','tc-headers','tc-author',
     'tc-ignoreFields','tc-ignoreArrayOrder','tc-compareErr','tc-expStatus','tc-expBody','tc-maxRt'].forEach(id => sv(id,''));
    sv('tc-vm','comparison'); sv('tc-enabled','true'); sv('tc-method','GET');
  }

  updateTcModalSections();
  openModal('tcModal');
}

async function saveTc() {
  const id    = document.getElementById('tc-id')?.value?.trim();
  const name  = document.getElementById('tc-name')?.value?.trim();
  if (!id || !name) { toast('ID and Name are required', 'error'); return; }

  const vm = document.getElementById('tc-vm')?.value || 'comparison';
  const ignoreFields = document.getElementById('tc-ignoreFields')?.value?.trim();
  const ignoreArr    = document.getElementById('tc-ignoreArrayOrder')?.value;
  const compareErr   = document.getElementById('tc-compareErr')?.value;
  const expStatus    = document.getElementById('tc-expStatus')?.value?.trim();
  const expBody      = document.getElementById('tc-expBody')?.value?.trim();
  const maxRt        = parseInt(document.getElementById('tc-maxRt')?.value) || 0;

  const queryStr = document.getElementById('tc-query')?.value?.trim();
  const queryParams = queryStr ? queryStr.split('&').map(p => {
    const [k,...v] = p.split('='); return { key: k.trim(), value: v.join('=').trim() };
  }).filter(p => p.key) : [];

  const payload = {
    id, name,
    description: document.getElementById('tc-desc')?.value?.trim(),
    enabled: document.getElementById('tc-enabled')?.value === 'true',
    verificationMode: vm,
    method: document.getElementById('tc-method')?.value || 'GET',
    endpoint: document.getElementById('tc-endpoint')?.value?.trim(),
    queryParams,
    jsonBody: document.getElementById('tc-body')?.value?.trim(),
    headers: document.getElementById('tc-headers')?.value?.trim(),
    author: document.getElementById('tc-author')?.value?.trim(),
    comparisonConfig: (vm !== 'automation') ? {
      ignoreFieldsRaw: ignoreFields || null,
      ignoreArrayOrder: ignoreArr ? ignoreArr === 'true' : null,
      compareErrorResponses: compareErr ? compareErr === 'true' : false,
    } : null,
    automationConfig: (vm !== 'comparison') ? {
      expectedStatus: expStatus || null,
      expectedBody: expBody || null,
      maxResponseTime: maxRt,
    } : null,
  };

  const isEdit = document.getElementById('tc-isEdit')?.value === 'true';
  const origId = document.getElementById('tc-origId')?.value;
  const group  = encodeURIComponent(state.currentGroup);
  const base   = `/suites/${state.activeSuiteId}/groups/${group}/cases`;

  const res = isEdit
    ? await api('PUT', `${base}/${encodeURIComponent(origId)}`, payload)
    : await api('POST', base, payload);

  if (res.success) {
    // Refresh suite
    const sr = await api('GET', `/suites/${state.activeSuiteId}`);
    if (sr.success) {
      const idx = state.suites.findIndex(s => s.id === state.activeSuiteId);
      if (idx >= 0) state.suites[idx] = sr.data;
    }
    closeModal('tcModal');
    toast(isEdit ? 'Test case updated' : 'Test case created');
    navigate('testcases', { group: state.currentGroup });
  } else toast(res.message, 'error');
}

async function toggleTc(tcId) {
  const group = encodeURIComponent(state.currentGroup);
  await api('PATCH', `/suites/${state.activeSuiteId}/groups/${group}/cases/${encodeURIComponent(tcId)}/toggle`);
  const sr = await api('GET', `/suites/${state.activeSuiteId}`);
  if (sr.success) {
    const idx = state.suites.findIndex(s => s.id === state.activeSuiteId);
    if (idx >= 0) state.suites[idx] = sr.data;
    const suite = state.suites[idx];
    const g = suite?.testGroups?.find(g => g.name === state.currentGroup);
    renderTcTable(g);
  }
}

async function deleteTc(tcId) {
  if (!confirm(`Delete test case "${tcId}"?`)) return;
  const group = encodeURIComponent(state.currentGroup);
  const res = await api('DELETE', `/suites/${state.activeSuiteId}/groups/${group}/cases/${encodeURIComponent(tcId)}`);
  if (res.success) {
    const sr = await api('GET', `/suites/${state.activeSuiteId}`);
    if (sr.success) {
      const idx = state.suites.findIndex(s => s.id === state.activeSuiteId);
      if (idx >= 0) state.suites[idx] = sr.data;
    }
    toast('Test case deleted');
    navigate('testcases', { group: state.currentGroup });
  } else toast(res.message, 'error');
}
