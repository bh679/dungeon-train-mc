// Advancement editor — model: data, the effective layout, edits and saving. Concatenated by build.py into one closure.
const M = window.MOCK, SP = M.sprites, IC = M.icons;
const DT_ROOT = 'dungeontrain:dungeon_train/root';
const TICKS_PER_HOUR = 72000;
/** Served by serve.py: edits are written to the working tree, not to an artifact database. */
const LOCAL = window.EDITOR_MODE === 'local';
const EDITS_KEY = LOCAL ? 'dt-adv-edits-local' : 'dt-adv-edits';
const rootEl = document.documentElement;
rootEl.style.setProperty('--dirt', `url(${SP.dirt})`);
rootEl.style.setProperty('--btn', `url(${SP.button})`);
rootEl.style.setProperty('--btn-hi', `url(${SP.button_highlighted})`);
rootEl.style.setProperty('--titlebox', `url(${SP.title_box})`);

const IX = 9, IY = 18;
let W = 252, H = 140, IW = 234, IH = 113, PER_ROW = 8;
const emptyEdits = () => ({ parents: {}, created: {}, deleted: [], icons: {}, tabTitles: {}, bgs: {}, texts: {}, values: {}, capstone: {}, unlocks: {}, visibility: {}, order: null });
/** Edit maps keyed by advancement id — what "Undo my edits to this" clears. */
const EDIT_KEYS = ['parents', 'icons', 'tabTitles', 'bgs', 'texts', 'values', 'capstone', 'unlocks', 'visibility'];
const view = { layout: 'proposed', other: false, creative: false, earned: true, tabId: DT_ROOT, withChildren: true };
try { Object.assign(view, JSON.parse(localStorage.getItem('dt-adv-view') || '{}')); } catch (e) {}
const saveView = () => { try { localStorage.setItem('dt-adv-view', JSON.stringify(view)); } catch (e) {} };

let edits = emptyEdits(), history = [], selected = null, picking = null;
let S = 3, scrolls = {}, bounds = null, layoutCache = null;
const gui = document.getElementById('gui'), tip = document.getElementById('tip'), menu = document.getElementById('menu');
const px = v => `${v * S}px`;
const esc = s => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/"/g, '&quot;');
const el = (cls, css, parent) => { const d = document.createElement('div'); d.className = cls; Object.assign(d.style, css || {}); parent && parent.appendChild(d); return d; };
const clone = o => JSON.parse(JSON.stringify(o));
const kindOf = id => id.startsWith('dungeontrain:editor') ? 'editor' : (id.startsWith('dungeontrain:') ? 'dt' : 'other');
const STONE_BG = 'minecraft:textures/gui/advancements/backgrounds/stone.png';
/** Data URI for a background resource path, from the swatches or the full block list. */
const bgUri = k => M.bgs[k] || M.moreBgs[k] || M.bgs[STONE_BG];
/** "stone bricks" from "minecraft:textures/block/stone_bricks.png". */
const bgName = k => (k || '').split('/').pop().replace(/\.png$/, '').replace(/_/g, ' ');
const grouped = n => Number(n).toLocaleString('en-US');

// ---------- required values ----------
function formatReq(req) {
  if (req.unit !== 'ticks') return grouped(req.n);
  const hours = req.n / TICKS_PER_HOUR;
  if (hours >= 24 && hours % 24 === 0) { const d = hours / 24; return `${grouped(d)} ${d === 1 ? 'day' : 'days'}`; }
  const h = Math.round(hours * 100) / 100;
  return `${grouped(h)} ${h === 1 ? 'hour' : 'hours'}`;
}
const descOf = n => n.req ? (n.d || '').replace(/%(\d+\$)?s/g, formatReq(n.req)) : (n.d || '');
const reqUnitLabel = req => ({ count: 'needed', metres: 'metres', ticks: 'hours' })[req.unit];
const reqToInput = req => req.unit === 'ticks' ? req.n / TICKS_PER_HOUR : req.n;
const inputToReq = (req, v) => req.unit === 'ticks' ? Math.round(v * TICKS_PER_HOUR) : Math.round(v);

