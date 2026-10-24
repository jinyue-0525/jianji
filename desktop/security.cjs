'use strict';
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const os = require('node:os');
const selfsigned = require('selfsigned');
const {atomicWrite, readJson, writeJson} = require('./files.cjs');
const {check, sha} = require('./protocol.cjs');
function privateIPv4(value) {
  if (typeof value !== 'string' || !/^\d{1,3}(?:\.\d{1,3}){3}$/.test(value)) return false;
  const p = value.split('.').map(Number); if (p.some(n => n > 255)) return false;
  return p[0] === 10 || (p[0] === 172 && p[1] >= 16 && p[1] <= 31) || (p[0] === 192 && p[1] === 168) || (p[0] === 169 && p[1] === 254);
}
function addresses() {
  const result = [];
  for (const [name, entries] of Object.entries(os.networkInterfaces())) for (const entry of entries || []) {
    if (!entry.internal && (entry.family === 'IPv4' || entry.family === 4) && privateIPv4(entry.address)) result.push({name, address: entry.address});
  }
  return result;
}
class SecureFiles {
  constructor(directory, safeStorage) { this.directory = directory; this.safeStorage = safeStorage; }
  available() {
    return this.safeStorage.isEncryptionAvailable() && (process.platform !== 'linux' || this.safeStorage.getSelectedStorageBackend?.() !== 'basic_text');
  }
  assertAvailable() { check(this.available(), '系统安全存储暂不可用，请解锁系统钥匙串后重新打开简记'); }
  read(name, fallback) {
    const file = path.join(this.directory, name); if (!fs.existsSync(file)) return fallback;
    this.assertAvailable(); return JSON.parse(this.safeStorage.decryptString(fs.readFileSync(file)));
  }
  write(name, value) { this.assertAvailable(); atomicWrite(path.join(this.directory, name), this.safeStorage.encryptString(JSON.stringify(value))); }
  async certificate() {
    this.assertAvailable(); const file = path.join(this.directory, 'tls-identity.json');
    let value = readJson(file, 1024 * 1024);
    if (!value) {
      const now = new Date(), later = new Date(now); later.setFullYear(later.getFullYear() + 10);
      const pems = await selfsigned.generate([{name: 'commonName', value: 'JianJi Local Sync'}], {keyType: 'rsa', keySize: 2048, algorithm: 'sha256',
        notBeforeDate: new Date(now.getTime() - 86400000), notAfterDate: later,
        extensions: [{name: 'basicConstraints', cA: false}, {name: 'keyUsage', digitalSignature: true, keyEncipherment: true},
          {name: 'extKeyUsage', serverAuth: true}, {name: 'subjectAltName', altNames: addresses().length ? addresses().map(item => ({type: 7, ip: item.address})) : [{type: 2, value: 'localhost'}]}]});
      value = {cert: pems.cert, encryptedKey: this.safeStorage.encryptString(pems.private).toString('base64')}; writeJson(file, value);
    }
    check(typeof value.cert === 'string' && typeof value.encryptedKey === 'string', '本机 TLS 身份文件无效，已保留原文件');
    const cert = new crypto.X509Certificate(value.cert), key = this.safeStorage.decryptString(Buffer.from(value.encryptedKey, 'base64'));
    check(cert.checkPrivateKey(crypto.createPrivateKey(key)), '本机 TLS 密钥与证书不匹配');
    return {cert: value.cert, key, fingerprint: sha(cert.raw)};
  }
}
module.exports = {SecureFiles, addresses, privateIPv4};
