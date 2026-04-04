reg('config', {
  render(el) {
    const suite = activeSuite();
    if (!suite) { el.innerHTML = `<div class="page">${noSuite()}</div>`; return; }

    el.innerHTML = `
    <div class="page">
      <div class="page-hd">
        <div><div class="page-title">Config</div><div class="page-sub">${esc(suite.settings?.suiteName)}</div></div>
      </div>
      <div class="tabs">
        <button class="tab-btn active" onclick="showTab('cfg-settings',this)">Settings</button>
        <button class="tab-btn" onclick="showTab('cfg-env',this)">Environments</button>
        <button class="tab-btn" onclick="showTab('cfg-auth',this)">Auth Profiles</button>
      </div>
      <div id="cfg-settings" class="tab-pane active"></div>
      <div id="cfg-env" class="tab-pane"></div>
      <div id="cfg-auth" class="tab-pane"></div>
    </div>

    <!-- Env Modal -->
    <div class="overlay hidden" id="envModal">
      <div class="modal">
        <div class="modal-hd"><span class="modal-title" id="envModalTitle">Environment</span><span class="modal-x" onclick="closeModal('envModal')">✕</span></div>
        <div class="modal-body">
          <div class="form-grid">
            <div class="form-group"><label class="form-label">Name *</label><input class="input" id="env-name" placeholder="source"></div>
            <div class="form-group"><label class="form-label">Auth Profile</label><input class="input" id="env-auth" placeholder="Legacy-OAuth2"></div>
            <div class="form-group form-full"><label class="form-label">Base URL *</label><input class="input" id="env-url" placeholder="https://api.example.com/legacy/api"></div>
          </div>
          <div style="margin-top:14px">
            <label class="form-label" style="display:block;margin-bottom:8px">Default Headers</label>
            <table style="width:100%;font-size:12px"><tbody id="envHdrBody"></tbody></table>
            <button class="btn btn-ghost btn-xs" style="margin-top:8px" onclick="addEnvHdr()">+ Header</button>
          </div>
        </div>
        <div class="modal-ft">
          <button class="btn btn-ghost" onclick="closeModal('envModal')">Cancel</button>
          <button class="btn btn-primary" onclick="saveEnv()">Save</button>
        </div>
      </div>
    </div>

    <!-- Auth Modal -->
    <div class="overlay hidden" id="authModal">
      <div class="modal">
        <div class="modal-hd"><span class="modal-title" id="authModalTitle">Auth Profile</span><span class="modal-x" onclick="closeModal('authModal')">✕</span></div>
        <div class="modal-body">
          <div class="form-grid">
            <div class="form-group"><label class="form-label">Name *</label><input class="input" id="ap-name"></div>
            <div class="form-group"><label class="form-label">Type</label>
              <select class="input" id="ap-type" onchange="onAuthType()">
                <option value="NONE">None</option><option value="BEARER">Bearer Token</option>
                <option value="BASIC">Basic Auth</option><option value="CLIENT_CREDENTIALS">OAuth2 Client Credentials</option>
              </select>
            </div>
            <div class="form-group form-full" id="ap-token-w" style="display:none"><label class="form-label">Bearer Token</label><input class="input" id="ap-token"></div>
            <div class="form-group form-full" id="ap-url-w" style="display:none"><label class="form-label">Token URL</label><input class="input" id="ap-tokenUrl"></div>
            <div class="form-group" id="ap-user-w" style="display:none"><label class="form-label">Username</label><input class="input" id="ap-username"></div>
            <div class="form-group" id="ap-pass-w" style="display:none"><label class="form-label">Password</label><input class="input" id="ap-password" type="password"></div>
            <div class="form-group" id="ap-cid-w" style="display:none"><label class="form-label">Client ID</label><input class="input" id="ap-clientId"></div>
            <div class="form-group" id="ap-csec-w" style="display:none"><label class="form-label">Client Secret</label><input class="input" id="ap-clientSecret" type="password"></div>
            <div class="form-group" id="ap-scope-w" style="display:none"><label class="form-label">Scope</label><input class="input" id="ap-scope"></div>
          </div>
        </div>
        <div class="modal-ft">
          <button class="btn btn-ghost" onclick="closeModal('authModal')">Cancel</button>
          <button class="btn btn-primary" onclick="saveAuth()">Save</button>
        </div>
      </div>
    </div>`;

    renderSettings(suite);
    renderEnvTab(suite);
    renderAuthTab(suite);
  },
  destroy() {}
});

