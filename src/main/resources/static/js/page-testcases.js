reg('testcases', {
  render(el, params) {
    const suite = activeSuite();
    const group = suite?.testGroups?.find(g => g.name === params.group);
    if (!suite || !group) { el.innerHTML = noSuite(); return; }

    el.innerHTML = `
    <div class="page">
      <div class="page-hd">
        <div>
          <div class="page-title">${esc(group.name)}</div>
          <div class="page-sub">${group.testCases?.length||0} test cases${group.description?' · '+esc(group.description):''}</div>
        </div>
        <div class="page-acts">
          <button class="btn btn-ghost btn-sm" onclick="runGroup('${esc(group.name)}')">▶ Run Group</button>
          <button class="btn btn-primary btn-sm" onclick="showTcModal()">+ New TC</button>
        </div>
      </div>

      <div class="filter-bar">
        <div class="search-wrap"><span class="search-icon">🔍</span><input class="search-inp" id="tcSearch" placeholder="Search ID, name, endpoint…" oninput="filterTcs()"></div>
        <button class="fc on" data-f="all" onclick="setTcFilter('all',this)">All</button>
        <button class="fc" data-f="comparison" onclick="setTcFilter('comparison',this)">Comparison</button>
        <button class="fc" data-f="automation" onclick="setTcFilter('automation',this)">Automation</button>
        <button class="fc" data-f="both" onclick="setTcFilter('both',this)">Both</button>
        <button class="fc" data-f="disabled" onclick="setTcFilter('disabled',this)">Disabled</button>
      </div>

      <div class="tbl-wrap">
        <table><thead><tr>
          <th style="width:28px"></th><th>ID</th><th>Name</th><th>Method</th>
          <th>Endpoint</th><th>Mode</th><th>Status</th><th>Enabled</th><th style="width:80px"></th>
        </tr></thead>
        <tbody id="tcBody"></tbody></table>
      </div>
    </div>

    <!-- TC Modal -->
    <div class="overlay hidden" id="tcModal">
      <div class="modal modal-lg">
        <div class="modal-hd"><span class="modal-title" id="tcModalTitle">New Test Case</span><span class="modal-x" onclick="closeModal('tcModal')">✕</span></div>
        <div class="modal-body">
          <input type="hidden" id="tc-edit" value="false">
          <input type="hidden" id="tc-origId" value="">

          <div class="form-grid" style="margin-bottom:14px">
            <div class="form-group"><label class="form-label">ID *</label><input class="input" id="tc-id" placeholder="TC-001"></div>
            <div class="form-group"><label class="form-label">Name *</label><input class="input" id="tc-name"></div>
            <div class="form-group form-full"><label class="form-label">Description</label><input class="input" id="tc-desc"></div>
            <div class="form-group"><label class="form-label">Verification Mode</label>
              <select class="input" id="tc-vm" onchange="updateTcSections()">
                <option value="comparison">Comparison</option>
                <option value="automation">Automation</option>
                <option value="both">Both</option>
              </select>
            </div>
            <div class="form-group"><label class="form-label">Enabled</label>
              <select class="input" id="tc-enabled"><option value="true">Yes</option><option value="false">No</option></select>
            </div>
            <div class="form-group"><label class="form-label">Method</label>
              <select class="input" id="tc-method"><option>GET</option><option>POST</option><option>PUT</option><option>PATCH</option><option>DELETE</option></select>
            </div>
            <div class="form-group form-full"><label class="form-label">Endpoint</label><input class="input" id="tc-endpoint" placeholder="/api/users/123"></div>
            <div class="form-group form-full"><label class="form-label">Query Params <span class="form-hint">key=value&key2=val</span></label><input class="input" id="tc-query"></div>
            <div class="form-group form-full"><label class="form-label">JSON Body</label><textarea class="input mono" id="tc-body" rows="3" style="font-size:11px"></textarea></div>
            <div class="form-group form-full"><label class="form-label">Headers <span class="form-hint">Key: Value, one per line</span></label><textarea class="input" id="tc-headers" rows="2"></textarea></div>
            <div class="form-group"><label class="form-label">Author</label><input class="input" id="tc-author"></div>
          </div>

          <div id="cmpSection" style="border-top:1px solid var(--border);padding-top:14px;margin-top:4px">
            <div style="font-size:12px;font-weight:600;color:var(--cmp);margin-bottom:10px">⚖ Comparison Overrides <span style="font-weight:400;color:var(--t3);font-size:11px">— blank = use suite defaults</span></div>
            <div class="form-grid">
              <div class="form-group"><label class="form-label">Ignore Fields</label><input class="input" id="tc-ignoreFields" placeholder="timestamp,requestId"></div>
              <div class="form-group"><label class="form-label">Ignore Array Order</label>
                <select class="input" id="tc-ignoreArr"><option value="">— suite default</option><option value="true">true</option><option value="false">false</option></select>
              </div>
              <div class="form-group"><label class="form-label">Compare Error Responses</label>
                <select class="input" id="tc-compareErr"><option value="">— suite default</option><option value="true">true</option><option value="false">false</option></select>
              </div>
            </div>
          </div>

          <div id="autoSection" class="hidden" style="border-top:1px solid var(--border);padding-top:14px;margin-top:14px">
            <div style="font-size:12px;font-weight:600;color:var(--auto);margin-bottom:10px">🤖 Automation Assertions</div>
            <div class="form-grid">
              <div class="form-group"><label class="form-label">Expected Status</label><input class="input" id="tc-expStatus" placeholder="200 or 2xx"></div>
              <div class="form-group"><label class="form-label">Max Response Time (ms)</label><input class="input" id="tc-maxRt" type="number"></div>
              <div class="form-group form-full"><label class="form-label">Body Assertions <span class="form-hint">one per line — e.g. $.user.name == "John"</span></label>
                <textarea class="input mono" id="tc-expBody" rows="5" style="font-size:11px"></textarea>
              </div>
            </div>
          </div>
        </div>
        <div class="modal-ft">
          <button class="btn btn-ghost" onclick="closeModal('tcModal')">Cancel</button>
          <button class="btn btn-primary" onclick="saveTc()">Save</button>
        </div>
      </div>
    </div>`;

    this._group = group.name;
    _tcFilter = 'all'; _tcSearch = '';
    renderTcBody(group);
  },
  destroy() { this._group = null; }
});

