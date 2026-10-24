const EASE = 'cubic-bezier(.2, 0, 0, 1)';
const media = window.matchMedia('(prefers-reduced-motion: reduce)');
const running = new Set();
const channels = new WeakMap();
const pressed = new Map();
let pageJob = null;
let initialized = false;

export function motionAllowed() { return !media.matches && typeof Element.prototype.animate === 'function'; }
function stop(element, channel) { channels.get(element)?.get(channel)?.stop(); }
function animate(element, channel, frames, duration, done = () => {}, easing = EASE) {
  stop(element, channel);
  if (!motionAllowed() || !element.isConnected) { done(); return {stop() {}, complete() {}}; }
  let table = channels.get(element);
  if (!table) { table = new Map(); channels.set(element, table); }
  const animation = element.animate(frames, {duration, easing, fill: 'both'});
  let settled = false;
  const release = () => {
    if (settled) return false;
    settled = true; animation.onfinish = null;
    if (table.get(channel) === record) table.delete(channel);
    running.delete(record); animation.cancel(); return true;
  };
  const record = {stop() { release(); }, complete() { if (release()) done(); }};
  table.set(channel, record); running.add(record);
  animation.onfinish = () => record.complete();
  return record;
}
function number(value, fallback = 1) { const result = Number.parseFloat(value); return Number.isFinite(result) ? result : fallback; }
function releasePress(button) {
  if (!button) return;
  const from = number(getComputedStyle(button).scale);
  const overshoot = button.getBoundingClientRect().width >= 144 ? 1.004 : 1.01;
  stop(button, 'press');
  button.style.removeProperty('scale');
  if (!motionAllowed()) return;
  animate(button, 'press', [{scale: from}, {scale: overshoot, offset: .65}, {scale: 1}], 240,
    () => button.style.removeProperty('scale'));
}
function press(button) {
  if (!motionAllowed()) return;
  const from = number(getComputedStyle(button).scale);
  const target = button.getBoundingClientRect().width >= 144 ? .985 : .96;
  animate(button, 'press', [{scale: from}, {scale: target}], 70, () => { button.style.scale = target; });
}
export function initMotion(host) {
  if (initialized) return;
  initialized = true;
  host.addEventListener('pointerdown', event => {
    if (event.button !== 0 || event.isPrimary === false) return;
    const button = event.target.closest('button');
    if (!button || button.disabled || button.closest('[inert]')) return;
    const previous = pressed.get(event.pointerId); if (previous) releasePress(previous.button);
    pressed.set(event.pointerId, {button, x: event.clientX, y: event.clientY}); press(button);
  }, {passive: true});
  document.addEventListener('pointermove', event => {
    const state = pressed.get(event.pointerId); if (!state) return;
    if (Math.hypot(event.clientX - state.x, event.clientY - state.y) > 8) {
      pressed.delete(event.pointerId); releasePress(state.button);
    }
  }, {passive: true});
  ['pointerup', 'pointercancel'].forEach(type => document.addEventListener(type, event => {
    const state = pressed.get(event.pointerId); if (!state) return;
    pressed.delete(event.pointerId); releasePress(state.button);
  }, {passive: true}));
  host.addEventListener('keydown', event => {
    if (event.repeat || ![' ', 'Enter'].includes(event.key)) return;
    const button = event.target.closest('button'); if (!button || button.disabled) return;
    pressed.set('keyboard', {button}); press(button);
  });
  document.addEventListener('keyup', event => {
    if (![' ', 'Enter'].includes(event.key)) return;
    const state = pressed.get('keyboard'); if (state) releasePress(state.button);
    pressed.delete('keyboard');
  });
  window.addEventListener('blur', () => {
    pressed.forEach(state => releasePress(state.button)); pressed.clear();
  });
}

