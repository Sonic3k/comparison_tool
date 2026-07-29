// ─── Reorder mode ─────────────────────────────────────────────────────────────
// Reordering is a mode you enter on purpose, not something you can trip over
// while reading results. Inside it every list is one row per item — no result
// columns, no run/edit/delete buttons — and there are three ways to move a row:
//
//   ↑ ↓      one slot at a time. What most edits actually need.
//   ⇱ lift   then click the gap you want it in. No dragging across 140 rows,
//            no holding the pointer at the edge waiting for autoscroll.
//   drag     for short hops. The row lifts out under the cursor and the gap it
//            will drop into stays open behind it.
//
// Nested lists are separate sortables, so a request physically cannot leave its
// test case and a group cannot leave its execution band — no silent clamping.
// Nothing is saved until Done; Cancel and Esc throw the session away.

// ─── Sortable list ────────────────────────────────────────────────────────────

function roSortable(list, onChange) {
  let st = null, scroll = null;

  list.addEventListener('pointerdown', e => {
    if (e.button !== 0) return;
    if (e.target.closest('[data-no-drag]')) return;
    const item = e.target.closest('.ro-item');
    if (!item || item.parentNode !== list) return;   // a nested list handles its own
    e.preventDefault();
    e.stopPropagation();
    clearLift();
    start(item, e);
  });

  function start(item, e) {
    const r = item.getBoundingClientRect();
    const clone = item.cloneNode(true);
    clone.classList.add('ro-float');
    Object.assign(clone.style, {
      position: 'fixed', left: r.left + 'px', top: r.top + 'px',
      width: r.width + 'px', margin: '0', pointerEvents: 'none', zIndex: '9999'
    });
    document.body.appendChild(clone);
    item.classList.add('ro-hole');
    document.body.classList.add('ro-dragging');

    st = { item, clone, dx: e.clientX - r.left, dy: e.clientY - r.top, moved: false };
    window.addEventListener('pointermove', move);
    window.addEventListener('pointerup', end, { once: true });
    window.addEventListener('pointercancel', end, { once: true });
  }

  function move(e) {
    if (!st) return;
    st.clone.style.left = (e.clientX - st.dx) + 'px';
    st.clone.style.top  = (e.clientY - st.dy) + 'px';
    edgeScroll(e.clientY);

    const kids = [...list.children].filter(c => c.classList.contains('ro-item'));
    const cur  = kids.indexOf(st.item);
    let want   = null;

    for (let i = 0; i < kids.length; i++) {
      if (kids[i] === st.item) continue;
      const r = kids[i].getBoundingClientRect();
      if (e.clientY >= r.top && e.clientY <= r.bottom) {
        want = e.clientY < r.top + r.height / 2 ? i : i + 1;
        break;
      }
    }
    if (want === null) {                       // past either end of the list
      const first = kids[0].getBoundingClientRect();
      const last  = kids[kids.length - 1].getBoundingClientRect();
      if (e.clientY < first.top)        want = 0;
      else if (e.clientY > last.bottom) want = kids.length;
      else return;
    }
    if (want > cur) want--;
    if (want === cur) return;

    const rest = kids.filter(k => k !== st.item);
    list.insertBefore(st.item, rest[want] || null);
    st.moved = true;
  }

  function end() {
    window.removeEventListener('pointermove', move);
    stopScroll();
    if (!st) return;
    const { item, clone, moved } = st;
    st = null;

    // Settle the floating copy back into the gap instead of blinking out.
    const r = item.getBoundingClientRect();
    clone.style.transition = 'left .16s ease, top .16s ease, opacity .16s ease';
    clone.style.left = r.left + 'px';
    clone.style.top  = r.top + 'px';
    clone.style.opacity = '0';
    setTimeout(() => clone.remove(), 180);

    item.classList.remove('ro-hole');
    document.body.classList.remove('ro-dragging');
    if (moved) { flash(item); onChange(list); }
  }

  // Auto-scroll on an interval, not on pointermove: a pointer held still at the
  // edge of the screen still expects the page to keep moving.
  function edgeScroll(y) {
    const M = 100, MAX = 20;
    let dy = 0;
    if (y < M)                     dy = -MAX * (1 - y / M);
    else if (y > innerHeight - M)  dy =  MAX * (1 - (innerHeight - y) / M);
    if (!dy) return stopScroll();
    if (scroll) { scroll.dy = dy; return; }
    scroll = { dy, id: setInterval(() => window.scrollBy(0, scroll.dy), 16) };
  }
  function stopScroll() { if (scroll) { clearInterval(scroll.id); scroll = null; } }
}