let _tcFilter = 'all', _tcSearch = '', _expanded = null;

function renderTcBody(group) {
  const tbody = document.getElementById('tcBody');
  if (!tbody) return;
  const tcs = group?.testCases || [];

  const filtered = tcs.filter(tc => {
    const vm = tc.verificationMode || 'comparison';
    const mf = _tcFilter === 'all' ? true : _tcFilter === 'disabled' ? !tc.enabled : vm === _tcFilter;
    const q  = _tcSearch.toLowerCase();
    const sf = !q || [tc.id,tc.name,tc.endpoint].some(v=>(v||'').toLowerCase().includes(q));
    return mf && sf;
  });

  if (!filtered.length) {
    tbody.innerHTML = `<tr><td colspan="9"><div class="empty" style="padding:28px"><div class="empty-icon" style="font-size:28px">🔍</div><div class="empty-title">No matches</div></div></td></tr>`;
    return;
  }

  tbody.innerHTML = filtered.map(tc => {
    const vm = tc.verificationMode || 'comparison';
    const res = tc.result;
    return `
    <tr class="tr-hover" onclick="toggleTcExp('${esc(tc.id)}')">
      <td style="text-align:center;color:var(--t3);font-size:11px" id="tc-arr-${esc(tc.id)}">▶</td>
      <td><code style="font-size:11px">${esc(tc.id)}</code></td>
      <td style="font-weight:500;color:var(--t1)">${esc(tc.name)}</td>
      <td>${methodBadge(tc.method||'GET')}</td>
      <td><code style="font-size:11px;color:var(--t2)">${esc(tc.endpoint)}</code></td>
      <td>${vmBadge(vm)}</td>
      <td>${res && res.status && res.status !== 'pending' ? statusBadge(res.status) : '<span style="font-size:11px;color:var(--t3)">—</span>'}</td>
      <td><div class="toggle ${tc.enabled?'on':''}" onclick="event.stopPropagation();toggleTc('${esc(tc.id)}')"></div></td>
      <td onclick="event.stopPropagation()" style="white-space:nowrap">
        <button class="btn btn-ghost btn-xs" onclick="showTcModal('${esc(tc.id)}')">Edit</button>
        <button class="btn btn-ghost btn-xs" style="color:var(--fail)" onclick="deleteTc('${esc(tc.id)}')">✕</button>
      </td>
    </tr>
    <tr class="tr-exp hidden" id="tc-exp-${esc(tc.id)}">
      <td colspan="9"><div class="exp-body">${renderTcDetail(tc)}</div></td>
    </tr>`;
  }).join('');
}