// A display-only DOM snapshot preserves editable values, scroll offsets and decoded images.
// It is taken before the editor releases blob URLs or a shared dialog replaces its content.
function snapshot(source) {
  const copy = source.cloneNode(true);
  const originalNodes = [source, ...source.querySelectorAll('*')];
  const copiedNodes = [copy, ...copy.querySelectorAll('*')];
  const scrolling = [];
  const bounds = source.getBoundingClientRect();
  originalNodes.forEach((original, index) => {
    const duplicate = copiedNodes[index];
    duplicate.removeAttribute('id');
    duplicate.removeAttribute('autofocus');
    duplicate.removeAttribute('name');
    duplicate.removeAttribute('for');
    [...duplicate.attributes].forEach(attribute => { if (attribute.name.startsWith('aria-')) duplicate.removeAttribute(attribute.name); });
    if (original.id === 'save') duplicate.style.color = getComputedStyle(original).color;
    if (original.id === 'editorDate') duplicate.style.maxWidth = getComputedStyle(original).maxWidth;
    if (original instanceof HTMLTextAreaElement) {
      duplicate.value = original.value;
      duplicate.setSelectionRange(original.selectionStart, original.selectionEnd, original.selectionDirection);
    }
    if (original instanceof HTMLInputElement && original.type !== 'file') {
      duplicate.value = original.value; duplicate.checked = original.checked;
      if (original.selectionStart !== null) duplicate.setSelectionRange(original.selectionStart, original.selectionEnd, original.selectionDirection);
    }
    if (original.scrollTop || original.scrollLeft) scrolling.push([duplicate, original.scrollTop, original.scrollLeft]);
    if (original instanceof HTMLImageElement) {
      const rect = original.getBoundingClientRect();
      if (!original.complete || !original.naturalWidth || !rect.width || !rect.height || rect.bottom < bounds.top || rect.top > bounds.bottom) {
        duplicate.removeAttribute('src'); return;
      }
      const canvas = document.createElement('canvas'), ratio = Math.min(window.devicePixelRatio || 1, 2);
      canvas.className = duplicate.className;
      canvas.style.cssText = duplicate.style.cssText;
      Object.assign(canvas.style, {display: 'block', width: `${rect.width}px`, height: `${rect.height}px`, maxWidth: '100%'});
      canvas.width = Math.max(1, Math.round(rect.width * ratio)); canvas.height = Math.max(1, Math.round(rect.height * ratio));
      const context = canvas.getContext('2d');
      if (context) {
        const fit = Math.min(canvas.width / original.naturalWidth, canvas.height / original.naturalHeight);
        const width = original.naturalWidth * fit, height = original.naturalHeight * fit;
        try { context.drawImage(original, (canvas.width - width) / 2, (canvas.height - height) / 2, width, height); }
        catch { return; }
        duplicate.replaceWith(canvas);
      }
    }
  });
  copy.classList.add('motion-snapshot'); copy.inert = true;
  copy.setAttribute('aria-hidden', 'true'); copy.setAttribute('role', 'presentation');
  return {copy, restoreScroll() { scrolling.forEach(([element, top, left]) => { element.scrollTop = top; element.scrollLeft = left; }); }};
}

// State changes and focus are synchronous; only the departing pixels survive for 300 ms.
export function transitionPage(from, to, forward, update) {
  pageJob?.complete();
  const host = to.closest('.phone');
  if (host?.classList.contains('desktop-workspace')) {
    const list = document.getElementById('listScreen');
    const editor = document.getElementById('editorScreen');
    const editing = to === editor;
    list.hidden = false; list.inert = false; list.removeAttribute('aria-hidden');
    editor.hidden = !editing; editor.inert = !editing;
    if (editing) editor.removeAttribute('aria-hidden'); else editor.setAttribute('aria-hidden', 'true');
    host.classList.toggle('desktop-has-editor', editing);
    if (!editing) host.classList.remove('desktop-focus');
    update?.();
    if (editing) animate(editor, 'page', [{opacity: .7, translate: '6px 0'}, {opacity: 1, translate: '0 0'}], 180);
    return;
  }
  const visibleSource = from && !from.hidden && from.isConnected;
  const visual = motionAllowed() && visibleSource ? snapshot(from) : null;
  if (visual) {
    Object.assign(visual.copy.style, {position: 'absolute', top: `${from.offsetTop}px`, left: `${from.offsetLeft}px`,
      width: `${from.offsetWidth}px`, height: `${from.offsetHeight}px`, margin: '0', flex: 'none', zIndex: '3'});
    host.append(visual.copy); visual.restoreScroll();
  }
  if (from && from !== to) { from.hidden = true; from.inert = true; from.setAttribute('aria-hidden', 'true'); }
  to.hidden = false; to.inert = false; to.removeAttribute('aria-hidden');
  try { update?.(); }
  catch (error) { visual?.copy.remove(); throw error; }
  if (!visual) return;
  let complete = false;
  const jobs = [];
  const job = {complete() {
    if (complete) return;
    complete = true; jobs.forEach(item => item.stop()); visual.copy.remove();
    if (pageJob === job) pageJob = null;
  }};
  pageJob = job;
  jobs.push(animate(to, 'page', [{transform: `translateX(${forward ? 24 : -24}px)`}, {transform: 'translateX(0)'}], 300));
  jobs.push(animate(visual.copy, 'page-old', [{opacity: 1, transform: 'translateX(0)'},
    {opacity: 0, transform: `translateX(${forward ? -10 : 10}px)`}], 300, () => job.complete()));
}

