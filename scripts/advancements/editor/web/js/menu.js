// Advancement editor — the edit menu opened by clicking an advancement.
// ---------- edit menu ----------
let anchorRect = null;
function openMenu(id, anchorEl) {
  selected = id;
  const a = anchorEl && anchorEl.isConnected ? anchorEl : document.querySelector(`#tree .node[data-node="${CSS.escape(id)}"]`);
  anchorRect = a ? a.getBoundingClientRect() : null;
  document.querySelectorAll('#tree .node.sel').forEach(x => x.classList.remove('sel'));
  a && a.classList.add('sel');
  hideHover();
  fillMenu(id);
  menu.hidden = false;
  menu.scrollTop = 0;
  placeMenu();
}
function closeMenu(keepSel) {
  menu.hidden = true;
  if (!keepSel) { selected = null; document.querySelectorAll('#tree .node.sel').forEach(x => x.classList.remove('sel')); }
}
function placeMenu() {
  const vw = document.documentElement.clientWidth, vh = window.innerHeight;
  const r = anchorRect || gui.getBoundingClientRect();
  const mw = menu.offsetWidth, mh = menu.offsetHeight;
  let x = r.right + 12, y = r.top - 10;
  if (x + mw > vw - 16) x = r.left - mw - 12;
  if (x < 16) { x = Math.max(16, Math.min(vw - mw - 16, r.left)); y = r.bottom + 10; }
  y = Math.max(16, Math.min(vh - mh - 16, y));
  menu.style.left = x + 'px'; menu.style.top = y + 'px';
}