function renderTcDetail(tc) {
  const vm   = tc.verificationMode || 'comparison';
  const auto = tc.automationConfig || {};
  const cmp  = tc.comparisonConfig || {};
  const res  = tc.result;
  const parts = [];

  if (tc.queryParams?.length) parts.push(`<div style="font-size:12px;margin-bottom:6px"><span style="color:var(--t3)">Query:</span> <code>${esc(tc.queryParams.map(p=>p.key+'='+p.value).join('&'))}</code></div>`);
  if (tc.jsonBody) parts.push(`<div style="margin-bottom:8px"><div style="font-size:11px;color:var(--t3);margin-bottom:3px">Body</div><pre style="font-size:11px;background:var(--bg);padding:8px;border-radius:var(--r1);overflow:auto;max-height:80px">${esc(tc.jsonBody)}</pre></div>`);

  if ((vm==='comparison'||vm==='both') && cmp.ignoreFieldsRaw)
    parts.push(`<div style="font-size:12px;margin-bottom:6px"><span style="color:var(--cmp)">Ignore fields:</span> <code>${esc(cmp.ignoreFieldsRaw)}</code></div>`);

  if ((vm==='automation'||vm==='both') && auto.expectedBody)
    parts.push(`<div style="margin-bottom:8px"><div style="font-size:11px;color:var(--auto);margin-bottom:3px">Assertions</div><pre class="assert-out">${esc(auto.expectedBody)}</pre></div>`);

  if (res && res.status && res.status !== 'pending') {
    parts.push(`<div style="border-top:1px solid var(--border);padding-top:8px;margin-top:4px">`);
    if (res.comparisonResult || res.differences?.length) {
      const diffs = res.differences?.length ? res.differences : (res.comparisonResult||'').split('\n').filter(Boolean);
      parts.push(`<div style="margin-bottom:8px"><div style="font-size:11px;color:var(--cmp);margin-bottom:3px">Comparison</div><div class="assert-out">${diffs.map(d=>`<div class="${res.status==='passed'?'a-pass':'a-fail'}">${esc(d)}</div>`).join('')}</div></div>`);
    }
    if (res.assertionResult)
      parts.push(`<div style="margin-bottom:8px"><div style="font-size:11px;color:var(--auto);margin-bottom:3px">Assertions</div><div class="assert-out">${res.assertionResult.split('\n').map(l=>`<div class="${l.startsWith('✗')?'a-fail':'a-pass'}">${esc(l)}</div>`).join('')}</div></div>`);

    if (res.sourceResponse || res.targetResponse) {
      parts.push(`<div style="display:grid;grid-template-columns:1fr 1fr;gap:8px">`);
      if (res.sourceResponse) parts.push(`<div><div style="font-size:11px;color:var(--t3);margin-bottom:3px">Source ${res.sourceStatus}</div><pre style="font-size:10px;background:var(--bg);padding:8px;border-radius:var(--r1);overflow:auto;max-height:100px">${esc(res.sourceResponse.substring(0,400))}${res.sourceResponse.length>400?'…':''}</pre></div>`);
      if (res.targetResponse) parts.push(`<div><div style="font-size:11px;color:var(--t3);margin-bottom:3px">Target ${res.targetStatus}</div><pre style="font-size:10px;background:var(--bg);padding:8px;border-radius:var(--r1);overflow:auto;max-height:100px">${esc(res.targetResponse.substring(0,400))}${res.targetResponse.length>400?'…':''}</pre></div>`);
      parts.push(`</div>`);
    }
    parts.push(`</div>`);
  }

  return parts.join('') || `<span style="font-size:12px;color:var(--t3)">No additional details</span>`;
}

function toggleTcExp(id) {
  const row = document.getElementById(`tc-exp-${id}`);
  const arr = document.getElementById(`tc-arr-${id}`);
  if (!row) return;
  const isOpen = !row.classList.contains('hidden');
  if (_expanded && _expanded !== id) {
    document.getElementById(`tc-exp-${_expanded}`)?.classList.add('hidden');
    const a = document.getElementById(`tc-arr-${_expanded}`); if(a) a.textContent='▶';
  }
  row.classList.toggle('hidden', isOpen);
  if (arr) arr.textContent = isOpen ? '▶' : '▼';
  _expanded = isOpen ? null : id;
}

function filterTcs() {
  _tcSearch = document.getElementById('tcSearch')?.value || '';
  const suite = activeSuite();
  const group = suite?.testGroups?.find(g => g.name === S.group);
  renderTcBody(group);
}

function setTcFilter(f, btn) {
  _tcFilter = f;
  document.querySelectorAll('[data-f]').forEach(b => b.classList.remove('on'));
  btn.classList.add('on');
  filterTcs();
}

function updateTcSections() {
  const vm = document.getElementById('tc-vm')?.value;
  document.getElementById('cmpSection')?.classList.toggle('hidden', vm==='automation');
  document.getElementById('autoSection')?.classList.toggle('hidden', vm==='comparison');
}

