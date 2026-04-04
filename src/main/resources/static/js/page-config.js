// ── Config Page ───────────────────────────────────────────────────────────────
registerPage('config', {
  render(container) {
    const suite = state.suites.find(s => s.id === state.activeSuiteId);
    if (!suite) {
      container.innerHTML = `<div class="page"><div class="empty-state">
        <div class="empty-icon">⚙️</div><div class="empty-title">No suite selected</div>
        <div class="empty-sub"><button class="btn btn-primary btn-sm" onclick="navigate('suites')">Go to Suites</button></div>
      </div></div>`;
      return;
    }
    container.innerHTML = `
    <div class="page">
      <div class="page-header">
        <div>
          <div class="page-title">Configuration</div>
          <div class="page-subtitle">${esc(suite.settings?.suiteName)}</div>
        </div>
      </div>
      <div class="tabs">
        <button class="tab-btn active" onclick="showTab('settings',this)">Settings</button>
        <button class="tab-btn" onclick="showTab('environments',this)">Environments</button>
        <button class="tab-btn" onclick="showTab('auth',this)">Auth Profiles</button>
      </div>
      <div id="cfg-settings" class="tab-pane active"></div>
      <div id="cfg-environments" class="tab-pane"></div>
      <div id="cfg-auth" class="tab-pane"></div>
    </div>

    <!-- Env Modal -->
    <div class="overlay hidden" id="envModal">
      <div class="modal">
        <div class="modal-header">
          <div class="modal-title" id="envModalTitle">Environment</div>
          <button class="modal-close" onclick="closeModal('envModal')">✕</button>
        </div>
        <div class="modal-body">
          <div class="form-grid">
            <div class="form-group"><label class="form-label">Name *</label><input class="form-input" id="env-name" placeholder="source"></div>
            <div class="form-group"><label class="form-label">Auth Profile</label><input class="form-input" id="env-auth" placeholder="Legacy-OAuth2"></div>
            <div class="form-group form-full"><label class="form-label">Base URL *</label><input class="form-input" id="env-url" placeholder="https://api.example.com/legacy/api"></div>
          </div>
          <div style="margin-top:16px">
            <label class="form-label" style="margin-bottom:8px;display:block">Default Headers</label>
            <table style="width:100%;font-size:13px"><tbody id="envHdrBody"></tbody></table>
            <button class="btn btn-ghost btn-xs mt-2" onclick="addEnvHdrRow()">+ Add Header</button>
          </div>
        </div>
        <div class="modal-footer">
          <button class="btn btn-ghost" onclick="closeModal('envModal')">Cancel</button>
          <button class="btn btn-primary" onclick="saveEnv()">Save</button>
        </div>
      </div>
    </div>

    <!-- Auth Modal -->
    <div class="overlay hidden" id="authModal">
      <div class="modal">
        <div class="modal-header">
          <div class="modal-title" id="authModalTitle">Auth Profile</div>
          <button class="modal-close" onclick="closeModal('authModal')">✕</button>
        </div>
        <div class="modal-body">
          <div class="form-grid">
            <div class="form-group"><label class="form-label">Profile Name *</label><input class="form-input" id="ap-name"></div>
            <div class="form-group"><label class="form-label">Type</label>
              <select class="form-input" id="ap-type" onchange="onAuthTypeChange()">
                <option value="NONE">None</option>
                <option value="BEARER">Bearer Token</option>
                <option value="BASIC">Basic Auth</option>
                <option value="CLIENT_CREDENTIALS">OAuth2 Client Credentials</option>
              </select>
            </div>
            <div class="form-group form-full" id="ap-token-wrap" style="display:none">
              <label class="form-label">Bearer Token</label><input class="form-input" id="ap-token">
            </div>
            <div class="form-group" id="ap-user-wrap" style="display:none">
              <label class="form-label">Username</label><input class="form-input" id="ap-username">
            </div>
            <div class="form-group" id="ap-pass-wrap" style="display:none">
              <label class="form-label">Password</label><input class="form-input" id="ap-password" type="password">
            </div>
            <div class="form-group form-full" id="ap-url-wrap" style="display:none">
              <label class="form-label">Token URL</label><input class="form-input" id="ap-tokenUrl">
            </div>
            <div class="form-group" id="ap-cid-wrap" style="display:none">
              <label class="form-label">Client ID</label><input class="form-input" id="ap-clientId">
            </div>
            <div class="form-group" id="ap-csec-wrap" style="display:none">
              <label class="form-label">Client Secret</label><input class="form-input" id="ap-clientSecret" type="password">
            </div>
            <div class="form-group" id="ap-scope-wrap" style="display:none">
              <label class="form-label">Scope</label><input class="form-input" id="ap-scope">
            </div>
          </div>
        </div>
        <div class="modal-footer">
          <button class="btn btn-ghost" onclick="closeModal('authModal')">Cancel</button>
          <button class="btn btn-primary" onclick="saveAuth()">Save</button>
        </div>
      </div>
    </div>`;

    renderSettingsTab(suite);
    renderEnvTab(suite);
    renderAuthTab(suite);
  },
  destroy() {}
});

