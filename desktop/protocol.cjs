'use strict';
const crypto = require('node:crypto');
const MAX_JSON = 32 * 1024 * 1024, MAX_RECORDS = 20000, MAX_IMAGE = 30 * 1024 * 1024;
const strict = expression => Object.freeze({test: value => typeof value === 'string' && expression.test(value)});
const UUID = strict(/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/);
const HASH = strict(/^[0-9a-f]{64}$/);
const ID = strict(/^(?:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}|conflict-[0-9a-f]{64})$/);
function check(value, message) { if (!value) throw new Error(message); }
function object(value) { return value !== null && typeof value === 'object' && !Array.isArray(value); }
function exact(value, keys, label) {
  check(object(value) && Object.keys(value).sort().join('\0') === [...keys].sort().join('\0'), `${label}字段不符合协议`);
}
function quote(value) {
  let output = '"';
  for (let i = 0; i < value.length; i++) {
    const code = value.charCodeAt(i);
    if (code === 34 || code === 92) output += '\\' + value[i];
    else if (code < 32 || code > 126) output += '\\u' + code.toString(16).padStart(4, '0');
    else output += value[i];
  }
  return output + '"';
}
function canonical(value) {
  if (value === null) return 'null';
  if (typeof value === 'string') return quote(value);
  if (typeof value === 'boolean') return String(value);
  if (typeof value === 'number') { check(Number.isSafeInteger(value), '数字超出协议范围'); return String(value); }
  if (Array.isArray(value)) return '[' + value.map(canonical).join(',') + ']';
  check(object(value), '数据类型不符合协议');
  return '{' + Object.keys(value).sort().map(key => quote(key) + ':' + canonical(value[key])).join(',') + '}';
}
function sha(value) { return crypto.createHash('sha256').update(value).digest('hex'); }
function fingerprint(record) { return sha(Buffer.from(canonical(record), 'utf8')); }
function clone(value) { return JSON.parse(JSON.stringify(value)); }
function integer(value) { return Number.isSafeInteger(value) && value >= 0; }
function validateRecord(record) {
  exact(record, ['kind', 'id', 'clock', 'deleted', 'data'], '记录');
  check(['folder', 'book', 'note'].includes(record.kind) && ID.test(record.id) && typeof record.deleted === 'boolean', '记录编号或类型无效');
  check(object(record.clock) && Object.keys(record.clock).length <= 256, '版本时钟无效');
  for (const [device, count] of Object.entries(record.clock)) check(UUID.test(device) && integer(count), '版本时钟无效');
  const data = record.data;
  if (record.kind === 'folder') {
    exact(data, ['name', 'color'], '文件夹'); check(typeof data.name === 'string' && typeof data.color === 'string', '文件夹内容无效');
  } else if (record.kind === 'book') {
    exact(data, ['name', 'folderId'], '笔记本'); check(typeof data.name === 'string' && ID.test(data.folderId), '笔记本内容无效');
  } else {
    exact(data, ['body', 'notebookId', 'createdAt', 'updatedAt', 'trashedAt', 'favorite', 'pinned', 'images', 'color', 'sticker', 'conflictOf'], '笔记');
    check(typeof data.body === 'string' && (data.notebookId === null || ID.test(data.notebookId)), '笔记内容无效');
    check([data.createdAt, data.updatedAt, data.trashedAt].every(integer), '笔记日期无效');
    check(typeof data.favorite === 'boolean' && typeof data.pinned === 'boolean', '笔记状态无效');
    check(Array.isArray(data.images) && data.images.length <= 10000 && data.images.every(hash => typeof hash === 'string' && HASH.test(hash)), '图片编号无效');
    check(['color', 'sticker', 'conflictOf'].every(key => typeof data[key] === 'string') && (!data.conflictOf || ID.test(data.conflictOf)), '笔记附加信息无效');
  }
  return record;
}
function validateEnvelope(value) {
  exact(value, ['protocol', 'deviceId', 'records'], '同步清单');
  check(value.protocol === 1 && UUID.test(value.deviceId) && Array.isArray(value.records) && value.records.length <= MAX_RECORDS, '同步协议或记录数量无效');
  const seen = new Set();
  value.records.forEach(record => {
    validateRecord(record);
    check(!seen.has(record.id), '同步清单包含重复编号'); seen.add(record.id);
  });
  check(Buffer.byteLength(canonical(value), 'utf8') <= MAX_JSON, '同步清单超过 32 MiB');
  return value;
}
function join(a, b) {
  const output = {};
  [...new Set([...Object.keys(a), ...Object.keys(b)])].sort().forEach(key => { output[key] = Math.max(a[key] || 0, b[key] || 0); });
  return output;
}
function compare(a, b) {
  let greater = false, smaller = false;
  new Set([...Object.keys(a), ...Object.keys(b)]).forEach(key => {
    if ((a[key] || 0) > (b[key] || 0)) greater = true;
    if ((a[key] || 0) < (b[key] || 0)) smaller = true;
  });
  return greater && !smaller ? 1 : smaller && !greater ? -1 : 0;
}
function mergePair(a, b) {
  const order = compare(a.clock, b.clock);
  if (order === 1) return {record: a};
  if (order === -1) return {record: b};
  const clock = join(a.clock, b.clock), sameData = canonical(a.data) === canonical(b.data);
  if (a.deleted === b.deleted && sameData) return {record: {...a, clock}};
  const af = fingerprint(a), bf = fingerprint(b);
  let winner;
  if (a.deleted !== b.deleted) winner = a.deleted ? a : b;
  else if (a.kind === 'note' && Boolean(a.data.trashedAt) !== Boolean(b.data.trashedAt)) winner = a.data.trashedAt ? a : b;
  else winner = af >= bf ? a : b;
  const loser = winner === a ? b : a;
  const result = {record: {...winner, clock}};
  if (a.kind === 'note' && !sameData && !loser.deleted) {
    result.copy = {kind: 'note', id: 'conflict-' + sha(a.id + '\n' + (loser === a ? af : bf)), clock: {...clock}, deleted: false,
      data: {...clone(loser.data), conflictOf: a.id, trashedAt: 0}};
  }
  return result;
}
function mergeRecords(local, incoming) {
  const kinds = new Map();
  for (const record of local) { check(!kinds.has(record.id), '本地记录包含重复编号'); kinds.set(record.id, record.kind); }
  const records = new Map(local.map(record => [record.kind + ':' + record.id, clone(record)]));
  const queue = [...incoming].sort((a, b) => (a.kind + ':' + a.id) < (b.kind + ':' + b.id) ? -1 : 1);
  const copies = new Set();
  for (let index = 0; index < queue.length; index++) {
    check(queue.length <= MAX_RECORDS * 4, '冲突数量超过同步上限');
    const next = clone(queue[index]), key = next.kind + ':' + next.id, previous = records.get(key);
    check(!kinds.has(next.id) || kinds.get(next.id) === next.kind, '同步编号对应不同类型，已停止合并'); kinds.set(next.id, next.kind);
    if (!previous) records.set(key, next);
    else {
      const merged = mergePair(previous, next); records.set(key, merged.record);
      if (merged.copy) {
        const copyKey = 'note:' + merged.copy.id;
        if (!records.has(copyKey)) copies.add(copyKey);
        queue.push(merged.copy);
      }
    }
    check(records.size <= MAX_RECORDS, '记录数量超过同步上限');
  }
  return {records: [...records.values()].sort((a, b) => (a.kind + ':' + a.id) < (b.kind + ':' + b.id) ? -1 : 1), conflicts: copies.size};
}
function liveHashes(records) {
  const hashes = new Set();
  records.forEach(record => { if (record.kind === 'note' && !record.deleted) record.data.images.forEach(hash => hashes.add(hash)); });
  return hashes;
}
module.exports = {MAX_JSON, MAX_RECORDS, MAX_IMAGE, UUID, HASH, ID, check, object, exact, canonical, sha, fingerprint, clone, integer, validateRecord, validateEnvelope, mergeRecords, liveHashes};