function showTcModal(tcId = null) {
  const suite = activeSuite();
  const group = suite?.testGroups?.find(g => g.name === S.group);
  const tc = tcId ? group?.testCases?.find(t => t.id === tcId) : null;

  document.getElementById('tcModalTitle').textContent = tc ? 'Edit Test Case' : 'New Test Case';
  document.getElementById('tc-edit').value = tc ? 'true' : 'false';
  document.getElementById('tc-origId').value = tc?.id || '';

  const sv = (id, v) => { const el=document.getElementById(id); if(el) el.value=v??''; };
  if (tc) {
    const cmp = tc.comparisonConfig || {}, auto = tc.automationConfig || {};
    sv('tc-id',tc.id); sv('tc-name',tc.name); sv('tc-desc',tc.description||'');
    sv('tc-vm',tc.verificationMode||'comparison'); sv('tc-enabled',String(tc.enabled!==false));
    sv('tc-method',tc.method||'GET'); sv('tc-endpoint',tc.endpoint||'');
    sv('tc-query',tc.queryParams?.map(p=>p.key+'='+p.value).join('&')||'');
    sv('tc-body',tc.jsonBody||''); sv('tc-headers',tc.headers||''); sv('tc-author',tc.author||'');
    sv('tc-ignoreFields',cmp.ignoreFieldsRaw||'');
    sv('tc-ignoreArr',cmp.ignoreArrayOrder!=null?String(cmp.ignoreArrayOrder):'');
    sv('tc-compareErr',cmp.compareErrorResponses!=null?String(cmp.compareErrorResponses):'');
    sv('tc-expStatus',auto.expectedStatus||''); sv('tc-expBody',auto.expectedBody||'');
    sv('tc-maxRt',auto.maxResponseTime||'');
  } else {
    ['tc-id','tc-name','tc-desc','tc-query','tc-body','tc-headers','tc-author',
     'tc-ignoreFields','tc-ignoreArr','tc-compareErr','tc-expStatus','tc-expBody','tc-maxRt'].forEach(id=>sv(id,''));
    sv('tc-vm','comparison'); sv('tc-enabled','true'); sv('tc-method','GET');
  }
  updateTcSections();
  openModal('tcModal');
}

async function saveTc() {
  const g = id => document.getElementById(id)?.value?.trim();
  const id = g('tc-id'), name = g('tc-name');
  if (!id||!name) { toast('ID and Name required','err'); return; }

  const vm = g('tc-vm');
  const qs = g('tc-query');
  const queryParams = qs ? qs.split('&').map(p=>{const[k,...v]=p.split('=');return{key:k.trim(),value:v.join('=').trim()};}).filter(p=>p.key) : [];

  const payload = {
    id, name, description: g('tc-desc'), enabled: g('tc-enabled')==='true',
    verificationMode: vm, method: g('tc-method')||'GET', endpoint: g('tc-endpoint'),
    queryParams, jsonBody: g('tc-body'), headers: g('tc-headers'), author: g('tc-author'),
    comparisonConfig: vm!=='automation' ? {
      ignoreFieldsRaw: g('tc-ignoreFields')||null,
      ignoreArrayOrder: g('tc-ignoreArr') ? g('tc-ignoreArr')==='true' : null,
      compareErrorResponses: g('tc-compareErr') ? g('tc-compareErr')==='true' : false,
    } : null,
    automationConfig: vm!=='comparison' ? {
      expectedStatus: g('tc-expStatus')||null,
      expectedBody: g('tc-expBody')||null,
      maxResponseTime: parseInt(g('tc-maxRt'))||0,
    } : null,
  };

  const isEdit = document.getElementById('tc-edit')?.value==='true';
  const origId = document.getElementById('tc-origId')?.value;
  const grp = encodeURIComponent(S.group);
  const base = `/suites/${S.active}/groups/${grp}/cases`;
  const r = isEdit ? await api('PUT',`${base}/${encodeURIComponent(origId)}`,payload) : await api('POST',base,payload);

  if (r.success) {
    await refreshSuite(S.active);
    closeModal('tcModal'); toast(isEdit?'TC updated':'TC created');
    const suite = activeSuite();
    const group = suite?.testGroups?.find(g => g.name === S.group);
    renderTcBody(group);
  } else toast(r.message,'err');
}

async function toggleTc(tcId) {
  const grp = encodeURIComponent(S.group);
  await api('PATCH',`/suites/${S.active}/groups/${grp}/cases/${encodeURIComponent(tcId)}/toggle`);
  await refreshSuite(S.active);
  const suite = activeSuite();
  const group = suite?.testGroups?.find(g => g.name === S.group);
  renderTcBody(group);
}

async function deleteTc(tcId) {
  if (!confirm(`Delete TC "${tcId}"?`)) return;
  const grp = encodeURIComponent(S.group);
  const r = await api('DELETE',`/suites/${S.active}/groups/${grp}/cases/${encodeURIComponent(tcId)}`);
  if (r.success) {
    await refreshSuite(S.active);
    toast('TC deleted');
    const suite = activeSuite();
    const group = suite?.testGroups?.find(g => g.name === S.group);
    renderTcBody(group);
  } else toast(r.message,'err');
}
