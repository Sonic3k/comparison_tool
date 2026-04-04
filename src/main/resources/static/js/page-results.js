// ── Results Page ──────────────────────────────────────────────────────────────
registerPage('results', {
  _taskId: null,

  render(container, params) {
    this._taskId = params?.taskId || null;
    container.innerHTML = `
    <div class="page">
      <div class="page-header">
        <div>
          <div class="page-title">Results</div>
          <div class="page-subtitle">Task execution history</div>
        </div>
        <div class="page-actions" id="resultsActions"></div>
      </div>

      <!-- Task selector -->
      <div id="taskSelector" style="margin-bottom:var(--sp-4)"></div>

      <!-- Results content -->
      <div id="resultsContent"></div>
    </div>`;

    this.refresh();
  },

  refresh() {
    renderTaskSelector(this._taskId);
    if (this._taskId) renderTaskResult(this._taskId);
    else if (state.tasks.length) {
      this._taskId = state.tasks[0].taskId;
      renderTaskSelector(this._taskId);
      renderTaskResult(this._taskId);
    } else {
      document.getElementById('resultsContent').innerHTML = `
        <div class="empty-state">
          <div class="empty-icon">📊</div>
          <div class="empty-title">No tasks yet</div>
          <div class="empty-sub">Run a suite to see results here</div>
        </div>`;
    }
  },

  destroy() { this._taskId = null; }
});

function renderTaskSelector(activeId) {
  const el = document.getElementById('taskSelector');
  if (!el || !state.tasks.length) return;
  el.innerHTML = `
    <div style="display:flex;gap:8px;overflow-x:auto;padding-bottom:4px">
      ${state.tasks.slice(0, 10).map(t => `
        <button onclick="selectResultTask('${t.taskId}')"
          style="flex-shrink:0;padding:6px 12px;border-radius:20px;font-size:12px;font-weight:500;
                 border:1px solid ${t.taskId === activeId ? 'var(--c-primary)' : 'var(--c-border)'};
                 background:${t.taskId === activeId ? 'var(--c-primary-lt)' : 'var(--c-surface)'};
                 color:${t.taskId === activeId ? 'var(--c-primary)' : 'var(--t-secondary)'};
                 cursor:pointer;white-space:nowrap;display:flex;align-items:center;gap:6px">
          <span class="task-status-dot dot-${t.status}" style="width:6px;height:6px;border-radius:50%;display:inline-block"></span>
          ${esc(t.suiteName)}
          ${t.verificationMode ? `<span style="font-size:10px;opacity:.7">${t.verificationMode}</span>` : ''}
        </button>`).join('')}
    </div>`;
}

function selectResultTask(taskId) {
  if (pages.results) pages.results._taskId = taskId;
  renderTaskSelector(taskId);
  renderTaskResult(taskId);
}

