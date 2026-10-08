// Advancement editor — hover box, toasts, the panels under the window, and start-up.
// ---------- hover box ----------
const measurer = document.createElement('span');
function textWidth(s) {
  measurer.style.cssText = `position:absolute;visibility:hidden;white-space:nowrap;font-family:var(--font-px);font-size:${8.5 * S}px`;
  measurer.textContent = s; document.body.appendChild(measurer);
  const w = measurer.getBoundingClientRect().width / S; measurer.remove(); return w;
}
function wrap(s, max) {
  const words = (s || '').split(/\s+/).filter(Boolean), lines = []; let cur = '';
  words.forEach(w => { const t = cur ? cur + ' ' + w : w; if (cur && textWidth(t) > max) { lines.push(cur); cur = w; } else cur = t; });
  if (cur) lines.push(cur); return lines;
}
function showHover(n) {
  if (document.getElementById('inside').classList.contains('drag')) return;
  const hover = document.getElementById('hover'), sc = curScroll(), desc = descOf(n);
  const tw = textWidth(n.t);
  let width = Math.max(tw + 37, 120);
  const all = [desc, n.hint || ''].join(' ');
  let probe = wrap(all, Math.max(width - 10, 150));
  if (probe.length > 1) width = Math.max(width, Math.min(200, Math.max(...probe.map(textWidth)) + 12));
  const dLines = wrap(desc, width - 10), hLines = wrap(n.hint || '', width - 10);
  const lines = dLines.concat(hLines);
  const wx = n.px + 3 + Math.round(sc.x), wy = n.py + Math.round(sc.y);
  const gx = gui.getBoundingClientRect().left;
  const flipX = gx + (IX + wx + width) * S > document.documentElement.clientWidth - 4;
  const descH = lines.length ? lines.length * 9 + 14 : 0;
  const flipY = wy + 26 + descH > IH + 10;
  const left = flipX ? wx + 26 - width : wx;
  hover.innerHTML = '';
  Object.assign(hover.style, { display: 'block', left: px(IX + left), top: px(28 + IY + wy - (flipY ? descH - 6 : 0)), width: px(width), height: px(26 + descH) });
  if (lines.length) {
    const d = el('desc', { top: flipY ? 0 : px(20), height: px(descH), color: n.f === 'challenge' ? 'var(--challenge)' : 'var(--task)' }, hover);
    const sp = document.createElement('span');
    sp.innerHTML = dLines.map(esc).join('<br>') + (hLines.length ? (dLines.length ? '<br>' : '') + `<span class="hint">${hLines.map(esc).join('<br>')}</span>` : '');
    d.appendChild(sp);
  }
  const barTop = flipY ? descH - 6 : 0;
  el('bar', { top: px(barTop), borderImage: `url(${SP[view.earned ? 'box_obtained' : 'box_unobtained']}) 3 fill stretch` }, hover);
  const ttl = el('ttl', { top: px(barTop + 9), left: px(flipX ? 6 : 32) }, hover); ttl.textContent = n.t;
  nodeEl(n, hover, flipX ? width - 26 : 0, barTop);
  document.getElementById('fade').classList.add('on');
}
function hideHover() {
  const h = document.getElementById('hover'); if (h) h.style.display = 'none';
  const f = document.getElementById('fade'); if (f) f.classList.remove('on');
}
function showTip(e, text, sub) {
  tip.innerHTML = ''; tip.append(text);
  if (sub) { const s = document.createElement('small'); s.textContent = sub; tip.appendChild(s); }
  tip.style.display = 'block';
  const r = tip.getBoundingClientRect();
  let x = e.clientX + 14; if (x + r.width > window.innerWidth - 8) x = e.clientX - r.width - 14;
  tip.style.left = x + 'px'; tip.style.top = Math.max(8, e.clientY - 18) + 'px';
}
function hideTip() { tip.style.display = 'none'; }

