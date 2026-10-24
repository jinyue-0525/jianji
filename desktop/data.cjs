'use strict';
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const P = require('./protocol.cjs');
const {mkdir, atomicWrite, writeJson, readJson} = require('./files.cjs');
const EMPTY = () => ({notes: [], folders: [], books: [], font: 'system', expanded: []});
const FONTS = new Set(['system', 'small', 'normal', 'large', 'extra']);
function iso(time) { const date = new Date(time); return Number.isNaN(date.getTime()) ? '1970-01-01T00:00:00.000Z' : date.toISOString(); }
function millis(value, nullable = false) {
  if (nullable && value === null) return 0;
  P.check(typeof value === 'string', '笔记日期格式无效');
  const time = Date.parse(value); P.check(P.integer(time), '笔记日期格式无效'); return time;
}
function normalizeState(input) {
  P.check(P.object(input) && Array.isArray(input.notes) && Array.isArray(input.folders) && Array.isArray(input.books), '本地笔记数据无效');
  P.check(input.notes.length + input.folders.length + input.books.length <= P.MAX_RECORDS, '记录数量超过上限');
  P.check(FONTS.has(input.font) && Array.isArray(input.expanded) && input.expanded.every(id => P.ID.test(id)), '本地显示设置无效');
  const seen = new Set(), reserve = (kind, value) => {
    P.check(P.object(value) && P.ID.test(value.id) && !seen.has(value.id), '记录编号无效或重复'); seen.add(value.id);
  };
  const folders = input.folders.map(folder => {
    reserve('folder', folder); P.check(typeof folder.name === 'string' && (folder.color === undefined || typeof folder.color === 'string'), '文件夹格式无效');
    return {id: folder.id, name: folder.name, ...(folder.color === undefined ? {} : {color: folder.color})};
  });
  const books = input.books.map(book => {
    reserve('book', book); P.check(typeof book.name === 'string' && P.ID.test(book.folderId), '笔记本格式无效');
    return {id: book.id, name: book.name, folderId: book.folderId};
  });
  const notes = input.notes.map(note => {
    reserve('note', note);
    P.check(typeof note.body === 'string' && (note.notebookId === null || P.ID.test(note.notebookId)) && typeof note.favorite === 'boolean' && typeof note.pinned === 'boolean', '笔记格式无效');
    millis(note.date); millis(note.createdAt); millis(note.deletedAt, true);
    P.check(Array.isArray(note.images) && note.images.length <= 10000 && note.images.every(hash => typeof hash === 'string' && P.HASH.test(hash)), '图片编号无效');
    const result = {id: note.id, body: note.body, notebookId: note.notebookId, date: note.date, createdAt: note.createdAt,
      deletedAt: note.deletedAt, favorite: note.favorite, pinned: note.pinned, images: [...note.images]};
    for (const key of ['color', 'sticker', 'conflictOf']) {
      if (note[key] !== undefined) { P.check(typeof note[key] === 'string', '笔记附加信息无效'); result[key] = note[key]; }
    }
    P.check(!result.conflictOf || P.ID.test(result.conflictOf), '冲突笔记编号无效'); return result;
  });
  const state = {notes, folders, books, font: input.font, expanded: [...new Set(input.expanded)]};
  P.check(Buffer.byteLength(JSON.stringify(state)) <= P.MAX_JSON, '本地数据超过 32 MiB'); return state;
}
function uniqueNames(items, group) {
  const used = new Set();
  return [...items].sort((a, b) => a.id < b.id ? -1 : 1).map(item => {
    const fold = value => value.replace(/[A-Z]/g, char => char.toLowerCase());
    const key = group(item) + '\0', original = item.name;
    let name = original;
    if (used.has(key + fold(name))) {
      const digest = P.sha(item.id); let length = 8, suffix = 2;
      name = original + ' (' + digest.slice(0, length) + ')';
      while (used.has(key + fold(name))) {
        if (length < 64) { length += 4; name = original + ' (' + digest.slice(0, length) + ')'; }
        else name = original + ' (' + digest + '-' + suffix++ + ')';
      }
    }
    used.add(key + fold(name)); return {...item, name};
  });
}
function project(records, preferences) {
  const live = records.filter(record => !record.deleted);
  const folders = uniqueNames(live.filter(r => r.kind === 'folder').map(r => ({id: r.id, name: r.data.name, color: r.data.color})), () => '');
  const folderIds = new Set(folders.map(folder => folder.id));
  const books = uniqueNames(live.filter(r => r.kind === 'book' && folderIds.has(r.data.folderId)).map(r => ({id: r.id, ...r.data})), book => book.folderId);
  const bookIds = new Set(books.map(book => book.id));
  const notes = live.filter(r => r.kind === 'note').map(({id, data}) => ({id, body: data.body, notebookId: bookIds.has(data.notebookId) ? data.notebookId : null,
    createdAt: iso(data.createdAt), date: iso(data.updatedAt), deletedAt: data.trashedAt ? iso(data.trashedAt) : null,
    favorite: data.favorite, pinned: data.pinned, images: [...data.images], color: data.color, sticker: data.sticker, conflictOf: data.conflictOf}));
  return {notes, folders, books, font: FONTS.has(preferences?.font) ? preferences.font : 'system', expanded: (preferences?.expanded || []).filter(id => folderIds.has(id))};
}
function wireData(kind, item, observed, previous) {
  const data = previous ? P.clone(previous.data) : kind === 'folder' ? {name: '', color: ''} : kind === 'book' ? {name: '', folderId: item.folderId} :
    {body: '', notebookId: null, createdAt: 0, updatedAt: 0, trashedAt: 0, favorite: false, pinned: false, images: [], color: '', sticker: '', conflictOf: ''};
  const fields = kind === 'folder' ? {name: 'name', color: 'color'} : kind === 'book' ? {name: 'name', folderId: 'folderId'} :
    {body: 'body', notebookId: 'notebookId', createdAt: 'createdAt', date: 'updatedAt', deletedAt: 'trashedAt', favorite: 'favorite', pinned: 'pinned', images: 'images', color: 'color', sticker: 'sticker', conflictOf: 'conflictOf'};
  for (const [local, wire] of Object.entries(fields)) {
    if (item[local] === undefined || (observed && JSON.stringify(item[local]) === JSON.stringify(observed[local]))) continue;
    data[wire] = ['createdAt', 'date', 'deletedAt'].includes(local) ? millis(item[local], local === 'deletedAt') : P.clone(item[local]);
  }
  return data;
}
class DataStore {
  constructor(directory, deviceId) {
    this.directory = directory; this.deviceId = deviceId; this.file = path.join(directory, 'sync-state.json'); this.blobDirectory = path.join(directory, 'blobs');
    this.backupDirectory = path.join(directory, 'backups'); this.verified = new Map(); this.locked = false; this.error = '';
    [directory, this.blobDirectory, this.backupDirectory].forEach(mkdir);
    this.document = {schema: 1, revision: 0, records: [], observed: EMPTY(), lastSyncAt: 0};
    try {
      const saved = readJson(this.file);
      if (saved) {
        P.check(saved.schema === 1 && P.integer(saved.revision) && Array.isArray(saved.records), '本地同步数据版本无效');
        P.validateEnvelope({protocol: 1, deviceId, records: saved.records}); normalizeState(saved.observed);
        this.document = saved;
      }
    } catch { this.error = '本地数据读取失败，原文件已保留；为避免覆盖内容，当前禁止保存和同步。请打开数据文件夹恢复备份。'; }
  }
  assertReady() { if (this.error) throw new Error(this.error); }
  loadState() { return {state: P.clone(this.document.observed), ...(this.error ? {error: this.error} : {})}; }
  persist(document) { writeJson(this.file, document); this.document = document; }
  saveState(input) {
    this.assertReady(); P.check(!this.locked, '正在同步，请稍后保存');
    const state = normalizeState(input), records = new Map(this.document.records.map(record => [record.kind + ':' + record.id, P.clone(record)]));
    for (const [kind, collection] of [['folder', 'folders'], ['book', 'books'], ['note', 'notes']]) {
      const observed = new Map(this.document.observed[collection].map(item => [item.id, item]));
      const present = new Set();
      for (const item of state[collection]) {
        present.add(item.id); const key = kind + ':' + item.id, prior = records.get(key);
        P.check(!prior?.deleted, '该编号已永久删除，请创建一条新记录');
        const data = wireData(kind, item, observed.get(item.id), prior);
        if (!prior || P.canonical(data) !== P.canonical(prior.data)) {
          const clock = {...prior?.clock}; clock[this.deviceId] = (clock[this.deviceId] || 0) + 1;
          P.check(Number.isSafeInteger(clock[this.deviceId]), '版本时钟超过上限'); records.set(key, {kind, id: item.id, clock, deleted: false, data});
        }
      }
      for (const id of observed.keys()) if (!present.has(id)) {
        const key = kind + ':' + id, prior = records.get(key);
        if (prior && !prior.deleted) {
          const clock = {...prior.clock}; clock[this.deviceId] = (clock[this.deviceId] || 0) + 1;
          P.check(Number.isSafeInteger(clock[this.deviceId]), '版本时钟超过上限'); records.set(key, {...prior, clock, deleted: true});
        }
      }
    }
    const result = [...records.values()]; P.validateEnvelope({protocol: 1, deviceId: this.deviceId, records: result}); this.requireImages(result);
    this.persist({...this.document, revision: this.document.revision + 1, records: result, observed: state});
  }
  merge(envelope, receipt) {
    this.assertReady(); P.validateEnvelope(envelope); this.requireImages(envelope.records);
    const result = P.mergeRecords(this.document.records, envelope.records);
    P.validateEnvelope({protocol: 1, deviceId: this.deviceId, records: result.records}); this.requireImages(result.records);
    const state = project(result.records, this.document.observed);
    const backup = path.join(this.backupDirectory, Date.now() + '-' + crypto.randomUUID() + '.json');
    writeJson(backup, {...this.document, incoming: P.clone(envelope)});
    this.persist({...this.document, revision: this.document.revision + 1, records: result.records, observed: state,
      receipts: [...(this.document.receipts || []).filter(item => item.createdAt > Date.now() - 86400000).slice(-127), receipt].filter(Boolean)});
    return {protocol: 1, deviceId: this.deviceId, records: P.clone(result.records), summary: {received: envelope.records.length, conflicts: result.conflicts}, state: P.clone(state)};
  }
  blobFile(hash) { P.check(typeof hash === 'string' && P.HASH.test(hash), '图片编号无效'); return path.join(this.blobDirectory, hash); }
  hasBlob(hash) {
    const file = this.blobFile(hash);
    let stat; try { stat = fs.statSync(file); } catch (error) { if (error.code === 'ENOENT') return false; throw error; }
    if (!stat.isFile() || stat.size <= 0 || stat.size > P.MAX_IMAGE) return false;
    const signature = stat.size + ':' + stat.mtimeMs;
    if (this.verified.get(hash) === signature) return true;
    if (P.sha(fs.readFileSync(file)) !== hash) return false;
    this.verified.set(hash, signature); return true;
  }
  requireImages(records) { for (const hash of P.liveHashes(records)) P.check(this.hasBlob(hash), '同步图片尚未传输完整，请重新同步'); }
  putImage(bytes, expected) {
    this.assertReady(); const data = Buffer.from(bytes);
    P.check(data.length > 0 && data.length <= P.MAX_IMAGE, '图片为空或超过 30 MiB');
    const hash = P.sha(data); if (expected !== undefined) P.check(P.HASH.test(expected) && expected === hash, '图片校验失败');
    if (!this.hasBlob(hash)) atomicWrite(this.blobFile(hash), data);
    this.verified.delete(hash); return hash;
  }
  getImage(hash) { return this.hasBlob(hash) ? fs.readFileSync(this.blobFile(hash)) : null; }
  removeImage(hash) {
    this.assertReady(); P.check(!this.locked, '正在同步，请稍后清理图片'); this.blobFile(hash);
    if (P.liveHashes(this.document.records).has(hash)) return;
    // Hard tombstones and every retained pre-sync snapshot can be recovered with their assets.
    const refers = records => records.some(record => record.kind === 'note' && record.data.images.includes(hash));
    if (refers(this.document.records)) return;
    for (const name of fs.readdirSync(this.backupDirectory)) {
      if (!name.endsWith('.json')) continue;
      const backup = readJson(path.join(this.backupDirectory, name));
      if (!backup || !Array.isArray(backup.records)) throw new Error('无法验证备份，已保留图片');
      if (refers(backup.records) || (Array.isArray(backup.incoming?.records) && refers(backup.incoming.records))) return;
    }
    try { fs.unlinkSync(this.blobFile(hash)); } catch (error) { if (error.code !== 'ENOENT') throw error; }
    this.verified.delete(hash);
  }
}
module.exports = {DataStore, EMPTY};