// ─── Lift and drop ────────────────────────────────────────────────────────────

let _lifted = null;

function roLift(btn) {
  const item = btn.closest('.ro-item');
  if (_lifted && _lifted.item === item) return clearLift();
  clearLift();
  _lifted = { item, list: item.parentNode };
  item.classList.add('ro-lifted');
  showSlots(_lifted.list, item);
}

function showSlots(list, item) {
  const items = [...list.children].filter(c => c.classList.contains('ro-item'));
  const at = items.indexOf(item);

  // Slots either side of the lifted row would put it back where it already is.
  const refs = items.filter((_, i) => i !== at && i !== at + 1);
  if (at !== items.length - 1) refs.push(null);

  for (const ref of refs) {
    const slot = document.createElement('div');
    slot.className = 'ro-slot';
    slot.innerHTML = '<span>move here</span>';
    slot.onclick = () => {
      list.insertBefore(item, ref);
      clearLift();
      flash(item);
      item.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
      roChanged(list);
    };
    list.insertBefore(slot, ref);
  }
}

function clearLift() {
  document.querySelectorAll('.ro-slot').forEach(s => s.remove());
  document.querySelectorAll('.ro-lifted').forEach(i => i.classList.remove('ro-lifted'));
  _lifted = null;
}

function roNudge(btn, dir) {
  clearLift();
  const item = btn.closest('.ro-item');
  const list = item.parentNode;
  const sib  = dir < 0 ? item.previousElementSibling : item.nextElementSibling;
  if (!sib || !sib.classList.contains('ro-item')) return;
  list.insertBefore(dir < 0 ? item : sib, dir < 0 ? sib : item);
  flash(item);
  item.scrollIntoView({ block: 'nearest' });
  roChanged(list);
}

function flash(el) {
  el.classList.remove('ro-flash');
  void el.offsetWidth;           // restart the animation
  el.classList.add('ro-flash');
}

/** Renumber and mark the session dirty. */
function roChanged(list) {
  [...list.children].filter(c => c.classList.contains('ro-item'))
    .forEach((it, i) => { const n = it.querySelector('.ro-idx'); if (n) n.textContent = i + 1; });
  _roDirty = true;
  const btn = document.getElementById('roDone');
  if (btn) { btn.classList.remove('btn-outline'); btn.classList.add('btn-teal'); }
}

let _roDirty = false;

// ─── Test case / request reorder ──────────────────────────────────────────────