// ── Tabs ──────────────────────────────────────────────────────────────────────
function showTab(name, btn) {
  document.querySelectorAll('.tab-pane').forEach(el => el.classList.remove('active'));
  document.querySelectorAll('.tab-btn').forEach(el => el.classList.remove('active'));
  document.getElementById(`cfg-${name}`)?.classList.add('active');
  btn.classList.add('active');
}

// ── Settings Tab ──────────────────────────────────────────────────────────────
function renderSettingsTab(suite) {
  const s = suite.settings || {};
  const ec = s.executionConfig || {};
  const cc = s.comparisonConfig || {};
  const el = document.getElementById('cfg-settings');
  if (!el) return;

  el.innerHTML = `
  <div class="card mb-4">
    <h4 style="margin-bottom:var(--sp-4)">Suite Info</h4>
    <div class="form-grid">
      <div class="form-group"><label class="form-label">Suite Name</label><input class="form-input" id="s-name" value="${esc(s.suiteName||'')}"></div>
      <div class="form-group"><label class="form-label">Version</label><input class="form-input" id="s-version" value="${esc(s.version||'')}"></div>
      <div class="form-group form-full"><label class="form-label">Description</label><input class="form-input" id="s-desc" value="${esc(s.description||'')}"></div>
      <div class="form-group"><label class="form-label">Author</label><input class="form-input" id="s-author" value="${esc(s.createdBy||'')}"></div>
      <div class="form-group"><label class="form-label">Last Updated By</label><input class="form-input" id="s-updatedBy" value="${esc(s.lastUpdatedBy||'')}"></div>
    </div>
  </div>

  <div class="card mb-4">
    <h4 style="margin-bottom:var(--sp-4)">Execution</h4>
    <div class="form-grid">
      <div class="form-group">
        <label class="form-label">Source Environment</label>
        <input class="form-input" id="s-srcEnv" value="${esc(ec.sourceEnvironment||'')}" placeholder="source">
      </div>
      <div class="form-group">
        <label class="form-label">Target Environment</label>
        <input class="form-input" id="s-tgtEnv" value="${esc(ec.targetEnvironment||'')}" placeholder="target">
      </div>
      <div class="form-group">
        <label class="form-label">Execution Mode</label>
        <select class="form-input" id="s-execMode">
          <option value="PARALLEL" ${ec.mode==='PARALLEL'?'selected':''}>Parallel</option>
          <option value="SOURCE_FIRST" ${ec.mode==='SOURCE_FIRST'?'selected':''}>Source First</option>
        </select>
      </div>
      <div class="form-group">
        <label class="form-label">Verification Mode <span class="form-hint">suite-level filter</span></label>
        <select class="form-input" id="s-vm">
          <option value="" ${!ec.verificationMode?'selected':''}>— per TC (all) —</option>
          <option value="comparison" ${ec.verificationMode==='comparison'?'selected':''}>Comparison only</option>
          <option value="automation" ${ec.verificationMode==='automation'?'selected':''}>Automation only</option>
          <option value="both" ${ec.verificationMode==='both'?'selected':''}>Both only</option>
        </select>
      </div>
      <div class="form-group"><label class="form-label">Timeout (s)</label><input class="form-input" id="s-timeout" type="number" value="${ec.timeout||30}"></div>
      <div class="form-group"><label class="form-label">Parallel Limit</label><input class="form-input" id="s-parallelLimit" type="number" value="${ec.parallelLimit||10}"></div>
      <div class="form-group"><label class="form-label">Delay Between Requests (ms)</label><input class="form-input" id="s-delay" type="number" value="${ec.delayBetweenRequests||0}"></div>
      <div class="form-group"><label class="form-label">Retries</label><input class="form-input" id="s-retries" type="number" value="${ec.retries||2}"></div>
    </div>
  </div>

  <div class="card mb-4">
    <h4 style="margin-bottom:var(--sp-4)">Comparison Rules</h4>
    <div class="form-grid">
      <div class="form-group form-full">
        <label class="form-label">Ignore Fields <span class="form-hint">— comma-separated</span></label>
        <input class="form-input" id="s-ignoreFields" value="${esc(cc.ignoreFieldsRaw||'')}" placeholder="timestamp,requestId,executionTime">
      </div>
      <div class="form-group">
        <label class="form-label">Case Sensitive</label>
        <select class="form-input" id="s-caseSensitive">
          <option value="true"  ${cc.caseSensitive!==false?'selected':''}>true</option>
          <option value="false" ${cc.caseSensitive===false?'selected':''}>false</option>
        </select>
      </div>
      <div class="form-group">
        <label class="form-label">Ignore Array Order</label>
        <select class="form-input" id="s-ignoreArrayOrder">
          <option value="false" ${!cc.ignoreArrayOrder?'selected':''}>false</option>
          <option value="true"  ${cc.ignoreArrayOrder?'selected':''}>true</option>
        </select>
      </div>
      <div class="form-group">
        <label class="form-label">Compare Error Responses (5xx)</label>
        <select class="form-input" id="s-compareErr">
          <option value="false" ${!cc.compareErrorResponses?'selected':''}>false — treat 5xx as error</option>
          <option value="true"  ${cc.compareErrorResponses?'selected':''}>true — compare all</option>
        </select>
      </div>
      <div class="form-group">
        <label class="form-label">Numeric Tolerance</label>
        <input class="form-input" id="s-tolerance" type="number" step="0.0001" value="${cc.numericTolerance||0.001}">
      </div>
    </div>
  </div>

  <div style="display:flex;justify-content:flex-end">
    <button class="btn btn-primary" onclick="saveSettings()">Save Settings</button>
  </div>`;
}

