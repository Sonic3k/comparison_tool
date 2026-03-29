// ─── Group Grid ───────────────────────────────────────────────────────────────
function gStats(group) {
  const cases = (group.testCases || []).filter(tc => tc.enabled);
  return {
    total:  cases.length,
    passed: cases.filter(tc => tc.result?.status === 'passed').length,
    failed: cases.filter(tc => tc.result?.status === 'failed' || tc.result?.status === 'error').length
  };
}

function filterGroups() { renderGroupGrid(suite?.testGroups || []); }

function renderGroupGrid(groups) {
  const q = (document.getElementById('groupSearch')?.value || '').toLowerCase();
  const list = q
    ? groups.filter(g => g.name.toLowerCase().includes(q) || (g.description || '').toLowerCase().includes(q))
    : groups;

  document.getElementById('groupGrid').innerHTML =
    list.map(grp => {
      const st = gStats(grp), pend = st.total - st.passed - st.failed;
      const disabled = grp.enabled === false;
      return `<div class="group-card${disabled ? ' group-card-disabled' : ''}" onclick="openGroupDetail('${esc(grp.name)}')">
        <div class="group-card-header">
          <div>
            <div class="group-card-name">${esc(grp.name)}${disabled ? ' <span style="font-size:11px;color:#9ca3af;font-weight:400">(disabled)</span>' : ''}</div>
            <div class="group-card-owner">${esc(grp.owner || '')}</div>
          </div>
          <div onclick="event.stopPropagation()" style="display:flex;gap:4px;align-items:center;flex-wrap:wrap">
            <button class="btn btn-teal btn-xs" onclick="runGroup('${esc(grp.name)}')">▶</button>
            <button class="btn btn-outline btn-xs" onclick="exportGroupXml('${esc(grp.name)}')" title="Export XML">⬇</button>
            <button class="btn btn-xs ${disabled ? 'toggle-off' : 'toggle-on'}"
              onclick="toggleGroup('${esc(grp.name)}')" title="${disabled ? 'Enable group' : 'Disable group'}">
              ${disabled ? '○' : '●'}
            </button>
            <button class="btn btn-outline btn-xs" style="color:var(--red)" onclick="deleteGroupByName('${esc(grp.name)}',event)">✕</button>
          </div>
        </div>
        ${grp.description ? `<div class="group-card-desc">${esc(grp.description)}</div>` : ''}
        <div class="group-card-stats">
          <div class="mini-stat blue"><div class="n">${st.total}</div><div class="l">Total</div></div>
          <div class="mini-stat green"><div class="n">${st.passed}</div><div class="l">Passed</div></div>
          <div class="mini-stat red"><div class="n">${st.failed}</div><div class="l">Failed</div></div>
          <div class="mini-stat gray"><div class="n">${pend}</div><div class="l">Pending</div></div>
        </div>
      </div>`;
    }).join('') +
    `<div class="add-group-card" onclick="openModal('addGroupModal')">
      <div class="plus">＋</div>
      <div style="font-size:13px;font-weight:500">Add Test Group</div>
    </div>`;
}

// ─── Group Toggle ─────────────────────────────────────────────────────────────
async function toggleGroup(name) {
  const res = await api('PATCH', '/groups/' + encodeURIComponent(name) + '/toggle');
  if (res.success) {
    const grp = suite.testGroups.find(g => g.name === name);
    if (grp) grp.enabled = res.data.enabled;
    renderGroupGrid(suite.testGroups);
    // Refresh detail header toggle button if we're viewing this group
    if (currentGroup === name) updateGroupDetailHeader(grp || res.data);
  }
}

// ─── Group CRUD ───────────────────────────────────────────────────────────────
async function createGroup() {
  const name = g('ng-name');
  if (!name) { alert('Group name required'); return; }
  const res = await api('POST', '/groups', { name, description: g('ng-desc'), owner: g('ng-owner'), enabled: true });
  if (res.success) {
    suite.testGroups.push(res.data);
    renderGroupGrid(suite.testGroups);
    ['ng-name','ng-desc','ng-owner'].forEach(id => sv(id, ''));
    closeModal('importGroupModal');
    toast(`Group "${name}" created`);
    openGroupDetail(name);
  } else alert(res.message);
}

