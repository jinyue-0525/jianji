import { uid } from './store.js';

const DATABASE = 'jianji-preview-images-v1';
const OBJECT_STORE = 'images';
const MAX_BYTES = 30 * 1024 * 1024;
const MAX_SIDE = 2048;
let databasePromise;

function storageError(error, action) {
  if (error && error.name === 'QuotaExceededError') return new Error('图片没有保存成功：浏览器本地存储空间不足，请释放空间后重试。');
  return new Error(`${action}：浏览器图片存储不可用，请允许此网站使用本地存储后重试。`);
}

function openDatabase() {
  if (databasePromise) return databasePromise;
  databasePromise = new Promise((resolve, reject) => {
    if (!globalThis.indexedDB) {
      reject(new Error('当前浏览器不支持本地图片存储，请使用支持 IndexedDB 的浏览器。'));
      return;
    }
    let request;
    let blocked = false;
    try { request = globalThis.indexedDB.open(DATABASE, 1); }
    catch (error) { reject(storageError(error, '无法打开图片存储')); return; }
    request.onupgradeneeded = () => {
      if (!request.result.objectStoreNames.contains(OBJECT_STORE)) request.result.createObjectStore(OBJECT_STORE);
    };
    request.onerror = () => reject(storageError(request.error, '无法打开图片存储'));
    request.onblocked = () => {
      blocked = true;
      reject(new Error('图片存储正在被另一个页面占用，请关闭其他简记页面后重试。'));
    };
    request.onsuccess = () => {
      const database = request.result;
      if (blocked) { database.close(); return; }
      database.onversionchange = () => { database.close(); databasePromise = undefined; };
      database.onclose = () => { databasePromise = undefined; };
      resolve(database);
    };
  }).catch(error => { databasePromise = undefined; throw error; });
  return databasePromise;
}

async function runTransaction(mode, action, execute) {
  const database = await openDatabase();
  return new Promise((resolve, reject) => {
    let transaction;
    let request;
    try {
      transaction = database.transaction(OBJECT_STORE, mode);
      request = execute(transaction.objectStore(OBJECT_STORE));
    } catch (error) { reject(storageError(error, action)); return; }
    transaction.oncomplete = () => resolve(request.result);
    transaction.onabort = () => reject(storageError(transaction.error || request.error, action));
    transaction.onerror = () => reject(storageError(transaction.error || request.error, action));
  });
}

async function decode(file) {
  if (typeof globalThis.createImageBitmap === 'function') {
    try {
      // Browsers apply EXIF orientation while decoding. Do not rotate a second time.
      const bitmap = await globalThis.createImageBitmap(file, { imageOrientation: 'from-image' });
      return { source: bitmap, width: bitmap.width, height: bitmap.height, release: () => bitmap.close() };
    } catch { /* Some browser-supported formats decode only through an image element. */ }
  }
  const url = URL.createObjectURL(file);
  const image = new Image();
  image.decoding = 'async';
  image.style.imageOrientation = 'from-image';
  try {
    await new Promise((resolve, reject) => {
      image.onload = resolve;
      image.onerror = () => reject(new Error('无法解码这张图片，请选择浏览器支持的 JPEG、PNG、WebP 等图片。'));
      image.src = url;
    });
    return { source: image, width: image.naturalWidth, height: image.naturalHeight, release: () => { URL.revokeObjectURL(url); image.src = ''; } };
  } catch (error) {
    URL.revokeObjectURL(url);
    throw error;
  }
}

async function prepare(file) {
  if (!(file instanceof Blob)) throw new Error('没有读取到图片文件，请重新选择图片。');
  if (!file.size) throw new Error('图片内容为空，请重新选择图片。');
  if (file.size > MAX_BYTES) throw new Error('图片超过 30 MB，请选择较小的图片。');
  if (file.type && !file.type.toLowerCase().startsWith('image/')) throw new Error('请选择图片文件，支持 JPEG、PNG、WebP 等浏览器可读取的图片。');
  const decoded = await decode(file);
  let canvas;
  try {
    if (!decoded.width || !decoded.height) throw new Error('图片尺寸无效，请重新选择图片。');
    const scale = Math.min(1, MAX_SIDE / Math.max(decoded.width, decoded.height));
    canvas = document.createElement('canvas');
    canvas.width = Math.max(1, Math.round(decoded.width * scale));
    canvas.height = Math.max(1, Math.round(decoded.height * scale));
    const context = canvas.getContext('2d', { alpha: false });
    if (!context) throw new Error('浏览器无法处理这张图片，请重新选择图片。');
    context.fillStyle = '#ffffff';
    context.fillRect(0, 0, canvas.width, canvas.height);
    context.imageSmoothingEnabled = true;
    context.imageSmoothingQuality = 'high';
    context.drawImage(decoded.source, 0, 0, canvas.width, canvas.height);
    return await new Promise((resolve, reject) => {
      try {
        canvas.toBlob(blob => {
          if (blob && blob.size) resolve(blob);
          else reject(new Error('图片转换失败，请选择另一张图片后重试。'));
        }, 'image/jpeg', 0.88);
      } catch { reject(new Error('浏览器无法将这张图片转换为 JPEG，请选择另一张图片。')); }
    });
  } catch (error) {
    if (error instanceof Error && /[\u4e00-\u9fff]/.test(error.message)) throw error;
    throw new Error('图片处理失败，可能是尺寸过大或格式不支持，请选择较小的图片。');
  } finally {
    decoded.release();
    if (canvas) { canvas.width = 0; canvas.height = 0; }
  }
}

export const images = {
  async import(file) {
    const blob = await prepare(file);
    if (globalThis.jianjiDesktop) return await globalThis.jianjiDesktop.putImage(await blob.arrayBuffer());
    const id = uid('image');
    await runTransaction('readwrite', '图片没有保存成功', storage => storage.add(blob, id));
    return id;
  },
  async get(id) {
    if (typeof id !== 'string' || !id) throw new Error('图片编号无效，无法读取图片。');
    if (globalThis.jianjiDesktop) {
      const bytes = await globalThis.jianjiDesktop.getImage(id);
      return bytes === null ? null : new Blob([bytes], {type: 'image/jpeg'});
    }
    const blob = await runTransaction('readonly', '图片读取失败', storage => storage.get(id));
    if (blob === undefined) return null;
    if (!(blob instanceof Blob)) throw new Error('这张图片的本地数据已损坏，请重新导入。');
    return blob;
  },
  async remove(id) {
    if (typeof id !== 'string' || !id) throw new Error('图片编号无效，无法删除图片。');
    if (globalThis.jianjiDesktop) { await globalThis.jianjiDesktop.removeImage(id); return; }
    await runTransaction('readwrite', '图片删除失败', storage => storage.delete(id));
  }
};