function fillMenuRaw(id) {
  const L = layoutCache, E = L.E, n = E.nodes[id];
  const isRoot = !E.parents[id], tabRoot = L.tabOf[id], kind = kindOf(id);
  const editable = E.editable && kind !== 'other';
  const copies = Object.keys(E.nodes).filter(k => E.nodes[k].copyOf === id);
  const below = countDesc(L, id);
  let h = `<div class="head"><div class="slot"><i style="background-image:url(${IC[n.i]})"></i></div>
    <div><h3>${esc(n.t)}</h3><div class="id">${esc(id)}</div></div>
    <button class="x" type="button" data-act="close" aria-label="Close">×</button></div>`;
  h += `<div class="note">${n.d ? esc(descOf(n)) + '<br>' : ''}${n.hint ? `<span style="color:#6b6b6b">Hint: ${esc(n.hint)}</span><br>` : ''}Tab: <b>${esc(tabTitle(L, tabRoot))}</b>${below ? ` · ${below} below it` : ''}${n.h ? ' · hidden until earned' : ''}</div>`;
  if (n.chain) h += `<div class="note">Band journey: when the game loads, this chain is put in band order. Only the first band\u2019s parent comes from here.</div>`;
  if (n.copyOf) h += `<div class="note">Copy of <b>${esc(E.nodes[n.copyOf] ? E.nodes[n.copyOf].t : n.copyOf)}</b> in ${esc(tabTitle(L, L.tabOf[n.copyOf]))}. Earning either earns both. Edit its value on the original.</div>`;
  if (copies.length) h += `<div class="note">A copy heads ${copies.map(c => `<b>${esc(tabTitle(L, L.tabOf[c]))}</b>`).join(', ')}. Earning this unlocks that tab.</div>`;
  if (!E.editable) h += `<div class="note">This is the layout as it is in the repo. Switch Layout to With my edits to edit.</div>`;
  else if (kind === 'other') h += `<div class="note">Vanilla advancement. Only Dungeon Train advancements can be edited.</div>`;
  if (editable) {
    if (id !== DT_ROOT) {
      const tabsOpts = L.tabs.filter(t => t.kind !== 'other').map(t => `<option value="${esc(t.id)}" ${t.id === tabRoot ? 'selected' : ''}>${esc(t.title)}</option>`).join('');
      h += `<div class="sec"><span>Move</span>
        ${below ? `<label class="check"><input type="checkbox" id="mKids" ${view.withChildren ? 'checked' : ''}> Bring its children (${below})</label>` : ''}
        <label for="mTab">Tab</label><select id="mTab">${tabsOpts}</select>
        <label for="mParent">Parent</label><select id="mParent">${parentOptions(L, id)}</select>
        <div class="row"><button class="mcbtn" type="button" data-act="pick">Pick parent on screen</button>${isRoot ? '' : '<button class="mcbtn" type="button" data-act="ownTab">Make it a tab</button>'}<button class="mcbtn" type="button" data-act="copyTab">Open a copy as a new tab</button></div>
        <div class="hint">Moving to a tab puts it straight under that tab’s first advancement.</div></div>`;
    }
    if (n.req && !n.copyOf) {
      h += `<div class="sec"><span>Required</span><div class="req"><input type="number" id="mReq" min="1" step="${n.req.unit === 'ticks' ? 'any' : 1}" value="${reqToInput(n.req)}" aria-label="Value required"><span>${reqUnitLabel(n.req)}</span></div>
        <div class="hint">Shows as “${esc(formatReq(n.req))}”. Shipped value ${esc(formatReq(M.nodes[id].req))}.</div></div>`;
    }
    if (isRoot) {
      const ord = L.tabs.filter(t => t.kind !== 'other').map(t => t.id), oi = ord.indexOf(id);
      h += `<div class="sec"><span>Tab</span><label for="mTabTitle">Tab name (blank uses the advancement title)</label>
        <input type="text" id="mTabTitle" value="${esc(E.titles[id] || '')}" placeholder="${esc(n.t)}" maxlength="40">
        <label>Background</label><div class="bgs">${bgSwatches(n.bg)}<button type="button" class="more" data-act="moreBgs" aria-expanded="${moreBgsOpen}" title="More backgrounds" aria-label="More backgrounds">+</button></div>
          ${moreBgsOpen ? `<input type="text" id="mBgSearch" placeholder="Search ${Object.keys(M.moreBgs).length} block textures" value="${esc(bgQuery)}"><div class="bgs more-list" id="mBgMore">${moreBgButtons(n.bg)}</div>` : ''}
        ${id === DT_ROOT ? '' : `<div class="row"><button class="mcbtn" type="button" data-act="tabLeft" ${oi <= 1 ? 'disabled' : ''}>← Move tab</button><button class="mcbtn" type="button" data-act="tabRight" ${oi >= ord.length - 1 ? 'disabled' : ''}>Move tab →</button></div>`}</div>`;
    }
    if (n.cap) h += capstoneSection(n);
    if (n.created && !n.copyOf) {
      h += `<div class="sec"><span>Text</span><label for="mTitle">Title</label><input type="text" id="mTitle" value="${esc(n.t)}" maxlength="60">
        <label for="mDesc">Description</label><textarea id="mDesc" maxlength="200">${esc(n.d)}</textarea></div>`;
    }
    h += `<div class="sec"><span>Icon</span><div class="icons" id="mIcons">${IC.map((u, i) => `<button type="button" data-icon="${i}" class="${i === n.i ? 'on' : ''}" title="${esc(M.iconNames[i])}" aria-label="${esc(M.iconNames[i])}"><i style="background-image:url(${u})"></i></button>`).join('')}</div></div>`;
    const touched = ['parents', 'icons', 'tabTitles', 'bgs', 'texts', 'values', 'capstone'].some(k => edits[k] && id in edits[k]) || id in edits.created;
    if (touched || n.created) h += `<div class="row">${touched ? '<button class="mcbtn" type="button" data-act="reset">Undo my edits to this</button>' : ''}${n.created ? '<button class="mcbtn" type="button" data-act="delete">Delete</button>' : ''}</div>`;
  }
  menu.innerHTML = h;
  wireMenu(id);
}
function fillMenu(id) {
  const a = document.activeElement, fid = a && menu.contains(a) ? a.id : null;
  const sel = a && a.tagName !== 'SELECT' && typeof a.selectionStart === 'number' ? [a.selectionStart, a.selectionEnd] : null;
  const scroll = menu.scrollTop, iconScroll = (menu.querySelector('#mIcons') || {}).scrollTop;
  fillMenuRaw(id);
  menu.scrollTop = scroll;
  const ic = menu.querySelector('#mIcons'); if (ic && iconScroll) ic.scrollTop = iconScroll;
  if (fid) { const f = document.getElementById(fid); if (f) { f.focus(); if (sel && f.setSelectionRange) try { f.setSelectionRange(sel[0], sel[1]); } catch (e) {} } }
  if (!menu.hidden) placeMenu();
}

function parentOptions(L, id) {
  const E = L.E, cur = E.parents[id] || '';
  const wk = view.withChildren;
  let o = `<option value="" ${cur ? '' : 'selected'}>None: it heads its own tab</option>`;
  L.tabs.filter(t => t.kind !== 'other').forEach(t => {
    o += `<optgroup label="${esc(t.title)}">`;
    t.nodes.slice().sort((a, b) => a.y - b.y || a.x - b.x).forEach(n => {
      if (n.id === id || (wk && isDescendant(L, id, n.id))) return;
      o += `<option value="${esc(n.id)}" ${n.id === cur ? 'selected' : ''}>${'  '.repeat(Math.min(n.x, 8))}${esc(n.t)}</option>`;
    });
    o += '</optgroup>';
  });
  return o;
}

