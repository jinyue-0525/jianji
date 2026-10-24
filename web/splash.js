// Port of SplashView: dp units map to CSS pixels; the device ratio only sharpens Canvas.
const DRAW_DURATION = 740;
const FADE_DURATION = 180;
const PAPER = new Path2D('M38 28H58L76 46V74Q76 80 70 80H38Q32 80 32 74V34Q32 28 38 28Z');
const FOLD = new Path2D('M58 28V41Q58 46 63 46H76Z');
const clamp = value => Math.max(0, Math.min(1, value));
const slice = (value, start, end) => clamp((value - start) / (end - start));
const easeOut = value => 1 - Math.pow(1 - value, 3);
const settle = value => 1 + 2.2 * Math.pow(value - 1, 3) + 1.2 * Math.pow(value - 1, 2);

// Canvas lacks PathMeasure; sample only the four small quadratic corners for its reveal.
const outline = [{ x: 38, y: 28, distance: 0 }];
function lineTo(x, y) {
  const previous = outline[outline.length - 1];
  outline.push({ x, y, distance: previous.distance + Math.hypot(x - previous.x, y - previous.y) });
}
function quadTo(cx, cy, x, y) {
  const start = outline[outline.length - 1];
  for (let step = 1; step <= 24; step++) {
    const t = step / 24, r = 1 - t;
    lineTo(r * r * start.x + 2 * r * t * cx + t * t * x,
      r * r * start.y + 2 * r * t * cy + t * t * y);
  }
}
lineTo(58, 28); lineTo(76, 46); lineTo(76, 74); quadTo(76, 80, 70, 80);
lineTo(38, 80); quadTo(32, 80, 32, 74); lineTo(32, 34); quadTo(32, 28, 38, 28);
const outlineLength = outline[outline.length - 1].distance;

function strokeOutline(context, fraction) {
  const end = outlineLength * fraction;
  if (end <= 0) return;
  context.beginPath(); context.moveTo(outline[0].x, outline[0].y);
  for (let i = 1; i < outline.length; i++) {
    const previous = outline[i - 1], current = outline[i];
    if (current.distance <= end) context.lineTo(current.x, current.y);
    else {
      const ratio = (end - previous.distance) / (current.distance - previous.distance);
      context.lineTo(previous.x + (current.x - previous.x) * ratio,
        previous.y + (current.y - previous.y) * ratio);
      break;
    }
  }
  context.stroke();
}