export function showLayer(element, kind = 'dialog') {
  const panel = element.querySelector('.category-sheet,.dialog');
  stop(element, 'layer'); if (panel) stop(panel, 'panel');
  element.hidden = false; element.inert = false; element.removeAttribute('aria-hidden');
  if (kind === 'popup') {
    animate(element, 'layer', [{opacity: 0, transform: 'translateY(4px) scale(.985)'}, {opacity: 1, transform: 'none'}], 180);
    return;
  }
  animate(element, 'layer', [{opacity: 0}, {opacity: 1}], kind === 'sheet' ? 200 : 180);
  if (panel) animate(panel, 'panel', [{transform: kind === 'sheet' ? 'translateY(32px)' : 'translateY(6px) scale(.985)'},
    {transform: 'none'}], kind === 'sheet' ? 300 : 260);
}
export function hideLayer(element, kind = 'dialog') {
  if (element.hidden) return;
  const panel = element.querySelector('.category-sheet,.dialog');
  const visual = motionAllowed() ? snapshot(element) : null;
  const opacity = number(getComputedStyle(element).opacity);
  const transform = getComputedStyle(panel || element).transform;
  stop(element, 'layer'); if (panel) stop(panel, 'panel');
  element.hidden = true; element.inert = true; element.setAttribute('aria-hidden', 'true');
  if (!visual) return;
  const copy = visual.copy, copyPanel = copy.querySelector('.category-sheet,.dialog');
  copy.style.zIndex = kind === 'popup' ? '5' : '9';
  element.parentElement.append(copy); visual.restoreScroll();
  const duration = kind === 'sheet' ? 200 : kind === 'popup' ? 140 : 180;
  const jobs = [];
  const remove = () => { jobs.forEach(job => job.stop()); copy.remove(); };
  if (copyPanel) jobs.push(animate(copyPanel, 'panel-out', [{transform},
    {transform: kind === 'sheet' ? 'translateY(24px)' : 'translateY(4px) scale(.985)'}], duration));
  jobs.push(animate(copy, 'layer-out', kind === 'popup' ? [{opacity, transform},
    {opacity: 0, transform: 'translateY(3px) scale(.985)'}] : [{opacity}, {opacity: 0}], duration, remove));
}

export function captureRows(rows) {
  const positions = new Map();
  rows.forEach(row => {
    if (!row.closest('[hidden]')) positions.set(row, row.getBoundingClientRect());
    stop(row, 'row');
  });
  return positions;
}
export function moveRow(row, previous) {
  if (row.closest('[hidden]')) return;
  if (!previous) {
    animate(row, 'row', [{opacity: 0, translate: '0 8px'}, {opacity: 1, translate: '0 0'}], 240);
  } else {
    const current = row.getBoundingClientRect();
    const delta = previous.top - current.top;
    if (Math.abs(delta) > .5 && Math.abs(delta) < row.parentElement.clientHeight) {
      animate(row, 'row', [{translate: `0 ${delta}px`}, {translate: '0 0'}], 240);
    }
  }
}
export function fold(element, arrow, expanded) {
  const from = element.hidden ? 0 : element.getBoundingClientRect().height;
  const opacity = element.hidden ? 0 : number(getComputedStyle(element).opacity);
  const rotation = getComputedStyle(arrow).transform;
  stop(element, 'fold'); stop(arrow, 'arrow');
  element.hidden = false; element.inert = !expanded;
  if (expanded) element.removeAttribute('aria-hidden'); else element.setAttribute('aria-hidden', 'true');
  element.style.height = 'auto'; element.style.overflow = 'hidden';
  const target = expanded ? element.getBoundingClientRect().height : 0;
  animate(arrow, 'arrow', [{transform: rotation}, {transform: expanded ? 'rotate(180deg)' : 'rotate(0deg)'}], 240);
  animate(element, 'fold', [{height: `${from}px`, opacity}, {height: `${target}px`, opacity: expanded ? 1 : 0}], 240, () => {
    element.hidden = !expanded; element.style.removeProperty('height'); element.style.removeProperty('overflow');
  });
}

media.addEventListener('change', event => {
  if (!event.matches) return;
  [...running].forEach(record => record.complete());
  pageJob?.complete();
  pressed.forEach(({button}) => button.style.removeProperty('scale')); pressed.clear();
});
