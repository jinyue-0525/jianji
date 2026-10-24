'use strict';
const path = require('node:path');
const os = require('node:os');
const {downloadArtifact} = require('@electron/get');
const version = require('../package.json').devDependencies.electron;
const targets = [{platform: 'darwin', arch: 'arm64'}, {platform: 'darwin', arch: 'x64'}, {platform: 'win32', arch: 'x64'}];
const cacheRoot = path.join(os.homedir(), '.cache', 'jianji-electron');
async function download(target) {
  return downloadArtifact({version, artifactName: 'electron', platform: target.platform, arch: target.arch, cacheRoot});
}
if (require.main === module) Promise.all(targets.map(async target => {
  const file = await download(target); process.stdout.write(`${target.platform}-${target.arch}: ${file}\n`);
})).catch(error => { process.stderr.write('Electron 官方发行包下载未完成：' + error.message + '\n'); process.exitCode = 1; });
module.exports = {download, targets, version};
