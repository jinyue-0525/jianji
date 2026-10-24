'use strict';
const {app, BrowserWindow, ipcMain, protocol, session, safeStorage, shell, Menu, dialog} = require('electron');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const os = require('node:os');
const P = require('./protocol.cjs');
const {mkdir, readJson, writeJson} = require('./files.cjs');
const {DataStore} = require('./data.cjs');
const {SecureFiles} = require('./security.cjs');
const {SyncServer} = require('./server.cjs');
app.setName('简记');
protocol.registerSchemesAsPrivileged([{scheme: 'app', privileges: {standard: true, secure: true, supportFetchAPI: true, corsEnabled: true}}]);
const WEB_ROOT = fs.existsSync(path.join(__dirname, 'web', 'index.html')) ? path.join(__dirname, 'web') : path.resolve(__dirname, '../web');
const ENTRY = 'app://jianji/index.html';
const ICON_PATH = path.join(__dirname, 'assets', 'app.png');
const CSP = "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; media-src 'self' blob:; connect-src 'self'; font-src 'self'; object-src 'none'; frame-src 'none'; base-uri 'none'; form-action 'none'";
let window = null, data = null, server = null, dataDirectory = '', pendingPrepare = null, lastPrepareId = null;
function trustedURL(value) {
  try {
    const url = new URL(value);
    return url.protocol === 'app:' && url.hostname === 'jianji' && url.pathname === '/index.html';
  } catch { return false; }
}
function trusted(event) {
  P.check(window && !window.isDestroyed() && event.sender === window.webContents && event.senderFrame === event.sender.mainFrame && trustedURL(event.senderFrame.url), '拒绝来自非简记页面的请求');
}
function emit(channel, value) {
  if (window && !window.isDestroyed() && !window.webContents.isDestroyed() && trustedURL(window.webContents.getURL())) window.webContents.send(channel, value);
}
function syncInfoChanged() { if (server) emit('jianji:sync-info', server.info()); }
function result(event) { emit('jianji:sync-result', {requestId: lastPrepareId, revision: data.document.revision, result: event}); lastPrepareId = null; }
function prepare(signal) {
  if (pendingPrepare || !window || window.isDestroyed() || !trustedURL(window.webContents.getURL())) return Promise.resolve(false);
  const id = crypto.randomUUID(); lastPrepareId = id;
  return new Promise(resolve => {
    let timer;
    const finish = ok => {
      if (pendingPrepare?.id !== id) return;
      clearTimeout(timer); signal.removeEventListener('abort', cancel); pendingPrepare = null; resolve(Boolean(ok));
    };
    const cancel = () => finish(false);
    pendingPrepare = {id, finish}; signal.addEventListener('abort', cancel, {once: true});
    timer = setTimeout(() => finish(false), 15000);
    if (signal.aborted) finish(false); else emit('jianji:prepare-sync', {requestId: id});
  });
}
function setupIPC() {
  ipcMain.on('jianji:load-state', event => {
    try { trusted(event); event.returnValue = {...data.loadState(), revision: data.document.revision}; }
    catch { event.returnValue = {state: {notes: [], folders: [], books: [], font: 'system', expanded: []}, error: '无法安全读取本地数据'}; }
  });
  ipcMain.on('jianji:save-state', (event, value) => {
    try { trusted(event); P.check(P.object(value) && value.revision === data.document.revision, '数据已更新，请等待同步完成后继续编辑'); data.saveState(value.state); event.returnValue = {ok: true, revision: data.document.revision}; }
    catch (error) { event.returnValue = {ok: false, error: error.message || '本地数据未能保存'}; }
  });
  ipcMain.on('jianji:prepared', (event, value) => {
    try { trusted(event); if (P.object(value) && value.requestId === pendingPrepare?.id) pendingPrepare.finish(value.ready === true); } catch {}
  });
  const handle = (channel, action) => ipcMain.handle(channel, async (event, ...args) => { trusted(event); return action(...args); });
  handle('jianji:put-image', bytes => {
    P.check(bytes instanceof ArrayBuffer || ArrayBuffer.isView(bytes), '图片内容格式无效');
    P.check(bytes.byteLength > 0 && bytes.byteLength <= P.MAX_IMAGE, '图片为空或超过 30 MiB');
    return data.putImage(bytes instanceof ArrayBuffer ? Buffer.from(bytes) : Buffer.from(bytes.buffer, bytes.byteOffset, bytes.byteLength));
  });
  handle('jianji:get-image', hash => { const bytes = data.getImage(hash); return bytes ? bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength) : null; });
  handle('jianji:remove-image', hash => data.removeImage(hash));
  handle('jianji:sync-info', () => server.info());
  handle('jianji:start-pairing', host => server.startPairing(host));
  handle('jianji:approve-pair', (id, approve) => server.approvePair(id, approve));
  handle('jianji:request-sync', id => server.requestSync(id));
  handle('jianji:revoke-peer', id => server.revokePeer(id));
  handle('jianji:open-data-folder', async () => { const error = await shell.openPath(dataDirectory); if (error) throw new Error('无法打开数据文件夹'); });
}
function createWindow() {
  window = new BrowserWindow({width: 1100, height: 800, minWidth: 920, minHeight: 600, title: '简记', backgroundColor: '#ffffff', show: false, icon: fs.existsSync(ICON_PATH) ? ICON_PATH : undefined,
    webPreferences: {preload: path.join(__dirname, 'preload.cjs'), contextIsolation: true, nodeIntegration: false, sandbox: true,
      webSecurity: true, allowRunningInsecureContent: false, navigateOnDragDrop: false}});
  if (process.platform === 'darwin' && fs.existsSync(ICON_PATH)) app.dock?.setIcon(ICON_PATH);
  window.webContents.setWindowOpenHandler(() => ({action: 'deny'}));
  window.webContents.on('will-navigate', (event, url) => { if (!trustedURL(url)) event.preventDefault(); });
  window.webContents.on('will-redirect', event => event.preventDefault());
  window.webContents.on('will-attach-webview', event => event.preventDefault());
  window.webContents.on('render-process-gone', () => pendingPrepare?.finish(false));
  window.webContents.on('did-start-navigation', (_event, _url, _inPlace, isMainFrame) => { if (isMainFrame) pendingPrepare?.finish(false); });
  window.on('closed', () => { pendingPrepare?.finish(false); window = null; });
  window.once('ready-to-show', () => window?.show());
  window.loadURL(ENTRY).catch(() => { dialog.showErrorBox('无法打开简记', '本地界面文件未能读取，笔记数据未被修改。'); });
}
async function setupProtocol() {
  const base = fs.realpathSync(WEB_ROOT);
  protocol.handle('app', async request => {
    try {
      const url = new URL(request.url);
      if (url.hostname !== 'jianji' || !['GET', 'HEAD'].includes(request.method)) return new Response('', {status: 403});
      const relative = decodeURIComponent(url.pathname).replace(/^\/+/, '') || 'index.html';
      const file = path.resolve(base, relative);
      if (!file.startsWith(base + path.sep)) return new Response('', {status: 403});
      const real = await fs.promises.realpath(file);
      if (!real.startsWith(base + path.sep) || !(await fs.promises.stat(real)).isFile()) return new Response('', {status: 403});
      const types = {'.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8', '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png', '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg', '.webp': 'image/webp', '.ico': 'image/x-icon'};
      const type = types[path.extname(real).toLowerCase()]; if (!type) return new Response('', {status: 403});
      return new Response(request.method === 'HEAD' ? null : await fs.promises.readFile(real), {headers: {'Content-Type': type, 'Content-Security-Policy': CSP,
        'X-Content-Type-Options': 'nosniff', 'Cache-Control': 'no-store'}});
    } catch { return new Response('', {status: 404}); }
  });
}
if (!app.requestSingleInstanceLock()) app.quit();
else {
  app.on('second-instance', () => { if (!window) { if (data) createWindow(); } else { if (window.isMinimized()) window.restore(); window.show(); window.focus(); } });
  app.whenReady().then(async () => {
    dataDirectory = app.getPath('userData'); mkdir(dataDirectory);
    const installationFile = path.join(dataDirectory, 'installation.json');
    let installation = readJson(installationFile, 32768);
    if (!installation) { installation = {deviceId: crypto.randomUUID(), name: ('简记 · ' + os.hostname()).slice(0, 80)}; writeJson(installationFile, installation); }
    P.check(P.UUID.test(installation.deviceId) && typeof installation.name === 'string', '本机身份文件无效，原文件已保留');
    data = new DataStore(dataDirectory, installation.deviceId);
    server = new SyncServer({data, secure: new SecureFiles(dataDirectory, safeStorage), ...installation, prepare, result, changed: syncInfoChanged});
    await setupProtocol(); setupIPC();
    session.defaultSession.setPermissionRequestHandler((contents, permission, callback, details) => callback(contents === window?.webContents && trustedURL(contents.getURL()) && permission === 'clipboard-sanitized-write' && (trustedURL(details.requestingUrl) || details.requestingUrl === 'app://jianji' || details.requestingUrl === 'app://jianji/')));
    session.defaultSession.setPermissionCheckHandler((contents, permission, origin, details) => contents === window?.webContents && trustedURL(contents.getURL()) && permission === 'clipboard-sanitized-write' && (trustedURL(details?.requestingUrl || origin) || origin === 'app://jianji' || origin === 'app://jianji/'));
    Menu.setApplicationMenu(Menu.buildFromTemplate([
      ...(process.platform === 'darwin' ? [{label: '简记', submenu: [{role: 'about'}, {type: 'separator'}, {role: 'hide'}, {role: 'hideOthers'}, {role: 'unhide'}, {type: 'separator'}, {role: 'quit'}]}] : []),
      {label: '编辑', submenu: [{role: 'undo'}, {role: 'redo'}, {type: 'separator'}, {role: 'cut'}, {role: 'copy'}, {role: 'paste'}, {role: 'selectAll'}]},
      {label: '窗口', submenu: [{role: 'minimize'}, {role: 'zoom'}, ...(process.platform === 'darwin' ? [{role: 'front'}] : [{role: 'close'}])]}]));
    app.setAboutPanelOptions({applicationName: '简记', applicationVersion: '2.0.0', authors: ['@yue'], copyright: '作者 @yue'});
    createWindow(); server.start();
  }).catch(() => { dialog.showErrorBox('简记暂时无法启动', '本地身份或数据文件未能安全读取，原文件已保留。请检查用户数据文件夹或备份后再打开。'); app.quit(); });
  app.on('activate', () => { if (!window && data) createWindow(); });
  app.on('window-all-closed', () => { if (process.platform !== 'darwin') app.quit(); });
  app.on('will-quit', () => { pendingPrepare?.finish(false); server?.close(); });
}
