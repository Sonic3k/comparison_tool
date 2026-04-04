reg('results', {
  render(el) {
    el.innerHTML = `
    <div class="page">
      <div class="page-hd">
        <div>
          <div class="page-title">Results</div>
          <div class="page-sub" id="resultsSub">—</div>
        </div>
        <div class="page-acts" id="resultsActs"></div>
      </div>
      <div id="resultsBody"></div>
    </div>`;
    this.refresh();
  },

  refresh() {
    const suite = activeSuite();
    if (!suite) { document.getElementById('resultsBody').innerHTML = noSuiteMsg(); return; }

    const groups = suite.testGroups || [];
    const allTcs = groups.flatMap(g => g.testCases || []);
    const withRes = allTcs.filter(tc => tc.result && tc.result.status && tc.result.status !== 'pending');
    const total   = allTcs.filter(tc => tc.enabled).length;
    const done    = withRes.length;
    const passed  = withRes.filter(tc => tc.result.status === 'passed').length;
    const failed  = withRes.filter(tc => tc.result.status === 'failed').length;
    const errors  = withRes.filter(tc => tc.result.status === 'error').length;
    const pending = total - done;

    // Check running task for this suite
    const runTask = S.tasks.find(t => t.suiteId === S.active && t.status === 'in_progress');
    const pendTask = S.tasks.find(t => t.suiteId === S.active && t.status === 'pending');

    // Sub title
    const sub = document.getElementById('resultsSub');
    if (sub) {
      if (runTask) sub.innerHTML = `<span class="t-dot t-dot-in_progress" style="display:inline-block;margin-right:4px"></span>Running… ${runTask.done}/${runTask.total} (${runTask.percent}%)`;
      else if (pendTask) sub.textContent = 'Pending in queue…';
      else if (done) {
        const lastAt = withRes.map(tc=>tc.result.executedAt).filter(Boolean).sort().pop();
        sub.textContent = `${done}/${total} executed${lastAt?' · last run '+lastAt.substring(11,19):''}`;
      } else sub.textContent = 'No results yet — run the suite to see results here';
    }

    // Actions
    const acts = document.getElementById('resultsActs');
    if (acts) {
      acts.innerHTML = `
        <button class="btn btn-ghost btn-sm" onclick="clearResults()">🗑 Clear Results</button>
        <button class="btn btn-ghost btn-sm" onclick="window.location.href='/api/suites/${S.active}/export/excel'">⬇ Excel</button>
        <button class="btn btn-ghost btn-sm" onclick="window.location.href='/api/suites/${S.active}/export/xml'">⬇ XML</button>
        <div class="dd-wrap">
          <div class="run-group">
            <button class="run-main" onclick="runSuite([])">▶ Re-run All</button>
            <button class="run-caret" onclick="toggleRunDd()">▾</button>
          </div>
          <div class="dd-menu hidden" id="runDdMenu"></div>
        </div>`;
    }

    if (!done && !runTask && !pendTask) {
      document.getElementById('resultsBody').innerHTML = `
        <div class="empty" style="padding:60px">
          <div class="empty-icon">📊</div>
          <div class="empty-title">No results yet</div>
          <div class="empty-sub">Run the suite to see results here</div>
        </div>`;
      return;
    }

    const rate = passRate(passed, done || 1);

    document.getElementById('resultsBody').innerHTML = `
      <!-- Summary -->
      <div class="card" style="margin-bottom:20px">
        <div style="display:flex;align-items:center;gap:24px">
          <div class="ring" style="flex-shrink:0">
            ${ringSvg(rate)}
            <div class="ring-text">
              <span style="color:${rate>=80?'var(--pass)':rate>=50?'var(--error)':'var(--fail)'}">${rate}%</span>
              <span style="font-size:10px;color:var(--t3)">pass</span>
            </div>
          </div>
          <div class="stat-grid" style="flex:1">
            <div class="stat-box stat-pass"><div class="stat-n">${passed}</div><div class="stat-l">Passed</div></div>
            <div class="stat-box stat-fail"><div class="stat-n">${failed+errors}</div><div class="stat-l">Failed</div></div>
            <div class="stat-box stat-total"><div class="stat-n">${done}</div><div class="stat-l">Executed</div></div>
            <div class="stat-box"><div class="stat-n stat-n" style="color:var(--t3)">${pending}</div><div class="stat-l">Pending</div></div>
          </div>
          ${runTask ? `
          <div style="flex-shrink:0;min-width:120px">
            <div style="font-size:11px;color:var(--t2);margin-bottom:6px">Running…</div>
            <div class="prog-bar" style="margin-bottom:4px">
              <div class="prog-seg prog-pass" style="width:${runTask.total?runTask.passed/runTask.total*100:0}%"></div>
              <div class="prog-seg prog-fail" style="width:${runTask.total?(runTask.failed+runTask.errorCount)/runTask.total*100:0}%"></div>
            </div>
            <div style="font-size:11px;color:var(--t3)">${runTask.done}/${runTask.total} · ${runTask.percent}%</div>
          </div>` : ''}
        </div>
        <div class="prog-bar" style="margin-top:14px">
          <div class="prog-seg prog-pass"  style="width:${done?passed/done*100:0}%"></div>
          <div class="prog-seg prog-error" style="width:${done?errors/done*100:0}%"></div>
          <div class="prog-seg prog-fail"  style="width:${done?failed/done*100:0}%"></div>
        </div>
      </div>

      <!-- Group accordions -->
      <div>${groups.map((g,i) => renderGroupAcc(g, i)).join('')}</div>`;
  },

  destroy() {}
});