let toastTimer;
function toast(msg) {
  if (picking) return;
  const b = document.getElementById('banner');
  b.innerHTML = `<span>${esc(msg)}</span>${history.length && layoutCache && layoutCache.E.editable ? '<button type="button" id="bToastUndo">Undo</button>' : ''}`;
  b.hidden = false;
  const u = document.getElementById('bToastUndo'); u && u.addEventListener('click', undo);
  clearTimeout(toastTimer); toastTimer = setTimeout(() => { if (!picking) b.hidden = true; }, 5000);
}

// ---------- panels ----------
function renderControls(L) {
  const set = (id, label, val) => { document.getElementById(id).innerHTML = `${label}: <b>${val}</b>`; };
  set('bLayout', 'Layout', view.layout === 'proposed' ? 'With my edits' : 'As in the repo');
  set('bOther', 'Other mods’ tabs', view.other ? 'Shown' : 'Hidden');
  set('bMode', 'Game mode', view.creative ? 'Creative' : 'Survival');
  set('bEarned', 'Progress', view.earned ? 'Earned' : 'Not earned');
  set('bKids', 'Moves bring children', view.withChildren ? 'Yes' : 'No');
  document.getElementById('bUndo').disabled = !history.length || !L.E.editable;
  document.getElementById('bNewTab').disabled = !L.E.editable;
}
function renderChips(L, tabs) {
  const box = document.getElementById('chips'); box.innerHTML = '';
  const caps = Object.values(L.E.nodes).filter(n => n.cap);
  const need = caps.filter(n => n.cap.req).length, wiped = caps.filter(n => n.cap.reset).length;
  document.getElementById('burrito').textContent =
    `The Everything Burrito needs ${need} advancements. It\u2019s Not That Simple resets ${wiped}.`;
  L.tabs.forEach(t => {
    const vis = tabs.includes(t);
    const c = document.createElement('button'); c.type = 'button'; c.className = 'chip' + (vis ? '' : ' off');
    c.innerHTML = `<i style="background-image:url(${IC[t.icon]})"></i>${esc(t.title)} <span>${t.nodes.length}</span>`;
    c.title = vis ? 'Open this tab' : (t.kind === 'editor' ? 'Creative only' : 'Hidden by default');
    c.addEventListener('click', () => { if (t.kind === 'editor') view.creative = true; if (t.kind === 'other') view.other = true; view.tabId = t.id; saveView(); render(); });
    box.appendChild(c);
  });
}

