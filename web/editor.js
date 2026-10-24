import {transitionPage} from './motion.js';

const MAX_HISTORY = 40;
const MAX_HISTORY_CHARACTERS = 500000;
const MAX_IMAGE_BYTES = 30 * 1024 * 1024;

function sameContent(left, right) {
  return left.body === right.body && left.images.length === right.images.length
    && left.images.every((id, index) => id === right.images[index]);
}

class EditorHistory {
  constructor(initial) {
    this.entries = [initial];
    this.position = 0;
  }
  get current() { return this.entries[this.position]; }
  get canUndo() { return this.position > 0; }
  get canRedo() { return this.position + 1 < this.entries.length; }
  rememberSelection(start, end) {
    this.entries[this.position] = {...this.current, start, end};
  }
  record(next, mergeTyping = false) {
    if (sameContent(this.current, next)) {
      this.entries[this.position] = next;
      return;
    }
    const hadRedo = this.canRedo;
    this.entries.splice(this.position + 1);
    if (mergeTyping && this.position > 0 && !hadRedo) this.entries[this.position] = next;
    else {
      this.entries.push(next);
      this.position++;
    }
    let characters = this.entries.reduce((total, entry) => total + entry.body.length, 0);
    // Keep the current document and its previous state, even for unusually large notes.
    while (this.entries.length > 2 && (this.entries.length > MAX_HISTORY || characters > MAX_HISTORY_CHARACTERS)) {
      characters -= this.entries.shift().body.length;
      this.position--;
    }
  }
  undo() { return this.canUndo ? this.entries[--this.position] : this.current; }
  redo() { return this.canRedo ? this.entries[++this.position] : this.current; }
}