function openCaseReorder() {
  const grp = suite?.testGroups?.find(g => g.name === currentGroup);
  if (!grp) return;

  const chunks = tcChunks(grp);
  const defs   = new Map((grp.testCaseDefs || []).map(d => [d.id, d]));

  const items = chunks.map((c, i) => {
    const def  = defs.get(c.tcId);
    const sub  = c.reqs.length > 1;
    const name = def && def.name && def.name !== c.tcId ? def.name : (c.reqs.length === 1 ? c.reqs[0].name : '');
    return `
      <div class="ro-item" data-tc-id="${esc(c.tcId)}"${sub ? '' : ` data-req-id="${esc(c.reqs[0].id)}"`}>
        <div class="ro-row">
          <span class="ro-idx">${i + 1}</span>
          <div class="ro-main">
            <div class="ro-name mono">${esc(c.tcId)}${name ? ` <span class="ro-sub">${esc(name)}</span>` : ''}</div>
            <div class="ro-meta">${sub ? c.reqs.length + ' requests · sequential' : esc(c.reqs[0].method || '') + ' ' + esc(c.reqs[0].endpoint || '')}</div>
          </div>
          <div class="ro-tools" data-no-drag>
            ${sub ? `<button class="ro-btn" onclick="roToggleSub(this)" title="Reorder the requests inside this test case">▸</button>` : ''}
            <button class="ro-btn" onclick="roNudge(this,-1)" title="Move up">↑</button>
            <button class="ro-btn" onclick="roNudge(this,1)"  title="Move down">↓</button>
            <button class="ro-btn ro-btn-lift" onclick="roLift(this)" title="Lift — then click where it should go">⇱</button>
          </div>
        </div>
        ${sub ? `<div class="ro-list ro-sublist" style="display:none">${
          c.reqs.map((r, j) => `
            <div class="ro-item ro-item-sub" data-req-id="${esc(r.id)}">
              <div class="ro-row">
                <span class="ro-idx">${j + 1}</span>
                <div class="ro-main">
                  <div class="ro-name">${esc(r.name || r.id)}</div>
                  <div class="ro-meta mono">${esc(r.method || '')} ${esc(r.endpoint || '')}</div>
                </div>
                <div class="ro-tools" data-no-drag>
                  <button class="ro-btn" onclick="roNudge(this,-1)" title="Move up">↑</button>
                  <button class="ro-btn" onclick="roNudge(this,1)"  title="Move down">↓</button>
                  <button class="ro-btn ro-btn-lift" onclick="roLift(this)" title="Lift — then click where it should go">⇱</button>
                </div>
              </div>
            </div>`).join('')
        }</div>` : ''}
      </div>`;
  }).join('');

  document.getElementById('caseReorder').innerHTML = `
    <div class="ro-bar">
      <div>
        <div class="ro-title">Reorder test cases — ${chunks.length} in “${esc(grp.name)}”</div>
        <div class="ro-hint">Drag a row, nudge it with ↑ ↓, or press ⇱ then click where it should go. ▸ opens a test case to reorder the requests inside it.</div>
      </div>
      <div style="display:flex;gap:8px;flex-shrink:0">
        <button class="btn btn-outline btn-sm" onclick="closeCaseReorder(false)">Cancel</button>
        <button class="btn btn-outline btn-sm" id="roDone" onclick="closeCaseReorder(true)">Done</button>
      </div>
    </div>
    <div class="ro-list" id="roCaseList">${items}</div>`;

  document.querySelectorAll('#caseReorder .ro-list').forEach(l => roSortable(l, roChanged));
  _roDirty = false;
  document.querySelector('#panel-groupDetail .table-wrap').style.display = 'none';
  document.getElementById('detailReorderBtn').style.display = 'none';
  document.getElementById('caseReorder').style.display = '';
}

function roToggleSub(btn) {
  const sub = btn.closest('.ro-item').querySelector('.ro-sublist');
  if (!sub) return;
  const open = sub.style.display === 'none';
  sub.style.display = open ? '' : 'none';
  btn.textContent = open ? '▾' : '▸';
  btn.classList.toggle('on', open);
}

async function closeCaseReorder(save) {
  clearLift();
  const grp = suite?.testGroups?.find(g => g.name === currentGroup);
  const dirty = _roDirty;
  const ids = save && dirty
    ? [...document.querySelectorAll('#roCaseList > .ro-item')].flatMap(item => {
        const subs = [...item.querySelectorAll('.ro-item-sub')];
        return subs.length ? subs.map(s => s.dataset.reqId) : [item.dataset.reqId];
      })
    : null;

  document.getElementById('caseReorder').style.display = 'none';
  document.getElementById('caseReorder').innerHTML = '';
  document.querySelector('#panel-groupDetail .table-wrap').style.display = '';
  document.getElementById('detailReorderBtn').style.display = '';
  _roDirty = false;
  if (!ids || !grp) return;

  const prev = grp.testRequests || [];
  const next = ids.map(id => prev.find(r => r.id === id)).filter(Boolean);
  if (next.length !== prev.length) { toast('Order looked inconsistent — nothing changed', true); return; }

  grp.testRequests = next;
  renderDetailCases(grp);
  const res = await api('PUT', `/groups/${encodeURIComponent(grp.name)}/cases/reorder`, { requestIds: ids });
  if (res.success) toast('Order saved');
  else { toast(res.message || 'Could not save the new order', true); await roReload(); }
}