async function deleteGroupByName(name, event) {
  if (event) event.stopPropagation();
  if (!confirm(`Delete group "${name}" and all its test cases?`)) return;
  const res = await api('DELETE', '/groups/' + encodeURIComponent(name));
  if (res.success) {
    suite.testGroups = suite.testGroups.filter(g => g.name !== name);
    renderGroupGrid(suite.testGroups);
    if (currentGroup === name) showPanel('groups');
    toast('Group deleted');
  } else alert(res.message);
}

// ─── Group Detail ─────────────────────────────────────────────────────────────
function openGroupDetail(name) {
  currentGroup = name;
  const grp = suite.testGroups.find(g => g.name === name);
  if (!grp) return;

  document.getElementById('breadcrumbGroup').textContent = name;
  document.getElementById('btnRunGroup').onclick    = () => runGroup(name);
  document.getElementById('btnAddCase').onclick     = () => showCaseModal(name);
  document.getElementById('btnDeleteGroup').onclick = () => deleteGroupByName(name);
  document.getElementById('btnExportGroupXml').onclick = () => exportGroupXml(name);

  updateGroupDetailHeader(grp);
  renderDetailStats(grp);
  renderDetailCases(grp);
  showPanel('groupDetail');
}

function updateGroupDetailHeader(grp) {
  document.getElementById('detailGroupName').textContent = grp.name;
  document.getElementById('detailGroupSub').textContent =
    [grp.description, grp.owner ? '· ' + grp.owner : ''].filter(Boolean).join(' ');

  // Update toggle button in detail header
  const btn = document.getElementById('btnToggleGroup');
  if (btn) {
    const disabled = grp.enabled === false;
    btn.textContent = disabled ? '○ Enable Group' : '● Disable Group';
    btn.className   = 'btn btn-sm ' + (disabled ? 'toggle-off' : 'toggle-on');
  }
}

function renderDetailStats(grp) {
  const st = gStats(grp), pend = st.total - st.passed - st.failed;
  document.getElementById('detailStats').innerHTML = st.total === 0 ? '' : `
    <div class="stats-row">
      <div class="stat-box blue"><div class="stat-num">${st.total}</div><div class="stat-lbl">Total</div></div>
      <div class="stat-box green"><div class="stat-num">${st.passed}</div><div class="stat-lbl">Passed</div></div>
      <div class="stat-box red"><div class="stat-num">${st.failed}</div><div class="stat-lbl">Failed / Error</div></div>
      <div class="stat-box amber"><div class="stat-num">${pend}</div><div class="stat-lbl">Pending</div></div>
    </div>`;
}