async function saveSettings() {
  const g = id => document.getElementById(id)?.value;
  const vm = g('s-vm');
  const res = await api('PUT', `/suites/${state.activeSuiteId}/settings`, {
    suiteName: g('s-name'), description: g('s-desc'), version: g('s-version'),
    createdBy: g('s-author'), lastUpdatedBy: g('s-updatedBy'),
    executionConfig: {
      mode: g('s-execMode'),
      verificationMode: vm || null,
      sourceEnvironment: g('s-srcEnv'),
      targetEnvironment: g('s-tgtEnv'),
      timeout: +g('s-timeout'),
      parallelLimit: +g('s-parallelLimit'),
      delayBetweenRequests: +g('s-delay'),
      retries: +g('s-retries'),
    },
    comparisonConfig: {
      ignoreFieldsRaw: g('s-ignoreFields'),
      caseSensitive: g('s-caseSensitive') === 'true',
      ignoreArrayOrder: g('s-ignoreArrayOrder') === 'true',
      compareErrorResponses: g('s-compareErr') === 'true',
      numericTolerance: +g('s-tolerance'),
    }
  });
  if (res.success) {
    const idx = state.suites.findIndex(s => s.id === state.activeSuiteId);
    if (idx >= 0) state.suites[idx].settings = res.data;
    toast('Settings saved');
  } else toast(res.message, 'error');
}

// ── Environments Tab ──────────────────────────────────────────────────────────
let _editEnvIdx = -1;

function renderEnvTab(suite) {
  const envs = suite.environments || [];
  const el = document.getElementById('cfg-environments');
  if (!el) return;
  el.innerHTML = `
  <div class="section-header">
    <div class="section-title">Environments</div>
    <button class="btn btn-primary btn-sm" onclick="showEnvModal()">+ Add</button>
  </div>
  <div id="envTable">
    ${envs.length ? `
    <div class="table-wrap">
      <table><thead><tr><th>Name</th><th>URL</th><th>Auth Profile</th><th>Headers</th><th></th></tr></thead>
      <tbody>${envs.map((e,i) => `
        <tr>
          <td style="font-weight:600">${esc(e.name)}</td>
          <td style="font-size:12px;color:var(--t-secondary)">${esc(e.url)}</td>
          <td><span class="badge badge-automation">${esc(e.authProfile||'—')}</span></td>
          <td style="font-size:11px;color:var(--t-muted)">${(e.headers||[]).map(h=>h.key+': '+h.value).join(', ')||'—'}</td>
          <td><button class="btn btn-ghost btn-xs" onclick="showEnvModal(${i})">Edit</button>
              <button class="btn btn-ghost btn-xs" style="color:var(--c-fail)" onclick="deleteEnv(${i})">✕</button></td>
        </tr>`).join('')}
      </tbody></table>
    </div>` : `<div class="empty-state"><div class="empty-icon">🌐</div><div class="empty-title">No environments</div></div>`}
  </div>`;
}

