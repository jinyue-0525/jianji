'use strict';
const {contextBridge, ipcRenderer} = require('electron');
const infoListeners = new Set(), prepareListeners = new Set(), resultListeners = new Set();
const preparations = new Map();
let revision = null;
function subscribe(collection, callback) {
  if (typeof callback !== 'function') throw new TypeError('需要回调函数');
  collection.add(callback); return () => collection.delete(callback);
}
function deliver(collection, value) { for (const callback of [...collection]) { try { callback(value); } catch {} } }
ipcRenderer.on('jianji:sync-info', (_event, value) => deliver(infoListeners, value));
ipcRenderer.on('jianji:prepare-sync', async (_event, {requestId}) => {
  if (typeof requestId !== 'string') return;
  if (!prepareListeners.size || [...preparations.values()].some(item => !item.finished)) {
    ipcRenderer.send('jianji:prepared', {requestId, ready: false}); return;
  }
  const job = {finished: false, result: null}; preparations.set(requestId, job);
  let ready = true;
  try { for (const callback of [...prepareListeners]) if (await callback() !== true) { ready = false; break; } }
  catch { ready = false; }
  job.finished = true;
  ipcRenderer.send('jianji:prepared', {requestId, ready});
  // A slow callback can finish after main's timeout. Repeat its failure result to release
  // a lock that the renderer acquired late, instead of leaving an unsaved editor frozen.
  if (job.result) { deliver(resultListeners, job.result); preparations.delete(requestId); }
});
ipcRenderer.on('jianji:sync-result', (_event, value) => {
  if (!value || typeof value.result?.ok !== 'boolean') return;
  if (Number.isSafeInteger(value.revision)) revision = value.revision;
  const job = preparations.get(value.requestId);
  if (job) { job.result = value.result; if (job.finished) preparations.delete(value.requestId); }
  deliver(resultListeners, value.result);
});
contextBridge.exposeInMainWorld('jianjiDesktop', Object.freeze({
  loadState: () => {
    const value = ipcRenderer.sendSync('jianji:load-state'); revision = value.revision;
    return {state: value.state, ...(value.error ? {error: value.error} : {})};
  },
  saveState: state => {
    const value = ipcRenderer.sendSync('jianji:save-state', {state, revision});
    if (value.ok) revision = value.revision;
    return value.ok ? {ok: true} : {ok: false, error: value.error};
  },
  putImage: bytes => {
    if (!(bytes instanceof ArrayBuffer) || !bytes.byteLength || bytes.byteLength > 30 * 1024 * 1024) return Promise.reject(new Error('图片为空或超过 30 MiB'));
    return ipcRenderer.invoke('jianji:put-image', bytes);
  },
  getImage: hash => ipcRenderer.invoke('jianji:get-image', hash),
  removeImage: hash => ipcRenderer.invoke('jianji:remove-image', hash),
  syncInfo: () => ipcRenderer.invoke('jianji:sync-info'),
  startPairing: host => ipcRenderer.invoke('jianji:start-pairing', host),
  approvePair: (id, approve) => ipcRenderer.invoke('jianji:approve-pair', id, approve),
  requestSync: id => ipcRenderer.invoke('jianji:request-sync', id),
  revokePeer: id => ipcRenderer.invoke('jianji:revoke-peer', id),
  onSyncInfo: callback => subscribe(infoListeners, callback),
  onPrepareSync: callback => subscribe(prepareListeners, callback),
  onSyncResult: callback => subscribe(resultListeners, callback),
  openDataFolder: () => ipcRenderer.invoke('jianji:open-data-folder')
}));
