// Advancement editor — the advancements window: tabs, tree, panning, drag-to-move and pick-on-screen.
// ---------- rendering ----------
function pickScale() {
  // The window stretches to the page width, like Better Advancements; pixel scale steps with the width.
  const avail = document.querySelector('.stage').clientWidth || (window.innerWidth - 32);
  S = Math.max(1, Math.min(3, Math.floor(avail / 320 * 2) / 2));
  W = Math.max(252, Math.floor(avail / S));
  const tallest = Math.floor((window.innerHeight * 0.72) / S) - 56;
  H = Math.max(140, Math.min(Math.round(W * 0.6), tallest));
  IW = W - 18; IH = H - 27;
  PER_ROW = Math.max(1, Math.floor((W + 4) / 32));
  gui.style.setProperty('--s', S);
}
const frameSprite = n => `${n.f}_frame_${view.earned ? 'obtained' : 'unobtained'}`;
function nodeEl(n, parent, x, y) {
  const d = el('node abs sprite', { left: px(x), top: px(y), backgroundImage: `url(${SP[frameSprite(n)] || SP.task_frame_obtained})` }, parent);
  el('ic', { backgroundImage: `url(${IC[n.i]})` }, d);
  return d;
}

function render() {
  pickScale();
  const L = layoutCache = computeLayout();
  const tabs = visibleTabs(L);
  let tab = tabs.find(t => t.id === view.tabId);
  if (!tab) { tab = tabs[0]; view.tabId = tab.id; }
  const ti = tabs.indexOf(tab);
  const rowsBelow = tabs.length > PER_ROW;
  gui.innerHTML = '';
  gui.style.width = px(W);
  gui.style.height = px(H + 28 + (rowsBelow ? 28 : 0));
  const top = 28;

  tabs.forEach((t, i) => {
    const below = i >= PER_ROW, idx = i % PER_ROW;
    const pos = idx === 0 ? 'left' : (idx * 32 + 28 >= W - 1 ? 'right' : 'middle');
    const sel = i === ti;
    const name = `tab_${below ? 'below' : 'above'}_${pos}${sel ? '_selected' : ''}`;
    const d = el('tab abs sprite', { left: px(idx * 32), top: px(below ? top + H - 4 : top - 28), backgroundImage: `url(${SP[name]})`, zIndex: sel ? 5 : 1 }, gui);
    el('ic', { left: px(6), top: px(below ? 10 : 9), backgroundImage: `url(${IC[t.icon]})` }, d);
    d.dataset.tab = t.id;
    d.setAttribute('role', 'tab'); d.setAttribute('aria-label', t.title); d.tabIndex = 0;
    d.addEventListener('click', () => { view.tabId = t.id; saveView(); render(); });
    d.addEventListener('keydown', e => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); d.click(); } });
    d.addEventListener('mousemove', e => { if (!drag || !drag.active) showTip(e, t.title, t.kind === 'other' ? 'Hidden unless “Other mods’ tabs” is Shown' : `${t.nodes.length} advancements`); });
    d.addEventListener('mouseleave', hideTip);
  });

  const inside = el('abs', { left: px(IX), top: px(top + IY), width: px(IW), height: px(IH), zIndex: 2 }, gui);
  inside.id = 'inside';
  inside.style.backgroundImage = `url(${bgUri(tab.bg)})`;
  inside.style.backgroundSize = `${16 * S}px ${16 * S}px`;
  const tree = el('', {}, inside); tree.id = 'tree';
  const svgNS = 'http://www.w3.org/2000/svg';
  const svg = document.createElementNS(svgNS, 'svg'); svg.id = 'lines'; tree.appendChild(svg);

  const byId = {}; tab.nodes.forEach(n => { byId[n.id] = n; n.px = n.x * 28; n.py = Math.floor(n.y * 27); });
  let minX = 1e9, minY = 1e9, maxX = -1e9, maxY = -1e9;
  tab.nodes.forEach(n => { minX = Math.min(minX, n.px); minY = Math.min(minY, n.py); maxX = Math.max(maxX, n.px + 28); maxY = Math.max(maxY, n.py + 27); });
  bounds = { minX, minY, maxX, maxY };
  svg.setAttribute('width', px(maxX + 40)); svg.setAttribute('height', px(maxY + 40));
  const segs = [];
  tab.nodes.forEach(n => {
    const p = byId[n.p]; if (!p) return;
    const i = p.px + 13, j = p.px + 26 + 4, k = p.py + 13, l = n.px + 13, m = n.py + 13;
    segs.push([j, k, i, k], [j - 1, m, l, m], [j - 1, Math.min(k, m), j - 1, Math.max(k, m)]);
  });
  [['#000', 3], ['#fff', 1]].forEach(([c, w]) => segs.forEach(([x1, y1, x2, y2]) => {
    const r = document.createElementNS(svgNS, 'rect');
    const o = (w - 1) / 2;
    r.setAttribute('x', (Math.min(x1, x2) - o) * S); r.setAttribute('y', (Math.min(y1, y2) - o) * S);
    r.setAttribute('width', (Math.abs(x2 - x1) + w) * S); r.setAttribute('height', (Math.abs(y2 - y1) + w) * S);
    r.setAttribute('fill', c); svg.appendChild(r);
  }));

  tab.nodes.forEach(n => {
    const d = nodeEl(n, tree, n.px + 3, n.py);
    d.dataset.node = n.id;
    if (n.id === selected) d.classList.add('sel');
    d.setAttribute('role', 'button'); d.setAttribute('aria-label', `Edit ${n.t}`); d.tabIndex = 0;
    d.addEventListener('mouseenter', () => { if (!drag || !drag.active) showHover(n); });
    d.addEventListener('mouseleave', hideHover);
    d.addEventListener('pointerdown', e => startNodeDrag(e, n));
    d.addEventListener('keydown', e => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); nodeClicked(n.id, d); } });
  });

  const fade = el('abs', { inset: 0 }, inside); fade.id = 'fade';
  const hover = el('abs', { zIndex: 6 }, gui); hover.id = 'hover';
  el('abs', { left: 0, top: px(top), width: px(W), height: px(H), boxSizing: 'border-box', borderStyle: 'solid', borderWidth: `${18 * S}px ${9 * S}px ${9 * S}px ${9 * S}px`, borderImage: `url(${SP.window}) 18 9 9 9 stretch`, zIndex: 3, pointerEvents: 'none' }, gui);
  const title = el('abs gtitle', { left: px(8), top: px(top + 6), zIndex: 4 }, gui);
  title.textContent = tab.title;

  if (!scrolls[tab.id]) scrolls[tab.id] = { x: -minX, y: -minY };
  clampScroll(); applyScroll();
  wirePan(inside);
  renderControls(L); renderChips(L, tabs); renderChanges(L);
  if (selected && !menu.hidden) { if (L.E.nodes[selected]) fillMenu(selected); else closeMenu(); }
}

