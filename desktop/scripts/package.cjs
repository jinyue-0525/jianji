'use strict';
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const crypto = require('node:crypto');
const {execFileSync} = require('node:child_process');
const {download, targets} = require('./download.cjs');
const {stage} = require('./stage.cjs');
const root = path.resolve(__dirname, '..'), pkg = require('../package.json');
const output = path.join(os.homedir(), 'Documents', 'outputs');
const instructions = '简记 ' + pkg.version + '\n作者 @yue\n\nmacOS：选择与你的电脑匹配的 arm64（Apple 芯片）或 x64（Intel）版本，将简记 .app 移至应用程序后打开。\nWindows：完整解压 portable.zip 后打开 Jianji.exe，不要只拷贝 EXE。\n\n两端连接同一 Wi-Fi，在电脑的同步面板生成二维码，手机扫码或粘贴配对码，并在电脑批准配对。配对不会自动传输笔记；同步时保持手机简记在前台。当前尚不提供蓝牙同步。\n笔记保存在本机用户数据目录，可通过应用里的“打开数据文件夹”访问；备份与图片文件请一并保留。\n\nmacOS 包为 ad-hoc 签名、未进行 Apple 公证；Windows 包未进行 Authenticode 签名。实际跨设备互通尚未测试。\n';
const selected = process.argv.slice(2);
const chosen = selected.length ? targets.filter(target => selected.includes(`${target.platform}-${target.arch}`)) : targets;
function run(file, args) { execFileSync(file, args, {stdio: 'inherit'}); }
function checksum(file) { return crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex'); }
function plist(file, key, value) {
  try { execFileSync('/usr/libexec/PlistBuddy', ['-c', `Set :${key} ${value}`, file], {stdio: 'pipe'}); }
  catch { run('/usr/libexec/PlistBuddy', ['-c', `Add :${key} string ${value}`, file]); }
}
async function main() {
  if (process.platform !== 'darwin') throw new Error('当前跨平台打包脚本需在 macOS 执行（使用系统 ditto/codesign，无 Wine 依赖）');
  if (!chosen.length) throw new Error('目标应为 darwin-arm64、darwin-x64 或 win32-x64');
  fs.mkdirSync(output, {recursive: true});
  fs.writeFileSync(path.join(output, `简记-${pkg.version}-桌面使用说明.txt`), instructions);
  const staged = stage(), produced = [];
  for (const target of chosen) {
    const archive = await download(target);
    const work = path.join(root, '.build', `runtime-${target.platform}-${target.arch}`);
    fs.rmSync(work, {recursive: true, force: true}); fs.mkdirSync(work, {recursive: true}); run('/usr/bin/ditto', ['-x', '-k', archive, work]);
    if (target.platform === 'darwin') {
      const name = `简记-${pkg.version}-macOS-${target.arch}`, bundle = path.join(output, name + '.app');
      fs.rmSync(bundle, {recursive: true, force: true}); fs.renameSync(path.join(work, 'Electron.app'), bundle);
      const contents = path.join(bundle, 'Contents'), resources = path.join(contents, 'Resources');
      fs.writeFileSync(path.join(resources, '使用说明.txt'), instructions);
      fs.rmSync(path.join(resources, 'default_app.asar'), {force: true}); fs.cpSync(staged, path.join(resources, 'app'), {recursive: true});
      fs.renameSync(path.join(contents, 'MacOS', 'Electron'), path.join(contents, 'MacOS', 'Jianji'));
      const info = path.join(contents, 'Info.plist');
      plist(info, 'CFBundleExecutable', 'Jianji'); plist(info, 'CFBundleName', '简记'); plist(info, 'CFBundleDisplayName', '简记');
      plist(info, 'CFBundleIdentifier', 'com.xiaotu.jianji.desktop'); plist(info, 'CFBundleShortVersionString', pkg.version); plist(info, 'CFBundleVersion', pkg.version);
      for (const file of ['LICENSE', 'LICENSES.chromium.html']) if (fs.existsSync(path.join(work, file))) fs.copyFileSync(path.join(work, file), path.join(resources, file));
      const icon = path.join(root, 'assets', 'app.icns'); if (fs.existsSync(icon)) fs.copyFileSync(icon, path.join(resources, 'electron.icns'));
      run('/usr/bin/codesign', ['--force', '--deep', '--sign', '-', '--preserve-metadata=entitlements', bundle]);
      const zip = path.join(output, name + '.zip'); fs.rmSync(zip, {force: true}); run('/usr/bin/ditto', ['-c', '-k', '--sequesterRsrc', '--keepParent', bundle, zip]);
      produced.push({platform: target.platform, arch: target.arch, app: bundle, archive: zip, sha256: checksum(zip)});
    } else {
      const name = `简记-${pkg.version}-Windows-x64`, folder = path.join(root, '.build', name);
      fs.rmSync(folder, {recursive: true, force: true}); fs.renameSync(work, folder);
      fs.renameSync(path.join(folder, 'electron.exe'), path.join(folder, 'Jianji.exe'));
      const resources = path.join(folder, 'resources'); fs.rmSync(path.join(resources, 'default_app.asar'), {force: true}); fs.cpSync(staged, path.join(resources, 'app'), {recursive: true});
      fs.writeFileSync(path.join(folder, '使用说明.txt'), instructions.replace(/\n/g, '\r\n'));
      const zip = path.join(output, name + '-portable.zip'); fs.rmSync(zip, {force: true}); run('/usr/bin/ditto', ['-c', '-k', '--keepParent', folder, zip]);
      produced.push({platform: target.platform, arch: target.arch, archive: zip, sha256: checksum(zip)});
    }
  }
  const manifest = path.join(output, `简记-${pkg.version}-桌面发布清单.json`);
  fs.writeFileSync(manifest, JSON.stringify({version: pkg.version, author: '@yue', signing: 'macOS ad-hoc; Windows unsigned', artifacts: produced}, null, 2));
  produced.forEach(item => process.stdout.write(item.archive + '\n')); process.stdout.write(manifest + '\n');
}
main().catch(error => { process.stderr.write('打包未完成：' + error.message + '\n'); process.exitCode = 1; });