function wireMenu(id) {
  const on = (sel, ev, fn) => { const x = menu.querySelector(sel); x && x.addEventListener(ev, fn); };
  menu.querySelectorAll('[data-act]').forEach(b => b.addEventListener('click', () => {
    const a = b.dataset.act;
    if (a === 'close') closeMenu();
    else if (a === 'pick') startPick(id);
    else if (a === 'ownTab') reparent(id, '');
    else if (a === 'copyTab') openCopyAsTab(id);
    else if (a === 'tabLeft') moveTab(id, -1);
    else if (a === 'tabRight') moveTab(id, 1);
    else if (a === 'reset') resetNode(id);
    else if (a === 'delete') deleteCreated(id);
    else if (a === 'moreBgs') { moreBgsOpen = !moreBgsOpen; fillMenu(id); if (moreBgsOpen) { const q = menu.querySelector('#mBgSearch'); q && q.focus(); } }
  }));
  on('#mKids', 'change', e => { view.withChildren = e.target.checked; saveView(); renderControls(layoutCache); fillMenu(id); });
  on('#mParent', 'change', e => reparent(id, e.target.value));
  const capEdit = (key, label) => e => { const v = e.target.checked; commit(ed => { ed.capstone = ed.capstone || {}; ed.capstone[id] = { ...(ed.capstone[id] || {}), [key]: v }; }, label(v)); };
  on('#mCapReq', 'change', capEdit('req', v => v ? 'Now counts towards the Everything Burrito.' : 'No longer counts towards the Everything Burrito.'));
  on('#mCapReset', 'change', capEdit('reset', v => v ? 'Now reset by It\u2019s Not That Simple.' : 'Now kept by It\u2019s Not That Simple.'));
  on('#mTab', 'change', e => moveToTab(id, e.target.value));
  let tt;
  on('#mTabTitle', 'input', e => { clearTimeout(tt); const v = e.target.value.trim(); tt = setTimeout(() => commit(ed => { if (v) ed.tabTitles[id] = v; else delete ed.tabTitles[id]; }), 400); });
  const textEdit = field => e => { clearTimeout(tt); const v = e.target.value; tt = setTimeout(() => commit(ed => { ed.texts = ed.texts || {}; ed.texts[id] = { ...(ed.texts[id] || {}), [field]: v }; }), 400); };
  on('#mTitle', 'input', textEdit('t'));
  on('#mDesc', 'input', textEdit('d'));
  on('#mReq', 'input', e => {
    clearTimeout(tt);
    const v = parseFloat(e.target.value);
    if (!(v > 0)) return;
    const req = M.nodes[id].req;
    tt = setTimeout(() => commit(ed => { ed.values = ed.values || {}; const raw = inputToReq(req, v); if (raw === req.n) delete ed.values[id]; else ed.values[id] = raw; }), 450);
  });
  menu.querySelectorAll('[data-icon]').forEach(b => b.addEventListener('click', () => commit(ed => { ed.icons[id] = +b.dataset.icon; }, `Icon set to ${M.iconNames[+b.dataset.icon]}.`)));
  const wireBgs = root => root.querySelectorAll('[data-bg]').forEach(b => b.addEventListener('click', () => commit(ed => { ed.bgs[id] = b.dataset.bg; }, `Background set to ${bgName(b.dataset.bg)}.`)));
  wireBgs(menu);
  on('#mBgSearch', 'input', e => { bgQuery = e.target.value; const box = menu.querySelector('#mBgMore'); box.innerHTML = moreBgButtons(layoutCache.E.nodes[id].bg); wireBgs(box); });
}

// ---------- background picker ----------
let moreBgsOpen = false, bgQuery = '';
function bgButton(k, uri, current) {
  const name = esc(bgName(k));
  return `<button type="button" title="${name}" aria-label="${name}" data-bg="${esc(k)}" class="${k === current ? 'on' : ''}" style="background-image:url(${uri})"></button>`;
}
function bgSwatches(current) {
  const keys = Object.keys(M.bgs);
  if (current && !M.bgs[current]) keys.push(current); // a picked block texture stays visible as a swatch
  return keys.map(k => bgButton(k, bgUri(k), current)).join('');
}
function moreBgButtons(current) {
  const q = bgQuery.trim().toLowerCase();
  const keys = Object.keys(M.moreBgs).filter(k => !q || bgName(k).includes(q));
  if (!keys.length) return '<span class="hint">No block texture matches.</span>';
  return keys.map(k => bgButton(k, M.moreBgs[k], current)).join('');
}

// ---------- Everything Burrito ----------
function capstoneSection(n) {
  const c = n.cap, dis = c.ed ? '' : ' disabled';
  const why = c.ed ? '' : `<div class="hint">${n.copyOf ? 'A tab copy never counts: its original does.' : 'Fixed for the capstone itself, its reward and the Editor tab.'}</div>`;
  return `<div class="sec"><span>Everything Burrito</span>
    <label class="check"><input type="checkbox" id="mCapReq"${c.req ? ' checked' : ''}${dis}> Counts towards Everything Burrito</label>
    <label class="check"><input type="checkbox" id="mCapReset"${c.reset ? ' checked' : ''}${dis}> Reset by It\u2019s Not That Simple</label>${why}</div>`;
}
