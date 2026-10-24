import {store, saveStore, uid, loadError, applyDesktopState} from './store.js';
import {images} from './images.js';
import {createEditor} from './editor.js';
import {playSplash} from './splash.js';
import {createSwipeController} from './swipe.js';
import {initMotion, showLayer, hideLayer, captureRows, moveRow, fold} from './motion.js';
import {mountDesktop} from './desktop-ui.js';

const $ = id => document.getElementById(id);
const scales = {system: 1, small: .85, normal: 1, large: 1.15, extra: 1.3};
const fontLabels = {system: '跟随系统', small: '小', normal: '标准', large: '大', extra: '特大'};
let category = 'all', limit = 40, managing = false, activeModal = null;
let dismissModal = null, returnFocus = null, nameAction = null, draftFont = store.font;
let toastTimer, searchTimer;
const appearedNoteIds = new Set();
const desktopMode = Boolean(window.jianjiDesktop) || new URLSearchParams(window.location.search).get('desktop') === '1';
let desktop = null, desktopSyncSnapshot = null, desktopRefreshQueued = false;
let selectionMode = false;
const selectedNoteIds = new Set();
const swipes = createSwipeController($('noteList'), {enabled: () => !selectionMode && category !== 'trash'});

function node(tag, className = '', text = '') {
  const element = document.createElement(tag);
  element.className = className;
  element.textContent = text;
  return element;
}
function icon(name) {
  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  svg.setAttribute('viewBox', '0 0 24 24');
  svg.setAttribute('aria-hidden', 'true');
  svg.dataset.icon = name;
  const use = document.createElementNS('http://www.w3.org/2000/svg', 'use');
  use.setAttribute('href', '#i-' + name); svg.append(use);
  return svg;
}
function button(label, className, action) {
  const view = node('button', className, label); view.type = 'button';
  view.addEventListener('click', action); return view;
}
function iconButton(name, label, action) {
  const view = button('', 'icon-button row-menu', action);
  view.setAttribute('aria-label', label); view.append(icon(name)); return view;
}
function bookFor(id) { return store.books.find(book => book.id === id); }
function location(id) {
  const book = bookFor(id); if (!book) return '未分类';
  const folder = store.folders.find(item => item.id === book.folderId);
  return (folder ? folder.name + ' / ' : '') + book.name;
}
function dateLabel(value) {
  const date = new Date(value);
  return `${date.getMonth() + 1}月${date.getDate()}日 ${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`;
}
function noteLabel(note) { return note.body.trim().replace(/\n/g, ' ').slice(0, 40) || '笔记'; }
function activeNotes() { return store.notes.filter(note => !note.deletedAt); }
function toast(message) {
  clearTimeout(toastTimer); $('toast').textContent = message; $('toast').hidden = false;
  toastTimer = setTimeout(() => { $('toast').hidden = true; }, 1800);
}
function closeMenu() {
  hideLayer($('menu'), 'popup');
  $('listMore').setAttribute('aria-expanded', 'false');
  $('editorMore').setAttribute('aria-expanded', 'false');
}
function closeModal(cancel = true) {
  if (!activeModal) return;
  const closing = activeModal; activeModal = null;
  hideLayer(closing, closing.classList.contains('sheet-layer') ? 'sheet' : 'dialog');
  const callback = dismissModal; dismissModal = null;
  $('listScreen').inert = false; $('editorScreen').inert = false;
  if ($('desktopSidebar')) $('desktopSidebar').inert = false;
  if (returnFocus?.isConnected && !returnFocus.closest('[hidden],[inert]')) returnFocus.focus({preventScroll: true});
  if (cancel) callback?.();
}
function openModal(element, onClose) {
  closeModal(); closeMenu(); returnFocus = document.activeElement;
  activeModal = element; dismissModal = onClose;
  showLayer(element, element.classList.contains('sheet-layer') ? 'sheet' : 'dialog');
  $('listScreen').inert = true; $('editorScreen').inert = true;
  if ($('desktopSidebar')) $('desktopSidebar').inert = true;
  element.querySelector('input:checked,input:not([type="file"]),button')?.focus();
}
function dialog({title, content, actions = [{label: '确定'}], onClose}) {
  closeModal();
  $('infoTitle').textContent = title; $('infoContent').replaceChildren(content);
  $('infoActions').replaceChildren();
  actions.forEach(action => $('infoActions').append(button(action.label,
    'dialog-action' + (action.primary ? '' : ' cancel'), () => {
      closeModal(false); action.onClick?.(); onClose?.();
    })));
  openModal($('infoModal'), onClose);
  return () => { if (activeModal === $('infoModal')) closeModal(); };
}
function alertMessage(title, message) {
  dialog({title, content: node('p', '', message), actions: [{label: '知道了', primary: true}]});
}
function confirmAction(title, message, accept = '确定', cancel = '取消') {
  return new Promise(resolve => dialog({title, content: node('p', '', message),
    actions: [{label: cancel, onClick: () => resolve(false)}, {label: accept, primary: true, onClick: () => resolve(true)}],
    onClose: () => resolve(false)}));
}
function commit(action) {
  const old = JSON.stringify(store);
  try { action(); if (saveStore() === false) throw new Error('无法保存更改，请重试。'); return true; }
  catch (error) {
    const previous = JSON.parse(old);
    // Retain note object references used by the open editor when rolling back.
    previous.notes = previous.notes.map(note => {
      const current = store.notes.find(item => item.id === note.id);
      return current ? Object.assign(current, note) : note;
    });
    Object.assign(store, previous);
    alertMessage('操作未完成', error.message || '无法保存，请重试。'); return false;
  }
}
const editor = createEditor({store, saveStore, images,
  ui: {$, icon, toast, alert: alertMessage, confirm: confirmAction, dialog},
  onClose: () => { closeMenu(); renderList(); },
  onMenu: anchor => showEditorMenu(anchor),
  onMove: note => moveNote(note)
});