function showTab(name, btn) {
  document.querySelectorAll('.tab-pane').forEach(el => el.classList.remove('active'));
  document.querySelectorAll('.tab-btn').forEach(el => el.classList.remove('active'));
  document.getElementById(name)?.classList.add('active');
  btn.classList.add('active');
}

// ── Settings ──────────────────────────────────────────────────────────────────
function renderSettings(suite) {
  const s = suite.settings || {};
  const ec = s.executionConfig || {};
  const cc = s.comparisonConfig || {};
  const el = document.getElementById('cfg-settings');
  if (!el) return;

  const vmOpts = [
    ['','— per TC (run all) —'],['comparison','Comparison only'],
    ['automation','Automation only'],['both','Both only']
  ].map(([v,l]) => `<option value="${v}" ${(ec.verificationMode||'')=== v?'selected':''}>${l}</option>`).join('');

  el.innerHTML = `
  <div class="card" style="margin-bottom:14px">
    <div style="font-size:13px;font-weight:600;margin-bottom:14px">Suite Info</div>
    <div class="form-grid">
      <div class="form-group"><label class="form-label">Suite Name</label><input class="input" id="s-name" value="${esc(s.suiteName||'')}"></div>
      <div class="form-group"><label class="form-label">Version</label><input class="input" id="s-version" value="${esc(s.version||'')}"></div>
      <div class="form-group form-full"><label class="form-label">Description</label><input class="input" id="s-desc" value="${esc(s.description||'')}"></div>
      <div class="form-group"><label class="form-label">Created By</label><input class="input" id="s-author" value="${esc(s.createdBy||'')}"></div>
      <div class="form-group"><label class="form-label">Last Updated By</label><input class="input" id="s-updatedBy" value="${esc(s.lastUpdatedBy||'')}"></div>
    </div>
  </div>

  <div class="card" style="margin-bottom:14px">
    <div style="font-size:13px;font-weight:600;margin-bottom:14px">Execution</div>
    <div class="form-grid">
      <div class="form-group"><label class="form-label">Source Environment</label><input class="input" id="s-srcEnv" value="${esc(ec.sourceEnvironment||'')}" placeholder="source"></div>
      <div class="form-group"><label class="form-label">Target Environment</label><input class="input" id="s-tgtEnv" value="${esc(ec.targetEnvironment||'')}" placeholder="target"></div>
      <div class="form-group"><label class="form-label">Execution Mode</label>
        <select class="input" id="s-execMode">
          <option value="PARALLEL" ${ec.mode==='PARALLEL'?'selected':''}>Parallel</option>
          <option value="SOURCE_FIRST" ${ec.mode==='SOURCE_FIRST'?'selected':''}>Source First</option>
        </select>
      </div>
      <div class="form-group"><label class="form-label">Verification Mode <span class="form-hint">— suite filter</span></label>
        <select class="input" id="s-vm">${vmOpts}</select>
      </div>
      <div class="form-group"><label class="form-label">Timeout (s)</label><input class="input" id="s-timeout" type="number" value="${ec.timeout||30}"></div>
      <div class="form-group"><label class="form-label">Parallel Limit</label><input class="input" id="s-parallelLimit" type="number" value="${ec.parallelLimit||10}"></div>
      <div class="form-group"><label class="form-label">Delay Between Requests (ms)</label><input class="input" id="s-delay" type="number" value="${ec.delayBetweenRequests||0}"></div>
      <div class="form-group"><label class="form-label">Retries</label><input class="input" id="s-retries" type="number" value="${ec.retries||2}"></div>
    </div>
  </div>

  <div class="card" style="margin-bottom:14px">
    <div style="font-size:13px;font-weight:600;margin-bottom:14px">Comparison Rules</div>
    <div class="form-grid">
      <div class="form-group form-full"><label class="form-label">Ignore Fields <span class="form-hint">comma-separated</span></label>
        <input class="input" id="s-ignoreFields" value="${esc(cc.ignoreFieldsRaw||'')}" placeholder="timestamp,requestId">
      </div>
      <div class="form-group"><label class="form-label">Ignore Array Order</label>
        <select class="input" id="s-ignoreArr"><option value="false" ${!cc.ignoreArrayOrder?'selected':''}>false</option><option value="true" ${cc.ignoreArrayOrder?'selected':''}>true</option></select>
      </div>
      <div class="form-group"><label class="form-label">Compare Error Responses (5xx)</label>
        <select class="input" id="s-compareErr"><option value="false" ${!cc.compareErrorResponses?'selected':''}>false — treat 5xx as error</option><option value="true" ${cc.compareErrorResponses?'selected':''}>true — compare all</option></select>
      </div>
      <div class="form-group"><label class="form-label">Case Sensitive</label>
        <select class="input" id="s-caseSens"><option value="true" ${cc.caseSensitive!==false?'selected':''}>true</option><option value="false" ${cc.caseSensitive===false?'selected':''}>false</option></select>
      </div>
      <div class="form-group"><label class="form-label">Numeric Tolerance</label>
        <input class="input" id="s-tolerance" type="number" step="0.0001" value="${cc.numericTolerance||0.001}">
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
  const r = await api('PUT', `/suites/${S.active}/settings`, {
    suiteName: g('s-name'), description: g('s-desc'), version: g('s-version'),
    createdBy: g('s-author'), lastUpdatedBy: g('s-updatedBy'),
    executionConfig: {
      mode: g('s-execMode'), verificationMode: vm||null,
      sourceEnvironment: g('s-srcEnv'), targetEnvironment: g('s-tgtEnv'),
      timeout: +g('s-timeout'), parallelLimit: +g('s-parallelLimit'),
      delayBetweenRequests: +g('s-delay'), retries: +g('s-retries'),
    },
    comparisonConfig: {
      ignoreFieldsRaw: g('s-ignoreFields'), caseSensitive: g('s-caseSens')==='true',
      ignoreArrayOrder: g('s-ignoreArr')==='true', compareErrorResponses: g('s-compareErr')==='true',
      numericTolerance: +g('s-tolerance'),
    }
  });
  if (r.success) { await refreshSuite(S.active); toast('Settings saved'); }
  else toast(r.message,'err');
}

// ── Environments ──────────────────────────────────────────────────────────────
let _envIdx = -1;

function renderEnvTab(suite) {
  const envs = suite.environments || [];
  const el = document.getElementById('cfg-env');
  if (!el) return;
  el.innerHTML = `
  <div class="sec-hd"><span class="sec-title">Environments</span><button class="btn btn-primary btn-sm" onclick="showEnvModal()">+ Add</button></div>
  ${envs.length ? `<div class="tbl-wrap"><table><thead><tr><th>Name</th><th>URL</th><th>Auth Profile</th><th>Headers</th><th></th></tr></thead><tbody>
    ${envs.map((e,i)=>`<tr>
      <td style="font-weight:600">${esc(e.name)}</td>
      <td style="font-size:11px;color:var(--t2)">${esc(e.url)}</td>
      <td><span class="badge b-auto">${esc(e.authProfile||'—')}</span></td>
      <td style="font-size:11px;color:var(--t3)">${(e.headers||[]).map(h=>h.key+': '+h.value).join(', ')||'—'}</td>
      <td><button class="btn btn-ghost btn-xs" onclick="showEnvModal(${i})">Edit</button>
          <button class="btn btn-ghost btn-xs" style="color:var(--fail)" onclick="deleteEnv(${i})">✕</button></td>
    </tr>`).join('')}
  </tbody></table></div>`
  : `<div class="empty"><div class="empty-icon" style="font-size:32px">🌐</div><div class="empty-title">No environments</div></div>`}`;
}

function addEnvHdr(k='',v='') {
  const tr = document.createElement('tr');
  tr.innerHTML = `<td><input class="input" style="font-size:11px" value="${esc(k)}" placeholder="Header-Name"></td>
    <td><input class="input" style="font-size:11px" value="${esc(v)}" placeholder="value"></td>
    <td><button class="btn btn-ghost btn-xs" style="color:var(--fail)" onclick="this.closest('tr').remove()">✕</button></td>`;
  document.getElementById('envHdrBody').appendChild(tr);
}

function showEnvModal(idx=-1) {
  _envIdx = idx;
  const suite = activeSuite();
  document.getElementById('envModalTitle').textContent = idx>=0?'Edit Environment':'Add Environment';
  document.getElementById('envHdrBody').innerHTML='';
  if (idx>=0 && suite) {
    const e = (suite.environments||[])[idx];
    document.getElementById('env-name').value=e.name||'';
    document.getElementById('env-url').value=e.url||'';
    document.getElementById('env-auth').value=e.authProfile||'';
    (e.headers||[]).forEach(h=>addEnvHdr(h.key,h.value));
  } else {
    ['env-name','env-url','env-auth'].forEach(id=>{document.getElementById(id).value='';});
  }
  openModal('envModal');
}

async function saveEnv() {
  const name = document.getElementById('env-name')?.value?.trim();
  if (!name) { toast('Name required','err'); return; }
  const headers=[];
  document.querySelectorAll('#envHdrBody tr').forEach(tr=>{
    const inp=tr.querySelectorAll('input'); const k=inp[0]?.value?.trim(),v=inp[1]?.value?.trim();
    if(k) headers.push({key:k,value:v||''});
  });
  const env={name,url:document.getElementById('env-url')?.value?.trim(),authProfile:document.getElementById('env-auth')?.value?.trim(),headers};
  const suite=activeSuite(); const envs=[...(suite?.environments||[])];
  if(_envIdx>=0) envs[_envIdx]=env; else envs.push(env);
  const r=await api('PUT',`/suites/${S.active}/environment`,envs);
  if(r.success){await refreshSuite(S.active);closeModal('envModal');toast('Environment saved');renderEnvTab(activeSuite());}
  else toast(r.message,'err');
}

async function deleteEnv(idx) {
  if(!confirm('Delete?')) return;
  const suite=activeSuite(); const envs=(suite?.environments||[]).filter((_,i)=>i!==idx);
  const r=await api('PUT',`/suites/${S.active}/environment`,envs);
  if(r.success){await refreshSuite(S.active);toast('Deleted');renderEnvTab(activeSuite());}
  else toast(r.message,'err');
}

// ── Auth ──────────────────────────────────────────────────────────────────────
let _authIdx=-1;

function renderAuthTab(suite) {
  const profiles=suite.authProfiles||[];
  const el=document.getElementById('cfg-auth');
  if(!el) return;
  el.innerHTML=`
  <div class="sec-hd"><span class="sec-title">Auth Profiles</span><button class="btn btn-primary btn-sm" onclick="showAuthModal()">+ Add</button></div>
  ${profiles.length?`<div class="tbl-wrap"><table><thead><tr><th>Name</th><th>Type</th><th>Token URL</th><th></th></tr></thead><tbody>
    ${profiles.map((p,i)=>`<tr>
      <td style="font-weight:600">${esc(p.name)}</td>
      <td><span class="badge b-cmp">${esc(p.type||p.authType||'NONE')}</span></td>
      <td style="font-size:11px;color:var(--t3)">${esc(p.tokenUrl||'—')}</td>
      <td><button class="btn btn-ghost btn-xs" onclick="showAuthModal(${i})">Edit</button>
          <button class="btn btn-ghost btn-xs" style="color:var(--fail)" onclick="deleteAuth(${i})">✕</button></td>
    </tr>`).join('')}
  </tbody></table></div>`
  :`<div class="empty"><div class="empty-icon" style="font-size:32px">🔑</div><div class="empty-title">No auth profiles</div></div>`}`;
}

function onAuthType() {
  const t=document.getElementById('ap-type')?.value;
  const vis=(id,show)=>{const el=document.getElementById(id);if(el)el.style.display=show?'':'none';};
  vis('ap-token-w',t==='BEARER'); vis('ap-url-w',t==='BASIC'||t==='CLIENT_CREDENTIALS');
  vis('ap-user-w',t==='BASIC'); vis('ap-pass-w',t==='BASIC');
  vis('ap-cid-w',t==='CLIENT_CREDENTIALS'); vis('ap-csec-w',t==='CLIENT_CREDENTIALS');
  vis('ap-scope-w',t==='CLIENT_CREDENTIALS');
}

function showAuthModal(idx=-1) {
  _authIdx=idx;
  const suite=activeSuite();
  document.getElementById('authModalTitle').textContent=idx>=0?'Edit Auth Profile':'Add Auth Profile';
  const g=id=>document.getElementById(id);
  if(idx>=0&&suite){
    const p=(suite.authProfiles||[])[idx];
    g('ap-name').value=p.name||''; g('ap-type').value=p.type||p.authType||'NONE';
    g('ap-tokenUrl').value=p.tokenUrl||''; g('ap-token').value=p.token||'';
    g('ap-username').value=p.username||''; g('ap-password').value=p.password||'';
    g('ap-clientId').value=p.clientId||''; g('ap-clientSecret').value=p.clientSecret||'';
    g('ap-scope').value=p.scope||'';
  } else {
    ['ap-name','ap-tokenUrl','ap-token','ap-username','ap-password','ap-clientId','ap-clientSecret','ap-scope'].forEach(id=>{if(g(id))g(id).value='';});
    g('ap-type').value='NONE';
  }
  onAuthType(); openModal('authModal');
}

async function saveAuth() {
  const g=id=>document.getElementById(id)?.value?.trim();
  const profile={name:g('ap-name'),type:g('ap-type'),tokenUrl:g('ap-tokenUrl'),token:g('ap-token'),username:g('ap-username'),password:g('ap-password'),clientId:g('ap-clientId'),clientSecret:g('ap-clientSecret'),scope:g('ap-scope')};
  if(!profile.name){toast('Name required','err');return;}
  const suite=activeSuite(); const profiles=[...(suite?.authProfiles||[])];
  if(_authIdx>=0) profiles[_authIdx]=profile; else profiles.push(profile);
  const r=await api('PUT',`/suites/${S.active}/auth-profiles`,profiles);
  if(r.success){await refreshSuite(S.active);closeModal('authModal');toast('Auth profile saved');renderAuthTab(activeSuite());}
  else toast(r.message,'err');
}

async function deleteAuth(idx) {
  if(!confirm('Delete?')) return;
  const suite=activeSuite(); const profiles=(suite?.authProfiles||[]).filter((_,i)=>i!==idx);
  const r=await api('PUT',`/suites/${S.active}/auth-profiles`,profiles);
  if(r.success){await refreshSuite(S.active);toast('Deleted');renderAuthTab(activeSuite());}
  else toast(r.message,'err');
}

function noSuite() { return `<div class="empty"><div class="empty-icon">⚙️</div><div class="empty-title">No suite selected</div></div>`; }