// ---------- model ----------
function effective() {
  if (view.layout === 'current') {
    const nodes = {}; Object.keys(M.nodes).forEach(id => { nodes[id] = { ...M.nodes[id] }; });
    return { nodes, parents: { ...M.gameParents }, order: M.gameOrder.slice(), titles: {}, editable: false };
  }
  const B = M.baseline;
  const deleted = new Set(edits.deleted);
  const created = { ...B.created, ...edits.created };
  Object.keys(created).forEach(k => { if (deleted.has(k)) delete created[k]; });
  const nodes = {};
  Object.keys(M.nodes).forEach(id => { nodes[id] = { ...M.nodes[id] }; });
  const values = edits.values || {};
  Object.keys(values).forEach(id => { if (nodes[id] && nodes[id].req) nodes[id].req = { ...nodes[id].req, n: values[id] }; });
  const texts = edits.texts || {};
  const resolve = (id, seen) => {
    if (nodes[id]) return nodes[id];
    const c = created[id]; if (!c || seen.has(id)) return null; seen.add(id);
    let n;
    if (c.copyOf) { const src = resolve(c.copyOf, seen); if (!src) return null; n = { ...src, h: true, copyOf: c.copyOf, bg: c.bg || src.bg }; }
    else n = { t: c.t, d: c.d || '', f: c.f || 'task', i: c.i, h: !!c.unlockedBy, bg: c.bg, unlockedBy: c.unlockedBy || undefined, trig: 'impossible', ...(texts[id] || {}) };
    n.created = true; nodes[id] = n; return n;
  };
  Object.keys(created).forEach(id => resolve(id, new Set()));
  const icons = { ...B.icons, ...edits.icons };
  Object.keys(icons).forEach(id => { if (nodes[id]) nodes[id].i = icons[id]; });
  Object.keys(nodes).forEach(id => {
    const n = nodes[id]; if (!n.copyOf || !nodes[n.copyOf]) return;
    const s = nodes[n.copyOf]; n.t = s.t; n.d = s.d; n.f = s.f; n.req = s.req; n.hint = s.hint; if (!(id in icons) && !M.nodes[id]) n.i = s.i; // a saved copy keeps its own icon
  });
  const unlocks = edits.unlocks || {};
  Object.keys(unlocks).forEach(id => { if (nodes[id]) { nodes[id] = { ...nodes[id], unlockedBy: unlocks[id] || undefined }; } });
  // Everything Burrito / It's Not That Simple. Reset follows "counts" unless it was set on its own.
  const caps = edits.capstone || {};
  Object.keys(nodes).forEach(id => {
    const n = nodes[id];
    if (n.created && !n.cap) n.cap = n.copyOf || n.unlockedBy ? { req: false, reset: false, ed: false } : { req: true, reset: true, ed: true };
    if (n.unlockedBy && n.cap) n.cap = { req: false, reset: false, ed: false }; // follows its source, never counts
    if (!n.cap) return;
    const e = caps[id] || {}, base = n.cap;
    const req = 'req' in e ? e.req : base.req;
    const reset = 'reset' in e ? e.reset : (base.reset === base.req ? req : base.reset);
    n.cap = { ...base, req, reset };
  });
  const bgs = { ...B.bgs, ...edits.bgs };
  Object.keys(bgs).forEach(id => { if (nodes[id]) nodes[id].bg = bgs[id]; });
  const parents = { ...M.gameParents, ...B.parents, ...edits.parents };
  Object.keys(parents).forEach(id => { if (!nodes[id]) delete parents[id]; else if (parents[id] && !nodes[parents[id]]) parents[id] = ''; });
  Object.keys(nodes).forEach(id => { if (!(id in parents)) parents[id] = ''; });
  // Visibility: the editor's mode, else the one saved in the repo, else the default for where it sits now.
  const visEdits = edits.visibility || {};
  Object.keys(nodes).forEach(id => {
    if (kindOf(id) === 'other') return;
    const n = nodes[id], base = M.nodes[id];
    const visDef = parents[id] ? 'parent' : (n.h ? 'earned' : 'always');
    const saved = base && base.vis && base.vis !== base.visDef ? base.vis : null;
    let vis = visEdits[id] || saved || visDef;
    if (!parents[id] && vis === 'parent') vis = visDef; // a tab head has no parent to wait for
    nodes[id] = { ...n, visDef, vis };
  });
  const order = (edits.order || B.order).filter(id => nodes[id] && !parents[id]);
  return { nodes, parents, order, titles: { ...B.tabTitles, ...edits.tabTitles }, editable: true };
}