function renderDetailCases(grp) {
  document.getElementById('detailCasesTable').innerHTML =
    (grp.testCases || []).map(tc => {
      const res = tc.result || {}, st = res.status || 'pending';
      const disabled = tc.enabled === false;
      return `
        <tr class="case-row${disabled ? ' case-disabled' : ''}" id="row-${esc(tc.id)}">
          <td style="text-align:center;color:#d1d5db;font-size:10px;cursor:pointer" id="arr-${esc(tc.id)}" onclick="toggleExpand('${esc(tc.id)}')">▶</td>
          <td class="mono" style="font-weight:600;cursor:pointer" onclick="toggleExpand('${esc(tc.id)}')">${esc(tc.id)}</td>
          <td style="cursor:pointer" onclick="toggleExpand('${esc(tc.id)}')">${esc(tc.name)}</td>
          <td><span class="bs s-method">${tc.method || ''}</span></td>
          <td class="mono" style="color:#6b7280">${esc(tc.endpoint || '')}</td>
          <td><span class="bs s-${st}">${st}</span></td>
          <td class="mono">${esc(res.sourceStatus || '')}</td>
          <td class="mono">${esc(res.targetStatus || '')}</td>
          <td onclick="event.stopPropagation()" style="white-space:nowrap">
            <button class="btn btn-xs ${disabled ? 'toggle-off' : 'toggle-on'}"
              onclick="toggleCase('${esc(grp.name)}','${esc(tc.id)}')"
              title="${disabled ? 'Enable' : 'Disable'}">
              ${disabled ? '○' : '●'}
            </button>
            <button class="btn btn-outline btn-xs" onclick="editCase('${esc(grp.name)}','${esc(tc.id)}')">Edit</button>
            <button class="btn btn-outline btn-xs" style="color:var(--red);margin-left:3px" onclick="deleteCase('${esc(grp.name)}','${esc(tc.id)}')">✕</button>
          </td>
        </tr>
        <tr class="expand-row" id="exp-${esc(tc.id)}">
          <td colspan="9" class="expand-cell">${renderExpand(tc)}</td>
        </tr>`;
    }).join('') ||
    '<tr><td colspan="9" style="text-align:center;color:#9ca3af;padding:32px">No test cases yet. Click "+ Add Case".</td></tr>';
}

function renderExpand(tc) {
  const res = tc.result || {}, diffs = (res.differences || '').split('\n').filter(Boolean);
  return `<div>
    ${diffs.length ? `<ul class="diff-list">${diffs.map(d => `<li class="diff-item">${esc(d)}</li>`).join('')}</ul>` : ''}
    <div class="expand-grid">
      <div>
        <div class="expand-label">Source Response (HTTP ${esc(res.sourceStatus || '—')})</div>
        <div class="expand-body">${esc(res.sourceResponse || '—')}</div>
      </div>
      <div>
        <div class="expand-label">Target Response (HTTP ${esc(res.targetStatus || '—')})</div>
        <div class="expand-body">${esc(res.targetResponse || '—')}</div>
      </div>
    </div>
    ${res.executedAt ? `<div style="font-size:11px;color:#9ca3af;margin-top:8px">Executed: ${esc(res.executedAt)}</div>` : ''}
  </div>`;
}

function toggleExpand(id) {
  const row = document.getElementById('exp-' + id); if (!row) return;
  const open = row.classList.toggle('open');
  const arr  = document.getElementById('arr-' + id); if (arr) arr.textContent = open ? '▼' : '▶';
}

// ─── Test Case Toggle ─────────────────────────────────────────────────────────
async function toggleCase(groupName, caseId) {
  const res = await api('PATCH', `/groups/${encodeURIComponent(groupName)}/cases/${encodeURIComponent(caseId)}/toggle`);
  if (res.success) {
    const grp = suite.testGroups.find(g => g.name === groupName);
    const tc  = grp?.testCases?.find(c => c.id === caseId);
    if (tc) tc.enabled = res.data.enabled;
    renderDetailCases(grp);
    renderDetailStats(grp);
    renderGroupGrid(suite.testGroups);
  }
}