function describeChanges(L) {
  const E = L.E, B = M.baseline, out = [];
  const name = id => (E.nodes[id] ? E.nodes[id].t : id.split(':')[1]);
  const place = p => p ? `under <em>${esc(name(p))}</em> (${esc(tabTitle(L, L.tabOf[p]))})` : '<em>its own tab</em>';
  Object.keys(edits.created).forEach(id => { if (!B.created[id] && E.nodes[id]) out.push({ id, html: `New ${E.parents[id] ? 'advancement' : 'tab'} <em>${esc(tabTitle(L, id) || name(id))}</em>${E.nodes[id].copyOf ? `, a copy of ${esc(name(E.nodes[id].copyOf))}` : ''}` }); });
  edits.deleted.forEach(id => out.push({ id: null, html: `Removed <em>${esc(B.created[id] && B.created[id].copyOf ? 'the copy of ' + name(B.created[id].copyOf) : id.split(':')[1])}</em>` }));
  Object.keys(edits.parents).forEach(id => {
    if (!E.nodes[id] || (edits.created[id] && !B.created[id])) return;
    const base = id in B.parents ? B.parents[id] : (M.gameParents[id] || '');
    if ((edits.parents[id] || '') !== (base || '')) out.push({ id, html: `Moved <em>${esc(name(id))}</em> ${place(edits.parents[id])}` });
  });
  Object.keys(edits.values || {}).forEach(id => E.nodes[id] && out.push({ id, html: `<em>${esc(name(id))}</em> now needs ${esc(formatReq(E.nodes[id].req))} (was ${esc(formatReq(M.nodes[id].req))})` }));
  Object.keys(edits.tabTitles).forEach(id => E.nodes[id] && out.push({ id, html: `Tab renamed to <em>${esc(edits.tabTitles[id])}</em>` }));
  Object.keys(edits.icons).forEach(id => E.nodes[id] && out.push({ id, html: `Icon of <em>${esc(name(id))}</em> is now ${esc(M.iconNames[edits.icons[id]])}` }));
  Object.keys(edits.bgs).forEach(id => E.nodes[id] && out.push({ id, html: `Background of <em>${esc(tabTitle(L, id))}</em> is now ${esc(bgName(edits.bgs[id]))}` }));
  Object.keys(edits.capstone || {}).forEach(id => {
    const n = E.nodes[id], base = M.nodes[id] && M.nodes[id].cap;
    if (!n || !n.cap || !base) return;
    const bits = [];
    if (n.cap.req !== base.req) bits.push(n.cap.req ? 'now counts towards the Everything Burrito' : 'no longer counts towards the Everything Burrito');
    if (n.cap.reset !== base.reset) bits.push(n.cap.reset ? 'is now reset by It\u2019s Not That Simple' : 'is now kept by It\u2019s Not That Simple');
    if (bits.length) out.push({ id, html: `<em>${esc(name(id))}</em> ${bits.join(' and ')}` });
  });
  Object.keys(edits.unlocks || {}).forEach(id => {
    const n = E.nodes[id]; if (!n) return;
    out.push({ id, html: n.unlockedBy ? `<em>${esc(tabTitle(L, id) || name(id))}</em> now unlocks with <em>${esc(name(n.unlockedBy))}</em>` : `<em>${esc(tabTitle(L, id) || name(id))}</em> no longer unlocks with another advancement` });
  });
  Object.keys(edits.texts || {}).forEach(id => E.nodes[id] && out.push({ id, html: `Text of <em>${esc(name(id))}</em> changed` }));
  if (edits.order) out.push({ id: null, html: `Tab order: ${edits.order.filter(i => E.nodes[i] && !E.parents[i]).map(i => esc(tabTitle(L, i))).join(' · ')}` });
  return out;
}
function renderChanges(L) {
  const ul = document.getElementById('changes'); ul.innerHTML = '';
  if (!L.E.editable) { ul.innerHTML = '<li><span>Showing the layout as it is in the repo. Switch Layout to With my edits to see and make edits.</span></li>'; return; }
  const ch = describeChanges(L);
  if (!ch.length) { ul.innerHTML = '<li><span>No edits yet. Click an advancement to change it.</span></li>'; return; }
  ch.forEach(c => {
    const li = document.createElement('li'); li.innerHTML = `<span>${c.html}</span>`;
    if (c.id && L.tabOf[c.id]) {
      const b = document.createElement('button'); b.type = 'button'; b.textContent = 'Show';
      b.addEventListener('click', () => { const t = L.tabOf[c.id]; if (kindOf(t) === 'editor') view.creative = true; view.tabId = t; render(); gui.scrollIntoView({ block: 'center' }); setTimeout(() => openMenu(c.id), 50); });
      li.appendChild(b);
    }
    ul.appendChild(li);
  });
}

function undo() { if (!history.length) return; edits = history.pop(); queueSave(); render(); toast('Undone.'); }