function computeLayout() {
  const E = effective();
  const kids = {};
  Object.keys(E.parents).forEach(id => { const p = E.parents[id]; (kids[p] = kids[p] || []).push(id); });
  Object.values(kids).forEach(a => a.sort());
  const roots = (kids[''] || []).slice();
  const dtExtra = roots.filter(r => kindOf(r) !== 'other' && !E.order.includes(r)).sort();
  const vanilla = M.gameOrder.filter(r => kindOf(r) === 'other' && roots.includes(r));
  const allRoots = [...E.order.filter(r => roots.includes(r)), ...dtExtra, ...vanilla];
  const tabOf = {};
  const tabs = allRoots.map(r => {
    const nodes = []; let row = 0;
    const place = (id, depth) => {
      const ys = (kids[id] || []).map(c => place(c, depth + 1));
      const y = ys.length ? ys[0] : row; if (!ys.length) row++;
      nodes.push({ ...E.nodes[id], id, p: E.parents[id], x: depth, y });
      tabOf[id] = r;
      return y;
    };
    place(r, 0);
    const rn = E.nodes[r];
    return { id: r, title: E.titles[r] || rn.t, icon: rn.i, bg: rn.bg || STONE_BG, nodes, kind: kindOf(r) };
  });
  return { E, tabs, tabOf, kids };
}
const visibleTabs = L => L.tabs.filter(t => t.kind === 'dt' || (t.kind === 'editor' && view.creative) || (t.kind === 'other' && view.other));
const tabTitle = (L, rootId) => { const t = L.tabs.find(t => t.id === rootId); return t ? t.title : ''; };
const countDesc = (L, id) => { let c = 0; (L.kids[id] || []).forEach(k => { c += 1 + countDesc(L, k); }); return c; };

// ---------- edits ----------
function commit(fn, message) {
  history.push(clone(edits));
  if (history.length > 100) history.shift();
  fn(edits);
  queueSave();
  render();
  if (message) toast(message);
}
function isDescendant(L, id, maybeChild) { let p = maybeChild; while (p) { if (p === id) return true; p = L.E.parents[p]; } return false; }

function canReparent(L, id, parent, withChildren) {
  if (!L.E.editable) return 'Switch Layout to With my edits to edit.';
  if (id === DT_ROOT) return 'Dungeon Train stays the first tab.';
  if (kindOf(id) === 'other') return 'Vanilla advancements can’t be edited.';
  if (parent && kindOf(parent) === 'other') return 'Dungeon Train advancements can’t go into a vanilla tab.';
  if (parent === id) return 'It can’t sit under itself.';
  if (parent && withChildren && isDescendant(L, id, parent)) return 'That is one of its own children. Untick “Bring its children” to move it there alone.';
  if (!withChildren && !L.E.parents[id] && (L.kids[id] || []).length) return 'This heads a tab, so its children need it. Tick “Bring its children”.';
  return null;
}