// ─── Test Case CRUD ───────────────────────────────────────────────────────────
function showCaseModal(groupName, tc = null) {
  const isEdit = tc !== null;
  document.getElementById('caseModalTitle').textContent = isEdit ? 'Edit Test Case' : 'Add Test Case';
  sv('tc-groupName', groupName);
  sv('tc-isEdit', isEdit ? 'true' : 'false');

  if (isEdit) {
    sv('tc-id', tc.id);             sv('tc-name', tc.name);
    sv('tc-desc', tc.description);  sv('tc-method', tc.method);
    sv('tc-enabled', String(tc.enabled !== false));
    sv('tc-endpoint', tc.endpoint); sv('tc-author', tc.author);
    sv('tc-body', tc.jsonBody);     sv('tc-headers', tc.headers);
    sv('tc-query', (tc.queryParams || []).map(p => p.key + '=' + p.value).join('\n'));
    sv('tc-form',  (tc.formParams  || []).map(p => p.key + '=' + p.value).join('\n'));
    const cmp = tc.comparisonConfig || {};
    sv('tc-ignoreFields', cmp.ignoreFieldsRaw || '');
    sv('tc-caseSens',    cmp.caseSensitive   !== undefined ? String(cmp.caseSensitive)   : '');
    sv('tc-ignoreOrder', cmp.ignoreArrayOrder !== undefined ? String(cmp.ignoreArrayOrder) : '');
    sv('tc-tolerance',   cmp.numericTolerance !== undefined ? String(cmp.numericTolerance) : '');
    sv('tc-compareErrorResponses', cmp.compareErrorResponses !== undefined ? String(cmp.compareErrorResponses) : '');
  } else {
    ['tc-id','tc-name','tc-desc','tc-endpoint','tc-author','tc-body',
     'tc-headers','tc-query','tc-form','tc-ignoreFields','tc-tolerance'].forEach(id => sv(id, ''));
    sv('tc-method', 'GET'); sv('tc-enabled', 'true');
    sv('tc-caseSens', ''); sv('tc-ignoreOrder', ''); sv('tc-compareErrorResponses', '');
  }
  openModal('caseModal');
}

function editCase(groupName, caseId) {
  const grp = suite.testGroups.find(g => g.name === groupName);
  const tc  = grp?.testCases?.find(c => c.id === caseId);
  if (tc) showCaseModal(groupName, tc);
}

async function saveTestCase() {
  const groupName = g('tc-groupName'), isEdit = g('tc-isEdit') === 'true';
  const parseLines = str => str.split('\n').map(s => s.trim()).filter(Boolean)
    .map(kv => { const i = kv.indexOf('='); return i < 0 ? { key: kv, value: '' } : { key: kv.slice(0, i), value: kv.slice(i + 1) }; });

  const cs = g('tc-caseSens'), io = g('tc-ignoreOrder'), tol = g('tc-tolerance'), cer = g('tc-compareErrorResponses');
  const hasCmp = g('tc-ignoreFields') || cs || io || tol || cer;

  const tc = {
    id: g('tc-id'), name: g('tc-name'), description: g('tc-desc'),
    enabled: g('tc-enabled') === 'true', method: g('tc-method'),
    endpoint: g('tc-endpoint'), author: g('tc-author'),
    jsonBody: g('tc-body'), headers: g('tc-headers'),
    queryParams: parseLines(g('tc-query')),
    formParams:  parseLines(g('tc-form')),
    comparisonConfig: hasCmp ? {
      ignoreFieldsRaw:       g('tc-ignoreFields'),
      caseSensitive:         cs  ? cs  === 'true' : undefined,
      ignoreArrayOrder:      io  ? io  === 'true' : undefined,
      numericTolerance:      tol ? +tol           : undefined,
      compareErrorResponses: cer ? cer === 'true' : undefined
    } : null
  };

  const url = '/groups/' + encodeURIComponent(groupName) + '/cases' + (isEdit ? '/' + encodeURIComponent(tc.id) : '');
  const res = await api(isEdit ? 'PUT' : 'POST', url, tc);
  if (!res.success) { alert(res.message); return; }

  const grp = suite.testGroups.find(g => g.name === groupName);
  if (isEdit) {
    const idx = grp.testCases.findIndex(c => c.id === tc.id);
    if (idx >= 0) grp.testCases[idx] = { ...grp.testCases[idx], ...tc };
  } else {
    grp.testCases.push(tc);
  }
  closeModal('caseModal');
  renderDetailCases(grp);
  renderDetailStats(grp);
  renderGroupGrid(suite.testGroups);
  toast(isEdit ? 'Test case updated' : 'Test case added');
}