async function renderTaskResult(taskId) {
  const el = document.getElementById('resultsContent');
  const actions = document.getElementById('resultsActions');
  if (!el) return;

  // Find from state first (may be live)
  let task = state.tasks.find(t => t.taskId === taskId);
  if (!task) {
    const res = await api('GET', `/tasks/${taskId}`);
    if (!res.success) { el.innerHTML = `<div class="empty-state"><div class="empty-title">Task not found</div></div>`; return; }
    task = res.data;
  }

  // Actions
  if (actions) {
    actions.innerHTML = `
      <button class="btn btn-ghost btn-sm" onclick="window.location.href='/api/tasks/${taskId}/export/excel'">⬇ Excel</button>
      <button class="btn btn-ghost btn-sm" onclick="window.location.href='/api/tasks/${taskId}/export/xml'">⬇ XML</button>
      ${task.status === 'pending' ? `<button class="btn btn-danger btn-sm" onclick="cancelTask('${taskId}')">Cancel</button>` : ''}`;
  }

  // Live progress
  if (task.status === 'pending' || task.status === 'in_progress') {
    el.innerHTML = renderLiveTask(task);
    return;
  }

  // Completed/failed
  if (!task.groupResults?.length) {
    el.innerHTML = `<div class="empty-state">
      <div class="empty-icon">${task.status === 'failed' ? '❌' : '⏳'}</div>
      <div class="empty-title">${task.status === 'failed' ? 'Task failed' : 'No results yet'}</div>
      ${task.errorMessage ? `<div class="empty-sub" style="color:var(--c-fail)">${esc(task.errorMessage)}</div>` : ''}
    </div>`;
    return;
  }

  const total  = task.total || 0;
  const passed = task.passed || 0;
  const failed = task.failed || 0;
  const errors = task.errorCount || 0;
  const rate   = passRate(passed, total);

  el.innerHTML = `
    <!-- Summary -->
    <div class="card mb-4">
      <div style="display:flex;align-items:center;gap:var(--sp-6)">
        <!-- Pass ring -->
        <div class="pass-ring" style="flex-shrink:0">
          ${passRingSvg(rate)}
          <div class="pass-ring-text">
            <span style="font-size:18px;font-weight:700;color:${rate>=80?'var(--c-pass)':rate>=50?'var(--c-error)':'var(--c-fail)'}">${rate}%</span>
            <span style="font-size:10px;color:var(--t-muted)">pass</span>
          </div>
        </div>

        <!-- Stats -->
        <div class="grid-4 flex-1">
          <div class="stat-box stat-pass">
            <div class="stat-n">${passed}</div><div class="stat-l">Passed</div>
          </div>
          <div class="stat-box stat-fail">
            <div class="stat-n">${failed}</div><div class="stat-l">Failed</div>
          </div>
          <div class="stat-box stat-error">
            <div class="stat-n">${errors}</div><div class="stat-l">Error</div>
          </div>
          <div class="stat-box stat-pending">
            <div class="stat-n">${total}</div><div class="stat-l">Total</div>
          </div>
        </div>

        <!-- Meta -->
        <div style="flex-shrink:0;text-align:right">
          ${taskStatusBadge(task.status)}
          ${task.verificationMode ? `<div style="margin-top:6px">${vmBadge(task.verificationMode)}</div>` : ''}
          <div style="font-size:11px;color:var(--t-muted);margin-top:8px">
            ${task.startedAt ? esc(task.startedAt) : ''}
            ${task.getDurationSeconds ? `· ${task.getDurationSeconds}s` : ''}
          </div>
        </div>
      </div>

      <!-- Progress bar -->
      <div class="progress-bar mt-4">
        <div class="progress-seg progress-pass" style="width:${passed/total*100}%"></div>
        <div class="progress-seg progress-error" style="width:${errors/total*100}%"></div>
        <div class="progress-seg progress-fail" style="width:${failed/total*100}%"></div>
      </div>
    </div>

    <!-- Group accordions -->
    <div id="groupAccordions">
      ${task.groupResults.map((gr, i) => renderGroupAccordion(gr, i, task)).join('')}
    </div>`;
}

function renderLiveTask(task) {
  return `
    <div class="card">
      <div style="display:flex;align-items:center;gap:var(--sp-4);margin-bottom:var(--sp-4)">
        <div class="task-status-dot dot-${task.status}" style="width:12px;height:12px"></div>
        <div>
          <div style="font-weight:600">${esc(task.suiteName)}</div>
          <div style="font-size:12px;color:var(--t-muted)">
            ${task.status === 'pending' ? 'Waiting for available slot…' : `Running: ${esc(task.currentGroup)} / ${esc(task.currentCase)}`}
          </div>
        </div>
        <div style="margin-left:auto;font-size:13px;color:var(--t-secondary)">${task.done} / ${task.total}</div>
      </div>
      <div class="progress-bar">
        <div class="progress-seg progress-pass"  style="width:${task.total?task.passed/task.total*100:0}%"></div>
        <div class="progress-seg progress-error" style="width:${task.total?task.errorCount/task.total*100:0}%"></div>
        <div class="progress-seg progress-fail"  style="width:${task.total?task.failed/task.total*100:0}%"></div>
      </div>
      <div style="text-align:center;margin-top:var(--sp-4);font-size:32px;font-weight:700;color:var(--c-primary)">${task.percent}%</div>
    </div>`;
}

