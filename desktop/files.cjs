'use strict';
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
function mkdir(directory) { fs.mkdirSync(directory, {recursive: true, mode: 0o700}); }
function atomicWrite(file, bytes) {
  mkdir(path.dirname(file));
  const temporary = file + '.tmp-' + crypto.randomUUID();
  let descriptor;
  try {
    descriptor = fs.openSync(temporary, 'wx', 0o600); fs.writeFileSync(descriptor, bytes); fs.fsyncSync(descriptor); fs.closeSync(descriptor); descriptor = undefined;
    fs.renameSync(temporary, file);
    let directory;
    try { directory = fs.openSync(path.dirname(file), 'r'); fs.fsyncSync(directory); }
    catch (error) { if (process.platform !== 'win32' && !['EINVAL', 'ENOTSUP', 'EISDIR'].includes(error.code)) throw error; }
    finally { if (directory !== undefined) fs.closeSync(directory); }
  } finally {
    if (descriptor !== undefined) fs.closeSync(descriptor);
    try { fs.unlinkSync(temporary); } catch (error) { if (error.code !== 'ENOENT') throw error; }
  }
}
function writeJson(file, value) { atomicWrite(file, Buffer.from(JSON.stringify(value), 'utf8')); }
function readJson(file, max = 128 * 1024 * 1024) {
  if (!fs.existsSync(file)) return null;
  if (fs.statSync(file).size > max) throw new Error('本地数据文件超过读取上限');
  return JSON.parse(new TextDecoder('utf-8', {fatal: true}).decode(fs.readFileSync(file)));
}
module.exports = {mkdir, atomicWrite, writeJson, readJson};