async function deleteCase(groupName, caseId) {
  if (!confirm(`Delete test case "${caseId}"?`)) return;
  const res = await api('DELETE', `/groups/${encodeURIComponent(groupName)}/cases/${encodeURIComponent(caseId)}`);
  if (res.success) {
    const grp = suite.testGroups.find(g => g.name === groupName);
    grp.testCases = grp.testCases.filter(c => c.id !== caseId);
    renderDetailCases(grp);
    renderDetailStats(grp);
    renderGroupGrid(suite.testGroups);
    toast('Test case deleted');
  } else alert(res.message);
}

// ─── Execution ────────────────────────────────────────────────────────────────
async function runAll()       { await startExec([]); }
async function runGroup(name) { await startExec([name]); }

async function startExec(groups) {
  const res = await api('POST', '/execute', { groups });
  if (!res.success) { toast(res.message, true); return; }
  document.getElementById('progressBox').style.display = '';
  startPolling();
}

function startPolling() { stopPolling(); pollTimer = setInterval(pollProgress, 1000); }
function stopPolling()  { if (pollTimer) { clearInterval(pollTimer); pollTimer = null; } }

async function pollProgress() {
  const res = await api('GET', '/execute/progress');
  if (!res.success) return;
  const p = res.data;
  document.getElementById('progressLabel').textContent = `${p.done} / ${p.total}`;
  document.getElementById('progressBar').style.width   = p.percent + '%';
  document.getElementById('progressDetail').textContent =
    p.currentGroup ? `[${p.currentGroup}] ${p.currentCase}` : '';

  if (p.completed) {
    stopPolling();
    document.getElementById('progressBox').style.display = 'none';
    const sr = await api('GET', '/suite');
    if (sr.success) {
      suite = sr.data;
      renderGroupGrid(suite.testGroups);
      if (currentGroup) {
        const grp = suite.testGroups.find(g => g.name === currentGroup);
        if (grp) { renderDetailCases(grp); renderDetailStats(grp); }
      }
      toast(`Done — ${p.passed} passed, ${p.failed} failed, ${p.errorCount} errors`);
    }
  }
}

// ─── Group XML Export ─────────────────────────────────────────────────────────
function exportGroupXml(name) {
  window.location.href = '/api/groups/' + encodeURIComponent(name) + '/export/xml';
}

// ─── Import Group Modal Tabs ──────────────────────────────────────────────────
function switchImportTab(tab) {
  document.querySelectorAll('.tab-pane').forEach(p => p.classList.remove('active'));
  document.querySelectorAll('.tab-btn:not(.coming-soon)').forEach(b => b.classList.remove('active'));
  document.getElementById('igtab-' + tab)?.classList.add('active');
  // Activate the clicked tab button
  document.querySelectorAll('.tab-btn:not(.coming-soon)').forEach(b => {
    if (b.getAttribute('onclick') === `switchImportTab('${tab}')`) b.classList.add('active');
  });
}

function openImportGroupModal() {
  // Reset to manual tab each time
  switchImportTab('manual');
  ['ng-name','ng-desc','ng-owner'].forEach(id => sv(id, ''));
  document.getElementById('ig-file').value = '';
  sv('ig-mode', 'new');
  openModal('importGroupModal');
}

// ─── Group XML Import ─────────────────────────────────────────────────────────
async function importGroupXml() {
  const fileInput = document.getElementById('ig-file');
  const file = fileInput.files[0];
  if (!file) { alert('Please choose an XML file'); return; }
  const mode = g('ig-mode');

  const fd = new FormData();
  fd.append('file', file);

  const res = await (await fetch(`/api/groups/import/xml?mode=${mode}`, { method: 'POST', body: fd })).json();
  if (!res.success) { toast(res.message, true); return; }

  const imported = res.data;
  const existingIdx = suite.testGroups.findIndex(g => g.name === imported.name);
  if (existingIdx >= 0) suite.testGroups[existingIdx] = imported;
  else suite.testGroups.push(imported);

  closeModal('importGroupModal');
  renderGroupGrid(suite.testGroups);
  toast(res.message);
  openGroupDetail(imported.name);
}