export function playSplash(host) {
  if (!host || window.matchMedia('(prefers-reduced-motion: reduce)').matches) return Promise.resolve();
  if (host.querySelector('.launch-splash')) return Promise.resolve();
  const overlay = document.createElement('div');
  overlay.className = 'launch-splash'; overlay.setAttribute('aria-hidden', 'true');
  const canvas = document.createElement('canvas'); overlay.append(canvas);
  const context = canvas.getContext('2d');
  if (!context) return Promise.resolve();
  const screenStates = [...host.querySelectorAll('.screen')].map(screen => [screen, screen.inert]);
  screenStates.forEach(([screen]) => { screen.inert = true; });
  host.append(overlay);
  const reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)');
  let width = 1, height = 1, ratio = 1, frame = 0, started = null, completed = false;
  let resolveFinished;
  const finished = new Promise(resolve => { resolveFinished = resolve; });
  const fontScale = Math.min(parseFloat(getComputedStyle(host).getPropertyValue('--scale')) || 1, 1.35);
  const fontFamily = getComputedStyle(host).fontFamily;

  function resize() {
    const status = host.querySelector('.statusbar');
    const navigation = host.querySelector('.home-indicator');
    const top = status ? status.offsetTop + status.offsetHeight : 0;
    const bottom = navigation ? host.clientHeight - navigation.offsetTop : 0;
    overlay.style.top = `${top}px`; overlay.style.bottom = `${bottom}px`;
    width = Math.max(1, host.clientWidth); height = Math.max(1, host.clientHeight - top - bottom);
    ratio = window.devicePixelRatio || 1;
    canvas.width = Math.round(width * ratio); canvas.height = Math.round(height * ratio);
  }
  function draw(progress) {
    context.setTransform(ratio, 0, 0, ratio, 0, 0); context.clearRect(0, 0, width, height);
    const layoutScale = Math.min(1, Math.max(1, width - 32) / 248, Math.max(1, height - 32) / 288);
    const centerX = width / 2, centerY = height * .42, tile = 96 * layoutScale;
    const settling = slice(progress, 0, .38), iconScale = .88 + .12 * settle(settling);
    const iconAlpha = easeOut(slice(progress, 0, .16));
    context.save();
    context.translate(centerX, centerY + 8 * layoutScale * (1 - easeOut(settling)));
    context.scale(iconScale, iconScale);
    context.fillStyle = '#4d6bfe'; context.globalAlpha = iconAlpha;
    context.beginPath(); context.roundRect(-tile / 2, -tile / 2, tile, tile, 25 * layoutScale); context.fill();
    context.translate(-tile / 2, -tile / 2); context.scale(tile / 108, tile / 108);
    const sheetAlpha = easeOut(slice(progress, .1, .4)) * iconAlpha;
    context.globalAlpha = sheetAlpha; context.fillStyle = '#ffffff'; context.fill(PAPER);
    context.fillStyle = '#dde3ff'; context.fill(FOLD);
    context.globalAlpha = iconAlpha; context.strokeStyle = '#ffffff'; context.lineWidth = 1.8;
    context.lineCap = 'round'; context.lineJoin = 'round';
    strokeOutline(context, easeOut(slice(progress, .02, .42)));
    context.globalAlpha = sheetAlpha; context.strokeStyle = '#4d6bfe'; context.lineWidth = 3.5;
    [[51, 61, .3, .48], [60, 65, .44, .64], [69, 55, .6, .78]].forEach(([y, end, from, to]) => {
      const value = slice(progress, from, to); if (value <= 0) return;
      context.beginPath(); context.moveTo(42, y); context.lineTo(42 + (end - 42) * easeOut(value), y); context.stroke();
    });
    context.restore();
    const textScale = fontScale * layoutScale;
    const titleProgress = easeOut(slice(progress, .24, .66));
    context.textAlign = 'center'; context.textBaseline = 'alphabetic';
    context.font = `500 ${30 * textScale}px ${fontFamily}`; context.fillStyle = '#1e2433'; context.globalAlpha = titleProgress;
    context.fillText('简记', centerX, centerY + 98 * layoutScale + 8 * layoutScale * (1 - titleProgress));
    const sloganProgress = easeOut(slice(progress, .44, .86));
    context.font = `400 ${14 * textScale}px ${fontFamily}`; context.fillStyle = '#697386'; context.globalAlpha = sloganProgress;
    context.fillText('把想法，轻轻记下', centerX, centerY + 126 * layoutScale + 6 * layoutScale * (1 - sloganProgress));
    context.globalAlpha = 1;
  }
  function complete() {
    if (completed) return;
    completed = true; cancelAnimationFrame(frame); observer.disconnect();
    document.removeEventListener('keydown', onKeyDown, true);
    document.removeEventListener('visibilitychange', onVisibilityChange);
    reducedMotion.removeEventListener('change', onMotionChange);
    overlay.remove(); screenStates.forEach(([screen, inert]) => { screen.inert = inert; }); resolveFinished();
  }
  function onKeyDown(event) {
    if (event.key === 'Escape') { event.preventDefault(); event.stopImmediatePropagation(); complete(); }
  }
  function onVisibilityChange() { if (document.hidden) complete(); }
  function onMotionChange(event) { if (event.matches) complete(); }
  function tick(time) {
    if (completed) return;
    if (started === null) started = time;
    const elapsed = time - started;
    draw(clamp(elapsed / DRAW_DURATION));
    if (elapsed >= DRAW_DURATION) {
      const fade = clamp((elapsed - DRAW_DURATION) / FADE_DURATION);
      overlay.style.opacity = String(1 - fade * fade);
      if (fade >= 1) { complete(); return; }
    }
    frame = requestAnimationFrame(tick);
  }
  const observer = new ResizeObserver(resize); observer.observe(host);
  document.addEventListener('keydown', onKeyDown, true);
  document.addEventListener('visibilitychange', onVisibilityChange);
  reducedMotion.addEventListener('change', onMotionChange);
  overlay.addEventListener('pointerdown', event => { event.preventDefault(); event.stopPropagation(); });
  resize(); draw(0); frame = requestAnimationFrame(tick);
  return finished;
}
