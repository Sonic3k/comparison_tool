// ─── Settings ─────────────────────────────────────────────────────────────────
async function saveSettings() {
  const res = await api('PUT', '/suite/settings', {
    suiteName: g('s-name'), description: g('s-desc'), version: g('s-version'),
    createdBy: g('s-createdBy'), createdDate: g('s-createdDate'),
    lastUpdatedBy: g('s-updatedBy'), lastUpdatedDate: g('s-updatedDate'),
    executionConfig: {
      mode: g('s-mode'), timeout: +g('s-timeout'), parallelLimit: +g('s-parallelLimit'),
      delayBetweenRequests: +g('s-delay'), retries: +g('s-retries'),
      sourceEnvironment: g('s-sourceEnv'), targetEnvironment: g('s-targetEnv')
    },
    comparisonConfig: {
      ignoreFieldsRaw: g('s-ignoreFields'),
      caseSensitive: g('s-caseSensitive') === 'true',
      ignoreArrayOrder: g('s-ignoreArrayOrder') === 'true',
      numericTolerance: +g('s-tolerance')
    }
  });
  if (res.success) { suite.settings = res.data; toast('Settings saved'); }
  else toast(res.message, true);
}

// ─── Environments ─────────────────────────────────────────────────────────────
let _editEnvIdx = -1;

function headersToStr(headers) {
  if (!headers || !headers.length) return '—';
  return headers.map(p => `${p.key}: ${p.value}`).join(', ');
}

function headersFromRows() {
  const list = [];
  document.querySelectorAll('#envHeadersBody tr').forEach(r => {
    const k = r.querySelector('.hdr-key').value.trim();
    const v = r.querySelector('.hdr-val').value.trim();
    if (k) list.push({ key: k, value: v });
  });
  return list;
}

function addEnvHeaderRow(key = '', value = '') {
  const tbody = document.getElementById('envHeadersBody');
  const tr = document.createElement('tr');
  tr.innerHTML = `
    <td><input class="hdr-key" value="${esc(key)}" placeholder="Header-Name" style="width:100%"/></td>
    <td><input class="hdr-val" value="${esc(value)}" placeholder="value" style="width:100%"/></td>
    <td><button class="btn btn-outline btn-xs" style="color:var(--red)" onclick="this.closest('tr').remove()">✕</button></td>`;
  tbody.appendChild(tr);
}

function renderEnvTable(envs) {
  document.getElementById('envTable').innerHTML = envs.length
    ? envs.map((e, i) => `<tr>
        <td><strong>${esc(e.name)}</strong></td>
        <td style="color:#6b7280;font-size:12px">${esc(e.url || '')}</td>
        <td><span class="bs s-pending">${esc(e.authProfile || '—')}</span></td>
        <td style="color:#6b7280;font-size:12px">${esc(headersToStr(e.headers))}</td>
        <td>
          <button class="btn btn-outline btn-xs" onclick="editEnv(${i})">Edit</button>
          <button class="btn btn-outline btn-xs" style="color:var(--red);margin-left:4px" onclick="deleteEnv(${i})">✕</button>
        </td>
      </tr>`).join('')
    : '<tr><td colspan="5" style="text-align:center;color:#9ca3af;padding:20px">No environments yet. Click "+ Add Environment".</td></tr>';
}

function populateEnvSelects(envs) {
  const names = envs.map(e => e.name);
  ['s-sourceEnv', 's-targetEnv'].forEach(id => {
    const el = document.getElementById(id); if (!el) return;
    const cur = el.value;
    el.innerHTML = '<option value="">— select —</option>' +
      names.map(n => `<option value="${esc(n)}">${esc(n)}</option>`).join('');
    el.value = names.includes(cur) ? cur : '';
  });
}

function showEnvModal(idx = -1) {
  _editEnvIdx = idx;
  document.getElementById('envModalTitle').textContent = idx >= 0 ? 'Edit Environment' : 'Add Environment';
  document.getElementById('envHeadersBody').innerHTML = '';
  if (idx >= 0) {
    const e = suite.environments[idx];
    sv('env-name', e.name); sv('env-url', e.url); sv('env-auth', e.authProfile || '');
    (e.headers || []).forEach(p => addEnvHeaderRow(p.key, p.value));
  } else {
    ['env-name', 'env-url', 'env-auth'].forEach(id => sv(id, ''));
  }
  openModal('envModal');
}

function editEnv(i) { showEnvModal(i); }