function renderGroupAcc(g, idx) {
  const tcs = g.testCases || [];
  const enabled = tcs.filter(t => t.enabled);
  const withRes = enabled.filter(t => t.result);
  const passed = withRes.filter(t => t.result.status==='passed').length;
  const failed = withRes.filter(t => ['failed','error'].includes(t.result?.status)).length;
  const pending = enabled.length - withRes.length;
  const rate = passRate(passed, withRes.length||1);
  const defaultOpen = failed > 0 || idx === 0;

  return `
  <div class="card-sm" style="margin-bottom:12px">
    <div class="acc-hd" onclick="toggleAcc('acc-${idx}','acc-arr-${idx}')">
      <span class="acc-arrow" id="acc-arr-${idx}">${defaultOpen?'▼':'▶'}</span>
      <span style="font-weight:600;flex:1">${esc(g.name)}</span>
      <span style="font-size:11px;color:var(--t3);margin-right:12px">${withRes.length}/${enabled.length} TCs</span>
      ${passed ? `<span class="badge b-pass" style="margin-right:4px">✓ ${passed}</span>` : ''}
      ${failed ? `<span class="badge b-fail" style="margin-right:4px">✗ ${failed}</span>` : ''}
      ${pending ? `<span class="badge b-pending">${pending} pending</span>` : ''}
      <span style="font-size:13px;font-weight:700;color:${rate>=80?'var(--pass)':rate>=50?'var(--error)':'var(--fail)'};margin-left:10px">${withRes.length?rate+'%':''}</span>
    </div>
    <div id="acc-${idx}" class="acc-body" style="display:${defaultOpen?'block':'none'}">
      <div class="tbl-wrap">
        <table><thead><tr>
          <th style="width:22px"></th><th>ID</th><th>Name</th><th>Mode</th>
          <th>Status</th><th>Comparison / Assertion</th><th>Executed</th>
        </tr></thead>
        <tbody>${enabled.map(tc => renderResRow(tc)).join('')}</tbody>
        </table>
      </div>
    </div>
  </div>`;
}

function renderResRow(tc) {
  const res = tc.result;
  const vm  = tc.verificationMode || 'comparison';
  const hasResult = res && res.status && res.status !== 'pending';
  if (!hasResult) {
    return `<tr>
      <td></td>
      <td><code style="font-size:11px">${esc(tc.id)}</code></td>
      <td style="color:var(--t2)">${esc(tc.name)}</td>
      <td>${vmBadge(vm)}</td>
      <td><span class="badge b-pending">pending</span></td>
      <td style="color:var(--t3);font-size:11px">—</td>
      <td></td>
    </tr>`;
  }

  const diffs = (res.differences||[]).filter(Boolean);
  const summary = diffs.length
    ? `<span style="color:var(--fail);font-size:11px;font-family:var(--mono)">${esc(diffs[0])}${diffs.length>1?` <span style="color:var(--t3)">+${diffs.length-1}</span>`:''}</span>`
    : res.assertionResult
    ? `<span style="font-size:11px;font-family:var(--mono);color:${res.status==='passed'?'var(--pass)':'var(--fail)'}">${esc(res.assertionResult.split('\n')[0])}</span>`
    : `<span style="color:var(--t3);font-size:11px">—</span>`;

  const safeId = tc.id.replace(/[^a-zA-Z0-9-]/g,'_');
  return `
  <tr class="tr-hover ${res.status==='passed'?'':''}" onclick="toggleResRow('res-${safeId}','res-arr-${safeId}')">
    <td style="text-align:center;font-size:10px;color:var(--t3)" id="res-arr-${safeId}">▶</td>
    <td><code style="font-size:11px">${esc(tc.id)}</code></td>
    <td style="font-weight:500;color:var(--t1);max-width:180px" class="truncate">${esc(tc.name)}</td>
    <td>${vmBadge(vm)}</td>
    <td>${statusBadge(res.status)}</td>
    <td style="max-width:280px">${summary}</td>
    <td style="font-size:11px;color:var(--t3);white-space:nowrap">${(res.executedAt||'').substring(11,19)}</td>
  </tr>
  <tr class="tr-exp hidden" id="res-${safeId}">
    <td colspan="7"><div class="exp-body">${renderResDetail(tc, res)}</div></td>
  </tr>`;
}