function addEnvHdrRow(key='', val='') {
  const tbody = document.getElementById('envHdrBody');
  const tr = document.createElement('tr');
  tr.innerHTML = `
    <td><input class="form-input form-input-xs" style="font-size:12px" value="${esc(key)}" placeholder="Header-Name"></td>
    <td><input class="form-input form-input-xs" style="font-size:12px" value="${esc(val)}" placeholder="value"></td>
    <td><button class="btn btn-ghost btn-xs" style="color:var(--c-fail)" onclick="this.closest('tr').remove()">✕</button></td>`;
  tbody.appendChild(tr);
}

function showEnvModal(idx = -1) {
  _editEnvIdx = idx;
  const suite = state.suites.find(s => s.id === state.activeSuiteId);
  document.getElementById('envModalTitle').textContent = idx >= 0 ? 'Edit Environment' : 'Add Environment';
  document.getElementById('envHdrBody').innerHTML = '';
  if (idx >= 0 && suite) {
    const e = (suite.environments||[])[idx];
    document.getElementById('env-name').value = e.name||'';
    document.getElementById('env-url').value = e.url||'';
    document.getElementById('env-auth').value = e.authProfile||'';
    (e.headers||[]).forEach(h => addEnvHdrRow(h.key, h.value));
  } else {
    ['env-name','env-url','env-auth'].forEach(id => document.getElementById(id).value = '');
  }
  openModal('envModal');
}

async function saveEnv() {
  const name = document.getElementById('env-name')?.value?.trim();
  if (!name) { toast('Name required','error'); return; }
  const headers = [];
  document.querySelectorAll('#envHdrBody tr').forEach(tr => {
    const inputs = tr.querySelectorAll('input');
    const k = inputs[0]?.value?.trim(), v = inputs[1]?.value?.trim();
    if (k) headers.push({key:k, value:v||''});
  });
  const env = { name, url: document.getElementById('env-url')?.value?.trim(),
                authProfile: document.getElementById('env-auth')?.value?.trim(), headers };
  const suite = state.suites.find(s => s.id === state.activeSuiteId);
  const envs = [...(suite?.environments||[])];
  if (_editEnvIdx >= 0) envs[_editEnvIdx] = env; else envs.push(env);
  const res = await api('PUT', `/suites/${state.activeSuiteId}/environment`, envs);
  if (res.success) {
    const idx = state.suites.findIndex(s => s.id === state.activeSuiteId);
    if (idx >= 0) state.suites[idx].environments = envs;
    closeModal('envModal');
    toast('Environment saved');
    renderEnvTab(state.suites[idx]);
  } else toast(res.message, 'error');
}

async function deleteEnv(idx) {
  if (!confirm('Delete this environment?')) return;
  const suite = state.suites.find(s => s.id === state.activeSuiteId);
  const envs = (suite?.environments||[]).filter((_,i)=>i!==idx);
  const res = await api('PUT', `/suites/${state.activeSuiteId}/environment`, envs);
  if (res.success) {
    const i = state.suites.findIndex(s => s.id === state.activeSuiteId);
    if (i>=0) state.suites[i].environments = envs;
    toast('Environment deleted');
    renderEnvTab(state.suites[i]);
  } else toast(res.message, 'error');
}

// ── Auth Profiles Tab ─────────────────────────────────────────────────────────
let _editAuthIdx = -1;