async function deleteEnv(i) {
  if (!confirm('Delete this environment?')) return;
  suite.environments.splice(i, 1);
  const res = await api('PUT', '/suite/environment', suite.environments);
  if (res.success) {
    renderEnvTable(suite.environments);
    populateEnvSelects(suite.environments);
    toast('Environment deleted');
  }
}

async function saveEnvironment() {
  const env = { name: g('env-name'), url: g('env-url'), authProfile: g('env-auth'), headers: headersFromRows() };
  if (!env.name) { alert('Environment name required'); return; }
  suite.environments = suite.environments || [];
  if (_editEnvIdx >= 0) suite.environments[_editEnvIdx] = env;
  else suite.environments.push(env);
  const res = await api('PUT', '/suite/environment', suite.environments);
  if (res.success) {
    renderEnvTable(suite.environments);
    populateEnvSelects(suite.environments);
    closeModal('envModal');
    toast('Environment saved');
  } else toast(res.message, true);
}

// ─── Auth Profiles ────────────────────────────────────────────────────────────
let _editAuthIdx = -1;

function renderAuthProfiles(profiles) {
  document.getElementById('authProfilesTable').innerHTML = profiles.length
    ? profiles.map((p, i) => `<tr>
        <td><strong>${esc(p.name)}</strong></td>
        <td><span class="bs s-pending">${esc(p.type || p.authType || 'NONE')}</span></td>
        <td style="color:#6b7280">${esc(p.description || '')}</td>
        <td class="mono" style="color:#6b7280">${esc(p.tokenUrl || '')}</td>
        <td>
          <button class="btn btn-outline btn-xs" onclick="showAuthModal(${i})">Edit</button>
          <button class="btn btn-outline btn-xs" style="color:var(--red);margin-left:4px" onclick="deleteAuth(${i})">Delete</button>
        </td>
      </tr>`).join('')
    : '<tr><td colspan="5" style="text-align:center;color:#9ca3af;padding:20px">No auth profiles yet.</td></tr>';
}

function showAuthModal(idx = -1) {
  _editAuthIdx = idx;
  document.getElementById('authModalTitle').textContent = idx >= 0 ? 'Edit Auth Profile' : 'Add Auth Profile';
  if (idx >= 0) {
    const p = suite.authProfiles[idx];
    sv('ap-name', p.name); sv('ap-type', p.type || p.authType || 'NONE');
    sv('ap-desc', p.description); sv('ap-tokenUrl', p.tokenUrl);
    sv('ap-username', p.username); sv('ap-password', p.password);
    sv('ap-clientId', p.clientId); sv('ap-clientSecret', p.clientSecret);
    sv('ap-scope', p.scope); sv('ap-token', p.token);
  } else {
    ['ap-name','ap-desc','ap-tokenUrl','ap-username','ap-password',
     'ap-clientId','ap-clientSecret','ap-scope','ap-token'].forEach(id => sv(id, ''));
    sv('ap-type', 'NONE');
  }
  onAuthTypeChange();
  openModal('authModal');
}

async function deleteAuth(i) {
  if (!confirm('Delete this auth profile?')) return;
  suite.authProfiles.splice(i, 1);
  const res = await api('PUT', '/suite/auth-profiles', suite.authProfiles);
  if (res.success) renderAuthProfiles(suite.authProfiles);
}

async function saveAuthProfile() {
  const profile = {
    name: g('ap-name'), type: g('ap-type'), description: g('ap-desc'),
    tokenUrl: g('ap-tokenUrl'), username: g('ap-username'), password: g('ap-password'),
    clientId: g('ap-clientId'), clientSecret: g('ap-clientSecret'),
    scope: g('ap-scope'), token: g('ap-token')
  };
  if (!profile.name) { alert('Profile name required'); return; }
  if (_editAuthIdx >= 0) suite.authProfiles[_editAuthIdx] = profile;
  else suite.authProfiles.push(profile);
  const res = await api('PUT', '/suite/auth-profiles', suite.authProfiles);
  if (res.success) {
    renderAuthProfiles(suite.authProfiles);
    closeModal('authModal');
    toast('Auth profile saved');
  }
}

function onAuthTypeChange() {
  const t = g('ap-type');
  vis('ap-tokenUrl-wrap', t === 'BASIC' || t === 'CLIENT_CREDENTIALS');
  vis('ap-user-wrap',  t === 'BASIC');
  vis('ap-pass-wrap',  t === 'BASIC');
  vis('ap-cid-wrap',   t === 'CLIENT_CREDENTIALS');
  vis('ap-csec-wrap',  t === 'CLIENT_CREDENTIALS');
  vis('ap-scope-wrap', t === 'CLIENT_CREDENTIALS');
  vis('ap-token-wrap', t === 'BEARER');
}