function renderResDetail(tc, res) {
  const parts = [];
  if (res.sourceStatus||res.targetStatus)
    parts.push(`<div style="display:flex;gap:20px;margin-bottom:8px;font-size:12px">
      <span>Source: <strong>${esc(res.sourceStatus||'—')}</strong></span>
      <span>Target: <strong>${esc(res.targetStatus||'—')}</strong></span>
      <span>Mode run: <strong>${esc(res.modeRun||'—')}</strong></span>
    </div>`);

  const diffs = (res.differences||[]).filter(Boolean);
  if (diffs.length)
    parts.push(`<div style="margin-bottom:10px"><div style="font-size:11px;color:var(--cmp);font-weight:600;margin-bottom:4px">Comparison Result</div>
      <div class="assert-out">${diffs.map(d=>`<div class="${res.status==='passed'?'a-pass':'a-fail'}">${esc(d)}</div>`).join('')}</div></div>`);
  else if (res.comparisonResult && !res.comparisonResult.includes('error'))
    parts.push(`<div style="margin-bottom:10px"><div style="font-size:11px;color:var(--cmp);font-weight:600;margin-bottom:4px">Comparison</div>
      <div class="assert-out a-pass">✓ No differences</div></div>`);

  if (res.assertionResult)
    parts.push(`<div style="margin-bottom:10px"><div style="font-size:11px;color:var(--auto);font-weight:600;margin-bottom:4px">Assertions</div>
      <div class="assert-out">${res.assertionResult.split('\n').map(l=>`<div class="${l.startsWith('✗')?'a-fail':'a-pass'}">${esc(l)}</div>`).join('')}</div></div>`);

  if (res.sourceResponse||res.targetResponse)
    parts.push(`<div style="display:grid;grid-template-columns:1fr 1fr;gap:10px">
      ${res.sourceResponse?`<div><div style="font-size:11px;color:var(--t3);margin-bottom:3px">Source Response</div><pre style="font-size:10px;background:var(--bg);padding:8px;border-radius:var(--r1);overflow:auto;max-height:120px">${esc(res.sourceResponse.substring(0,600))}${res.sourceResponse.length>600?'…':''}</pre></div>`:''}
      ${res.targetResponse?`<div><div style="font-size:11px;color:var(--t3);margin-bottom:3px">Target Response</div><pre style="font-size:10px;background:var(--bg);padding:8px;border-radius:var(--r1);overflow:auto;max-height:120px">${esc(res.targetResponse.substring(0,600))}${res.targetResponse.length>600?'…':''}</pre></div>`:''}
    </div>`);

  // Edit button
  parts.push(`<div style="margin-top:10px;padding-top:8px;border-top:1px solid var(--border)">
    <button class="btn btn-ghost btn-xs" onclick="editTcFromResults('${esc(tc.id)}','${esc(tc.result?.['_group']||'')}')">Edit TC</button>
  </div>`);

  return parts.join('') || '<span style="font-size:12px;color:var(--t3)">No detail</span>';
}

function editTcFromResults(tcId, groupName) {
  // Find which group this TC belongs to
  const suite = activeSuite();
  let found = null, foundGroup = null;
  for (const g of suite?.testGroups||[]) {
    const tc = g.testCases?.find(t => t.id === tcId);
    if (tc) { found = tc; foundGroup = g.name; break; }
  }
  if (!found) { toast('TC not found','err'); return; }
  go('testcases', { group: foundGroup });
  // Open modal after navigation
  setTimeout(() => showTcModal(tcId), 100);
}

let _resExp = null;
function toggleResRow(id, arrId) {
  const row = document.getElementById(id);
  const arr = document.getElementById(arrId);
  if (!row) return;
  const isOpen = !row.classList.contains('hidden');
  if (_resExp && _resExp !== id) {
    document.getElementById(_resExp)?.classList.add('hidden');
    const pa = _resExp.replace('res-','res-arr-');
    const ael = document.getElementById(pa); if(ael) ael.textContent='▶';
  }
  row.classList.toggle('hidden', isOpen);
  if (arr) arr.textContent = isOpen ? '▶' : '▼';
  _resExp = isOpen ? null : id;
}

function toggleAcc(bodyId, arrId) {
  const el = document.getElementById(bodyId);
  const arr = document.getElementById(arrId);
  if (!el) return;
  const isOpen = el.style.display !== 'none';
  el.style.display = isOpen ? 'none' : 'block';
  if (arr) arr.textContent = isOpen ? '▶' : '▼';
}

async function clearResults() {
  if (!confirm('Clear all results for this suite?')) return;
  const suite = activeSuite();
  if (!suite) return;
  // Clear results by setting null on all TCs
  const groups = suite.testGroups || [];
  for (const g of groups) {
    for (const tc of g.testCases || []) {
      tc.result = null;
    }
  }
  pages.results?.refresh?.();
  toast('Results cleared');
}

function noSuiteMsg() { return `<div class="empty"><div class="empty-icon">📊</div><div class="empty-title">No suite selected</div></div>`; }