function reparent(id, parent, withChildren) {
  if (withChildren === undefined) withChildren = view.withChildren;
  const L = layoutCache, err = canReparent(L, id, parent, withChildren);
  if (err) { toast(err); return; }
  if ((L.E.parents[id] || '') === (parent || '')) return;
  const n = L.E.nodes[id], oldParent = L.E.parents[id] || '';
  const kids = (L.kids[id] || []).slice();
  const where = parent ? `under “${L.E.nodes[parent].t}” in ${tabTitle(L, L.tabOf[parent])}` : 'into its own tab';
  const extra = kids.length ? (withChildren ? ` with ${countDesc(L, id)} below it` : `; its ${kids.length} ${kids.length === 1 ? 'child stays' : 'children stay'} behind`) : '';
  commit(e => {
    if (!withChildren) kids.forEach(k => { e.parents[k] = (k === parent) ? oldParent : oldParent; });
    // moving it under its own child without the children: that child first steps up to the old parent
    e.parents[id] = parent || '';
    if (!parent) { const ord = (e.order || M.baseline.order).slice(); if (!ord.includes(id)) ord.push(id); e.order = ord; }
  }, `Moved “${n.t}” ${where}${extra}.`);
}

function moveToTab(id, tabRoot) {
  if (!tabRoot) return;
  if (layoutCache.tabOf[id] === tabRoot) { toast('It is already in that tab.'); return; }
  reparent(id, tabRoot);
}

const slugify = s => s.toLowerCase().replace(/[^a-z0-9]+/g, '_').replace(/^_|_$/g, '') || 'tab';
/** Tab heads live under dungeon_train/ so they get Dungeon Train's tab visibility rules. */
const TAB_PREFIX = 'dungeontrain:dungeon_train/tab_';
const uniqueId = (taken, base) => { let id = base, n = 2; while (taken(id)) id = `${base}_${n++}`; return id; };
const tabIdFor = (L, name) => uniqueId(id => !!L.E.nodes[id], TAB_PREFIX + slugify(name));

function openCopyAsTab(id) {
  const L = layoutCache, n = L.E.nodes[id];
  const rootId = tabIdFor(L, n.t);
  commit(e => {
    e.created[rootId] = { copyOf: id, bg: STONE_BG };
    e.parents[rootId] = '';
    e.order = [...(e.order || M.baseline.order).filter(x => x !== rootId), rootId];
  }, `Made a tab headed by a copy of “${n.t}”. Move advancements into it.`);
  view.tabId = rootId; saveView(); render(); openMenu(rootId);
}

function newTab() {
  const L = layoutCache;
  if (!L.E.editable) { toast('Switch Layout to With my edits to edit.'); return; }
  const rootId = tabIdFor(L, 'new');
  const icon = Math.max(0, M.iconNames.indexOf('knowledge book'));
  commit(e => {
    e.created[rootId] = { t: 'New Tab', d: 'Describe this tab', f: 'task', i: icon, bg: STONE_BG, unlockedBy: '' };
    e.parents[rootId] = '';
    e.order = [...(e.order || M.baseline.order), rootId];
  }, 'Added a new tab. Name it, pick what unlocks it, then move advancements into it.');
  view.tabId = rootId; saveView(); render(); openMenu(rootId);
}

function deleteCreated(id) {
  const L = layoutCache;
  if ((L.kids[id] || []).length) { toast('Move its advancements somewhere else first.'); return; }
  if (Object.keys(L.E.nodes).some(k => L.E.nodes[k].copyOf === id)) { toast('Delete its copies first.'); return; }
  const t = L.E.nodes[id].t;
  commit(e => {
    EDIT_KEYS.forEach(k => { if (e[k]) delete e[k][id]; });
    delete e.created[id];
    if (M.baseline.created[id] && !e.deleted.includes(id)) e.deleted.push(id);
    if (e.order) e.order = e.order.filter(x => x !== id);
  }, `Deleted “${t}”.`);
  closeMenu();
}

function moveTab(id, dir) {
  const L = layoutCache;
  const ord = L.tabs.filter(t => t.kind !== 'other').map(t => t.id);
  const i = ord.indexOf(id), j = i + dir;
  if (i < 1 || j < 1 || j >= ord.length) return;
  [ord[i], ord[j]] = [ord[j], ord[i]];
  commit(e => { e.order = ord; });
}