const curScroll = () => scrolls[view.tabId];
function clampScroll() {
  const b = bounds, s = curScroll();
  if (b.maxX - b.minX <= IW) s.x = IW / 2 - (b.maxX + b.minX) / 2; else s.x = Math.max(IW - b.maxX, Math.min(-b.minX, s.x));
  if (b.maxY - b.minY <= IH) s.y = IH / 2 - (b.maxY + b.minY) / 2; else s.y = Math.max(IH - b.maxY, Math.min(-b.minY, s.y));
}
function applyScroll() {
  const s = curScroll(), t = document.getElementById('tree'), i = document.getElementById('inside');
  t.style.transform = `translate(${Math.round(s.x) * S}px, ${Math.round(s.y) * S}px)`;
  i.style.backgroundPosition = `${Math.round(s.x) % 16 * S}px ${Math.round(s.y) % 16 * S}px`;
}
function wirePan(inside) {
  let pan = null;
  inside.addEventListener('pointerdown', e => {
    if (e.target.closest('.node')) return;
    const s = curScroll(); pan = { x: e.clientX, y: e.clientY, sx: s.x, sy: s.y, moved: false };
    inside.setPointerCapture(e.pointerId); inside.classList.add('drag'); hideHover();
  });
  inside.addEventListener('pointermove', e => {
    if (!pan) return; const s = curScroll();
    if (Math.abs(e.clientX - pan.x) + Math.abs(e.clientY - pan.y) > 3) pan.moved = true;
    s.x = pan.sx + (e.clientX - pan.x) / S; s.y = pan.sy + (e.clientY - pan.y) / S; clampScroll(); applyScroll();
  });
  const end = () => { if (pan && !pan.moved && !picking) closeMenu(); pan = null; inside.classList.remove('drag'); };
  inside.addEventListener('pointerup', end); inside.addEventListener('pointercancel', end);
  inside.addEventListener('wheel', e => {
    e.preventDefault(); const s = curScroll();
    const dx = e.shiftKey ? e.deltaY : e.deltaX, dy = e.shiftKey ? 0 : e.deltaY;
    s.x -= dx / S * 0.5; s.y -= dy / S * 0.5; clampScroll(); applyScroll(); hideHover();
  }, { passive: false });
}

