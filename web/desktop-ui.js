export function mountDesktop({host, store, editor, getCategory, actions, ui}) {
  const {node, button, icon} = ui;
  const bridge = window.jianjiDesktop;
  document.body.classList.add('desktop-mode');
  document.body.classList.toggle('desktop-preview', !bridge);
  document.title = '简记';
  host.classList.add('desktop-workspace');
  host.setAttribute('aria-label', '简记写作工作区');
  const preview = document.querySelector('.preview-label');
  preview.textContent = '简记 · 桌面布局预览 · 设备同步请在电脑客户端中使用';
  const sidebar = node('aside', 'desktop-sidebar'); sidebar.id = 'desktopSidebar';
  sidebar.setAttribute('aria-label', '笔记导航');
  const brand = node('header', 'desktop-brand');
  brand.append(icon('notes'), node('strong', '', '简记'), node('span', '', '留住每一个想法'));
  const create = button('写笔记', 'desktop-create', actions.createNote); create.prepend(icon('edit'));
  create.title = '新建笔记 · ⌘ / Ctrl + N';
  const navigation = node('nav', 'desktop-navigation');
  const heading = node('div', 'desktop-folders-heading');
  heading.append(node('span', '', '文件夹'));
  const addFolder = button('', 'desktop-small-icon', actions.createFolder);
  addFolder.setAttribute('aria-label', '新建文件夹'); addFolder.append(icon('plus'));
  const manage = button('管理', 'desktop-text-button', actions.manage);
  heading.append(manage, addFolder);
  const folders = node('div', 'desktop-folders');
  const bottom = node('footer', 'desktop-sidebar-footer');
  const syncButton = button('设备同步', 'desktop-sync-entry', openSync); syncButton.prepend(icon('wifi'));
  const utilities = node('div', 'desktop-utilities');
  utilities.append(button('字体大小', 'desktop-text-button', actions.showFonts), button('导出文字', 'desktop-text-button', actions.exportNotes));
  bottom.append(syncButton, utilities);
  sidebar.append(brand, create, navigation, heading, folders, bottom); host.prepend(sidebar);
  const blank = node('section', 'desktop-blank');
  blank.append(icon('edit'), node('h1', '', '让想法，有处安放。'), node('p', '', '选择一篇笔记，或开始一段新的文字。'));
  blank.append(button('开始写作', 'desktop-blank-create', actions.createNote)); host.append(blank);
  const focusButton = button('', 'icon-button desktop-focus-button', toggleFocus);
  focusButton.append(icon('collapse')); focusButton.setAttribute('aria-label', '进入专注写作');
  focusButton.title = '专注写作 · ⌘ / Ctrl + Shift + F';
  document.querySelector('.editor-toolbar').insertBefore(focusButton, document.getElementById('editorMore'));
  const busy = node('div', 'desktop-sync-busy', '正在同步，请稍候…');
  busy.setAttribute('role', 'status'); busy.hidden = true; document.body.append(busy);
  let signature = '', info = null, syncPanel = null;
  const collapsed = new Set();

  function row(label, glyph, count, selected, action, extra = '') {
    const view = button('', 'desktop-nav-row ' + extra, action);
    view.append(icon(glyph), node('span', 'desktop-nav-label', label), node('span', 'desktop-nav-count', String(count)));
    view.setAttribute('aria-pressed', String(selected)); view.title = label; return view;
  }
  function refresh() {
    const next = JSON.stringify([getCategory(), store.folders, store.books, store.notes.map(n => [n.id, n.notebookId, n.favorite, n.deletedAt]), [...collapsed]]);
    if (signature === next) return; signature = next;
    const active = store.notes.filter(n => !n.deletedAt), category = getCategory();
    const counts = {all: active.length, unfiled: active.filter(n => !store.books.some(b => b.id === n.notebookId)).length,
      favorite: active.filter(n => n.favorite).length, trash: store.notes.length - active.length};
    navigation.replaceChildren(...[['all', '全部笔记', 'notes'], ['unfiled', '未分类', 'document'], ['favorite', '我的收藏', 'star'], ['trash', '最近删除', 'trash']]
      .map(([id, label, glyph]) => row(label, glyph, counts[id], category === id, () => { leaveFocus(); actions.selectCategory(id); })));
    folders.replaceChildren();
    store.folders.forEach(folder => {
      const books = store.books.filter(b => b.folderId === folder.id);
      const group = node('div', 'desktop-folder-group');
      const folderRow = row(folder.name, 'folder', books.length, false, () => {
        collapsed.has(folder.id) ? collapsed.delete(folder.id) : collapsed.add(folder.id); refresh();
      }, 'desktop-folder-row');
      folderRow.removeAttribute('aria-pressed'); folderRow.setAttribute('aria-expanded', String(!collapsed.has(folder.id)));
      folderRow.append(icon(collapsed.has(folder.id) ? 'chevronRight' : 'chevronDown')); group.append(folderRow);
      if (!collapsed.has(folder.id)) {
        books.forEach(book => group.append(row(book.name, 'notebook', active.filter(n => n.notebookId === book.id).length,
          category === book.id, () => { leaveFocus(); actions.selectCategory(book.id); }, 'desktop-book-row')));
        if (!books.length) group.append(button('添加笔记本', 'desktop-empty-folder', actions.manage));
      }
      folders.append(group);
    });
    if (!store.folders.length) folders.append(button('创建文件夹，整理你的写作', 'desktop-no-folders', actions.createFolder));
  }
  function leaveFocus() { host.classList.remove('desktop-focus'); focusButton.setAttribute('aria-label', '进入专注写作'); }
  function toggleFocus() {
    if (!editor.currentId) return;
    const enabled = host.classList.toggle('desktop-focus');
    focusButton.setAttribute('aria-label', enabled ? '退出专注写作' : '进入专注写作');
    document.getElementById('body').focus({preventScroll: true});
  }
  async function run(view, action) {
    if (view) view.disabled = true;
    try { return await action(); }
    catch (error) { if (syncPanel) syncPanel.error.textContent = error.message || '操作未完成，请重试。'; else ui.toast(error.message || '操作未完成'); }
    finally { if (view?.isConnected) view.disabled = false; }
  }
  function isSyncPanelOpen() { return Boolean(syncPanel?.content.isConnected && !syncPanel.content.closest('[hidden]')); }
  function peerDate(timestamp) { return timestamp ? new Date(timestamp).toLocaleString('zh-CN', {month:'numeric',day:'numeric',hour:'2-digit',minute:'2-digit'}) : '尚未同步'; }
  function renderDevices() {
    if (!syncPanel || !info) return;
    const {status, devices, pending, address} = syncPanel;
    status.textContent = info.status || '连接同一 Wi-Fi，打开手机上的简记。';
    const options = (info.addresses || []).map(a => [a.address, `${a.name} · ${a.address}`]);
    const current = address.value;
    if (JSON.stringify(options) !== address.dataset.options) {
      address.replaceChildren(...options.map(([value, label]) => { const option = node('option', '', label); option.value = value; return option; }));
      if (options.some(([value]) => value === current)) address.value = current;
      address.dataset.options = JSON.stringify(options);
    }
    syncPanel.generate.disabled = !options.length || !info.port;
    pending.replaceChildren();
    (info.pending || []).forEach(request => {
      const item = node('div', 'desktop-peer pending-peer');
      const details = node('div', 'desktop-peer-details');
      details.append(node('strong', '', request.name), node('span', '', '请求配对 · 仅批准你正在操作的手机'));
      const approve = button('允许', 'desktop-primary small', () => run(approve, () => bridge.approvePair(request.id, true)));
      const reject = button('拒绝', 'desktop-text-button', () => run(reject, () => bridge.approvePair(request.id, false)));
      item.append(details, reject, approve); pending.append(item);
    });
    devices.replaceChildren();
    if (!info.peers?.length) devices.append(node('p', 'desktop-sync-empty', '还没有配对设备。用手机扫描下方二维码，开始连接。'));
    (info.peers || []).forEach(peer => {
      const item = node('div', 'desktop-peer');
      const details = node('div', 'desktop-peer-details');
      details.append(node('strong', '', peer.name), node('span', '', '上次同步 · ' + peerDate(peer.lastSyncAt)));
      const sync = button('同步', 'desktop-primary small', () => run(sync, async () => {
        await bridge.requestSync(peer.id); syncPanel && (syncPanel.error.textContent = '同步请求已发送，请保持手机上的简记在前台。');
      }));
      const revoke = button('解除', 'desktop-text-button destructive', () => {
        const content = node('p', '', `解除与“${peer.name}”的配对？两端已有的笔记会保留，再次同步需要重新扫码。`);
        ui.dialog({title: '解除配对', content, actions: [{label: '取消', onClick: openSync}, {label: '解除', primary: true,
          onClick: async () => { await run(null, () => bridge.revokePeer(peer.id)); openSync(); }}]});
      });
      item.append(details, sync, revoke); devices.append(item);
    });
  }
  function openSync() {
    if (!bridge) { ui.alert('设备同步', '这是桌面布局预览。请打开简记电脑客户端，在“设备同步”中生成二维码，再用安卓简记的“电脑同步”扫码配对。'); return; }
    const content = node('div', 'desktop-sync-panel');
    const status = node('p', 'desktop-sync-status', '正在读取本机连接信息…');
    const pending = node('div', 'desktop-pair-requests');
    const devices = node('div', 'desktop-devices');
    const pair = node('section', 'desktop-pair-section');
    pair.append(node('h3', '', '连接手机'), node('p', '', '两台设备连接同一 Wi-Fi。手机打开“电脑同步”，扫描二维码后在这里允许配对。'));
    const address = node('select', 'desktop-address'); address.setAttribute('aria-label', '选择局域网地址');
    const qr = node('div', 'desktop-qr'); qr.hidden = true;
    const error = node('p', 'desktop-sync-message'); error.setAttribute('role', 'status');
    let expiresAt = 0, timer;
    const generate = button('生成配对二维码', 'desktop-primary', () => run(generate, async () => {
      error.textContent = ''; const result = await bridge.startPairing(address.value);
      if (syncPanel?.content !== content) return;
      qr.replaceChildren(); qr.hidden = false; expiresAt = result.expiresAt;
      const img = node('img'); img.src = result.qrDataUrl; img.alt = '简记手机配对二维码'; img.width = 208; img.height = 208;
      const expiry = node('span', 'desktop-qr-expiry');
      const copy = button('复制配对码', 'desktop-text-button', () => run(copy, async () => {
        await navigator.clipboard.writeText(result.payload); error.textContent = '配对码已复制，可在手机的“粘贴配对码”中使用。';
      }));
      qr.append(img, expiry, copy); generate.textContent = '刷新二维码';
      const tick = () => {
        const seconds = Math.max(0, Math.ceil((expiresAt - Date.now()) / 1000));
        expiry.textContent = seconds ? `二维码 ${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')} 后失效` : '二维码已失效，请刷新';
        img.style.opacity = seconds ? '1' : '.2'; copy.disabled = !seconds;
      }; clearInterval(timer); tick(); timer = setInterval(tick, 1000);
    }));
    const controls = node('div', 'desktop-pair-controls'); controls.append(address, generate);
    pair.append(controls, qr);
    const note = node('p', 'desktop-sync-footnote', '配对后可在任一端发起双向同步。同步时手机需保持简记在前台；两端同时修改会保留冲突副本。');
    const dataFolder = button('打开本机数据文件夹', 'desktop-text-button', () => run(dataFolder, () => bridge.openDataFolder()));
    content.append(status, pending, devices, pair, error, note, dataFolder);
    ui.dialog({title: '设备同步', content, actions: [{label: '完成', primary: true}], onClose: () => {
      clearInterval(timer); if (syncPanel?.content === content) syncPanel = null;
    }});
    syncPanel = {content, status, pending, devices, address, generate, error}; renderDevices();
    run(null, async () => { info = await bridge.syncInfo(); renderDevices(); });
  }
  if (bridge) {
    bridge.onSyncInfo(value => { info = value; renderDevices(); });
    bridge.syncInfo().then(value => { info = value; }).catch(() => {});
  }
  refresh();
  return {refresh, openSync, toggleFocus, leaveFocus, isSyncPanelOpen, setBusy(value) { busy.hidden = !value; }};
}