function titleForCategory() {
  return ({all: '全部笔记', unfiled: '未分类', favorite: '我的收藏', trash: '最近删除'})[category] || bookFor(category)?.name || '全部笔记';
}
function sortedNotes(notes) {
  return [...notes].sort((a, b) => Number(b.pinned) - Number(a.pinned) || new Date(b.date) - new Date(a.date) || b.id.localeCompare(a.id));
}
function matchingNotes() {
  const query = $('search').value.trim().toLocaleLowerCase();
  return sortedNotes(store.notes.filter(note => {
    if (category === 'trash' ? !note.deletedAt : note.deletedAt) return false;
    if (category === 'favorite' && !note.favorite) return false;
    if (category === 'unfiled' && bookFor(note.notebookId)) return false;
    if (bookFor(category) && note.notebookId !== category) return false;
    return note.body.toLocaleLowerCase().includes(query);
  }));
}
function longPress(view, action, enabled = () => true) {
  let timer, x, y, fired = false;
  const clear = () => { clearTimeout(timer); };
  view.addEventListener('pointerdown', event => {
    fired = false; clear();
    if (event.button !== 0 || !enabled()) return;
    x = event.clientX; y = event.clientY;
    timer = setTimeout(() => { if (enabled() && view.isConnected && !view.closest('[hidden],[inert]')) { fired = true; action(); } }, 500);
  });
  view.addEventListener('pointermove', event => { if (Math.hypot(event.clientX - x, event.clientY - y) > 8) clear(); });
  ['pointerup', 'pointercancel', 'pointerleave'].forEach(type => view.addEventListener(type, clear));
  view.addEventListener('click', event => { if (fired) { event.preventDefault(); event.stopImmediatePropagation(); fired = false; } }, true);
  view.addEventListener('contextmenu', event => { event.preventDefault(); clear(); if (!fired && enabled()) action(); });
  return clear;
}
function updateSelectionUI(notes = matchingNotes()) {
  if (category === 'trash') { selectionMode = false; selectedNoteIds.clear(); }
  const matching = new Set(notes.map(note => note.id));
  [...selectedNoteIds].forEach(id => { if (!matching.has(id)) selectedNoteIds.delete(id); });
  $('normalHeader').hidden = selectionMode; $('selectionHeader').hidden = !selectionMode;
  $('selectionBar').hidden = !selectionMode; $('newNote').hidden = selectionMode || category === 'trash';
  $('listScreen').classList.toggle('is-selecting', selectionMode);
  $('selectionCount').textContent = `已选 ${selectedNoteIds.size}`;
  const allSelected = notes.length > 0 && notes.every(note => selectedNoteIds.has(note.id));
  $('selectAll').textContent = allSelected ? '取消全选' : '全选'; $('selectAll').disabled = !notes.length;
  $('deleteSelected').textContent = `删除（${selectedNoteIds.size}）`; $('deleteSelected').disabled = selectedNoteIds.size === 0;
  $('noteList').querySelectorAll(':scope > .note').forEach(row => {
    const checked = selectedNoteIds.has(row.dataset.noteId), front = row.querySelector('.note-front');
    row.classList.toggle('is-selected', checked); row.querySelector('.note-selection-mark').hidden = !selectionMode;
    const current = store.notes.find(note => note.id === row.dataset.noteId);
    if (current) front.setAttribute('aria-label', noteLabel(current) + (current.favorite ? '，已收藏' : '') + (current.pinned ? '，已置顶' : '') + (selectionMode ? '，点按选择' : category === 'trash' ? '，点按恢复或永久删除' : '，点按编辑，长按更多操作，左滑删除'));
    if (selectionMode) { front.setAttribute('role', 'checkbox'); front.setAttribute('aria-checked', String(checked)); }
    else { front.removeAttribute('role'); front.removeAttribute('aria-checked'); }
    row.querySelector('.note-delete').disabled = selectionMode || category === 'trash';
  });
}
function enterSelection(id = null) {
  if (category === 'trash') return;
  closeMenu(); closeModal(false); swipes.closeOpen(true); selectionMode = true; selectedNoteIds.clear();
  if (id && matchingNotes().some(note => note.id === id)) selectedNoteIds.add(id);
  updateSelectionUI(); $('selectionCancel').focus({preventScroll: true});
}
function exitSelection(render = true) {
  selectionMode = false; selectedNoteIds.clear(); swipes.closeOpen(true);
  if (render) { updateSelectionUI(); $('categoryButton').focus({preventScroll: true}); }
}
function clearSelectionForSearch() { selectedNoteIds.clear(); swipes.closeOpen(true); updateSelectionUI(); }
function toggleSelected(id) {
  if (selectedNoteIds.has(id)) selectedNoteIds.delete(id); else selectedNoteIds.add(id);
  updateSelectionUI();
}
function deleteSelection() {
  const ids = new Set(matchingNotes().filter(note => selectedNoteIds.has(note.id) && !note.deletedAt).map(note => note.id));
  if (!selectionMode || !ids.size) return;
  if (editor.importing || (editor.currentId && !editor.save())) return;
  const deletedAt = new Date().toISOString();
  if (commit(() => { store.notes.forEach(note => { if (ids.has(note.id)) note.deletedAt = deletedAt; }); })) {
    if (ids.has(editor.currentId)) editor.closeForSync();
    exitSelection(false); renderList(); toast('已移至最近删除，可恢复');
  }
}
function renderList() {
  const notes = matchingNotes(), title = titleForCategory();
  $('categoryLabel').textContent = title;
  $('categoryButton').setAttribute('aria-label', title + '，选择分类');
  $('listScreen').setAttribute('aria-label', title);
  desktop?.refresh();
  $('noteCount').textContent = ($('search').value.trim() ? '找到 ' : '') + notes.length + ' 条';
  $('clearSearch').hidden = !$('search').value;
  $('newNote').hidden = selectionMode || category === 'trash';
  const list = $('noteList');
  const rows = new Map([...list.querySelectorAll(':scope > .note')].map(row => [row.dataset.noteId, row]));
  const positions = captureRows([...rows.values()]);
  list.classList.toggle('empty-list', !notes.length);
  if (!notes.length) {
    let title = '还没有笔记', description = '点右下角，开始记录';
    if (category === 'favorite') { title = '暂无收藏'; description = '在笔记的更多菜单中添加收藏'; }
    if (category === 'trash') { title = '最近删除为空'; description = '删除的笔记会保留在这里'; }
    if ($('search').value.trim()) { title = '没有找到相关内容'; description = '换个关键词试试'; }
    let empty = list.querySelector('.empty');
    if (!empty) { swipes.closeOpen(true); empty = node('div', 'empty'); empty.append(icon('document'), node('strong'), node('p')); list.replaceChildren(empty); }
    empty.querySelector('strong').textContent = title; empty.querySelector('p').textContent = description;
    updateSelectionUI(notes); return;
  }
  const kept = new Set(), visibleRows = [];
  notes.slice(0, limit).forEach((note, index) => {
    let row = rows.get(note.id);
    if (!row) {
      const id = note.id;
      row = node('div', 'note'); row.dataset.noteId = id;
      const front = button('', 'note-front', () => {
        const current = store.notes.find(item => item.id === id); if (!current) return;
        if (selectionMode) { toggleSelected(id); return; }
        if (category === 'trash') trashMenu(current); else { swipes.closeOpen(true); if (editor.open(id) && desktopMode) renderList(); }
      });
      const marker = node('span', 'note-selection-mark'); marker.setAttribute('aria-hidden', 'true'); marker.hidden = true; marker.append(icon('check'));
      front.append(marker, node('div', 'note-content'));
      const remove = button('删除', 'note-delete', () => {
        const current = store.notes.find(item => item.id === id); if (current && !selectionMode && category !== 'trash') trashNote(current);
      });
      remove.replaceChildren(icon('trash'), node('span', '', '删除'));
      remove.setAttribute('aria-label', '删除这篇笔记'); row.append(remove, front);
      const cancelLongPress = longPress(front, () => {
        const current = store.notes.find(item => item.id === id); if (!current) return;
        if (category === 'trash') trashMenu(current); else noteMenu(current);
      }, () => !selectionMode && !swipes.isOpen(row) && !row.classList.contains('is-swiping'));
      swipes.bind(row, front, remove, cancelLongPress);
    }
    const front = row.querySelector('.note-front'), content = row.querySelector('.note-content');
    front.setAttribute('aria-label', noteLabel(note) + (note.favorite ? '，已收藏' : '') + (note.pinned ? '，已置顶' : '') + (selectionMode ? '，点按选择' : category === 'trash' ? '，点按恢复或永久删除' : '，点按编辑，长按更多操作，左滑删除'));
    const text = note.body.trim(), split = text.indexOf('\n');
    const title = (split < 0 ? text : text.slice(0, split).trim()) || (note.images.length ? '图片笔记' : '空白笔记');
    const preview = split < 0 ? '' : text.slice(split + 1).trim().replace(/\n/g, ' ');
    const date = dateLabel(note.date);
    const notebookName = !bookFor(category) ? bookFor(note.notebookId)?.name || '' : '';
    const signature = JSON.stringify([title, preview, date, notebookName, note.favorite, note.pinned, note.images.length, note.conflictOf || '']);
    row.classList.toggle('is-current', desktopMode && editor.currentId === note.id);
    if (row.notePresentation !== signature) {
      row.notePresentation = signature; content.replaceChildren(node('p', 'note-title', title));
      if (preview) content.append(node('p', 'note-preview', preview));
      const metadata = node('div', 'note-meta'); metadata.append(node('p', 'note-date', date));
      const flags = node('span', 'meta-flags'); flags.setAttribute('aria-hidden', 'true');
      if (note.favorite) flags.append(icon('star'));
      if (note.pinned) flags.append(icon('pin'));
      if (note.images.length) flags.append(icon('image'), node('span', '', String(note.images.length)));
      if (note.conflictOf) flags.append(node('span', 'conflict-label', '冲突副本'));
      metadata.append(flags);
      if (notebookName) { const label = node('span', 'note-notebook', notebookName); label.title = notebookName; metadata.append(label); }
      content.append(metadata);
    }
    kept.add(row); visibleRows.push(row);
    if (list.children[index] !== row) list.insertBefore(row, list.children[index] || null);
  });
  [...list.children].forEach(child => { if (!kept.has(child)) { if (child.classList.contains('note')) swipes.reset(child); child.remove(); } });
  if (notes.length > limit) list.append(button('加载更多', 'load-more', () => { limit += 40; renderList(); }));
  updateSelectionUI(notes);
  if (!list.closest('[hidden]')) visibleRows.forEach(row => {
    const previous = positions.get(row);
    if (previous || !appearedNoteIds.has(row.dataset.noteId)) moveRow(row, previous);
    appearedNoteIds.add(row.dataset.noteId);
  });
}
function selectCategory(id) { exitSelection(false); category = id; limit = 40; $('search').value = ''; closeModal(false); renderList(); }
function categoryRow(iconName, title, count, selected, action) {
  const row = button('', 'category-row', action);
  row.setAttribute('aria-pressed', String(selected));
  row.append(icon(iconName), node('span', 'row-label', title), node('span', 'row-count', String(count))); return row;
}
function renderCategories() {
  $('categoryTitle').textContent = managing ? '管理分类' : '笔记本';
  const content = $('categoryContent'); content.replaceChildren();
  const notes = activeNotes();
  if (!managing) {
    const general = node('div', 'category-general');
    [
      ['document', '全部笔记', 'all', notes.length],
      ['document', '未分类', 'unfiled', notes.filter(note => !bookFor(note.notebookId)).length],
      ['star', '我的收藏', 'favorite', notes.filter(note => note.favorite).length],
      ['trash', '最近删除', 'trash', store.notes.filter(note => note.deletedAt).length]
    ].forEach(([name, title, id, count]) => general.append(categoryRow(name, title, count, category === id, () => selectCategory(id))));
    content.append(general);
  }
  const heading = node('div', 'folders-heading'); heading.append(node('span', '', '文件夹'), button(managing ? '完成' : '管理', 'folder-manage', () => { managing = !managing; renderCategories(); })); content.append(heading);
  store.folders.forEach(folder => {
    const books = store.books.filter(book => book.folderId === folder.id), group = node('div', 'folder-group');
    const header = node('div', 'folder-header');
    const expand = button('', 'category-row folder-expand', () => {
      const open = !store.expanded.includes(folder.id);
      if (commit(() => { store.expanded = open ? [...store.expanded, folder.id] : store.expanded.filter(id => id !== folder.id); })) {
        fold(children, arrow, open); expand.setAttribute('aria-expanded', String(open));
      }
    });
    expand.setAttribute('aria-expanded', String(store.expanded.includes(folder.id)));
    expand.setAttribute('aria-label', folder.name + '，文件夹，' + books.length + ' 个笔记本');
    const arrow = icon('chevronDown'); arrow.classList.add('folder-arrow');
    const folderTitle = node('span', 'folder-title');
    folderTitle.append(node('span', 'row-label', folder.name), node('span', 'folder-count', books.length + ' 本'));
    expand.append(icon('folder'), folderTitle, arrow);
    longPress(expand, () => folderMenu(folder)); header.append(expand);
    if (managing) header.append(iconButton('more', '管理文件夹 ' + folder.name, () => folderMenu(folder)));
    const children = node('div', 'folder-children'); children.hidden = !store.expanded.includes(folder.id);
    books.forEach(book => {
      const line = node('div', 'folder-header');
      const row = categoryRow('notebook', book.name, notes.filter(note => note.notebookId === book.id).length, category === book.id, () => selectCategory(book.id));
      longPress(row, () => notebookMenu(book)); line.append(row);
      if (managing) line.append(iconButton('more', '管理笔记本 ' + book.name, () => notebookMenu(book)));
      children.append(line);
    });
    children.append(button('＋ 新建笔记本', 'add-category', () => openName('book', null, folder.id)));
    group.append(header, children); content.append(group);
  });
  content.append(button('＋ 新建文件夹', 'add-category', () => openName('folder')));
}
function showCategories(manage = false) { if (selectionMode) exitSelection(); swipes.closeOpen(true); managing = manage; renderCategories(); openModal($('categoryModal')); }
function showItems(title, items) {
  const content = node('div', 'dialog-list');
  items.forEach(([label, action]) => content.append(button(label, 'menu-item', () => { closeModal(false); action(); })));
  dialog({title, content, actions: []});
}
function folderMenu(folder) {
  showItems(folder.name, [['重命名', () => openName('folder', folder)], ['删除文件夹', async () => {
    if (!await confirmAction('删除文件夹？', '其中的笔记会移至未分类，内容不会被删除。', '删除')) return;
    const ids = store.books.filter(book => book.folderId === folder.id).map(book => book.id);
    if (commit(() => {
      store.notes.forEach(note => { if (ids.includes(note.notebookId)) note.notebookId = null; });
      store.books = store.books.filter(book => book.folderId !== folder.id);
      store.folders = store.folders.filter(item => item.id !== folder.id); store.expanded = store.expanded.filter(id => id !== folder.id);
    })) { if (ids.includes(category)) category = 'all'; renderList(); }
  }]]);
}
function notebookMenu(book) {
  showItems(book.name, [['重命名笔记本', () => openName('book', book, book.folderId)], ['删除笔记本', async () => {
    if (!await confirmAction('删除笔记本？', '其中的笔记会移至未分类，内容不会被删除。', '删除')) return;
    if (commit(() => { store.notes.forEach(note => { if (note.notebookId === book.id) note.notebookId = null; }); store.books = store.books.filter(item => item.id !== book.id); })) {
      if (category === book.id) category = 'all'; renderList();
    }
  }]]);
}
function openName(type, existing = null, parent = null) {
  const label = type === 'folder' ? '文件夹' : '笔记本';
  $('nameTitle').textContent = (existing ? '重命名' : '新建') + label;
  $('nameLabel').textContent = label + '名称'; $('categoryName').value = existing?.name || '';
  $('nameError').hidden = true; $('categoryName').removeAttribute('aria-invalid');
  nameAction = {type, existing, parent}; openModal($('nameModal'));
  $('categoryName').focus(); $('categoryName').setSelectionRange($('categoryName').value.length, $('categoryName').value.length);
}
$('nameForm').addEventListener('submit', event => {
  event.preventDefault(); const value = $('categoryName').value.trim();
  if (!value) { $('nameError').textContent = '请输入名称'; $('nameError').hidden = false; $('categoryName').setAttribute('aria-invalid', 'true'); $('categoryName').focus(); return; }
  const {type, existing, parent} = nameAction; const id = existing?.id || uid(type);
  if (!commit(() => {
    if (existing) existing.name = value;
    else if (type === 'folder') { store.folders.push({id, name: value}); store.expanded.push(id); }
    else { store.books.push({id, name: value, folderId: parent}); if (!store.expanded.includes(parent)) store.expanded.push(parent); }
  })) return;
  closeModal(false);
  if (type === 'book' && !existing) category = id;
  renderList(); if (type === 'folder' && !existing) showCategories();
});
function moveNote(note) {
  if (editor.currentId && !editor.save()) return;
  const content = node('div', 'move-options');
  [{id: null, name: '未分类'}, ...store.books.map(book => ({id: book.id, name: location(book.id)}))].forEach(item => {
    const choice = button('', 'move-option', () => {
      closeModal(false);
      if (commit(() => { note.notebookId = item.id; note.date = new Date().toISOString(); })) { editor.syncLocation(); renderList(); }
    });
    choice.setAttribute('role', 'radio'); choice.setAttribute('aria-checked', String((bookFor(note.notebookId)?.id || null) === item.id));
    choice.append(node('span', 'radio-dot'), node('span', '', item.name)); content.append(choice);
  });
  content.setAttribute('role', 'radiogroup'); content.setAttribute('aria-label', '目标笔记本');
  dialog({title: '移动到笔记本', content, actions: [{label: '取消'}]});
}
function toggleFlag(note, field) {
  if (editor.currentId && !editor.save()) return;
  if (commit(() => { note[field] = !note[field]; if (editor.currentId === note.id) note.date = new Date().toISOString(); })) {
    renderList(); if (editor.currentId) { editor.syncLocation(); toast(field === 'favorite' ? (note.favorite ? '已收藏' : '已取消收藏') : (note.pinned ? '已置顶笔记' : '已取消置顶')); }
  }
}
function trashNote(note) {
  if (editor.importing) { toast('图片还在处理中，请稍后再操作'); return; }
  const current = store.notes.find(item => item.id === note.id); if (!current || current.deletedAt) return;
  if (editor.currentId && !editor.save()) return;
  swipes.closeOpen(true);
  if (commit(() => { current.deletedAt = new Date().toISOString(); })) {
    selectedNoteIds.delete(current.id);
    if (editor.currentId === current.id) editor.closeForSync();
    renderList();
    toast('已移至最近删除，可恢复');
  }
}
function noteMenu(note) {
  showItems(noteLabel(note), [['多选', () => enterSelection(note.id)], [note.favorite ? '取消收藏' : '收藏', () => toggleFlag(note, 'favorite')],
    [note.pinned ? '取消置顶' : '置顶', () => toggleFlag(note, 'pinned')],
    ['移动到笔记本', () => moveNote(note)], ['删除', () => trashNote(note)]]);
}
function trashMenu(note) {
  showItems(noteLabel(note), [['恢复', () => { if (commit(() => { note.deletedAt = null; })) renderList(); }], ['永久删除', async () => {
    if (!await confirmAction('永久删除？', '这篇笔记及其中的图片将无法恢复。', '永久删除')) return;
    if (commit(() => { store.notes = store.notes.filter(item => item.id !== note.id); })) {
      renderList();
      for (const id of note.images) if (!store.notes.some(item => item.images.includes(id))) {
        try { await images.remove(id); } catch { toast('笔记已删除，图片副本暂未清理'); }
      }
    }
  }]]);
}
function popup(anchor, items) {
  swipes.closeOpen(true);
  if (!$('menu').hidden) { closeMenu(); return; }
  const menu = $('menu'); menu.replaceChildren();
  items.forEach(([label, action, value]) => {
    const item = button('', 'menu-item', () => { closeMenu(); action(); }); item.setAttribute('role', 'menuitem'); item.append(node('span', '', label));
    if (value) item.append(node('span', 'menu-value', value)); menu.append(item);
  });
  showLayer(menu, 'popup'); anchor.setAttribute('aria-expanded', 'true');
  menu.style.top = `${anchor.getBoundingClientRect().bottom - $('phone').getBoundingClientRect().top}px`;
  if (desktopMode) menu.style.right = `${Math.max(16, $('phone').getBoundingClientRect().right - anchor.getBoundingClientRect().right)}px`;
  menu.querySelector('button')?.focus();
}
function showEditorMenu(anchor) {
  const note = store.notes.find(item => item.id === editor.currentId); if (!note) return;
  popup(anchor, [[note.favorite ? '取消收藏' : '收藏', () => toggleFlag(note, 'favorite')],
    [note.pinned ? '取消置顶' : '置顶笔记', () => toggleFlag(note, 'pinned')], ['移动到笔记本', () => moveNote(note)],
    ['分享文字', () => shareNote(note)], ['删除', () => trashNote(note)]]);
}
async function shareNote(note) {
  if (!editor.save()) return;
  if (!note.body.trim()) { toast('暂无可分享的文字'); return; }
  if (navigator.share) {
    try { await navigator.share({text: note.body}); return; }
    catch (error) { if (error.name === 'AbortError') return; }
  }
  const text = node('textarea', 'share-text'); text.readOnly = true; text.value = note.body; text.setAttribute('aria-label', '待分享的笔记文字');
  dialog({title: '分享笔记', content: text, actions: [{label: '取消'}, {label: '复制文字', primary: true, onClick: async () => {
    try { await navigator.clipboard.writeText(note.body); toast('文字已复制'); }
    catch { dialog({title: '选择文字后手动复制', content: text, actions: [{label: '关闭'}]}); text.focus(); text.select(); }
  }}]});
}
async function exportNotes() {
  const notes = sortedNotes(activeNotes()); if (!notes.length) { toast('暂无可导出的笔记'); return; }
  if (!await confirmAction('导出文字', '将正常笔记的文字保存为 TXT。图片与最近删除的内容不包含在内。', '选择位置')) return;
  let text = '简记 · 文字导出\n\n';
  notes.forEach(note => {
    const date = new Date(note.date);
    const stamp = `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')} ${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`;
    text += '位置：' + location(note.notebookId) + '\n' + stamp + '\n\n' + note.body;
    if (note.images.length) text += '\n[另含 ' + note.images.length + ' 张图片，未包含在文字导出中]';
    text += '\n\n────────────────────────\n\n';
  });
  const now = new Date(), name = `简记-${now.getFullYear()}${String(now.getMonth() + 1).padStart(2, '0')}${String(now.getDate()).padStart(2, '0')}.txt`;
  const blob = new Blob([text], {type: 'text/plain;charset=utf-8'}), url = URL.createObjectURL(blob);
  const download = node('a'); download.href = url; download.download = name; document.body.append(download); download.click(); download.remove(); setTimeout(() => URL.revokeObjectURL(url), 1000);
  toast('文字已导出');
}
function applyFont() { $('phone').style.setProperty('--scale', scales[store.font] || 1); }
function showFonts() {
  draftFont = store.font;
  document.querySelectorAll('[name="fontSize"]').forEach(input => { input.checked = input.value === draftFont; });
  $('fontSample').style.setProperty('--sample-scale', scales[draftFont]); openModal($('fontModal'));
}
$('fontApply').addEventListener('click', () => { if (commit(() => { store.font = draftFont; })) { applyFont(); closeModal(false); } });
$('fontCancel').addEventListener('click', () => closeModal());
document.querySelectorAll('[name="fontSize"]').forEach(input => input.addEventListener('change', () => { draftFont = input.value; $('fontSample').style.setProperty('--sample-scale', scales[draftFont]); }));
$('listMore').addEventListener('click', event => {
  const items = [['管理分类', () => showCategories(true)], ['导出文字', exportNotes], ['字体大小', showFonts, fontLabels[store.font]]];
  if (category !== 'trash') items.unshift(['多选笔记', () => enterSelection()]);
  items.unshift([desktopMode ? '设备同步' : '电脑同步', () => desktop ? desktop.openSync() : alertMessage('电脑同步', '请在安卓 App 中打开“电脑同步”，扫描电脑版显示的二维码。网页预览中的数据与两端客户端独立。')]);
  items.push(['关于简记', () => dialog({title: '简记 2.0.0', content: node('p', '', '专注记录。\n\n作者 @yue\n\n笔记保存在本机，无需登录。可与同一 Wi-Fi 下已配对的设备双向同步。卸载前请同步或导出。'), actions: [{label: '知道了', primary: true}]})]);
  popup(event.currentTarget, items);
});
$('selectionCancel').addEventListener('click', () => exitSelection());
$('selectAll').addEventListener('click', () => {
  const notes = matchingNotes(), all = notes.length > 0 && notes.every(note => selectedNoteIds.has(note.id));
  selectedNoteIds.clear(); if (!all) notes.forEach(note => selectedNoteIds.add(note.id)); updateSelectionUI(notes);
});
$('deleteSelected').addEventListener('click', deleteSelection);
$('categoryButton').addEventListener('click', () => showCategories());
$('categoryClose').addEventListener('click', () => closeModal());
$('nameCancel').addEventListener('click', () => closeModal());
$('newNote').addEventListener('click', () => {
  const now = new Date().toISOString(), id = uid('note');
  if (commit(() => store.notes.push({id, body: '', date: now, createdAt: now, notebookId: bookFor(category)?.id || null, favorite: false, pinned: false, deletedAt: null, images: []}))) editor.open(id, true);
});
$('search').addEventListener('input', () => { clearSelectionForSearch(); clearTimeout(searchTimer); limit = 40; $('clearSearch').hidden = !$('search').value; searchTimer = setTimeout(renderList, 180); });
$('search').addEventListener('keydown', event => { if (event.key === 'Enter') { event.preventDefault(); $('search').blur(); renderList(); } });
$('clearSearch').addEventListener('click', () => { $('search').value = ''; clearSelectionForSearch(); limit = 40; renderList(); $('search').focus(); });
document.addEventListener('click', event => { if (!event.target.closest('#menu,#listMore,#editorMore')) closeMenu(); });
document.querySelectorAll('.modal-layer').forEach(layer => layer.addEventListener('click', event => { if (event.target === layer) closeModal(); }));
document.addEventListener('keydown', event => {
  if (desktopMode && !activeModal && (event.metaKey || event.ctrlKey) && !event.altKey) {
    const key = event.key.toLowerCase();
    if (key === 'n') { event.preventDefault(); createDesktopNote(); return; }
    if (key === 'f') { event.preventDefault(); if (event.shiftKey) desktop?.toggleFocus(); else { desktop?.leaveFocus(); $('search').focus(); } return; }
  }
  if (event.key === 'Escape') {
    if (selectionMode) { event.preventDefault(); closeModal(); closeMenu(); exitSelection(); return; }
    swipes.closeOpen();
    if (activeModal) closeModal(); else if (!$('menu').hidden) closeMenu(); else if (editor.currentId) editor.back();
    else if ($('search').value) { $('search').value = ''; renderList(); } else if (category !== 'all') selectCategory('all');
    return;
  }
  if (event.key === 'Tab' && activeModal) {
    const targets = [...activeModal.querySelectorAll('button:not([disabled]),input,textarea,select,[tabindex="0"]')].filter(el => !el.closest('[hidden]'));
    const first = targets[0], last = targets[targets.length - 1];
    if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus(); }
    else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus(); }
  }
});
function createDesktopNote() {
  if (selectionMode) exitSelection(false);
  if (category === 'trash') selectCategory('all');
  $('newNote').click();
}
if (desktopMode) {
  desktop = mountDesktop({host: $('phone'), store, editor, getCategory: () => category,
    actions: {selectCategory, createNote: createDesktopNote, createFolder: () => openName('folder'), manage: () => showCategories(true), showFonts, exportNotes},
    ui: {node, button, icon, dialog, toast, alert: alertMessage}});
  window.addEventListener('jianji:state-saved', () => {
    if (desktopRefreshQueued) return;
    desktopRefreshQueued = true;
    queueMicrotask(() => { desktopRefreshQueued = false; renderList(); });
  });
}
if (window.jianjiDesktop) {
  window.jianjiDesktop.onPrepareSync(() => {
    if (editor.importing || (activeModal && !desktop.isSyncPanelOpen()) || !editor.save()) return false;
    desktopSyncSnapshot = {id: editor.currentId, body: $('body').value, start: $('body').selectionStart, end: $('body').selectionEnd, scroll: $('body').closest('.body-wrap').scrollTop};
    $('phone').inert = true; desktop.setBusy(true); return true;
  });
  window.jianjiDesktop.onSyncResult(result => {
    try {
      if (result.ok && result.state) {
        const previous = desktopSyncSnapshot;
        editor.closeForSync(); applyDesktopState(result.state);
        if (!['all','unfiled','favorite','trash'].includes(category) && !bookFor(category)) category = 'all';
        exitSelection(false); applyFont(); renderList();
        if (previous?.id && store.notes.some(note => note.id === previous.id && !note.deletedAt)) {
          editor.open(previous.id);
          if ($('body').value === previous.body) {
            $('body').setSelectionRange(previous.start, previous.end);
            $('body').closest('.body-wrap').scrollTop = previous.scroll;
          }
        }
        if (result.summary?.conflicts) toast(`已保留 ${result.summary.conflicts} 份冲突副本`);
      } else if (result.error) toast(result.error);
    } catch (error) { alertMessage('界面更新失败', error.message || '同步数据已保存在本机，请重新打开简记。'); }
    finally {
      desktopSyncSnapshot = null; $('phone').inert = false; desktop.setBusy(false);
      if (activeModal) { $('listScreen').inert = true; $('editorScreen').inert = true; $('desktopSidebar').inert = true; }
      desktop.refresh();
    }
  });
}
initMotion($('phone')); applyFont(); renderList();
if (loadError) alertMessage('已有数据暂时无法读取', loadError);
else if (!desktopMode) playSplash($('phone'));