// ---------- drag a node onto a node or a tab ----------
let drag = null, tabHoverTimer = null;
function startNodeDrag(e, n) {
  if (e.button !== 0) return;
  e.stopPropagation();
  drag = { id: n.id, x: e.clientX, y: e.clientY, active: false, ghost: null, src: e.currentTarget };
  document.addEventListener('pointermove', onDragMove);
  document.addEventListener('pointerup', onDragEnd, { once: true });
}
function dropTargetAt(x, y) {
  const hit = document.elementFromPoint(x, y);
  if (!hit) return null;
  const node = hit.closest('#tree .node'); if (node) return { el: node, parent: node.dataset.node };
  const tab = hit.closest('.tab'); if (tab) return { el: tab, parent: tab.dataset.tab, tab: true };
  return null;
}
function onDragMove(e) {
  if (!drag) return;
  if (!drag.active) {
    if (Math.abs(e.clientX - drag.x) + Math.abs(e.clientY - drag.y) < 6) return;
    if (!layoutCache.E.editable || kindOf(drag.id) === 'other' || drag.id === DT_ROOT) return;
    drag.active = true; hideHover(); hideTip();
    const below = view.withChildren ? countDesc(layoutCache, drag.id) : 0;
    drag.ghost = el('ghost', { backgroundImage: `url(${IC[layoutCache.E.nodes[drag.id].i]})` }, document.body);
    if (below) drag.ghost.innerHTML = `<b>+${below}</b>`;
    markMoving();
  }
  drag.ghost.style.left = (e.clientX - 24) + 'px'; drag.ghost.style.top = (e.clientY - 24) + 'px';
  const t = dropTargetAt(e.clientX, e.clientY);
  document.querySelectorAll('.drop').forEach(x => x.classList.remove('drop'));
  clearTimeout(tabHoverTimer);
  if (t && t.parent !== drag.id) {
    t.el.classList.add('drop');
    if (t.tab && t.parent !== view.tabId) tabHoverTimer = setTimeout(() => { view.tabId = t.parent; render(); markMoving(); }, 450);
  }
}
function markMoving() {
  if (!drag) return;
  const ids = [drag.id]; if (view.withChildren) { const walk = id => (layoutCache.kids[id] || []).forEach(k => { ids.push(k); walk(k); }); walk(drag.id); }
  ids.forEach(id => { const m = document.querySelector(`#tree .node[data-node="${CSS.escape(id)}"]`); m && m.classList.add('moving'); });
}
function onDragEnd(e) {
  document.removeEventListener('pointermove', onDragMove);
  clearTimeout(tabHoverTimer);
  const d = drag; drag = null;
  document.querySelectorAll('.drop, .moving').forEach(x => x.classList.remove('drop', 'moving'));
  if (!d) return;
  if (d.ghost) d.ghost.remove();
  if (!d.active) { nodeClicked(d.id, d.src); return; }
  const t = dropTargetAt(e.clientX, e.clientY);
  if (t && t.parent !== d.id) reparent(d.id, t.parent);
}

function nodeClicked(id, anchorEl) {
  if (picking) {
    const who = picking; endPick();
    if (id !== who) reparent(who, id);
    openMenu(who);
    return;
  }
  openMenu(id, anchorEl);
}

// ---------- pick-on-screen ----------
function startPick(id) {
  picking = id; closeMenu(true);
  const b = document.getElementById('banner'); b.hidden = false;
  b.innerHTML = `<span>Click the new parent for “${esc(layoutCache.E.nodes[id].t)}”. You can switch tabs first.</span><button type="button" id="bCancelPick">Cancel</button>`;
  document.getElementById('bCancelPick').addEventListener('click', () => { const w = picking; endPick(); openMenu(w); });
}
function endPick() { picking = null; document.getElementById('banner').hidden = true; }