function resetNode(id) {
  commit(e => {
    EDIT_KEYS.forEach(k => { if (e[k]) delete e[k][id]; });
    if (e.created[id] && !M.baseline.created[id]) delete e.created[id];
    e.deleted = e.deleted.filter(x => x !== id);
  }, 'Undid your edits to this one.');
}

// ---------- saving ----------
let docRef = null, saveTimer = null, lastSaved = '', writable = true;
const setStatus = (t, err) => { const s = document.getElementById('status'); s.textContent = t; s.classList.toggle('err', !!err); };
function queueSave() {
  try { localStorage.setItem(EDITS_KEY, JSON.stringify(edits)); } catch (e) {}
  if (!docRef) { setStatus(LOCAL ? 'Unsaved edits in this browser. Save to repo writes them.' : 'Saved in this browser only'); return; }
  if (!writable) { setStatus('View only: your edits are not saved', true); return; }
  setStatus('Saving…');
  clearTimeout(saveTimer);
  saveTimer = setTimeout(async () => {
    const body = JSON.stringify(edits);
    if (body === lastSaved) { setStatus('Saved'); return; }
    try { await docRef.set({ edits: JSON.parse(body), savedAt: new Date().toISOString() }); lastSaved = body; setStatus('Saved'); }
    catch (err) {
      const code = err && err.code;
      if (code === 'permission_denied' || code === 'not_granted') { writable = false; setStatus('View only: your edits are not saved', true); }
      else setStatus('Couldn’t save. Your next edit will try again.', true);
    }
  }, 600);
}
async function connect() {
  try { const raw = localStorage.getItem(EDITS_KEY); if (raw) edits = { ...emptyEdits(), ...JSON.parse(raw) }; } catch (e) {}
  render();
  setStatus(LOCAL ? 'Showing the repo as it is now' : 'Saved in this browser only');
  if (LOCAL || !window.claude || !window.claude.use) return;
  const db = await window.claude.use('db');
  if (!db) return;
  docRef = db.doc('layouts/main');
  setStatus('Connected');
  docRef.onSnapshot(snap => {
    if (snap.metadata && snap.metadata.hasPendingWrites) return;
    const data = snap.exists ? snap.data() : null;
    const remote = data && data.edits ? JSON.stringify(data.edits) : null;
    if (remote) {
      lastSaved = remote;
      if (remote !== JSON.stringify(edits)) { edits = { ...emptyEdits(), ...JSON.parse(remote) }; render(); }
      setStatus('Saved');
    } else if (JSON.stringify(edits) !== JSON.stringify(emptyEdits())) queueSave();
    else setStatus('Saved');
  }, () => setStatus('Lost the connection. Edits stay in this browser.', true));
}

// ---------- export ----------
/**
 * Every edit as the change set apply.py writes into the repo: only what differs from the files as
 * they are. Icons are item ids and backgrounds resource paths, exactly as the JSON stores them.
 */
