const STORAGE_KEY = 'jianji-preview-data-v2';
const FONTS = new Set(['system', 'small', 'normal', 'large', 'extra']);
const DEFAULT_BOOKS = { morning: 'daily', reading: 'reading-book', shopping: 'daily' };
let nextId = 0;

export function uid(prefix = 'item') {
  const crypto = globalThis.crypto;
  if (globalThis.jianjiDesktop && crypto?.randomUUID) return crypto.randomUUID();
  let random;
  if (crypto && typeof crypto.randomUUID === 'function') random = crypto.randomUUID();
  else if (crypto && typeof crypto.getRandomValues === 'function') {
    random = Array.from(crypto.getRandomValues(new Uint8Array(16)), value => value.toString(16).padStart(2, '0')).join('');
  } else random = `${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}-${++nextId}`;
  return `${prefix}-${random}`;
}

function sampleNotes() {
  return [
    { id: 'morning', body: '今天的安排\n整理一下桌面，把最近的想法写下来。下午去散步，路上买些水果。', date: '2026-10-16T10:46:00+08:00' },
    { id: 'reading', body: '阅读随记\n读到喜欢的句子就停一停，想想它和自己的生活有什么联系。', date: '2026-10-15T20:18:00+08:00' },
    { id: 'shopping', body: '周末采购\n牛奶、鸡蛋、燕麦、苹果。\n顺便看看书店有没有新书。', date: '2026-10-14T18:32:00+08:00' }
  ];
}

function defaultOrganization() {
  return {
    folders: [{ id: 'life', name: '生活' }, { id: 'study', name: '学习' }],
    books: [{ id: 'daily', folderId: 'life', name: '日常记录' }, { id: 'reading-book', folderId: 'study', name: '阅读随记' }]
  };
}

function requireValue(condition, message) {
  if (!condition) throw new Error(message);
}

