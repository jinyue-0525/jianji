'use strict';
const fs = require('node:fs');
const path = require('node:path');
const root = path.resolve(__dirname, '..');
function stage(destination = path.join(root, '.build', 'app')) {
  const sourceWeb = path.resolve(root, '../web');
  if (!fs.existsSync(path.join(sourceWeb, 'index.html'))) throw new Error('共享 web 界面尚未准备完成');
  fs.rmSync(destination, {recursive: true, force: true}); fs.mkdirSync(destination, {recursive: true});
  for (const file of fs.readdirSync(root)) if (file.endsWith('.cjs')) fs.copyFileSync(path.join(root, file), path.join(destination, file));
  const pkg = require('../package.json');
  fs.writeFileSync(path.join(destination, 'package.json'), JSON.stringify({name: pkg.name, version: pkg.version, description: pkg.description, author: pkg.author, private: true, main: pkg.main, dependencies: pkg.dependencies}, null, 2));
  fs.cpSync(sourceWeb, path.join(destination, 'web'), {recursive: true});
  const assets = path.join(root, 'assets');
  if (fs.existsSync(assets)) fs.cpSync(assets, path.join(destination, 'assets'), {recursive: true});
  const lock = JSON.parse(fs.readFileSync(path.join(root, 'package-lock.json'), 'utf8'));
  for (const [relative, detail] of Object.entries(lock.packages)) {
    if (!relative.startsWith('node_modules/') || detail.dev) continue;
    const source = path.join(root, relative), target = path.join(destination, relative);
    if (!fs.existsSync(source)) { if (detail.optional) continue; throw new Error('生产依赖尚未安装：' + relative); }
    fs.mkdirSync(path.dirname(target), {recursive: true});
    fs.cpSync(source, target, {recursive: true, dereference: true,
      filter: file => file === source || !path.relative(source, file).split(path.sep).includes('node_modules')});
  }
  return destination;
}
if (require.main === module) { try { process.stdout.write(stage() + '\n'); } catch (error) { process.stderr.write(error.message + '\n'); process.exitCode = 1; } }
module.exports = {stage};