// ─── Group reorder ────────────────────────────────────────────────────────────

function openGroupReorder() {
  const groups = suite?.testGroups || [];
  if (groups.length < 2) return;

  const bands = [[], [], []];
  groups.forEach(g => bands[bandOf(g.name)].push(g));

  const section = (band) => {
    if (!bands[band].length) return '';
    const rows = bands[band].map((g, i) => {
      const st = gStats(g);
      return `
        <div class="ro-item" data-group-name="${esc(g.name)}">
          <div class="ro-row">
            <span class="ro-idx">${i + 1}</span>
            <div class="ro-main">
              <div class="ro-name">${esc(g.name)}${g.enabled === false ? ' <span class="ro-off">disabled</span>' : ''}</div>
              <div class="ro-meta">${st.total} test cases · ${st.reqs} requests</div>
            </div>
            <div class="ro-tools" data-no-drag>
              <button class="ro-btn" onclick="roNudge(this,-1)" title="Move up">↑</button>
              <button class="ro-btn" onclick="roNudge(this,1)"  title="Move down">↓</button>
              <button class="ro-btn ro-btn-lift" onclick="roLift(this)" title="Lift — then click where it should go">⇱</button>
            </div>
          </div>
        </div>`;
    }).join('');
    return `<div class="ro-section">${BAND_LABEL[band]}</div><div class="ro-list" data-band="${band}">${rows}</div>`;
  };

  document.getElementById('groupReorder').innerHTML = `
    <div class="ro-bar">
      <div>
        <div class="ro-title">Reorder test groups — ${groups.length} total</div>
        <div class="ro-hint">Drag a row, nudge it with ↑ ↓, or press ⇱ then click where it should go. Setup and teardown groups always run around the rest, so they reorder within their own section.</div>
      </div>
      <div style="display:flex;gap:8px;flex-shrink:0">
        <button class="btn btn-outline btn-sm" onclick="closeGroupReorder(false)">Cancel</button>
        <button class="btn btn-outline btn-sm" id="roDone" onclick="closeGroupReorder(true)">Done</button>
      </div>
    </div>
    ${section(0)}${section(1)}${section(2)}`;

  document.querySelectorAll('#groupReorder .ro-list').forEach(l => roSortable(l, roChanged));
  _roDirty = false;
  document.getElementById('groupGridWrap').style.display = 'none';
  document.getElementById('groupReorder').style.display = '';
}

async function closeGroupReorder(save) {
  clearLift();
  const dirty = _roDirty;
  const names = save && dirty
    ? [...document.querySelectorAll('#groupReorder .ro-item')].map(i => i.dataset.groupName)
    : null;

  document.getElementById('groupReorder').style.display = 'none';
  document.getElementById('groupReorder').innerHTML = '';
  document.getElementById('groupGridWrap').style.display = '';
  _roDirty = false;
  if (!names) return;

  const prev = suite.testGroups || [];
  const next = names.map(n => prev.find(g => g.name === n)).filter(Boolean);
  if (next.length !== prev.length) { toast('Order looked inconsistent — nothing changed', true); return; }

  suite.testGroups = next;
  renderGroupGrid(next);
  const res = await api('PUT', '/groups/reorder', { names });
  if (res.success) toast('Order saved');
  else { toast(res.message || 'Could not save the new order', true); await roReload(); }
}

async function roReload() {
  const res = await api('GET', '/suite');
  if (!res.success || !res.data) return;
  suite = res.data;
  renderGroupGrid(suite.testGroups);
  const grp = suite.testGroups.find(g => g.name === currentGroup);
  if (grp) { renderDetailStats(grp); renderDetailCases(grp); }
}

// Esc backs out one step at a time: drop the lift first, then the whole session.
document.addEventListener('keydown', e => {
  if (e.key !== 'Escape') return;
  if (_lifted) { clearLift(); return; }
  if (document.getElementById('caseReorder')?.style.display === '')  closeCaseReorder(false);
  else if (document.getElementById('groupReorder')?.style.display === '') closeGroupReorder(false);
});