function renderAuthTab(suite) {
  const profiles = suite.authProfiles || [];
  const el = document.getElementById('cfg-auth');
  if (!el) return;
  el.innerHTML = `
  <div class="section-header">
    <div class="section-title">Auth Profiles</div>
    <button class="btn btn-primary btn-sm" onclick="showAuthModal()">+ Add</button>
  </div>
  ${profiles.length ? `
  <div class="table-wrap">
    <table><thead><tr><th>Name</th><th>Type</th><th>Token URL</th><th></th></tr></thead>
    <tbody>${profiles.map((p,i) => `
      <tr>
        <td style="font-weight:600">${esc(p.name)}</td>
        <td><span class="badge badge-comparison">${esc(p.type||p.authType||'NONE')}</span></td>
        <td style="font-size:12px;color:var(--t-muted)">${esc(p.tokenUrl||'—')}</td>
        <td><button class="btn btn-ghost btn-xs" onclick="showAuthModal(${i})">Edit</button>
            <button class="btn btn-ghost btn-xs" style="color:var(--c-fail)" onclick="deleteAuth(${i})">✕</button></td>
      </tr>`).join('')}
    </tbody></table>
  </div>` : `<div class="empty-state"><div class="empty-icon">🔑</div><div class="empty-title">No auth profiles</div></div>`}`;
}

function onAuthTypeChange() {
  const t = document.getElementById('ap-type')?.value;
  const vis = (id, show) => { const el = document.getElementById(id); if(el) el.style.display = show ? '' : 'none'; };
  vis('ap-token-wrap', t === 'BEARER');
  vis('ap-url-wrap',   t === 'BASIC' || t === 'CLIENT_CREDENTIALS');
  vis('ap-user-wrap',  t === 'BASIC');
  vis('ap-pass-wrap',  t === 'BASIC');
  vis('ap-cid-wrap',   t === 'CLIENT_CREDENTIALS');
  vis('ap-csec-wrap',  t === 'CLIENT_CREDENTIALS');
  vis('ap-scope-wrap', t === 'CLIENT_CREDENTIALS');
}

function showAuthModal(idx = -1) {
  _editAuthIdx = idx;
  const suite = state.suites.find(s => s.id === state.activeSuiteId);
  document.getElementById('authModalTitle').textContent = idx >= 0 ? 'Edit Auth Profile' : 'Add Auth Profile';
  const g = id => document.getElementById(id);
  if (idx >= 0 && suite) {
    const p = (suite.authProfiles||[])[idx];
    g('ap-name').value = p.name||''; g('ap-type').value = p.type||p.authType||'NONE';
    g('ap-tokenUrl').value = p.tokenUrl||''; g('ap-token').value = p.token||'';
    g('ap-username').value = p.username||''; g('ap-password').value = p.password||'';
    g('ap-clientId').value = p.clientId||''; g('ap-clientSecret').value = p.clientSecret||'';
    g('ap-scope').value = p.scope||'';
  } else {
    ['ap-name','ap-tokenUrl','ap-token','ap-username','ap-password','ap-clientId','ap-clientSecret','ap-scope'].forEach(id => { if(g(id)) g(id).value=''; });
    g('ap-type').value = 'NONE';
  }
  onAuthTypeChange();
  openModal('authModal');
}

async function saveAuth() {
  const g = id => document.getElementById(id)?.value?.trim();
  const profile = { name: g('ap-name'), type: g('ap-type'), tokenUrl: g('ap-tokenUrl'),
                    token: g('ap-token'), username: g('ap-username'), password: g('ap-password'),
                    clientId: g('ap-clientId'), clientSecret: g('ap-clientSecret'), scope: g('ap-scope') };
  if (!profile.name) { toast('Name required','error'); return; }
  const suite = state.suites.find(s => s.id === state.activeSuiteId);
  const profiles = [...(suite?.authProfiles||[])];
  if (_editAuthIdx >= 0) profiles[_editAuthIdx] = profile; else profiles.push(profile);
  const res = await api('PUT', `/suites/${state.activeSuiteId}/auth-profiles`, profiles);
  if (res.success) {
    const idx = state.suites.findIndex(s => s.id === state.activeSuiteId);
    if (idx>=0) state.suites[idx].authProfiles = profiles;
    closeModal('authModal');
    toast('Auth profile saved');
    renderAuthTab(state.suites[idx]);
  } else toast(res.message, 'error');
}

async function deleteAuth(idx) {
  if (!confirm('Delete this auth profile?')) return;
  const suite = state.suites.find(s => s.id === state.activeSuiteId);
  const profiles = (suite?.authProfiles||[]).filter((_,i)=>i!==idx);
  const res = await api('PUT', `/suites/${state.activeSuiteId}/auth-profiles`, profiles);
  if (res.success) {
    const i = state.suites.findIndex(s => s.id === state.activeSuiteId);
    if (i>=0) state.suites[i].authProfiles = profiles;
    toast('Auth profile deleted');
    renderAuthTab(state.suites[i]);
  } else toast(res.message, 'error');
}