function renderGroupAccordion(gr, idx, task) {
  const rate = passRate(gr.passed, gr.total);
  const hasFailures = gr.failed > 0 || gr.error > 0;
  const defaultOpen = hasFailures || idx === 0;

  return `
  <div class="card-sm mb-4" style="overflow:hidden">
    <div style="display:flex;align-items:center;gap:var(--sp-4);cursor:pointer;padding:0 0 0 0"
         onclick="toggleAccordion('acc-${idx}')">
      <div style="flex:1">
        <div style="display:flex;align-items:center;gap:var(--sp-3)">
          <span id="acc-arrow-${idx}" style="font-size:12px;color:var(--t-muted)">${defaultOpen?'▼':'▶'}</span>
          <span style="font-weight:600">${esc(gr.groupName)}</span>
          <span style="font-size:11px;color:var(--t-muted)">${gr.total} TCs</span>
        </div>
      </div>
      <div style="display:flex;align-items:center;gap:var(--sp-3)">
        ${gr.passed ? `<span class="badge badge-pass">✓ ${gr.passed}</span>` : ''}
        ${gr.failed ? `<span class="badge badge-fail">✗ ${gr.failed}</span>` : ''}
        ${gr.error  ? `<span class="badge badge-error">⚠ ${gr.error}</span>`  : ''}
        <span style="font-size:12px;font-weight:700;color:${rate>=80?'var(--c-pass)':rate>=50?'var(--c-error)':'var(--c-fail)'}">${rate}%</span>
      </div>
    </div>
    <div id="acc-${idx}" style="display:${defaultOpen?'block':'none'};margin-top:var(--sp-3)">
      <div class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>ID</th><th>Name</th><th>Mode</th>
              <th>Status</th><th>Comparison</th><th>Assertion</th><th>At</th>
            </tr>
          </thead>
          <tbody>
            ${(gr.caseResults||[]).map(cr => renderCaseRow(cr)).join('')}
          </tbody>
        </table>
      </div>
    </div>
  </div>`;
}

function renderCaseRow(cr) {
  const statusColors = {passed:'var(--c-pass)',failed:'var(--c-fail)',error:'var(--c-error)',pending:'var(--t-muted)'};
  const compLines = (cr.comparisonResult||'').split('\n').filter(Boolean);
  const assertLines = (cr.assertionResult||'').split('\n').filter(Boolean);

  return `
  <tr class="${cr.status==='passed'?'':'row-clickable'}" onclick="toggleCrExpand('${esc(cr.caseId)}')">
    <td><code style="font-size:11px">${esc(cr.caseId)}</code></td>
    <td style="font-weight:500;max-width:200px" class="truncate">${esc(cr.caseName)}</td>
    <td>${vmBadge(cr.verificationMode||'comparison')}</td>
    <td>${statusBadge(cr.status)}</td>
    <td style="max-width:200px">
      ${compLines.length
        ? `<div style="font-size:11px;color:${cr.status==='passed'?'var(--c-pass)':'var(--c-fail)'};font-family:var(--font-mono)">${esc(compLines[0])}${compLines.length>1?` <span style="color:var(--t-muted)">+${compLines.length-1} more</span>`:''}</div>`
        : '<span style="color:var(--t-muted);font-size:11px">—</span>'}
    </td>
    <td style="max-width:180px">
      ${assertLines.length
        ? `<div style="font-size:11px;font-family:var(--font-mono);color:${cr.status==='passed'?'var(--c-pass)':'var(--c-fail)'}">${esc(assertLines[0])}</div>`
        : '<span style="color:var(--t-muted);font-size:11px">—</span>'}
    </td>
    <td style="white-space:nowrap;font-size:11px;color:var(--t-muted)">${esc((cr.executedAt||'').substring(11,19))}</td>
  </tr>
  <tr class="row-expand-area hidden" id="cr-exp-${esc(cr.caseId)}">
    <td colspan="7">
      <div class="expand-content" style="font-size:12px">
        ${renderCrDetail(cr)}
      </div>
    </td>
  </tr>`;
}