function object(value) {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

function has(value, key) {
  return Object.prototype.hasOwnProperty.call(value, key);
}

function normalizeNotes(value, legacy = false) {
  requireValue(Array.isArray(value), '笔记数据应为列表');
  const seen = new Set();
  return value.map((note, index) => {
    const label = `第 ${index + 1} 篇笔记`;
    requireValue(object(note) && typeof note.id === 'string' && note.id.length > 0 && typeof note.body === 'string' && typeof note.date === 'string', `${label}的编号、正文或日期格式不正确`);
    requireValue(!seen.has(note.id), `${label}的编号重复`);
    seen.add(note.id);
    const normalized = {
      id: note.id,
      body: note.body,
      date: note.date,
      createdAt: has(note, 'createdAt') ? note.createdAt : note.date,
      notebookId: has(note, 'notebookId') ? note.notebookId : (legacy ? DEFAULT_BOOKS[note.id] || null : null),
      favorite: has(note, 'favorite') ? note.favorite : false,
      pinned: has(note, 'pinned') ? note.pinned : false,
      deletedAt: has(note, 'deletedAt') ? note.deletedAt : null,
      images: has(note, 'images') ? note.images : [],
      conflictOf: has(note, 'conflictOf') ? note.conflictOf : '',
      color: has(note, 'color') ? note.color : 'cream',
      sticker: has(note, 'sticker') ? note.sticker : ''
    };
    requireValue(typeof normalized.createdAt === 'string', `${label}的创建日期格式不正确`);
    requireValue(normalized.notebookId === null || typeof normalized.notebookId === 'string', `${label}的笔记本编号格式不正确`);
    requireValue(typeof normalized.favorite === 'boolean' && typeof normalized.pinned === 'boolean', `${label}的收藏或置顶状态格式不正确`);
    requireValue(normalized.deletedAt === null || typeof normalized.deletedAt === 'string', `${label}的删除日期格式不正确`);
    requireValue(Array.isArray(normalized.images) && normalized.images.every(id => typeof id === 'string' && id.length > 0), `${label}的图片编号格式不正确`);
    requireValue(typeof normalized.conflictOf === 'string' && typeof normalized.color === 'string' && typeof normalized.sticker === 'string', `${label}的同步属性格式不正确`);
    normalized.images = normalized.images.slice();
    return normalized;
  });
}

function normalizeOrganization(value) {
  requireValue(object(value) && Array.isArray(value.folders) && Array.isArray(value.books), '分类数据格式不正确');
  const folderIds = new Set();
  const bookIds = new Set();
  const folders = value.folders.map(folder => {
    requireValue(object(folder) && typeof folder.id === 'string' && folder.id.length > 0 && typeof folder.name === 'string', '文件夹数据格式不正确');
    requireValue(!folderIds.has(folder.id), '文件夹编号重复');
    folderIds.add(folder.id);
    return { id: folder.id, name: folder.name, color: typeof folder.color === 'string' ? folder.color : 'cream' };
  });
  const books = value.books.map(book => {
    requireValue(object(book) && typeof book.id === 'string' && book.id.length > 0 && typeof book.folderId === 'string' && typeof book.name === 'string', '笔记本数据格式不正确');
    requireValue(!bookIds.has(book.id), '笔记本编号重复');
    bookIds.add(book.id);
    return { id: book.id, folderId: book.folderId, name: book.name };
  });
  return { folders, books };
}

function normalizeExpanded(value) {
  requireValue(Array.isArray(value) && value.every(id => typeof id === 'string'), '文件夹展开状态格式不正确');
  return [...new Set(value)];
}

function normalizeFont(value) {
  requireValue(FONTS.has(value), '字体大小设置格式不正确');
  return value;
}

function normalizeStore(value) {
  requireValue(object(value), '保存的数据格式不正确');
  return {
    notes: normalizeNotes(value.notes),
    ...normalizeOrganization(value),
    font: normalizeFont(value.font),
    expanded: normalizeExpanded(value.expanded)
  };
}

function parse(raw, label) {
  try { return JSON.parse(raw); }
  catch { throw new Error(`${label}不是有效的 JSON，原始数据已保留`); }
}

function readStorage(key) {
  try { return globalThis.localStorage.getItem(key); }
  catch { throw new Error('浏览器禁止读取本地存储，请允许此网站使用本地存储后刷新'); }
}

function loadStore() {
  const value = { notes: [], folders: [], books: [], font: 'system', expanded: [] };
  if (globalThis.jianjiDesktop) {
    try {
      const loaded = globalThis.jianjiDesktop.loadState();
      if (loaded.error) throw new Error(loaded.error);
      return {value: normalizeStore(loaded.state), error: ''};
    } catch (error) { return {value, error: error.message || '无法读取本机笔记文件'}; }
  }
  const errors = [];
  let raw;
  try { raw = readStorage(STORAGE_KEY); }
  catch (error) { return { value, error: error.message }; }
  if (raw !== null) {
    try { return { value: normalizeStore(parse(raw, '已保存的数据')), error: '' }; }
    catch (error) { return { value, error: error.message }; }
  }

  // Read each older key independently so readable notes remain available even
  // when another preference is damaged. No original key is changed here.
  try {
    const notes = readStorage('jianji-preview-notes');
    value.notes = normalizeNotes(notes === null ? sampleNotes() : parse(notes, '旧版笔记'), true);
  } catch (error) { errors.push(error.message); }
  try {
    const organization = readStorage('jianji-preview-organization');
    Object.assign(value, normalizeOrganization(organization === null ? defaultOrganization() : parse(organization, '旧版分类')));
  } catch (error) { errors.push(error.message); }
  try {
    const expanded = readStorage('jianji-preview-expanded');
    value.expanded = expanded === null ? value.folders.map(folder => folder.id) : normalizeExpanded(parse(expanded, '文件夹展开状态'));
  } catch (error) { errors.push(error.message); }
  try {
    const font = readStorage('jianji-preview-font');
    value.font = font === null ? 'system' : normalizeFont(font);
  } catch (error) { errors.push(error.message); }
  return { value, error: errors.join('；') };
}

const loaded = loadStore();
export const store = loaded.value;
export const loadError = loaded.error
  ? `本地数据读取失败：${loaded.error}。原始数据未被修改；为避免丢失内容，修复后刷新页面才能保存。`
  : '';

export function saveStore() {
  if (loadError) throw new Error(loadError);
  let serialized;
  try { serialized = JSON.stringify(normalizeStore(store)); }
  catch (error) { throw new Error(`没有保存成功：${error.message}。当前内容仍保留在页面中。`); }
  if (globalThis.jianjiDesktop) {
    const result = globalThis.jianjiDesktop.saveState(JSON.parse(serialized));
    if (!result?.ok) throw new Error(result?.error || '没有保存成功，请重试。');
    window.dispatchEvent(new CustomEvent('jianji:state-saved'));
    return;
  }
  try { globalThis.localStorage.setItem(STORAGE_KEY, serialized); }
  catch (error) {
    if (error && (error.name === 'QuotaExceededError' || error.name === 'NS_ERROR_DOM_QUOTA_REACHED')) {
      throw new Error('没有保存成功：浏览器本地存储空间不足，请先导出笔记并释放空间。当前内容仍保留在页面中。');
    }
    throw new Error('没有保存成功：浏览器无法写入本地存储，请允许此网站使用本地存储。当前内容仍保留在页面中。');
  }
  window.dispatchEvent(new CustomEvent('jianji:state-saved'));
}

export function applyDesktopState(state) {
  Object.assign(store, normalizeStore(state));
}