function exportChanges() {
  const saved = view.layout; view.layout = 'proposed';
  const L = computeLayout(); view.layout = saved;
  const E = L.E, out = { version: 1, parents: {}, created: {}, deleted: edits.deleted.slice(), icons: {}, values: {}, backgrounds: {}, tabNames: {}, capstone: {}, unlocks: {}, visibility: {}, order: null };
  Object.keys(E.nodes).forEach(id => {
    const n = E.nodes[id], base = M.nodes[id];
    if (kindOf(id) === 'other') return;
    if (!base) {
      out.created[id] = n.copyOf
        ? { parent: E.parents[id] || null, copyOf: n.copyOf, icon: M.iconIds[n.i], background: n.bg || null }
        : { parent: E.parents[id] || null, title: n.t, description: n.d, frame: n.f, icon: M.iconIds[n.i], background: n.bg || null, unlockedBy: n.unlockedBy || null };
      if (n.cap && n.cap.ed && (!n.cap.req || !n.cap.reset)) out.capstone[id] = { required: n.cap.req, reset: n.cap.reset };
      if (n.vis && n.vis !== n.visDef) out.visibility[id] = n.vis;
      return;
    }
    if ((E.parents[id] || '') !== (M.gameParents[id] || '')) out.parents[id] = E.parents[id] || null;
    if (n.i !== base.i) out.icons[id] = M.iconIds[n.i];
    if (n.req && base.req && n.req.n !== base.req.n && !n.copyOf) out.values[id] = { field: base.req.field, from: base.req.n, to: n.req.n };
    if (!E.parents[id] && n.bg && n.bg !== base.bg) out.backgrounds[id] = n.bg;
    if (!E.parents[id] && !n.copyOf && (n.unlockedBy || '') !== (base.unlockedBy || '')) out.unlocks[id] = n.unlockedBy || null;
    const wantVis = n.vis === n.visDef ? null : n.vis, savedVis = base.vis !== base.visDef ? base.vis : null;
    if (wantVis !== savedVis) out.visibility[id] = wantVis;
    if (n.cap && base.cap && n.cap.ed && (n.cap.req !== base.cap.req || n.cap.reset !== base.cap.reset)) out.capstone[id] = { required: n.cap.req, reset: n.cap.reset };
  });
  const titles = E.titles;
  Object.keys({ ...titles, ...M.baseline.tabTitles }).forEach(id => {
    if (!E.nodes[id] || E.parents[id]) return;
    const now = titles[id] || null, was = M.baseline.tabTitles[id] || null;
    if (now !== was) out.tabNames[id] = now;
  });
  const order = L.tabs.filter(t => t.kind !== 'other').map(t => t.id);
  if (JSON.stringify(order) !== JSON.stringify(M.baseline.order)) out.order = order;
  return renameNewTabs(out, L);
}

/**
 * A new tab gets its id when it is made, before it has a name ("tab_new"). Ids are permanent once saved,
 * so on export each placeholder is renamed after the tab's name or title (tab_challenges), everywhere
 * the change set mentions it.
 */
function renameNewTabs(out, L) {
  const taken = new Set(Object.keys(M.nodes));
  const renames = {};
  Object.keys(out.created).forEach(id => {
    if (!/\/tab_new(_\d+)?$/.test(id)) return;
    const name = L.E.titles[id] || L.E.nodes[id].t;
    const target = uniqueId(x => taken.has(x), TAB_PREFIX + slugify(name));
    taken.add(target);
    if (target !== id) renames[id] = target;
  });
  if (!Object.keys(renames).length) return out;
  let text = JSON.stringify(out);
  Object.keys(renames).forEach(from => { text = text.split(`"${from}"`).join(`"${renames[from]}"`); });
  return JSON.parse(text);
}

async function saveToRepo() {
  const changes = exportChanges();
  setStatus('Writing to the repo…');
  try {
    const res = await fetch('/api/apply', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(changes) });
    const report = await res.json();
    if (!res.ok) throw new Error(report.error || res.statusText);
    try { localStorage.removeItem(EDITS_KEY); } catch (e) {}
    sessionStorage.setItem('dt-adv-report', JSON.stringify(report));
    location.reload();
  } catch (err) {
    setStatus(`Couldn\u2019t save to the repo: ${err.message}. Your edits are still here.`, true);
  }
}

// ---------- visibility ----------
const VIS_LABELS = { parent: 'Hidden until parent', always: 'Always visible (while its parent is)', earned: 'Hidden until earned' };

/** Would the game show this advancement to a player who has earned nothing? (AdvancementVisibilityRule) */
function visibleWithNothingEarned(E, id) {
  const n = E.nodes[id];
  if (!n || !n.vis) return true;
  const parent = E.parents[id];
  if (!parent) return n.vis === 'always';
  return n.vis === 'always' && visibleWithNothingEarned(E, parent);
}
