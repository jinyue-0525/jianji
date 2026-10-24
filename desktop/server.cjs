'use strict';
const https = require('node:https');
const fs = require('node:fs');
const crypto = require('node:crypto');
const path = require('node:path');
const {readJson, writeJson} = require('./files.cjs');
const QRCode = require('qrcode');
const P = require('./protocol.cjs');
const {addresses, privateIPv4} = require('./security.cjs');
const PAIR_LIFETIME = 5 * 60 * 1000;
const token = () => crypto.randomBytes(32).toString('base64url');
const tokenHash = value => P.sha(value);
const same = (a, b) => typeof a === 'string' && typeof b === 'string' && a.length === b.length && crypto.timingSafeEqual(Buffer.from(a), Buffer.from(b));
class HttpError extends Error { constructor(status, message) { super(message); this.status = status; } }
function requireHttp(condition, status, message) { if (!condition) throw new HttpError(status, message); }
async function readBody(request, limit) {
  const stated = request.headers['content-length'];
  requireHttp(stated === undefined || (/^\d+$/.test(stated) && Number(stated) <= limit), 413, '请求内容超过大小上限');
  const chunks = []; let count = 0;
  for await (const chunk of request) { count += chunk.length; requireHttp(count <= limit, 413, '请求内容超过大小上限'); chunks.push(chunk); }
  return Buffer.concat(chunks, count);
}
async function jsonBody(request) {
  requireHttp(/^application\/json(?:\s*;|$)/i.test(request.headers['content-type'] || ''), 415, '请发送 JSON 请求');
  const bytes = await readBody(request, P.MAX_JSON);
  try { return JSON.parse(new TextDecoder('utf-8', {fatal: true}).decode(bytes)); } catch { throw new HttpError(400, 'JSON 格式无效'); }
}
function send(response, status, body) {
  if (response.destroyed || response.writableEnded) return;
  const bytes = Buffer.from(JSON.stringify(body));
  response.writeHead(status, {'Content-Type': 'application/json; charset=utf-8', 'Content-Length': bytes.length,
    'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff', 'Cross-Origin-Resource-Policy': 'same-origin'}); response.end(bytes);
}
class SyncServer {
  constructor({data, secure, deviceId, name, prepare, result, changed}) {
    Object.assign(this, {data, secure, deviceId, name, prepare, result, changed});
    this.peers = []; this.pairings = new Map(); this.requests = new Map(); this.rate = new Map(); this.port = 0;
    this.status = '正在启动局域网同步'; this.server = null; this.connections = new Set(); this.inflight = 0; this.queued = 0; this.tail = Promise.resolve();
  }
  async start() {
    try {
      const peers = this.secure.read('paired-devices.bin', []);
      P.check(Array.isArray(peers) && peers.every(peer => P.object(peer) && P.UUID.test(peer.id) && P.UUID.test(peer.deviceId) &&
        typeof peer.name === 'string' && typeof peer.confirmed === 'boolean' && P.HASH.test(peer.tokenHash) &&
        typeof peer.accessToken === 'string' && /^[A-Za-z0-9_-]{43}$/.test(peer.accessToken) && same(peer.tokenHash, tokenHash(peer.accessToken)) &&
        [peer.createdAt, peer.lastSyncAt, peer.requested, peer.acknowledged].every(P.integer)), '配对信息无效，已保留原文件');
      this.peers = peers.filter(peer => peer.confirmed);
      this.secure.write('paired-devices.bin', this.peers);
      this.tls = await this.secure.certificate();
      this.server = https.createServer({key: this.tls.key, cert: this.tls.cert, minVersion: 'TLSv1.2', handshakeTimeout: 10000, requestTimeout: 90000, headersTimeout: 15000, keepAliveTimeout: 5000}, (request, response) => this.handle(request, response));
      this.server.maxConnections = 32;
      this.server.on('connection', socket => { this.connections.add(socket); socket.on('close', () => this.connections.delete(socket)); socket.setTimeout(90000, () => socket.destroy()); });
      this.server.on('clientError', (_error, socket) => socket.destroy());
      this.server.on('tlsClientError', () => {});
      const portFile = path.join(this.data.directory, 'network.json'), savedPort = readJson(portFile, 32768)?.port;
      P.check(savedPort === undefined || (Number.isInteger(savedPort) && savedPort > 0 && savedPort <= 65535), '本机同步端口记录无效');
      await new Promise((resolve, reject) => { this.server.once('error', reject); this.server.listen(savedPort || 0, '0.0.0.0', () => { this.server.removeListener('error', reject); resolve(); }); });
      this.server.on('error', () => { this.status = '局域网服务异常，请重新打开简记'; this.changed(); });
      this.port = this.server.address().port; writeJson(portFile, {port: this.port});
      this.status = addresses().length ? '同一 Wi-Fi 下可扫码配对；手机需保持简记打开' : '未找到局域网 IPv4 地址，请连接 Wi-Fi';
      this.timer = setInterval(() => this.expire(), 15000); this.timer.unref();
    } catch (error) { this.server?.close(); this.port = 0; this.status = error.message?.includes('安全存储') ? error.message : '局域网同步暂不可用，原数据和配对信息已保留；请重新打开简记'; }
    this.changed();
  }
  info() {
    return {deviceId: this.deviceId, name: this.name, addresses: addresses(), port: this.port,
      peers: this.peers.filter(peer => peer.confirmed).map(peer => ({id: peer.id, name: peer.name, lastSyncAt: peer.lastSyncAt || 0})),
      pending: [...this.requests.values()].filter(item => item.status === 'pending' && item.expiresAt > Date.now()).map(item => ({id: item.id, name: item.name})), status: this.status};
  }
  persistPeers(next) { this.secure.write('paired-devices.bin', next); this.peers = next; this.changed(); }
  expire() {
    const now = Date.now(); let changed = false;
    for (const [key, item] of this.pairings) if (item.expiresAt <= now) { this.pairings.delete(key); changed = true; }
    for (const [id, item] of this.requests) if (item.expiresAt <= now) {
      if (item.peerId) { try { this.persistPeers(this.peers.filter(peer => peer.id !== item.peerId || peer.confirmed)); } catch { continue; } }
      this.requests.delete(id); changed = true;
    }
    for (const [address, item] of this.rate) if (item.until < now) this.rate.delete(address);
    if (changed) this.changed();
  }
  async startPairing(host) {
    this.expire(); requireHttp(this.port > 0, 503, '局域网同步服务尚未就绪'); this.secure.assertAvailable();
    P.check(typeof host === 'string' && privateIPv4(host) && addresses().some(item => item.address === host), '请选择本机当前的局域网地址');
    const secret = token(), expiresAt = Date.now() + PAIR_LIFETIME;
    this.pairings.clear(); this.pairings.set(tokenHash(secret), {expiresAt, used: false});
    const payload = 'jianji://pair?data=' + Buffer.from(JSON.stringify({v: 1, host, port: this.port, fingerprint: this.tls.fingerprint, token: secret, deviceId: this.deviceId, name: this.name})).toString('base64url');
    const qrDataUrl = await QRCode.toDataURL(payload, {errorCorrectionLevel: 'M', width: 320, margin: 2});
    return {qrDataUrl, payload, expiresAt};
  }
  approvePair(id, approve) {
    this.expire(); P.check(typeof id === 'string' && typeof approve === 'boolean', '配对操作无效');
    const request = this.requests.get(id); P.check(request && request.status === 'pending', '这次配对已失效，请重新扫码');
    if (!approve) { request.status = 'rejected'; this.changed(); return; }
    const accessToken = token(), peerId = crypto.randomUUID();
    const peer = {id: peerId, deviceId: request.deviceId, name: request.name, tokenHash: tokenHash(accessToken), accessToken,
      confirmed: false, createdAt: Date.now(), lastSyncAt: 0, requested: 0, acknowledged: 0};
    this.persistPeers([...this.peers.filter(item => item.deviceId !== peer.deviceId), peer]);
    request.status = 'approved'; request.peerId = peerId; request.accessToken = accessToken; this.changed();
  }
  requestSync(id) {
    const next = P.clone(this.peers), peer = next.find(item => item.id === id && item.confirmed); P.check(peer, '配对设备不存在');
    peer.requested = (peer.requested || 0) + 1; P.check(Number.isSafeInteger(peer.requested), '同步请求次数超过上限');
    this.persistPeers(next); this.status = '已请求同步，请在手机上打开简记并保持前台'; this.changed();
  }
  revokePeer(id) {
    P.check(typeof id === 'string', '设备编号无效'); this.persistPeers(this.peers.filter(peer => peer.id !== id));
    for (const [requestId, item] of this.requests) if (item.peerId === id) this.requests.delete(requestId);
    this.changed();
  }
  authenticate(request, allowUnconfirmed = false) {
    const header = request.headers.authorization || '', match = /^Bearer ([A-Za-z0-9_-]{43,128})$/.exec(header);
    requireHttp(match, 401, '请先配对这台电脑'); const hash = tokenHash(match[1]);
    const peer = this.peers.find(item => same(item.tokenHash, hash));
    requireHttp(peer && (allowUnconfirmed || peer.confirmed), 401, '配对已失效，请重新扫码'); return peer;
  }
  async handle(request, response) {
    this.inflight++;
    try {
      requireHttp(this.inflight <= 8, 503, '同步服务正忙，请稍后重试');
      const address = (request.socket.remoteAddress || '').replace(/^::ffff:/, '');
      requireHttp(privateIPv4(address), 403, '仅接受同一局域网设备连接');
      requireHttp(typeof request.url === 'string' && request.url.startsWith('/') && !request.url.startsWith('//') && request.url.length < 2048, 400, '请求地址无效');
      const url = new URL(request.url, 'https://local.invalid'), route = url.pathname;
      if (route.startsWith('/v1/pair/')) {
        const bucket = this.rate.get(address) || {count: 0, until: Date.now() + 60000};
        if (bucket.until < Date.now()) { bucket.count = 0; bucket.until = Date.now() + 60000; }
        bucket.count++; this.rate.set(address, bucket); requireHttp(bucket.count <= 100, 429, '配对请求过于频繁，请稍后重试'); this.expire();
      }
      if (route === '/v1/pair/request' && request.method === 'POST') {
        const body = await jsonBody(request);
        requireHttp(P.object(body) && typeof body.token === 'string' && body.token.length <= 128 && P.UUID.test(body.deviceId) && body.deviceId !== this.deviceId && typeof body.name === 'string' && body.name.length > 0 && body.name.length <= 128, 400, '配对信息无效');
        const hash = tokenHash(body.token), pairing = this.pairings.get(hash);
        requireHttp(pairing && pairing.expiresAt > Date.now() && !pairing.used, 401, '二维码已使用或过期，请重新扫码');
        requireHttp(this.requests.size < 32, 429, '待处理配对过多'); pairing.used = true;
        const id = crypto.randomUUID(); this.requests.set(id, {id, deviceId: body.deviceId, name: body.name, tokenHash: hash, status: 'pending', expiresAt: Date.now() + PAIR_LIFETIME});
        this.changed(); send(response, 200, {requestId: id, status: 'pending'}); return;
      }
      if (route === '/v1/pair/status' && request.method === 'GET') {
        const item = this.requests.get(url.searchParams.get('requestId'));
        const match = /^Pair ([A-Za-z0-9_-]{43,128})$/.exec(request.headers.authorization || '');
        requireHttp(item && item.expiresAt > Date.now() && match && same(item.tokenHash, tokenHash(match[1])), 401, '配对请求已失效');
        send(response, 200, item.status === 'approved' ? {status: 'approved', peerId: item.peerId, accessToken: item.accessToken, deviceId: this.deviceId, name: this.name} : {status: item.status}); return;
      }
      if (route === '/v1/pair/finish' && request.method === 'POST') {
        const peer = this.authenticate(request, true), body = await jsonBody(request);
        if (peer.confirmed && peer.finishedRequestId === body?.requestId) { send(response, 200, {ok: true}); return; }
        const item = this.requests.get(body?.requestId);
        requireHttp(item && item.status === 'approved' && item.peerId === peer.id && item.expiresAt > Date.now(), 409, '配对确认已过期，请重新扫码');
        const next = P.clone(this.peers), confirmed = next.find(item => item.id === peer.id); confirmed.confirmed = true; confirmed.finishedRequestId = item.id; this.persistPeers(next);
        this.requests.delete(item.id); this.pairings.delete(item.tokenHash); this.changed(); send(response, 200, {ok: true}); return;
      }
      const peer = this.authenticate(request);
      if (route === '/v1/status' && request.method === 'GET') {
        send(response, 200, {protocol: 1, deviceId: this.deviceId, name: this.name, lastSyncAt: peer.lastSyncAt || 0, syncRequested: peer.requested || 0}); return;
      }
      if (route === '/v1/peers/self' && request.method === 'DELETE') { this.revokePeer(peer.id); send(response, 200, {ok: true}); return; }
      if (route === '/v1/blobs/check' && request.method === 'POST') {
        const body = await jsonBody(request); requireHttp(P.object(body) && Array.isArray(body.hashes) && body.hashes.length <= 20000 && body.hashes.every(hash => typeof hash === 'string' && P.HASH.test(hash)), 400, '图片清单无效');
        send(response, 200, {missing: [...new Set(body.hashes)].filter(hash => !this.data.hasBlob(hash))}); return;
      }
      const blobMatch = /^\/v1\/blobs\/([0-9a-f]{64})$/.exec(route);
      if (blobMatch && request.method === 'PUT') {
        const bytes = await readBody(request, P.MAX_IMAGE);
        try { this.data.putImage(bytes, blobMatch[1]); } catch { throw new HttpError(400, '图片校验失败或未能安全保存'); }
        send(response, 200, {ok: true}); return;
      }
      if (blobMatch && request.method === 'GET') {
        requireHttp(this.data.hasBlob(blobMatch[1]), 404, '图片尚不可用'); const file = this.data.blobFile(blobMatch[1]), stat = fs.statSync(file);
        response.writeHead(200, {'Content-Type': 'application/octet-stream', 'Content-Length': stat.size, 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff'});
        const stream = fs.createReadStream(file); stream.on('error', () => response.destroy()); response.on('close', () => stream.destroy()); stream.pipe(response); return;
      }
      if (route === '/v1/sync' && request.method === 'POST') {
        const envelope = await jsonBody(request);
        try { P.validateEnvelope(envelope); } catch (error) { throw new HttpError(400, error.message); }
        requireHttp(envelope.deviceId === peer.deviceId, 403, '同步设备编号与配对不一致');
        requireHttp(this.queued < 4, 503, '同步服务正忙，请稍后重试'); this.queued++;
        const signal = new AbortController(); request.once('aborted', () => signal.abort()); response.once('close', () => { if (!response.writableEnded) signal.abort(); });
        const task = this.tail.then(() => this.synchronize(peer.id, envelope, signal.signal));
        this.tail = task.catch(() => {});
        try { send(response, 200, await task); } finally { this.queued--; } return;
      }
      if (route === '/v1/sync/ack' && request.method === 'POST') {
        const body = await jsonBody(request);
        const receipt = this.data.document.receipts?.find(item => item.id === body?.syncId && item.peerId === peer.id);
        requireHttp(receipt && receipt.createdAt > Date.now() - 86400000 && (body.syncRequested === undefined || P.integer(body.syncRequested)), 409, '同步回执无效，请重新同步');
        const next = P.clone(this.peers), updated = next.find(item => item.id === peer.id);
        updated.lastSyncAt = Date.now(); updated.acknowledged = Math.max(updated.acknowledged || 0, Math.min(body.syncRequested || 0, receipt.requested || 0));
        this.persistPeers(next); this.status = updated.acknowledged < updated.requested ? '电脑已请求下一次同步，请保持手机简记打开' : '上次同步已完成'; this.changed(); send(response, 200, {ok: true}); return;
      }
      throw new HttpError(404, '接口不存在');
    } catch (error) { send(response, error.status || 500, {error: error.status ? error.message : '同步服务未能完成请求，原数据已保留，请重试'}); }
    finally { this.inflight--; }
  }
  async synchronize(peerId, envelope, signal) {
    let imported = null;
    try {
      const peer = this.peers.find(item => item.id === peerId && item.confirmed); requireHttp(peer, 401, '配对已撤销');
      requireHttp(!signal.aborted, 409, '同步连接已取消'); this.status = '正在等待电脑保存当前笔记'; this.changed();
      const ready = await this.prepare(signal); requireHttp(ready && !signal.aborted, 409, '电脑有尚未保存的编辑，请保持简记打开后重试');
      requireHttp(this.peers.some(item => item.id === peerId && item.confirmed), 401, '配对已撤销');
      this.data.locked = true; this.status = '正在合并笔记和分类'; this.changed();
      const receipt = {id: crypto.randomUUID(), peerId, requested: peer.requested || 0, createdAt: Date.now()};
      imported = this.data.merge(envelope, receipt);
      this.status = '电脑已合并，等待手机完成图片导入';
      return {protocol: 1, deviceId: this.deviceId, records: imported.records, summary: imported.summary, syncId: receipt.id};
    } catch (error) {
      this.status = '本次同步未完成，请检查两端后重试';
      throw error instanceof HttpError ? error : new HttpError(409, error.message?.includes('图片') ? '图片尚未传输完整，请重试' : '同步未完成，原数据已保留');
    } finally {
      this.data.locked = false;
      this.result(imported ? {ok: true, state: imported.state, summary: imported.summary} : {ok: false, error: '本次同步未完成，编辑内容已保留，请重试'});
      this.changed();
    }
  }
  close() { clearInterval(this.timer); this.connections.forEach(socket => socket.destroy()); this.server?.close(); }
}
module.exports = {SyncServer};
