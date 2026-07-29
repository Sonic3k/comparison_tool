// ─── Drag to reorder ──────────────────────────────────────────────────────────
// Pointer-events based, no library: <tr> does not survive HTML5 drag-and-drop
// intact (the drag image is a mangled single cell) and a CDN dependency is not
// an option in locked-down browsers.
//
// The dragged unit is moved live in the DOM as the pointer crosses a
// neighbour's midpoint, so the browser handles layout and there is no
// placeholder to keep in sync. A unit can span several elements (a test case
// header plus its member rows) and is clamped to a scope, so a request can
// never leave its test case and a group can never leave its execution band.
//
//   makeSortable(container, {
//     axis:         'y' for lists/tables, 'xy' for card grids
//     unitOf:       (handleEl) -> { els: [Element], scope: string } | null
//     unitsInScope: (scope)    -> [{ els: [Element] }] in current DOM order
//     onDrop:       ()         -> void, only fired when something actually moved
//   })

function makeSortable(container, opts) {
  const { unitOf, unitsInScope, onDrop, axis = 'y' } = opts;
  let drag = null;
  let scrollTimer = null;

  container.addEventListener('pointerdown', onDown);

  function onDown(e) {
    if (e.button !== 0) return;
    const handle = e.target.closest('[data-drag-handle]');
    if (!handle || !container.contains(handle)) return;

    const unit = unitOf(handle);
    if (!unit || !unit.els || !unit.els.length) return;

    e.preventDefault();
    e.stopPropagation();
    drag = { els: unit.els, scope: unit.scope, moved: false };
    drag.els.forEach(el => el.classList.add('drag-active'));
    document.body.classList.add('dragging');

    window.addEventListener('pointermove', onMove);
    window.addEventListener('pointerup', onUp, { once: true });
    window.addEventListener('pointercancel', onUp, { once: true });
  }

  function onMove(e) {
    if (!drag) return;
    edgeScroll(e.clientY);

    const dragged = new Set(drag.els);
    const units = unitsInScope(drag.scope).filter(u => !dragged.has(u.els[0]));
    const target = pickTarget(units, e.clientX, e.clientY, axis);
    if (!target) return;

    const tEls = target.unit.els;
    const ref  = target.before ? tEls[0] : tEls[tEls.length - 1].nextElementSibling;

    // Already in place — inserting would only churn the DOM.
    if (ref === drag.els[0]) return;
    if (ref === drag.els[drag.els.length - 1].nextElementSibling) return;
    if (ref && dragged.has(ref)) return;

    const parent = tEls[0].parentNode;
    drag.els.forEach(el => parent.insertBefore(el, ref));
    drag.moved = true;
  }

  function onUp() {
    window.removeEventListener('pointermove', onMove);
    stopScroll();
    if (!drag) return;
    drag.els.forEach(el => el.classList.remove('drag-active'));
    document.body.classList.remove('dragging');
    const moved = drag.moved;
    drag = null;
    if (moved) onDrop();
  }

  // ── Insertion point ────────────────────────────────────────────────────────

  function pickTarget(units, x, y, axis) {
    if (!units.length) return null;

    for (const u of units) {
      const r = rectOf(u.els);
      const inside = axis === 'xy'
        ? (x >= r.left && x <= r.right && y >= r.top && y <= r.bottom)
        : (y >= r.top && y <= r.bottom);
      if (inside) {
        const before = axis === 'xy'
          ? x < r.left + r.width / 2
          : y < r.top + r.height / 2;
        return { unit: u, before };
      }
    }

    // Past the ends of the scope — clamp instead of doing nothing, otherwise
    // the last slot is unreachable when the pointer overshoots the list.
    const first = rectOf(units[0].els);
    const last  = rectOf(units[units.length - 1].els);
    if (y < first.top)   return { unit: units[0], before: true };
    if (y > last.bottom) return { unit: units[units.length - 1], before: false };
    return null;
  }

  // Union of the unit's visible boxes. Collapsed member rows report a zero rect
  // and must be skipped, otherwise a collapsed test case gets a bogus box.
  function rectOf(els) {
    let top = Infinity, bottom = -Infinity, left = Infinity, right = -Infinity;
    for (const el of els) {
      const r = el.getBoundingClientRect();
      if (!r.width && !r.height) continue;
      top = Math.min(top, r.top);   bottom = Math.max(bottom, r.bottom);
      left = Math.min(left, r.left); right = Math.max(right, r.right);
    }
    if (top === Infinity) {
      const r = els[0].getBoundingClientRect();
      return { top: r.top, bottom: r.bottom, left: r.left, right: r.right, width: r.width, height: r.height };
    }
    return { top, bottom, left, right, width: right - left, height: bottom - top };
  }

  // ── Auto-scroll near the viewport edges ────────────────────────────────────
  // On an interval, not on pointermove: a user holding the pointer still at the
  // bottom of the screen gets no move events but still expects to keep going.

  function edgeScroll(y) {
    const MARGIN = 90, MAX = 18;
    let dy = 0;
    if (y < MARGIN)                       dy = -MAX * (1 - y / MARGIN);
    else if (y > innerHeight - MARGIN)    dy =  MAX * (1 - (innerHeight - y) / MARGIN);

    if (!dy) return stopScroll();
    if (scrollTimer) { scrollTimer.dy = dy; return; }
    scrollTimer = { dy, id: setInterval(() => window.scrollBy(0, scrollTimer.dy), 16) };
  }

  function stopScroll() {
    if (scrollTimer) { clearInterval(scrollTimer.id); scrollTimer = null; }
  }
}
