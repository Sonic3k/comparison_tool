// ─── Group Grid ───────────────────────────────────────────────────────────────
function gStats(group) {
  const cases = (group.testRequests || []).filter(tc => tc.enabled);
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
    `<div class="add-group-card" onclick="openImportGroupModal()">
      <div class="plus">＋</div>
      <div style="font-size:13px;font-weight:500">Add Test Group</div>
    </div>`;

  renderSuiteSummary(suite?.testGroups || []);
}

function renderSuiteSummary(groups) {
  const bar = document.getElementById('suiteSummaryBar');
  if (!bar) return;

  let total = 0, passed = 0, failed = 0, error = 0;
  for (const grp of groups) {
    for (const tc of (grp.testRequests || [])) {
      if (tc.enabled === false) continue;
      total++;
      const s = tc.result?.status;
      if (s === 'passed')      passed++;
      else if (s === 'failed') failed++;
      else if (s === 'error')  error++;
    }
  }

  const executed = passed + failed + error;
  if (executed === 0) { bar.style.display = 'none'; return; }
  bar.style.display = '';

  const pending = total - executed;
  const passRate = total > 0 ? Math.round(passed / total * 100) : 0;

  document.getElementById('sn-total').textContent = total;
  document.getElementById('sn-pass').textContent  = passed;
  document.getElementById('sn-fail').textContent  = failed;
  document.getElementById('sn-error').textContent = error;
  document.getElementById('sn-pend').textContent  = pending;
  document.getElementById('summaryPassRate').textContent = `${passRate}% pass rate`;

  document.getElementById('bar-pass').style.width  = (passed / total * 100) + '%';
  document.getElementById('bar-fail').style.width  = (failed / total * 100) + '%';
  document.getElementById('bar-error').style.width = (error  / total * 100) + '%';
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
  document.getElementById('groupExportXml').onclick = e => {
    e.preventDefault();
    document.getElementById('groupExportMenu').classList.remove('open');
    exportGroupXml(name);
  };
  document.getElementById('groupExportPostman').onclick = e => {
    e.preventDefault();
    document.getElementById('groupExportMenu').classList.remove('open');
    openExportModal('postman', name);
  };
  document.getElementById('groupExportBruno').onclick = e => {
    e.preventDefault();
    document.getElementById('groupExportMenu').classList.remove('open');
    openExportModal('bruno', name);
  };

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

function modeBadge(mode) {
  const cfg = {
    comparison: ['#e0f2fe','#0369a1','CMP'],
    automation: ['#f3e8ff','#7c3aed','AUTO'],
    both:       ['#fef9c3','#a16207','BOTH']
  };
  const [bg, fg, label] = cfg[mode] || cfg['comparison'];
  return `<span style="font-size:10px;font-weight:700;padding:1px 6px;border-radius:4px;background:${bg};color:${fg}">${label}</span>`;
}

function caseBadges(tc, grouped) {
  let b = '';
  if (!grouped && tc.testCaseId && tc.testCaseId !== tc.id) b += `<span class="tc-mini tcid" title="Belongs to test case ${esc(tc.testCaseId)}">${esc(tc.testCaseId)}</span>`;
  if (tc.phase && tc.phase !== 'test') b += `<span class="tc-mini ${esc(tc.phase)}">${esc(tc.phase)}</span>`;
  if (tc.extractVariables) b += `<span class="tc-mini vars" title="Extracts: ${esc(tc.extractVariables)}">vars</span>`;
  return b;
}

function tcRollupStatus(reqs) {
  const sts = reqs.map(r => r.result?.status || 'pending');
  if (sts.includes('error'))  return 'error';
  if (sts.includes('failed')) return 'failed';
  if (sts.length && sts.every(x => x === 'passed')) return 'passed';
  return 'pending';
}

let _tcMembers = {};   // headerKey -> [requestIds]
let _tcOfReq   = {};   // requestId -> headerKey

function renderDetailCases(grp) {
  _tcMembers = {}; _tcOfReq = {};
  const reqs = grp.testRequests || [];

  // Chunk by testCaseId, first-appearance order (mirrors execution order)
  const chunks = [];
  const byId = new Map();
  for (const r of reqs) {
    const tcId = r.testCaseId || r.id;
    if (!byId.has(tcId)) { const c = { tcId, reqs: [] }; byId.set(tcId, c); chunks.push(c); }
    byId.get(tcId).reqs.push(r);
  }
  const defs = new Map((grp.testCaseDefs || []).map(d => [d.id, d]));

  let html = '', idx = 0;
  for (const c of chunks) {
    if (c.reqs.length > 1) {
      const key = 'i' + (idx++);
      _tcMembers[key] = c.reqs.map(r => r.id);
      c.reqs.forEach(r => { _tcOfReq[r.id] = key; });
      const def  = defs.get(c.tcId);
      const roll = tcRollupStatus(c.reqs);
      html += `
        <tr class="tc-head-row" onclick="toggleTcGroup('${key}')">
          <td style="text-align:center;color:#93c5fd;font-size:10px" id="tcarr-${key}">▼</td>
          <td colspan="4">
            <span class="mono" style="font-weight:700;color:var(--blue)">${esc(c.tcId)}</span>
            ${def && def.name && def.name !== c.tcId ? `<span style="color:#6b7280;font-size:12px;margin-left:8px">${esc(def.name)}</span>` : ''}
            <span class="tc-mini tcid" style="margin-left:8px">${c.reqs.length} requests · sequential</span>
          </td>
          <td></td>
          <td><span class="bs s-${roll}" id="tcst-${key}">${roll}</span></td>
          <td></td>
          <td onclick="event.stopPropagation()" style="white-space:nowrap">
            <button class="btn btn-teal btn-xs" onclick="runTestCase('${esc(grp.name)}','${esc(c.tcId)}')"
              title="Run this test case only — group setup & Global Setup included so {{variables}} resolve">▶ Run TC</button>
          </td>
        </tr>` + c.reqs.map(r => caseRowHtml(grp, r, key)).join('');
    } else {
      html += caseRowHtml(grp, c.reqs[0], null);
    }
  }

  document.getElementById('detailCasesTable').innerHTML =
    html ||
    '<tr><td colspan="9" style="text-align:center;color:#9ca3af;padding:32px">No test cases yet. Click "+ Add Case".</td></tr>';
}

function caseRowHtml(grp, tc, groupKey) {
  const res = tc.result || {}, st = res.status || 'pending';
  const disabled = tc.enabled === false;
  const mode = tc.verificationMode || 'comparison';
  const memberCls = groupKey ? ` tc-member tcm-${groupKey}` : '';
  const idPrefix  = groupKey ? '<span style="color:#93c5fd">└ </span>' : '';
  return `
        <tr class="case-row${disabled ? ' case-disabled' : ''}${memberCls}" id="row-${esc(tc.id)}">
          <td style="text-align:center;color:#d1d5db;font-size:10px;cursor:pointer" id="arr-${esc(tc.id)}" onclick="toggleExpand('${esc(tc.id)}')">▶</td>
          <td class="mono" style="font-weight:600;cursor:pointer" onclick="toggleExpand('${esc(tc.id)}')">${idPrefix}${esc(tc.id)}</td>
          <td style="cursor:pointer" onclick="toggleExpand('${esc(tc.id)}')">${esc(tc.name)}${caseBadges(tc, !!groupKey)}</td>
          <td><span class="bs s-method">${tc.method || ''}</span></td>
          <td class="mono" style="color:#6b7280">${esc(tc.endpoint || '')}</td>
          <td>${modeBadge(mode)}</td>
          <td><span class="bs s-${st}" id="st-${esc(tc.id)}">${st}</span></td>
          <td class="mono">${esc(res.targetStatus || '')}</td>
          <td onclick="event.stopPropagation()" style="white-space:nowrap">
            <button class="btn btn-xs ${disabled ? 'toggle-off' : 'toggle-on'}"
              onclick="toggleCase('${esc(grp.name)}','${esc(tc.id)}')"
              title="${disabled ? 'Enable' : 'Disable'}">
              ${disabled ? '○' : '●'}
            </button>
            <button class="btn btn-outline btn-xs" onclick="openCaseDrawer('${esc(grp.name)}','${esc(tc.id)}')" title="Open response panel">⧉</button>
            <button class="btn btn-outline btn-xs" onclick="rerunCase('${esc(grp.name)}','${esc(tc.id)}', this)" title="Re-run this request only (no setup, no variables)">↻</button>
            <button class="btn btn-outline btn-xs" onclick="showCurl('${esc(grp.name)}','${esc(tc.id)}')" title="Show cURL for manual debug">⌘</button>
            <button class="btn btn-outline btn-xs" onclick="editCase('${esc(grp.name)}','${esc(tc.id)}')">Edit</button>
            <button class="btn btn-outline btn-xs" style="color:var(--red);margin-left:3px" onclick="deleteCase('${esc(grp.name)}','${esc(tc.id)}')">✕</button>
          </td>
        </tr>
        <tr class="expand-row${memberCls}" id="exp-${esc(tc.id)}">
          <td colspan="9" class="expand-cell">${renderExpand(tc)}</td>
        </tr>`;
}

function toggleTcGroup(key) {
  const rows = document.querySelectorAll('.tcm-' + key);
  if (!rows.length) return;
  const collapse = rows[0].style.display !== 'none';
  rows.forEach(r => { r.style.display = collapse ? 'none' : ''; });
  const arr = document.getElementById('tcarr-' + key);
  if (arr) arr.textContent = collapse ? '▶' : '▼';
}


function renderExpand(tc) {
  const res = tc.result || {};
  const mode = res.modeRun || tc.verificationMode || 'comparison';

  // Comparison block
  const hasCmp = mode === 'comparison' || mode === 'both';
  const diffs = (res.comparisonResult || '').split('\n').filter(Boolean);
  const cmpBlock = hasCmp ? `
    <div style="margin-bottom:12px">
      <div class="expand-label" style="color:#0369a1">⇄ Comparison Result
        ${res.sourceStatus ? `<span style="color:#6b7280;font-weight:400;font-size:11px">· src ${res.sourceStatus} / tgt ${res.targetStatus || '—'}</span>` : ''}
      </div>
      ${diffs.length
        ? `<ul class="diff-list">${diffs.map(d => `<li class="diff-item">${esc(d)}</li>`).join('')}</ul>`
        : (hasCmp && res.status ? `<div style="color:#059669;font-size:12px">✓ No differences</div>` : '')}
      ${res.sourceResponse || res.targetResponse ? `
        <div class="expand-grid" style="margin-top:8px">
          <div>
            <div class="expand-label">Source Response (${esc(res.sourceStatus || '—')})</div>
            <div class="expand-body">${esc(res.sourceResponse || '—')}</div>
          </div>
          <div>
            <div class="expand-label">Target Response (${esc(res.targetStatus || '—')})</div>
            <div class="expand-body">${esc(res.targetResponse || '—')}</div>
          </div>
        </div>` : ''}
    </div>` : '';

  // Automation block
  const hasAuto = mode === 'automation' || mode === 'both';
  const assertLines = (res.assertionResult || '').split('\n').filter(Boolean);
  const autoBlock = hasAuto ? `
    <div style="margin-bottom:12px">
      <div class="expand-label" style="color:#7c3aed">✓ Assertion Result
        ${res.targetStatus ? `<span style="color:#6b7280;font-weight:400;font-size:11px">· tgt ${res.targetStatus}</span>` : ''}
      </div>
      ${assertLines.length
        ? `<div style="font-size:12px;font-family:monospace;background:#faf5ff;border:1px solid #e9d5ff;border-radius:6px;padding:8px;margin-top:4px;white-space:pre-wrap">${assertLines.map(l => {
            const fail = l.startsWith('✗');
            return `<span style="color:${fail ? '#dc2626' : '#059669'}">${esc(l)}</span>`;
          }).join('\n')}</div>`
        : (hasAuto && res.status ? `<div style="color:#059669;font-size:12px">✓ All assertions passed</div>` : '')}
      ${res.targetResponse && !hasCmp ? `
        <div style="margin-top:8px">
          <div class="expand-label">Target Response (${esc(res.targetStatus || '—')})</div>
          <div class="expand-body">${esc(res.targetResponse || '—')}</div>
        </div>` : ''}
    </div>` : '';

  return `<div>
    ${cmpBlock}${autoBlock}
    ${res.executedAt ? `<div style="font-size:11px;color:#9ca3af;margin-top:4px">Executed: ${esc(res.executedAt)}</div>` : ''}
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
    const tc  = grp?.testRequests?.find(c => c.id === caseId);
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
    sv('tc-verificationMode', tc.verificationMode || 'comparison');
    sv('tc-testCaseId', tc.testCaseId && tc.testCaseId !== tc.id ? tc.testCaseId : '');
    sv('tc-phase', tc.phase || 'test');
    sv('tc-extract', tc.extractVariables || '');
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
    const auto = tc.automationConfig || {};
    sv('tc-expStatus',  auto.expectedStatus  || '');
    sv('tc-expBody',    auto.expectedBody    || '');
    sv('tc-expHeaders', auto.expectedHeaders || '');
    sv('tc-maxRt',      auto.maxResponseTime  ? String(auto.maxResponseTime) : '');
  } else {
    ['tc-id','tc-name','tc-desc','tc-endpoint','tc-author','tc-body',
     'tc-headers','tc-query','tc-form','tc-ignoreFields','tc-tolerance',
     'tc-expStatus','tc-expBody','tc-expHeaders','tc-maxRt','tc-testCaseId','tc-extract'].forEach(id => sv(id, ''));
    sv('tc-method', 'GET'); sv('tc-enabled', 'true'); sv('tc-verificationMode', 'comparison');
    sv('tc-phase', 'test');
    sv('tc-caseSens', ''); sv('tc-ignoreOrder', ''); sv('tc-compareErrorResponses', '');
  }
  updateModeUI();
  openModal('caseModal');
}

function updateModeUI() {
  const mode = g('tc-verificationMode');
  const showCmp  = mode === 'comparison' || mode === 'both';
  const showAuto = mode === 'automation'  || mode === 'both';
  const cmpSec  = document.getElementById('cmpSection');
  const autoSec = document.getElementById('autoSection');
  if (cmpSec)  cmpSec.style.display  = showCmp  ? '' : 'none';
  if (autoSec) autoSec.style.display = showAuto ? '' : 'none';
}

function editCase(groupName, caseId) {
  const grp = suite.testGroups.find(g => g.name === groupName);
  const tc  = grp?.testRequests?.find(c => c.id === caseId);
  if (tc) showCaseModal(groupName, tc);
}

async function saveTestCase() {
  const groupName = g('tc-groupName'), isEdit = g('tc-isEdit') === 'true';
  const parseLines = str => str.split('\n').map(s => s.trim()).filter(Boolean)
    .map(kv => { const i = kv.indexOf('='); return i < 0 ? { key: kv, value: '' } : { key: kv.slice(0, i), value: kv.slice(i + 1) }; });

  const cs = g('tc-caseSens'), io = g('tc-ignoreOrder'), tol = g('tc-tolerance'), cer = g('tc-compareErrorResponses');
  const hasCmp = g('tc-ignoreFields') || cs || io || tol || cer;

  const mode = g('tc-verificationMode') || 'comparison';
  const expStatus = g('tc-expStatus'), expBody = g('tc-expBody'),
        expHeaders = g('tc-expHeaders'), maxRt = g('tc-maxRt');
  const hasAuto = expStatus || expBody || expHeaders || maxRt;

  const tc = {
    id: g('tc-id'), name: g('tc-name'), description: g('tc-desc'),
    enabled: g('tc-enabled') === 'true', verificationMode: mode, method: g('tc-method'),
    testCaseId: g('tc-testCaseId') || null, phase: g('tc-phase') || 'test',
    extractVariables: g('tc-extract'),
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
    } : null,
    automationConfig: hasAuto ? {
      expectedStatus:  expStatus,
      expectedBody:    expBody,
      expectedHeaders: expHeaders,
      maxResponseTime: maxRt ? parseInt(maxRt) : 0
    } : null
  };

  const url = '/groups/' + encodeURIComponent(groupName) + '/cases' + (isEdit ? '/' + encodeURIComponent(tc.id) : '');
  const res = await api(isEdit ? 'PUT' : 'POST', url, tc);
  if (!res.success) { alert(res.message); return; }

  const grp = suite.testGroups.find(g => g.name === groupName);
  if (isEdit) {
    const idx = grp.testRequests.findIndex(c => c.id === tc.id);
    if (idx >= 0) grp.testRequests[idx] = { ...grp.testRequests[idx], ...tc };
  } else {
    grp.testRequests.push(tc);
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
    grp.testRequests = grp.testRequests.filter(c => c.id !== caseId);
    renderDetailCases(grp);
    renderDetailStats(grp);
    renderGroupGrid(suite.testGroups);
    toast('Test case deleted');
  } else alert(res.message);
}

// ─── Execution ────────────────────────────────────────────────────────────────
async function runAll()       { await startExec([], 'All groups'); }
async function runGroup(name) { await startExec([name], `Group "${name}"`); }

// ─── Re-run a single case ─────────────────────────────────────────────────────
async function rerunCase(groupName, caseId, btnEl) {
  if (btnEl) { btnEl.disabled = true; btnEl.textContent = '⟳'; }
  try {
    const res = await api('POST', '/execute/case', { groupName, caseId });
    if (!res.success) { toast(res.message, true); return; }
    const updated = res.data;
    // Replace TC in local suite + re-render only this group's detail rows
    const grp = suite.testGroups.find(g => g.name === groupName);
    if (grp) {
      const idx = grp.testRequests.findIndex(c => c.id === caseId);
      if (idx >= 0) grp.testRequests[idx] = updated;
      renderDetailCases(grp);
      renderDetailStats(grp);
    }
    renderGroupGrid(suite.testGroups);
    const st = (updated.result && updated.result.status) || 'pending';
    toast(`Re-ran ${caseId}: ${st}`);
  } catch (e) {
    toast('Re-run failed: ' + e.message, true);
  }
}

// ─── Show cURL for a case ─────────────────────────────────────────────────────
async function showCurl(groupName, caseId) {
  const res = await api('GET', `/execute/case/curl?groupName=${encodeURIComponent(groupName)}&caseId=${encodeURIComponent(caseId)}`);
  if (!res.success) { toast(res.message, true); return; }
  const { source, target } = res.data;
  document.getElementById('curlCaseId').textContent = caseId;
  document.getElementById('curlSource').textContent = source || '# No source env';
  document.getElementById('curlTarget').textContent = target || '# No target env';
  openModal('curlModal');
}

function copyCurl(which) {
  const el = document.getElementById(which === 'source' ? 'curlSource' : 'curlTarget');
  navigator.clipboard.writeText(el.textContent).then(
    () => toast(`Copied ${which} cURL`),
    () => toast('Copy failed', true)
  );
}

let _execLabel = '';
let _seenRecent = new Set();

/** Flip status badges of rows in the open group detail as the engine reports them. */
function applyLiveRowUpdates(p) {
  if (!currentGroup) return;
  for (const k of (p.active || [])) {
    const i = k.indexOf('::');
    if (i < 0 || k.slice(0, i) !== currentGroup) continue;
    const el = document.getElementById('st-' + k.slice(i + 2));
    if (el && el.textContent !== 'running') { el.textContent = 'running'; el.className = 'bs s-running'; }
  }
  const touched = new Set();
  for (const k of (p.active || [])) {
    const i = k.indexOf('::');
    if (i >= 0 && k.slice(0, i) === currentGroup) {
      const key = _tcOfReq[k.slice(i + 2)];
      if (key) touched.add(key);
    }
  }
  for (const e of (p.recent || [])) {
    const sig = e.group + '::' + e.requestId + '@' + e.at;
    if (_seenRecent.has(sig)) continue;
    _seenRecent.add(sig);
    if (e.group !== currentGroup) continue;
    const el = document.getElementById('st-' + e.requestId);
    if (el) { el.textContent = e.status; el.className = 'bs s-' + e.status; }
    if (_tcOfReq[e.requestId]) touched.add(_tcOfReq[e.requestId]);
  }
  for (const key of touched) refreshTcHeader(key);
}

/** Recompute a TC header rollup from the live badge states of its members. */
function refreshTcHeader(key) {
  const el = document.getElementById('tcst-' + key);
  if (!el) return;
  const sts = (_tcMembers[key] || []).map(id => document.getElementById('st-' + id)?.textContent || 'pending');
  let roll = 'pending';
  if (sts.includes('running'))      roll = 'running';
  else if (sts.includes('error'))   roll = 'error';
  else if (sts.includes('failed'))  roll = 'failed';
  else if (sts.length && sts.every(x => x === 'passed')) roll = 'passed';
  el.textContent = roll;
  el.className = 'bs s-' + roll;
}

async function startExec(groups, label) { await startExecBody({ groups }, label); }

/** Run ONE logical test case — its member requests run sequentially; group
 *  setup/teardown and Global Setup are included so {{variables}} resolve. */
async function runTestCase(groupName, tcId) {
  await startExecBody({
    scope: 'testcases',
    includeSetup: true,
    testCases: [{ groupName, testCaseId: tcId }]
  }, `Test case "${tcId}"`);
}

async function startExecBody(body, label) {
  const res = await api('POST', '/execute', body);
  if (!res.success) { toast(res.message, true); return; }
  _execLabel = label || 'All groups';
  const t = document.getElementById('progressTitle');
  if (t) t.textContent = '⚡ Executing…';
  const sb = document.getElementById('btnStopExec');
  if (sb) sb.disabled = false;
  document.getElementById('progressCounts').innerHTML = '';
  document.getElementById('progressDetail').textContent = '';
  document.getElementById('progressBar').style.width = '0%';
  // NOTE: must be 'block' — '' falls back to the stylesheet's display:none,
  // which is why the progress box historically never showed up.
  document.getElementById('progressBox').style.display = 'block';
  _seenRecent = new Set();
  toast('▶ Started: ' + _execLabel);
  pollProgress();          // fill the box immediately, no 1s dead air
  startPolling();
}

async function stopExec() {
  const res = await api('POST', '/execute/stop');
  if (!res.success) { toast(res.message, true); return; }
  toast('Stopping — in-flight requests will finish, teardown still runs');
}

function startPolling() { stopPolling(); pollTimer = setInterval(pollProgress, 1000); }
function stopPolling()  { if (pollTimer) { clearInterval(pollTimer); pollTimer = null; } }

async function pollProgress() {
  const res = await api('GET', '/execute/progress');
  if (!res.success) return;
  const p = res.data;

  document.getElementById('progressLabel').textContent = `${p.done} / ${p.total}`;
  document.getElementById('progressBar').style.width   = (p.percent || 0) + '%';
  document.getElementById('progressCounts').innerHTML =
    `<span style="color:#059669;font-weight:600">${p.passed || 0} ✓</span>` +
    `<span style="color:#dc2626;font-weight:600;margin-left:8px">${p.failed || 0} ✗</span>` +
    (p.errorCount ? `<span style="color:#d97706;font-weight:600;margin-left:8px">${p.errorCount} !</span>` : '');

  const stopping = p.state === 'stopping';
  const t = document.getElementById('progressTitle');
  if (t) t.textContent = stopping ? '⏳ Stopping…' : '⚡ Executing…';
  const sb = document.getElementById('btnStopExec');
  if (sb) sb.disabled = stopping;

  // Show exactly what is running right now (backend reports live keys)
  const act = p.active || [];
  document.getElementById('progressDetail').textContent =
    act.length ? '▶ Running: ' + act.map(k => (k.split('::')[1] || k)).join(' · ')
               : (stopping ? 'Finishing in-flight requests…'
                           : (p.currentGroup ? `[${p.currentGroup}] ${p.currentCase || ''}` : ''));

  applyLiveRowUpdates(p);

  if (p.state === 'done' || p.state === 'stopped' || p.state === 'aborted') {
    await finishExec(p);
  }
}

async function finishExec(p) {
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
    renderResultsPanel();
    refreshCaseDrawer();
  }
  // Stay where you are — summary modal offers Results as an option
  openExecDoneModal(p);
}

function openExecDoneModal(p) {
  const state = p.state || (p.stopped ? 'stopped' : (p.error ? 'aborted' : 'done'));
  const titles = { done: '✅ Execution finished', stopped: '⏹ Execution stopped', aborted: '❌ Execution aborted' };
  document.getElementById('edTitle').textContent  = titles[state] || titles.done;
  document.getElementById('edScope').textContent  = 'Scope: ' + (_execLabel || 'All groups');
  document.getElementById('ed-total').textContent = p.total || 0;
  document.getElementById('ed-pass').textContent  = p.passed || 0;
  document.getElementById('ed-fail').textContent  = p.failed || 0;
  document.getElementById('ed-error').textContent = p.errorCount || 0;
  const bits = [];
  if (p.elapsedMs != null) bits.push('Duration: ' + fmtDur(p.elapsedMs));
  if (state === 'stopped') bits.push('Remaining requests were skipped — teardown still ran.');
  if (state === 'aborted' && p.error) bits.push('Error: ' + p.error);
  document.getElementById('edMeta').textContent = bits.join('  ·  ');
  openModal('execDoneModal');
}

// ─── Group XML Export ─────────────────────────────────────────────────────────
function exportGroupXml(name) {
  window.location.href = '/api/groups/' + encodeURIComponent(name) + '/export/xml';
}

// ─── Results Panel ────────────────────────────────────────────────────────────
function renderResultsPanel() {
  const groups = suite?.testGroups || [];
  let total = 0, passed = 0, failed = 0, error = 0;

  for (const grp of groups) {
    for (const tc of (grp.testRequests || [])) {
      if (tc.enabled === false) continue;
      total++;
      const s = tc.result?.status;
      if (s === 'passed')      passed++;
      else if (s === 'failed') failed++;
      else if (s === 'error')  error++;
    }
  }

  const executed = passed + failed + error;
  const pending  = total - executed;
  const passRate = total > 0 ? Math.round(passed / total * 100) : 0;

  const empty   = document.getElementById('resultsEmpty');
  const summBox = document.getElementById('resultsSummaryBox');
  const breakdown = document.getElementById('resultsGroupBreakdown');

  if (executed === 0) {
    empty.style.display = ''; summBox.style.display = 'none';
    breakdown.innerHTML = ''; return;
  }

  empty.style.display = 'none'; summBox.style.display = '';

  // Overall numbers
  document.getElementById('resultsPassRate').textContent = passRate + '%';
  document.getElementById('rs-total').textContent = total;
  document.getElementById('rs-pass').textContent  = passed;
  document.getElementById('rs-fail').textContent  = failed;
  document.getElementById('rs-error').textContent = error;
  document.getElementById('rs-pend').textContent  = pending;
  document.getElementById('rs-bar-pass').style.width  = (passed / total * 100) + '%';
  document.getElementById('rs-bar-fail').style.width  = (failed / total * 100) + '%';
  document.getElementById('rs-bar-error').style.width = (error  / total * 100) + '%';
  document.getElementById('resultsRunAt').textContent =
    'Last run: ' + new Date().toLocaleTimeString();

  // Per-group breakdown
  breakdown.innerHTML = groups.map(grp => {
    const cases = (grp.testRequests || []).filter(tc => tc.enabled !== false);
    const gPass  = cases.filter(tc => tc.result?.status === 'passed').length;
    const gFail  = cases.filter(tc => tc.result?.status === 'failed').length;
    const gError = cases.filter(tc => tc.result?.status === 'error').length;
    const gPend  = cases.length - gPass - gFail - gError;
    const gTotal = cases.length;
    const gRate  = gTotal > 0 ? Math.round(gPass / gTotal * 100) : 0;

    // Failed/error TCs list
    const badTcs = cases.filter(tc => tc.result?.status === 'failed' || tc.result?.status === 'error');

    return `<div class="box" style="margin-bottom:12px">
      <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:10px">
        <div style="font-weight:600;font-size:14px;cursor:pointer;color:var(--blue)"
             onclick="openGroupDetail('${esc(grp.name)}')">${esc(grp.name)}</div>
        <span style="font-size:13px;font-weight:700;color:${gRate===100?'#059669':'#d97706'}">${gRate}%</span>
      </div>
      <div style="display:flex;gap:8px;margin-bottom:10px;flex-wrap:wrap">
        <div class="sum-pill sum-total"><span class="sum-n">${gTotal}</span><span class="sum-l">Total</span></div>
        <div class="sum-pill sum-pass" ><span class="sum-n">${gPass}</span><span class="sum-l">Passed</span></div>
        <div class="sum-pill sum-fail" ><span class="sum-n">${gFail}</span><span class="sum-l">Failed</span></div>
        <div class="sum-pill sum-error"><span class="sum-n">${gError}</span><span class="sum-l">Error</span></div>
        <div class="sum-pill sum-pend" ><span class="sum-n">${gPend}</span><span class="sum-l">Pending</span></div>
      </div>
      <div style="background:#e5e7eb;border-radius:999px;height:6px;overflow:hidden;display:flex;margin-bottom:${badTcs.length?'12px':'0'}">
        <div style="background:#10b981;width:${gTotal>0?gPass/gTotal*100:0}%;height:100%"></div>
        <div style="background:#ef4444;width:${gTotal>0?gFail/gTotal*100:0}%;height:100%"></div>
        <div style="background:#f97316;width:${gTotal>0?gError/gTotal*100:0}%;height:100%"></div>
      </div>
      ${badTcs.length ? `<div style="display:flex;flex-direction:column;gap:4px">
        ${badTcs.map(tc => {
          const st = tc.result?.status;
          const diffs = (tc.result?.differences || '').split('\n').filter(Boolean);
          return `<div style="display:flex;align-items:flex-start;gap:8px;padding:6px 8px;border-radius:6px;background:${st==='error'?'#fff7ed':'#fef2f2'};cursor:pointer"
                      onclick="openGroupDetail('${esc(grp.name)}');openCaseDrawer('${esc(grp.name)}','${esc(tc.id)}')">
            <span class="bs s-${st}" style="flex-shrink:0">${st}</span>
            <div>
              <span style="font-weight:600;font-size:12px">${esc(tc.id)}</span>
              <span style="color:#6b7280;font-size:12px;margin-left:6px">${esc(tc.name)}</span>
              ${diffs.length ? `<div style="font-size:11px;color:#9ca3af;margin-top:2px">${esc(diffs[0])}${diffs.length>1?' …':''}</div>` : ''}
            </div>
          </div>`;
        }).join('')}
      </div>` : `<div style="font-size:12px;color:#059669;margin-top:4px">✓ All tests passed</div>`}
    </div>`;
  }).join('');
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
  document.getElementById('ig-file').value      = '';
  document.getElementById('ig-file-json').value = '';
  sv('ig-mode',      'new');
  sv('ig-mode-json', 'new');
  openModal('importGroupModal');
}

// ─── Group File Import (XML + JSON) ──────────────────────────────────────────
async function importGroupFile(format) {
  const fileInputId = format === 'json' ? 'ig-file-json' : 'ig-file';
  const modeId      = format === 'json' ? 'ig-mode-json' : 'ig-mode';
  const file = document.getElementById(fileInputId).files[0];
  if (!file) { toast(`Please choose a ${format.toUpperCase()} file`); return; }

  const fd = new FormData();
  fd.append('file', file);

  const mode = document.getElementById(modeId).value;
  const res  = await (await fetch(`/api/groups/import/${format}?mode=${mode}`, { method: 'POST', body: fd })).json();
  if (!res.success) { toast(res.message); return; }

  const imported    = res.data;
  const existingIdx = suite.testGroups.findIndex(g => g.name === imported.name);
  if (existingIdx >= 0) suite.testGroups[existingIdx] = imported;
  else suite.testGroups.push(imported);

  closeModal('importGroupModal');
  renderGroupGrid(suite.testGroups);
  toast(res.message);
  openGroupDetail(imported.name);
}

// Backward-compat alias
function importGroupXml() { importGroupFile('xml'); }

// ─── Case Response Drawer ─────────────────────────────────────────────────────
let drawerCase = null; // { group, id }

function openCaseDrawer(groupName, caseId) {
  drawerCase = { group: groupName, id: caseId };
  renderCaseDrawer();
  document.getElementById('caseDrawer').classList.add('open');
}

function closeCaseDrawer() {
  drawerCase = null;
  document.getElementById('caseDrawer').classList.remove('open');
}

function refreshCaseDrawer() { if (drawerCase) renderCaseDrawer(); }

function drawerPre(text) {
  return `<div class="drawer-pre">${esc(text)}<button class="copy-mini" onclick="copyDrawer(this)">Copy</button></div>`;
}

function renderCaseDrawer() {
  if (!drawerCase) return;
  const grp = suite?.testGroups?.find(g => g.name === drawerCase.group);
  const tc  = grp?.testRequests?.find(c => c.id === drawerCase.id);
  if (!tc) { closeCaseDrawer(); return; }

  const res = tc.result || {};
  const st  = res.status || 'pending';
  const stEl = document.getElementById('drawerStatus');
  stEl.textContent = st;
  stEl.className   = 'bs s-' + st;
  document.getElementById('drawerCaseId').textContent = tc.id;
  document.getElementById('drawerRerunBtn').onclick = () => drawerRerun();
  document.getElementById('drawerCurlBtn').onclick  = () => showCurl(drawerCase.group, drawerCase.id);

  const sub = [esc(drawerCase.group)];
  if (tc.testCaseId && tc.testCaseId !== tc.id) sub.push('Test case <b>' + esc(tc.testCaseId) + '</b>');
  if (tc.phase && tc.phase !== 'test') sub.push(esc(tc.phase));
  if (tc.name) sub.push(esc(tc.name));
  document.getElementById('drawerSub').innerHTML = sub.join(' · ');

  const mode    = res.modeRun || tc.verificationMode || 'comparison';
  const hasCmp  = mode === 'comparison' || mode === 'both';
  const hasAuto = mode === 'automation' || mode === 'both';

  let html = '';

  const q = (tc.queryParams || []).map(p2 => p2.key + '=' + p2.value).join('&');
  html += `<div class="req-line"><b>${esc(tc.method || 'GET')}</b> ${esc(tc.endpoint || '')}${q ? '?' + esc(q) : ''}</div>`;
  if (tc.jsonBody) {
    html += `<div class="expand-label">Request Body</div>` + drawerPre(prettyJson(tc.jsonBody));
  }

  if (!res.executedAt && !res.sourceStatus && !res.targetStatus) {
    html += `<div style="color:#9ca3af;font-size:13px;padding:16px 0">Not executed yet — run the group or hit ↻ Re-run.</div>`;
  } else {
    if (hasCmp) {
      const diffs = (res.comparisonResult || '').split('\n').filter(Boolean);
      html += `<div class="expand-label" style="color:#0369a1;margin-top:10px">⇄ Comparison Result</div>`;
      html += diffs.length
        ? `<ul class="diff-list">${diffs.map(d => `<li class="diff-item">${esc(d)}</li>`).join('')}</ul>`
        : `<div style="color:#059669;font-size:12px;margin-bottom:10px">✓ No differences</div>`;
      html += `<div class="drawer-2col" style="margin-bottom:14px">
        <div>
          <div class="expand-label">Source Response (${esc(res.sourceStatus || '—')})</div>
          ${drawerPre(prettyJson(res.sourceResponse) || '—')}
        </div>
        <div>
          <div class="expand-label">Target Response (${esc(res.targetStatus || '—')})</div>
          ${drawerPre(prettyJson(res.targetResponse) || '—')}
        </div>
      </div>`;
    }
    if (hasAuto) {
      const lines = (res.assertionResult || '').split('\n').filter(Boolean);
      html += `<div class="expand-label" style="color:#7c3aed;margin-top:4px">✓ Assertion Result · tgt ${esc(res.targetStatus || '—')}</div>`;
      html += lines.length
        ? `<div style="font-size:12px;font-family:Consolas,monospace;background:#faf5ff;border:1px solid #e9d5ff;border-radius:6px;padding:8px;margin-bottom:12px;white-space:pre-wrap">${lines.map(l => `<span style="color:${l.startsWith('✗') ? '#dc2626' : '#059669'}">${esc(l)}</span>`).join('\n')}</div>`
        : `<div style="color:#059669;font-size:12px;margin-bottom:12px">✓ All assertions passed</div>`;
      if (!hasCmp && res.targetResponse) {
        html += `<div class="expand-label">Target Response (${esc(res.targetStatus || '—')})</div>` + drawerPre(prettyJson(res.targetResponse));
      }
    }
    if (res.executedAt) {
      html += `<div style="font-size:11px;color:#9ca3af;margin-top:8px">Executed: ${esc(res.executedAt)} · mode ${esc(mode)}</div>`;
    }
  }

  document.getElementById('drawerBody').innerHTML = html;
}

function copyDrawer(btn) {
  const clone = btn.parentElement.cloneNode(true);
  const cp = clone.querySelector('.copy-mini');
  if (cp) cp.remove();
  navigator.clipboard.writeText(clone.innerText).then(
    () => { btn.textContent = 'Copied'; setTimeout(() => btn.textContent = 'Copy', 1200); },
    () => toast('Copy failed', true)
  );
}

async function drawerRerun() {
  if (!drawerCase) return;
  const btn = document.getElementById('drawerRerunBtn');
  await rerunCase(drawerCase.group, drawerCase.id, btn);
  btn.disabled = false;
  btn.textContent = '↻ Re-run';
  renderCaseDrawer();
}