function renderCrDetail(cr) {
  const parts = [];
  if (cr.sourceStatus || cr.targetStatus) {
    parts.push(`<div style="display:flex;gap:16px;margin-bottom:8px">
      <span>Source: <strong>${esc(cr.sourceStatus||'—')}</strong></span>
      <span>Target: <strong>${esc(cr.targetStatus||'—')}</strong></span>
    </div>`);
  }
  if (cr.comparisonResult) {
    parts.push(`<div style="margin-bottom:8px">
      <div style="font-weight:600;color:var(--c-comparison);margin-bottom:4px">Comparison</div>
      <div class="assertion-output">${cr.comparisonResult.split('\n').map(l=>`<div class="${l.startsWith('✗')||l.includes('differ')||l.includes('missing')?'assertion-fail':'assertion-pass'}">${esc(l)}</div>`).join('')}</div>
    </div>`);
  }
  if (cr.assertionResult) {
    parts.push(`<div style="margin-bottom:8px">
      <div style="font-weight:600;color:var(--c-automation);margin-bottom:4px">Assertions</div>
      <div class="assertion-output">${cr.assertionResult.split('\n').map(l=>`<div class="${l.startsWith('✗')?'assertion-fail':'assertion-pass'}">${esc(l)}</div>`).join('')}</div>
    </div>`);
  }
  if (cr.sourceResponse || cr.targetResponse) {
    parts.push(`<div style="display:grid;grid-template-columns:1fr 1fr;gap:8px">
      ${cr.sourceResponse ? `<div><div style="font-weight:500;margin-bottom:4px">Source Response</div><pre style="font-size:10px;background:var(--c-bg);padding:8px;border-radius:4px;overflow:auto;max-height:120px">${esc(cr.sourceResponse.substring(0,500))}${cr.sourceResponse.length>500?'…':''}</pre></div>` : ''}
      ${cr.targetResponse ? `<div><div style="font-weight:500;margin-bottom:4px">Target Response</div><pre style="font-size:10px;background:var(--c-bg);padding:8px;border-radius:4px;overflow:auto;max-height:120px">${esc(cr.targetResponse.substring(0,500))}${cr.targetResponse.length>500?'…':''}</pre></div>` : ''}
    </div>`);
  }
  return parts.join('') || '<span style="color:var(--t-muted)">No detail</span>';
}

let _expandedCrId = null;
function toggleCrExpand(id) {
  const safe = id.replace(/[^a-zA-Z0-9-]/g, '_');
  const row = document.getElementById(`cr-exp-${safe}`);
  if (!row) return;
  const isOpen = !row.classList.contains('hidden');
  if (_expandedCrId && _expandedCrId !== safe) {
    document.getElementById(`cr-exp-${_expandedCrId}`)?.classList.add('hidden');
  }
  row.classList.toggle('hidden', isOpen);
  _expandedCrId = isOpen ? null : safe;
}

function toggleAccordion(id) {
  const el = document.getElementById(id);
  if (!el) return;
  const idx = id.split('-')[1];
  const arrow = document.getElementById(`acc-arrow-${idx}`);
  const isOpen = el.style.display !== 'none';
  el.style.display = isOpen ? 'none' : 'block';
  if (arrow) arrow.textContent = isOpen ? '▶' : '▼';
}