const toggle = (k, fn) => document.getElementById(k).addEventListener('click', () => { fn(); saveView(); closeMenu(); render(); });
toggle('bLayout', () => { view.layout = view.layout === 'proposed' ? 'current' : 'proposed'; });
toggle('bOther', () => { view.other = !view.other; });
toggle('bMode', () => { view.creative = !view.creative; });
toggle('bEarned', () => { view.earned = !view.earned; });
document.getElementById('bKids').addEventListener('click', () => { view.withChildren = !view.withChildren; saveView(); renderControls(layoutCache); if (selected && !menu.hidden) fillMenu(selected); });
document.getElementById('bUndo').addEventListener('click', undo);
document.getElementById('bNewTab').addEventListener('click', newTab);
document.getElementById('bReset').addEventListener('click', () => {
  const b = document.getElementById('bReset');
  if (b.dataset.armed) { delete b.dataset.armed; b.textContent = 'Discard edits'; commit(e => Object.assign(e, emptyEdits()), 'All edits cleared. Undo brings them back.'); closeMenu(); return; }
  b.dataset.armed = '1'; b.innerHTML = '<b>Click again to clear all edits</b>';
  setTimeout(() => { if (b.dataset.armed) { delete b.dataset.armed; b.textContent = 'Discard edits'; } }, 4000);
});
document.getElementById('bCopy').addEventListener('click', async () => {
  const text = JSON.stringify(exportChanges(), null, 2);
  try { await navigator.clipboard.writeText(text); toast('Copied your changes as JSON. apply.py writes them into the repo.'); }
  catch (e) {
    const ta = document.createElement('textarea'); ta.value = text; ta.style.cssText = 'position:fixed;left:16px;bottom:80px;height:40vh;z-index:50;width:calc(100vw - 32px);font:12px monospace;background:#000;color:#fff';
    document.body.appendChild(ta); ta.select(); toast('Copy the selected JSON, then click anywhere.');
    setTimeout(() => document.addEventListener('pointerdown', function rm(ev) { if (ev.target !== ta) { ta.remove(); document.removeEventListener('pointerdown', rm); } }), 0);
  }
});
document.addEventListener('keydown', e => {
  if (e.key === 'Escape') {
    if (picking) { const w = picking; endPick(); openMenu(w); }
    else if (!menu.hidden) closeMenu();
    else if (document.body.classList.contains('full')) setFull(false);
  }
  if ((e.metaKey || e.ctrlKey) && e.key === 'z' && !/INPUT|TEXTAREA|SELECT/.test((document.activeElement || {}).tagName || '')) { e.preventDefault(); undo(); }
});
document.addEventListener('pointerdown', e => {
  if (menu.hidden || picking) return;
  if (menu.contains(e.target) || e.target.closest('#gui') || e.target.closest('#banner')) return;
  closeMenu();
});
window.addEventListener('scroll', () => { if (!menu.hidden && selected) { const a = document.querySelector(`#tree .node[data-node="${CSS.escape(selected)}"]`); if (a) { anchorRect = a.getBoundingClientRect(); placeMenu(); } } }, { passive: true });
let rt; window.addEventListener('resize', () => { clearTimeout(rt); rt = setTimeout(() => { render(); if (!menu.hidden) placeMenu(); }, 120); });
// Full screen: a CSS overlay (works inside an embedded page too), plus the browser's own full screen
// when it is allowed. Leaving either leaves both.
function setFull(on) {
  document.body.classList.toggle('full', on);
  document.getElementById('bFull').setAttribute('aria-pressed', String(on));
  if (on && document.documentElement.requestFullscreen && !document.fullscreenElement) {
    document.documentElement.requestFullscreen().catch(() => {});
  } else if (!on && document.fullscreenElement && document.exitFullscreen) {
    document.exitFullscreen().catch(() => {});
  }
  closeMenu();
  render();
  setTimeout(render, 350); // again once the browser's own full screen has settled its size
}
document.getElementById('bFull').addEventListener('click', () => setFull(true));
document.getElementById('bExitFull').addEventListener('click', () => setFull(false));
document.addEventListener('fullscreenchange', () => { if (!document.fullscreenElement && document.body.classList.contains('full')) setFull(false); });
const bSave = document.getElementById('bSave');
bSave.hidden = !LOCAL;
bSave.addEventListener('click', saveToRepo);
showSaveReport();
(document.fonts ? document.fonts.ready : Promise.resolve()).then(() => render());
connect();

/** After Save to repo reloads the page: say what was written, and what still needs a person. */
function showSaveReport() {
  let report = null;
  try { report = JSON.parse(sessionStorage.getItem('dt-adv-report') || 'null'); sessionStorage.removeItem('dt-adv-report'); } catch (e) {}
  if (!report) return;
  const box = document.getElementById('report');
  const files = report.written || [], todo = report.todo || [];
  box.innerHTML = `<h2>Saved to the repo</h2><p>${files.length} file${files.length === 1 ? '' : 's'} changed. Review them with <b>git diff</b>.</p>`
    + (todo.length ? `<p>Still to do by hand:</p><ul class="notes">${todo.map(t => `<li>${esc(t)}</li>`).join('')}</ul>` : '');
  box.hidden = false;
}
