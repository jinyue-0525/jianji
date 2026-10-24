// Horizontal note actions coexist with native vertical scrolling and the row's FLIP motion.
export function createSwipeController(container, {enabled}) {
  const WIDTH = 72;
  const states = new WeakMap();
  const reduced = window.matchMedia('(prefers-reduced-motion: reduce)');
  let opened = null, active = null;

  function offset(front) {
    const value = getComputedStyle(front).transform;
    if (!value || value === 'none') return 0;
    try { return new DOMMatrixReadOnly(value).m41; } catch { return 0; }
  }
  function visibleAction(state, visible) {
    state.remove.inert = !visible;
    state.remove.setAttribute('aria-hidden', String(!visible));
    state.remove.tabIndex = visible ? 0 : -1;
    state.remove.style.visibility = visible ? 'visible' : 'hidden';
  }
  function settle(state, reveal, immediately = false) {
    clearTimeout(state.timer);
    state.target = reveal ? -WIDTH : 0;
    state.row.classList.remove('is-swiping');
    state.row.classList.toggle('is-open', reveal);
    if (opened && opened !== state && reveal) settle(opened, false);
    if (reveal) opened = state; else if (opened === state) opened = null;
    if (immediately || reduced.matches) state.front.style.transition = 'none';
    else state.front.style.removeProperty('transition');
    if (reveal) visibleAction(state, true);
    else { state.remove.inert = true; state.remove.tabIndex = -1; state.remove.setAttribute('aria-hidden', 'true'); }
    state.front.style.transform = `translateX(${state.target}px)`;
    const finish = () => {
      if (state.target === 0) visibleAction(state, false);
      state.front.style.removeProperty('transition');
    };
    if (immediately || reduced.matches) finish(); else state.timer = setTimeout(finish, 220);
  }
  function releaseCapture(gesture) {
    if (!gesture) return;
    try { if (gesture.state.front.hasPointerCapture(gesture.id)) gesture.state.front.releasePointerCapture(gesture.id); } catch {}
  }
  function cancelGesture() {
    const gesture = active; active = null;
    if (!gesture) return;
    gesture.state.cancelLongPress();
    if (gesture.axis === 'x') gesture.state.blockClick = true;
    releaseCapture(gesture); settle(gesture.state, false);
  }
  function closeOpen(immediately = false) {
    cancelGesture();
    if (opened) settle(opened, false, immediately);
  }
  function bind(row, front, remove, cancelLongPress) {
    const state = {row, front, remove, cancelLongPress, target: 0, timer: 0, blockClick: false};
    states.set(row, state); visibleAction(state, false);
    front.addEventListener('pointerdown', event => {
      state.blockClick = false;
      if (!enabled() || event.button !== 0 || event.isPrimary === false) return;
      cancelGesture();
      if (opened && opened !== state) settle(opened, false);
      state.blockClick = false;
      active = {state, id: event.pointerId, x: event.clientX, y: event.clientY,
        start: offset(front), wasOpen: state.target < 0, axis: null};
    });
    front.addEventListener('pointermove', event => {
      const gesture = active;
      if (!gesture || gesture.state !== state || gesture.id !== event.pointerId) return;
      if (!enabled()) { cancelGesture(); return; }
      const dx = event.clientX - gesture.x, dy = event.clientY - gesture.y;
      if (!gesture.axis) {
        if (Math.max(Math.abs(dx), Math.abs(dy)) <= 8) return;
        state.cancelLongPress();
        if (Math.abs(dx) > Math.abs(dy) * 1.25 && (dx < 0 || gesture.start < 0)) {
          gesture.axis = 'x'; row.classList.add('is-swiping'); visibleAction(state, true);
          try { front.setPointerCapture(event.pointerId); } catch {}
        } else { gesture.axis = 'y'; return; }
      }
      if (gesture.axis !== 'x') return;
      if (event.cancelable) event.preventDefault();
      const next = Math.max(-WIDTH, Math.min(0, gesture.start + dx));
      front.style.transform = `translateX(${next}px)`;
    }, {passive: false});
    front.addEventListener('pointerup', event => {
      const gesture = active;
      if (!gesture || gesture.state !== state || gesture.id !== event.pointerId) return;
      active = null;
      if (gesture.axis === 'x') {
        if (event.cancelable) event.preventDefault();
        state.blockClick = true; state.cancelLongPress();
        const next = offset(front);
        releaseCapture(gesture); settle(state, next <= -WIDTH / 2);
      } else if (gesture.axis === 'y') {
        state.blockClick = true; settle(state, false);
      }
    });
    const cancelled = event => {
      if (active?.state === state && active.id === event.pointerId) cancelGesture();
    };
    front.addEventListener('pointercancel', cancelled);
    front.addEventListener('lostpointercapture', cancelled);
    front.addEventListener('click', event => {
      if (state.blockClick) {
        state.blockClick = false; event.preventDefault(); event.stopImmediatePropagation(); return;
      }
      if (state.target < 0 || row.classList.contains('is-open')) {
        settle(state, false); event.preventDefault(); event.stopImmediatePropagation();
      }
    }, true);
  }
  container.addEventListener('scroll', () => closeOpen(), {passive: true});
  document.addEventListener('pointerdown', event => {
    if (opened && !opened.row.contains(event.target)) settle(opened, false);
  }, {passive: true});
  reduced.addEventListener('change', () => {
    if (reduced.matches) closeOpen(true);
  });
  return {bind, closeOpen, isOpen: row => states.get(row)?.target < 0,
    reset(row) {
      const state = states.get(row); if (!state) return;
      if (active?.state === state) cancelGesture();
      state.cancelLongPress(); settle(state, false, true);
    }};
}