export function createEditor({store, saveStore, images, ui, onClose, onMenu, onMove}) {
  const $ = ui.$;
  const body = $('body');
  const editorScreen = $('editorScreen');
  const bodyWrap = body.closest('.body-wrap');
  let imageList = $('imageList');
  if (!imageList) {
    imageList = document.createElement('div');
    imageList.id = 'imageList';
    body.insertAdjacentElement('afterend', imageList);
  }
  imageList.classList.add('editor-images');
  let imagePicker = $('imagePicker');
  if (!imagePicker) {
    imagePicker = document.createElement('input');
    imagePicker.id = 'imagePicker';
    editorScreen.append(imagePicker);
  }
  imagePicker.type = 'file';
  imagePicker.accept = 'image/*';
  imagePicker.multiple = false;
  imagePicker.hidden = true;
  if (bodyWrap) {
    Object.assign(bodyWrap.style, {display: 'block', overflowY: 'auto', overflowX: 'hidden'});
  }
  Object.assign(body.style, {flex: 'none', overflow: 'hidden', resize: 'none', minHeight: '48px', maxHeight: 'none'});

  let currentId = null;
  let freshDraft = false;
  let attachments = [];
  let history = null;
  let dirty = false;
  let importing = false;
  let composing = false;
  let saveTimer = 0;
  let historyTimer = 0;
  let lastTypingAt = 0;
  let lastEditKind = 0;
  let editKind = 0;
  let beforeEdit = null;
  let lastInputBody = '';
  let generation = 0;
  let pickerGeneration = 0;
  let pendingImport = null;
  let previewClose = null;
  const cleanupCandidates = new Set();
  const cards = new Map();

  function currentNote() { return store.notes.find(note => note.id === currentId); }
  function errorDetail(error) { return error && error.message ? `\n${error.message}` : ''; }
  function setStatus(message) {
    $('saveStatus').textContent = message;
    $('saveStatus').title = message;
    $('saveStatus').setAttribute('aria-label', message);
  }
  function snapshot() {
    const value = body.value;
    const start = Math.max(0, Math.min(value.length, body.selectionStart ?? 0));
    const end = Math.max(start, Math.min(value.length, body.selectionEnd ?? start));
    return {body: value, images: [...attachments], start, end};
  }
  function formatDate(value) {
    let date = new Date(value || Date.now());
    if (Number.isNaN(date.getTime())) date = new Date();
    return `${date.getMonth() + 1}月${date.getDate()}日 ${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`;
  }
  function updateDate(value) {
    const label = formatDate(value);
    $('editorDate').textContent = label;
    $('editorDate').title = label;
    $('editorDate').setAttribute('aria-label', label);
  }
  function fitMetadata() {
    if (editorScreen.hidden) return;
    const width = $('wordCount').closest('.metadata').clientWidth;
    const available = width - 40 - 8 - 24 - $('wordCount').scrollWidth;
    const maximum = Math.max(48, Math.min(120, available));
    $('folderButton').style.maxWidth = `${maximum}px`;
    $('folderButton').querySelector('.folder-chip').style.maxWidth = `${maximum}px`;
  }
  function updateCount() {
    let count = 0;
    for (const character of body.value) if (!/[\u0009-\u000d\u001c-\u0020\u00a0\u1680\u2000-\u200a\u2028\u2029\u202f\u205f\u3000]/u.test(character)) count++;
    $('wordCount').textContent = `${count} 字`;
    $('wordCount').setAttribute('aria-label', `正文 ${count} 字，不含空格和换行`);
    fitMetadata();
  }
  function resizeBody(keepCaretVisible = false) {
    if (currentId === null || editorScreen.hidden) return;
    fitMetadata();
    const scrollTop = bodyWrap?.scrollTop || 0;
    body.style.height = '0px';
    body.style.height = `${Math.max(48, body.scrollHeight)}px`;
    body.scrollTop = 0;
    if (!bodyWrap) return;
    bodyWrap.scrollTop = scrollTop;
    if (keepCaretVisible && document.activeElement === body && body.selectionEnd === body.value.length) {
      const bottom = body.getBoundingClientRect().bottom;
      const visibleBottom = bodyWrap.getBoundingClientRect().bottom;
      if (bottom > visibleBottom) bodyWrap.scrollTop += bottom - visibleBottom + 12;
    }
  }
  function updateHistory() {
    const pending = history && !sameContent(history.current, snapshot());
    $('undo').disabled = !history || composing || !(history.canUndo || pending);
    $('redo').disabled = !history || composing || !history.canRedo || pending;
  }
  function recordTyping() {
    if (!history || currentId === null || composing) return;
    const now = performance.now();
    const merge = now - lastTypingAt < 1200 && editKind === lastEditKind && editKind !== 0;
    history.record(snapshot(), merge);
    lastTypingAt = now;
    lastEditKind = editKind;
    updateHistory();
  }
  function finishHistoryGroup() {
    clearTimeout(historyTimer);
    if (history && !composing) history.record(snapshot(), false);
    lastTypingAt = 0;
    lastEditKind = 0;
  }
  function rememberSelection() {
    if (history && !composing && sameContent(history.current, snapshot())) {
      history.rememberSelection(body.selectionStart, body.selectionEnd);
    }
  }
  function changed() {
    if (currentId === null) return;
    dirty = true;
    setStatus(importing ? '正在导入图片…' : '保存中…');
    clearTimeout(saveTimer);
    saveTimer = setTimeout(() => {
      if (composing) changed();
      else persist(false);
    }, 700);
  }
  function persist(tellUser) {
    clearTimeout(saveTimer);
    if (currentId === null) return true;
    const note = currentNote();
    if (!note) {
      setStatus('保存失败 · 点右上角重试');
      if (tellUser) ui.alert('没有保存成功', '这篇笔记已不存在，你的编辑内容仍保留在当前页面。');
      return false;
    }
    if (!dirty) return true;
    const previous = {body: note.body, images: note.images, date: note.date};
    note.body = body.value;
    note.images = [...attachments];
    note.date = typeof note.date === 'number' ? Date.now() : new Date().toISOString();
    try {
      if (saveStore() === false) throw new Error('浏览器未能保存数据，请检查存储空间后重试。');
      dirty = false;
      setStatus(importing ? '正在导入图片…' : '已保存');
      updateDate(note.date || note.createdAt);
      return true;
    } catch (error) {
      Object.assign(note, previous);
      setStatus(importing ? '保存失败 · 图片仍在导入' : '保存失败 · 点右上角重试');
      if (tellUser) ui.alert('没有保存成功', '你的编辑内容还在，请重试。' + errorDetail(error));
      return false;
    }
  }
  function save() { return persist(true); }

  function syncLocation() {
    const note = currentNote();
    if (note) updateDate(note.date || note.createdAt);
    const book = (store.books || []).find(item => item.id === note?.notebookId);
    const folder = book && (store.folders || []).find(item => item.id === book.folderId);
    const name = book ? `${folder ? `${folder.name} / ` : ''}${book.name}` : '未分类';
    let chip = $('folderButton').querySelector('.folder-chip');
    if (!chip) {
      chip = document.createElement('span');
      chip.className = 'folder-chip';
      $('folderButton').replaceChildren(chip);
    }
    chip.textContent = name;
    $('folderButton').setAttribute('aria-label', `当前位置：${name}，点按移动笔记`);
    $('folderButton').title = name;
  }

  function releaseCard(card) {
    card.active = false;
    card.image.onload = null;
    card.image.onerror = null;
    card.image.removeAttribute('src');
    if (card.url) URL.revokeObjectURL(card.url);
    card.url = null;
    card.blob = null;
    card.element.remove();
  }
  function clearCards() {
    for (const card of cards.values()) releaseCard(card);
    cards.clear();
  }
  function imageFailed(card) {
    card.image.hidden = true;
    card.image.style.display = 'none';
    card.status.hidden = false;
    card.status.textContent = '图片暂时无法读取，原文件引用已保留';
    card.open.setAttribute('aria-label', '图片暂时无法读取，原文件引用已保留');
  }
  async function loadImage(card, token) {
    try {
      const blob = await images.get(card.id);
      if (!card.active || token !== generation) return;
      if (!blob) { imageFailed(card); return; }
      card.blob = blob;
      card.url = URL.createObjectURL(blob);
      card.image.onload = () => {
        if (!card.active || token !== generation) return;
        card.status.hidden = true;
        card.open.style.minHeight = '0';
      };
      card.image.onerror = () => {
        if (!card.active || token !== generation) return;
        imageFailed(card);
        URL.revokeObjectURL(card.url);
        card.url = null;
        card.blob = null;
      };
      card.image.src = card.url;
    } catch {
      if (card.active && token === generation) imageFailed(card);
    }
  }
  function previewImage(card) {
    if (!card.url || !card.blob || !card.image.naturalWidth) {
      ui.toast('图片暂未加载，请稍后重试');
      return;
    }
    body.blur();
    const preview = document.createElement('img');
    preview.className = 'editor-image-preview';
    preview.alt = '笔记图片预览';
    const url = URL.createObjectURL(card.blob);
    preview.src = url;
    Object.assign(preview.style, {display: 'block', width: '100%', maxHeight: '60dvh', objectFit: 'contain'});
    previewClose = ui.dialog({
      title: '图片预览', content: preview, actions: [{label: '关闭', primary: true}],
      onClose() { URL.revokeObjectURL(url); previewClose = null; }
    });
  }
  async function removeImage(id, occurrence = 0) {
    const token = generation;
    const accepted = await ui.confirm('移除这张图片？', '只会移除笔记里的副本，设备里的原图不会受影响。', '移除', '保留');
    if (!accepted || token !== generation || currentId === null || composing) return;
    let remaining = occurrence;
    const index = attachments.findIndex(value => value === id && remaining-- === 0);
    if (index < 0) return;
    finishHistoryGroup();
    attachments.splice(index, 1);
    cleanupCandidates.add(id);
    history.record(snapshot(), false);
    changed();
    renderImages();
    updateHistory();
    save();
  }
  function createCard(id, occurrence = 0) {
    const element = document.createElement('div');
    element.className = 'editor-image-card';
    const open = document.createElement('button');
    open.type = 'button';
    open.className = 'editor-image-open';
    open.setAttribute('aria-label', '笔记中的图片，点按查看大图');
    Object.assign(open.style, {display: 'block', width: '100%', minHeight: '100px'});
    const image = document.createElement('img');
    image.alt = '笔记图片';
    image.decoding = 'async';
    Object.assign(image.style, {display: 'block', width: '100%', maxHeight: '420px', objectFit: 'contain'});
    open.append(image);
    const status = document.createElement('div');
    status.className = 'editor-image-status';
    status.textContent = '正在加载图片…';
    status.setAttribute('role', 'status');
    const remove = document.createElement('button');
    remove.type = 'button';
    remove.className = 'editor-image-remove';
    remove.textContent = '移除图片';
    remove.style.minHeight = '48px';
    remove.addEventListener('click', () => removeImage(id, occurrence));
    element.append(open, status, remove);
    const card = {id, element, open, image, status, active: true, url: null, blob: null};
    open.addEventListener('click', () => previewImage(card));
    loadImage(card, generation);
    return card;
  }
  function renderImages() {
    const counts = new Map();
    const entries = attachments.map(id => {
      const occurrence = counts.get(id) || 0; counts.set(id, occurrence + 1);
      return {id, occurrence, key: JSON.stringify([id, occurrence])};
    });
    const kept = new Set(entries.map(entry => entry.key));
    for (const [key, card] of cards) {
      if (!kept.has(key)) { releaseCard(card); cards.delete(key); }
    }
    for (let index = 0; index < entries.length; index++) {
      const {id, occurrence, key} = entries[index];
      let card = cards.get(key);
      if (!card) { card = createCard(id, occurrence); cards.set(key, card); }
      if (imageList.children[index] !== card.element) imageList.insertBefore(card.element, imageList.children[index] || null);
    }
  }

  function restoreSnapshot(next) {
    const imagesChanged = attachments.length !== next.images.length || attachments.some((id, index) => id !== next.images[index]);
    attachments.forEach(id => cleanupCandidates.add(id));
    attachments = [...next.images];
    body.value = next.body;
    body.setSelectionRange(next.start, next.end);
    lastInputBody = next.body;
    beforeEdit = null;
    lastTypingAt = 0;
    if (imagesChanged) renderImages();
    updateCount();
    resizeBody();
    changed();
    updateHistory();
  }
  function undo() {
    if (!history || composing || currentId === null) return;
    finishHistoryGroup();
    if (history.canUndo) restoreSnapshot(history.undo());
    updateHistory();
  }
  function redo() {
    if (!history || composing || currentId === null) return;
    finishHistoryGroup();
    if (history.canRedo) restoreSnapshot(history.redo());
    updateHistory();
  }
  function setImporting(value) {
    importing = value;
    $('imageButton').disabled = value;
    $('imageButton').setAttribute('aria-busy', String(value));
    $('saveStatus').setAttribute('aria-busy', String(value));
    $('saveStatus').classList.toggle('is-importing', value);
  }
  function completeImport(id, token) {
    if (token !== generation || currentId === null) {
      Promise.resolve(images.remove(id)).catch(() => {});
      return;
    }
    if (composing) { pendingImport = {id, token}; return; }
    pendingImport = null;
    setImporting(false);
    finishHistoryGroup();
    attachments.push(id);
    cleanupCandidates.add(id);
    history.record(snapshot(), false);
    changed();
    renderImages();
    updateHistory();
    if (save()) ui.toast('已插入图片副本');
  }
  async function importImage(file) {
    if (!file || currentId === null) return;
    if (importing) { ui.toast('上一张图片还在处理中'); return; }
    const token = generation;
    setImporting(true);
    setStatus('正在导入图片…');
    try {
      if (!file.size) throw new Error('图片内容为空。');
      if (file.size > MAX_IMAGE_BYTES) throw new Error('图片超过 30 MB，请选择较小的图片。');
      if (file.type && !file.type.startsWith('image/')) throw new Error('请选择 JPEG、PNG 或 WebP 等图片文件。');
      const id = await images.import(file);
      if (!id || typeof id !== 'string') throw new Error('未能保存图片副本。');
      completeImport(id, token);
    } catch (error) {
      if (token !== generation) return;
      setImporting(false);
      setStatus(dirty ? '有内容尚未保存' : '已保存');
      ui.alert('图片没有添加成功', '请重新选择图片。支持不超过 30 MB 的常见图片。' + errorDetail(error));
    }
  }

  async function cleanupImages(candidates) {
    let failed = false;
    for (const id of candidates) {
      if (store.notes.some(note => Array.isArray(note.images) && note.images.includes(id))) continue;
      try { await images.remove(id); } catch { failed = true; }
    }
    if (failed) ui.toast('笔记已保存，部分图片副本暂未清理');
  }
  function closeEditor() {
    clearTimeout(saveTimer);
    clearTimeout(historyTimer);
    const candidates = [...cleanupCandidates];
    cleanupCandidates.clear();
    if (previewClose) previewClose();
    body.blur();
    transitionPage(editorScreen, $('listScreen'), false, () => {
      // The departing snapshot already owns its pixels before URLs and cards are released.
      clearCards();
      currentId = null;
      generation++;
      history = null;
      composing = false;
      pendingImport = null;
      dirty = false;
      cleanupImages(candidates);
      onClose();
    });
  }
  function back() {
    if (currentId === null) return;
    if (importing) { ui.toast('正在处理图片，请稍候'); return; }
    body.blur();
    const note = currentNote();
    const empty = !body.value.trim() && attachments.length === 0 && !note?.favorite && !note?.pinned && !note?.sticker;
    if (freshDraft && note && !note.deletedAt && empty) {
      clearTimeout(saveTimer);
      const index = store.notes.indexOf(note);
      store.notes.splice(index, 1);
      try {
        if (saveStore() === false) throw new Error('浏览器未能保存更改。');
        closeEditor();
      } catch (error) {
        store.notes.splice(index, 0, note);
        ui.alert('暂时无法关闭', '空白草稿未能清理，请重试。' + errorDetail(error));
      }
      return;
    }
    if (save()) closeEditor();
  }
  function open(noteId, fresh = false) {
    if (importing) { ui.toast('正在处理图片，请稍候'); return false; }
    const note = store.notes.find(item => item.id === noteId);
    if (!note) { ui.alert('无法打开笔记', '这篇笔记不存在或已被删除。'); return false; }
    if (currentId !== null && !save()) return false;
    clearTimeout(saveTimer);
    clearTimeout(historyTimer);
    if (previewClose) previewClose();
    const from = currentId === null ? $('listScreen') : editorScreen;
    transitionPage(from, editorScreen, true, () => {
      clearCards();
      if (currentId !== null) cleanupImages([...cleanupCandidates]);
      cleanupCandidates.clear();
      generation++;
      currentId = noteId;
      freshDraft = fresh;
      attachments = Array.isArray(note.images) ? [...note.images] : [];
      body.value = typeof note.body === 'string' ? note.body : '';
      body.setSelectionRange(0, 0);
      lastInputBody = body.value;
      beforeEdit = null;
      composing = false;
      dirty = false;
      pendingImport = null;
      lastTypingAt = 0;
      lastEditKind = 0;
      editKind = 0;
      history = new EditorHistory(snapshot());
      setImporting(false);
      imagePicker.value = '';
      updateDate(note.date || note.createdAt);
      setStatus('已保存');
      updateCount();
      updateHistory();
      syncLocation();
      renderImages();
      resizeBody();
      if (bodyWrap) bodyWrap.scrollTop = 0;
      if (fresh) body.focus({preventScroll: true});
      else $('back').focus({preventScroll: true});
    });
    return true;
  }

  $('back').addEventListener('click', back);
  $('undo').addEventListener('click', undo);
  $('redo').addEventListener('click', redo);
  $('save').addEventListener('click', () => {
    body.blur();
    if (save()) ui.toast(importing ? '当前内容已保存，图片仍在导入' : '已保存');
  });
  $('editorMore').addEventListener('click', event => { if (currentId !== null) onMenu(event.currentTarget); });
  $('folderButton').addEventListener('click', () => {
    body.blur();
    if (currentId !== null && save()) onMove(currentNote());
  });
  $('imageButton').addEventListener('click', () => {
    if (currentId === null) return;
    if (importing) { ui.toast('上一张图片还在处理中'); return; }
    body.blur();
    pickerGeneration = generation;
    imagePicker.value = '';
    imagePicker.click();
  });
  imagePicker.addEventListener('change', () => {
    const file = imagePicker.files?.[0];
    imagePicker.value = '';
    if (file && pickerGeneration === generation) importImage(file);
  });
  body.addEventListener('beforeinput', event => {
    if (!history || currentId === null) return;
    if (!composing && (event.inputType === 'historyUndo' || event.inputType === 'historyRedo') && event.cancelable) {
      event.preventDefault();
      if (event.inputType === 'historyUndo') undo(); else redo();
      return;
    }
    rememberSelection();
    beforeEdit = {body: body.value, start: body.selectionStart, end: body.selectionEnd};
  });
  body.addEventListener('input', event => {
    if (!history || currentId === null) return;
    const previous = beforeEdit?.body ?? lastInputBody;
    const selectionLength = beforeEdit ? beforeEdit.end - beforeEdit.start : 0;
    const delta = body.value.length - previous.length;
    editKind = delta > 0 && selectionLength === 0 ? 1 : delta < 0 && !event.inputType?.startsWith('insert') ? -1 : 0;
    lastInputBody = body.value;
    beforeEdit = null;
    updateCount();
    resizeBody(true);
    changed();
    clearTimeout(historyTimer);
    if (!composing && !event.isComposing) historyTimer = setTimeout(recordTyping, 350);
    updateHistory();
  });
  body.addEventListener('compositionstart', () => {
    clearTimeout(historyTimer);
    if (history && !sameContent(history.current, snapshot())) recordTyping();
    composing = true;
    updateHistory();
  });
  body.addEventListener('compositionend', () => {
    composing = false;
    if (currentId === null) return;
    clearTimeout(historyTimer);
    historyTimer = setTimeout(recordTyping, 350);
    changed();
    updateCount();
    resizeBody(true);
    updateHistory();
    if (pendingImport) {
      const {id, token} = pendingImport;
      completeImport(id, token);
    }
  });
  body.addEventListener('select', rememberSelection);
  body.addEventListener('keyup', rememberSelection);
  body.addEventListener('click', rememberSelection);
  body.addEventListener('blur', () => { if (!composing && currentId !== null) finishHistoryGroup(); });
  body.addEventListener('keydown', event => {
    if (composing || event.isComposing || !(event.metaKey || event.ctrlKey) || event.altKey) return;
    const key = event.key.toLowerCase();
    if (key === 'z' || key === 'y') {
      event.preventDefault();
      if (key === 'y' || event.shiftKey) redo(); else undo();
    } else if (key === 's') {
      event.preventDefault();
      if (save()) ui.toast(importing ? '当前内容已保存，图片仍在导入' : '已保存');
    }
  });
  bodyWrap?.addEventListener('click', event => { if (event.target === bodyWrap) body.focus(); });
  if (typeof ResizeObserver !== 'undefined' && bodyWrap) new ResizeObserver(() => resizeBody()).observe(bodyWrap);
  const phone = body.closest('.phone');
  if (phone) new MutationObserver(() => resizeBody()).observe(phone, {attributes: true, attributeFilter: ['style', 'class']});
  window.addEventListener('beforeunload', event => {
    if (currentId !== null && (!persist(false) || importing)) {
      event.preventDefault();
      event.returnValue = '';
    }
  });
  window.addEventListener('pagehide', () => {
    if (currentId !== null) persist(false);
  });

  return {open, save, back, syncLocation,
    closeForSync() { if (currentId !== null) closeEditor(); },
    get currentId() { return currentId; }, get importing() { return importing; }};
}
