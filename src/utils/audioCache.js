import { Capacitor, registerPlugin } from '@capacitor/core';
import { parseBlob } from 'music-metadata';

// Only fully downloaded, verified files are published. Playback owns every URL lease.
// Web intentionally plays the remote source first: the separate full fetch can use extra data.
const DB_NAME = 'MoeAudioCacheDB';
const DB_VERSION = 2;
const META_STORE = 'meta';
const BLOB_STORE = 'blobs';
const MIB = 1024 * 1024;
const DEFAULT_LIMIT_MB = 500;
const IMPORT_CHUNK_BYTES = 128 * 1024;
const nativeCache = registerPlugin('MoePlayback');
const isAndroid = () => Capacitor.isNativePlatform() && Capacitor.getPlatform() === 'android';

let dbPromise;
let mutationTail = Promise.resolve();
let downloadTail = Promise.resolve();
let generation = 0;
let nativeListener;
let settingsListenerInstalled = false;
let appliedCacheSignature;
const inflight = new Map();
const reservations = new Map();
const controllers = new Set();
const liveObjectUrls = new Map();
const pendingDeletes = new Map();
const validated = new Set();
const debug = (message, error) => console.debug('[AudioCache]', message, error?.name || '');

const readObject = (key) => {
    try {
        const value = JSON.parse(globalThis.localStorage?.getItem(key) || '{}');
        return value && typeof value === 'object' && !Array.isArray(value) ? value : {};
    } catch {
        return {};
    }
};
const readSettings = () => readObject('settings');
const isEnabled = () => ![false, 'off', 'false', 0, '0'].includes(readSettings().audioCacheEnabled);
const getLimitBytes = () => {
    const value = Number(readSettings().audioCacheLimitMB);
    return Number.isFinite(value) && value > 0 && value * MIB <= Number.MAX_SAFE_INTEGER
        ? Math.max(1, Math.floor(value * MIB)) : DEFAULT_LIMIT_MB * MIB;
};
const accountId = () => String(readObject('MoeData').UserInfo?.userid || '');
const previewFlag = (song) => song?.isPreview === true || song?.isPreview === 1 || song?.isPreview === 'true';
const identity = (song = {}) => {
    const source = song.isCloud || song.source === 'cloud' ? 'cloud' : 'online';
    const hash = String(song.hash || song.logicalHash || '');
    return {
        source, hash,
        accountId: source === 'cloud' ? accountId() : 'public',
        playHash: String(song.playHash || hash),
        resolvedQuality: String(song.resolvedQuality || song.quality || (source === 'cloud' ? 'original' : readSettings().quality || '128')),
        isPreview: previewFlag(song)
    };
};
const buildCacheKey = (song) => {
    if (!song || song.isLocal) return '';
    const item = identity(song);
    if (!item.hash || (item.source === 'cloud' && !item.accountId)) return '';
    return JSON.stringify(['v2', item.source, item.accountId, item.hash, item.playHash, item.resolvedQuality, item.isPreview]);
};
const songMetadata = (song, media = {}) => {
    const item = identity(song);
    const image = typeof (song.img || song.cover) === 'string' ? song.img || song.cover : '';
    return {
        ...item, isCloud: item.source === 'cloud',
        name: String(song.name || song.displayName || song.hash || ''),
        author: String(song.author || ''),
        img: image.startsWith('blob:') ? '' : image,
        timeLength: Number(song.timeLength || song.timelen) || (Number(song.duration || media.duration) * 1000) || 0,
        qualityLabel: String(song.qualityLabel || item.resolvedQuality),
        ...(song.previewDuration ? { previewDuration: Number(song.previewDuration) } : {})
    };
};
const visibleToAccount = (meta) => !meta.isCloud || (!!accountId() && meta.accountId === accountId());
const serialize = (work) => {
    const result = mutationTail.catch(() => {}).then(work);
    mutationTail = result.catch(() => {});
    return result;
};
const requestResult = (request) => new Promise((resolve, reject) => {
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
});
const transactionDone = (tx) => new Promise((resolve, reject) => {
    tx.oncomplete = () => resolve();
    tx.onerror = () => reject(tx.error || new Error('Cache transaction failed'));
    tx.onabort = () => reject(tx.error || new Error('Cache transaction aborted'));
});
const openDb = () => {
    if (dbPromise) return dbPromise;
    dbPromise = new Promise((resolve, reject) => {
        if (typeof indexedDB === 'undefined') return reject(new Error('IndexedDB unavailable'));
        const request = indexedDB.open(DB_NAME, DB_VERSION);
        request.onerror = () => reject(request.error);
        request.onupgradeneeded = () => {
            const db = request.result;
            const meta = db.objectStoreNames.contains(META_STORE)
                ? request.transaction.objectStore(META_STORE) : db.createObjectStore(META_STORE, { keyPath: 'key' });
            if (!meta.indexNames.contains('hash')) meta.createIndex('hash', 'hash', { unique: false });
            if (!db.objectStoreNames.contains(BLOB_STORE)) db.createObjectStore(BLOB_STORE, { keyPath: 'key' });
        };
        request.onsuccess = () => {
            const db = request.result;
            db.onversionchange = () => { db.close(); dbPromise = null; };
            resolve(db);
        };
    }).catch(error => { dbPromise = null; throw error; });
    return dbPromise;
};
const allMeta = async () => {
    const db = await openDb();
    return requestResult(db.transaction(META_STORE).objectStore(META_STORE).getAll());
};
const hashMeta = async (hash) => {
    const db = await openDb();
    return requestResult(db.transaction(META_STORE).objectStore(META_STORE).index('hash').getAll(hash));
};
const readBlob = async (key) => {
    const db = await openDb();
    return (await requestResult(db.transaction(BLOB_STORE).objectStore(BLOB_STORE).get(key)))?.blob || null;
};
const putMeta = async (meta) => {
    const db = await openDb();
    const tx = db.transaction(META_STORE, 'readwrite');
    const done = transactionDone(tx);
    tx.objectStore(META_STORE).put(meta);
    await done;
};
const putEntry = async (meta, blob) => {
    const db = await openDb();
    const tx = db.transaction([META_STORE, BLOB_STORE], 'readwrite');
    const done = transactionDone(tx);
    tx.objectStore(META_STORE).put(meta);
    tx.objectStore(BLOB_STORE).put({ key: meta.key, blob });
    await done;
};
const deleteKeys = async (keys) => {
    if (!keys.length) return;
    const db = await openDb();
    const tx = db.transaction([META_STORE, BLOB_STORE], 'readwrite');
    const done = transactionDone(tx);
    keys.forEach(key => {
        tx.objectStore(META_STORE).delete(key);
        tx.objectStore(BLOB_STORE).delete(key);
        validated.delete(key);
    });
    await done;
};
const leased = (key) => [...liveObjectUrls.values()].some(item => item.key === key);
const usedBytes = (metas) => metas.reduce((sum, item) => sum + (Number(item.size) || 0), 0);
const reservedBytes = () => [...reservations.values()].reduce((sum, size) => sum + size, 0);
const detachedBytes = () => [...pendingDeletes.values()].reduce((sum, size) => sum + size, 0);
const retire = async (metas) => {
    // Deleting the IDB record does not revoke a Blob URL: its retained bytes remain in the budget.
    await deleteKeys(metas.map(item => item.key));
    metas.forEach(item => { if (leased(item.key)) pendingDeletes.set(item.key, item.size); });
};
const evictToFit = async (incomingBytes = 0, preserveKey = '') => {
    const limit = getLimitBytes();
    if (incomingBytes + reservedBytes() > limit) return false;
    const metas = await allMeta();
    let total = usedBytes(metas) + detachedBytes() + reservedBytes() + incomingBytes;
    if (total <= limit) return true;
    const candidates = metas.filter(item => item.key !== preserveKey && !leased(item.key))
        .sort((a, b) => (a.lastAccessAt || 0) - (b.lastAccessAt || 0));
    // Do not destroy old songs for a new file that cannot fit even after eviction.
    if (total - usedBytes(candidates) > limit) return false;
    const doomed = [];
    for (const item of candidates) {
        if (total <= limit) break;
        total -= item.size;
        doomed.push(item.key);
    }
    await deleteKeys(doomed);
    return true;
};
const webStats = async () => {
    const metas = await allMeta();
    return {
        count: metas.filter(item => item.complete && visibleToAccount(item)).length,
        totalSize: usedBytes(metas), limitBytes: getLimitBytes(),
        pendingBytes: detachedBytes(), downloadingBytes: reservedBytes(),
        legacyBytes: usedBytes(metas.filter(item => !item.complete)),
        enabled: isEnabled()
    };
};
const dispatch = (detail) => {
    if (typeof window !== 'undefined' && typeof CustomEvent === 'function') {
        window.dispatchEvent(new CustomEvent('audio-cache-change', { detail }));
    }
};
const notify = async (status = 'ready', extra = {}) => {
    const stats = await getCacheStats();
    dispatch({ ...stats, stats, status, ...extra });
};
const ensureListeners = () => {
    if (typeof window === 'undefined') return;
    if (!settingsListenerInstalled) {
        settingsListenerInstalled = true;
        window.addEventListener('settings-change', () => { void applyCacheSettings(); });
    }
    if (isAndroid() && !nativeListener) {
        nativeListener = nativeCache.addListener('cacheChange', (event) => {
            void notify(event.status || 'ready', { cacheKey: event.cacheKey, reason: event.reason });
        }).catch(() => { nativeListener = null; });
    }
};
const assertActive = (token, signal) => {
    if (token !== generation || !isEnabled() || signal?.aborted) {
        throw new DOMException('Cache operation cancelled', 'AbortError');
    }
};

const responseLength = (response) => {
    if (!response.ok || response.type === 'opaque') throw new Error('Unusable response');
    const mime = (response.headers.get('content-type') || '').split(';')[0].trim().toLowerCase();
    if (/^(text\/|application\/(json|.*\+json|xml|.*\+xml|javascript))/.test(mime)) throw new Error('Not audio');
    let declared = Number(response.headers.get('content-length')) || 0;
    if (response.headers.get('content-encoding') && response.headers.get('content-encoding') !== 'identity') declared = 0;
    if (response.status === 206) {
        const range = /^bytes\s+(\d+)-(\d+)\/(\d+)$/i.exec(response.headers.get('content-range') || '');
        if (!range || Number(range[1]) !== 0 || Number(range[2]) + 1 !== Number(range[3])) throw new Error('Partial audio response');
        if (declared && declared !== Number(range[3])) throw new Error('Range length mismatch');
        declared = Number(range[3]);
    } else if (response.status !== 200) {
        throw new Error('Expected a complete audio response');
    }
    return { declared, mime };
};
const inspectBlob = async (blob, song = {}, declared = 0) => {
    if (!(blob instanceof Blob) || !blob.size || (declared && blob.size !== declared)) throw new Error('Audio size mismatch');
    const head = new Uint8Array(await blob.slice(0, 64).arrayBuffer());
    const text = new TextDecoder().decode(head).trimStart();
    if (/^(?:<!doctype|<html|<\?xml|\{|\[)/i.test(text)) throw new Error('Error document instead of audio');
    if (text.startsWith('RIFF') && head.length >= 12) {
        const length = new DataView(head.buffer).getUint32(4, true) + 8;
        if (length > blob.size) throw new Error('Truncated wave file');
    }
    const metadata = await parseBlob(blob, { duration: true, skipCovers: true });
    const duration = Number(metadata.format.duration);
    if (!metadata.format.codec || !Number.isFinite(duration) || duration <= 0) throw new Error('No valid audio track');
    const expected = previewFlag(song) ? Number(song.previewDuration || 0)
        : Number(song.timeLength || song.timelen || Number(song.duration) * 1000) / 1000;
    if (expected > 0 && Math.abs(expected - duration) > Math.max(2, expected * 0.03)) throw new Error('Incomplete audio duration');
    return { duration, mime: blob.type || 'application/octet-stream' };
};
const upgradeLegacy = async (meta, blob, requested) => {
    // v1 never recorded the source/account/preview flag. Never guess a cloud owner,
    // or label a short legacy preview as a complete song without a trusted duration.
    if (!requested || requested.isCloud || previewFlag(requested) || !meta.quality
        || !(Number(requested.timeLength || requested.timelen || Number(requested.duration) * 1000) > 0)) return null;
    const media = await inspectBlob(blob, requested, meta.size);
    const song = songMetadata({ ...requested, playHash: meta.key.split('@')[0], resolvedQuality: meta.quality }, media);
    const next = { ...song, key: buildCacheKey(song), size: blob.size, mime: blob.type, complete: true,
        version: 2, createdAt: meta.createdAt || Date.now(), lastAccessAt: Date.now() };
    // Retain the old key until the same bytes have been published successfully.
    await putEntry(next, blob);
    await deleteKeys([meta.key]);
    validated.add(next.key);
    return next;
};
const matches = (meta, song, allowFallback) => {
    const wanted = identity(song);
    if (meta.hash !== wanted.hash || !visibleToAccount(meta)) return false;
    if (meta.complete) {
        if (meta.source !== wanted.source || meta.accountId !== wanted.accountId || meta.isPreview !== wanted.isPreview) return false;
    } else if (wanted.source === 'cloud' || wanted.isPreview || !meta.quality) return false;
    if (song.cacheKey && meta.key === song.cacheKey) return true;
    if (song.playHash && meta.playHash && meta.playHash !== String(song.playHash) && !allowFallback) return false;
    return allowFallback || String(meta.resolvedQuality || meta.quality) === wanted.resolvedQuality;
};
const checkedEntry = async (meta, requested) => {
    const blob = await readBlob(meta.key);
    if (!blob || !blob.size || blob.size !== meta.size) {
        await retire([meta]);
        return null;
    }
    if (!meta.complete) {
        try {
            const upgraded = await upgradeLegacy(meta, blob, requested);
            return upgraded ? { meta: upgraded, blob } : null;
        } catch {
            // An uncertain legacy file is retained for recovery, but never advertised as offline-ready.
            return null;
        }
    }
    try {
        if (!validated.has(meta.key)) {
            await inspectBlob(blob, meta, meta.size);
            validated.add(meta.key);
        }
        return { meta, blob };
    } catch {
        await retire([meta]);
        return null;
    }
};
const findWebEntry = async (song, allowFallback) => {
    const wanted = identity(song);
    const candidates = (await hashMeta(wanted.hash)).filter(item => matches(item, song, allowFallback));
    candidates.sort((a, b) => Number(b.key === song.cacheKey) - Number(a.key === song.cacheKey)
        || Number(b.resolvedQuality === wanted.resolvedQuality) - Number(a.resolvedQuality === wanted.resolvedQuality)
        || (b.lastAccessAt || 0) - (a.lastAccessAt || 0));
    for (const meta of candidates) {
        const entry = await checkedEntry(meta, song);
        if (entry) return entry;
    }
    return null;
};
const publicSong = (meta) => {
    const { key, version, complete, createdAt, lastAccessAt, ...song } = meta;
    return { ...song, cacheKey: key, __fromCache: true };
};
const nativeSong = (song) => ({ ...songMetadata(song), ...(song.cacheKey ? { cacheKey: song.cacheKey } : {}) });
const importLegacyEntry = async (entry, token) => {
    let id;
    try {
        assertActive(token);
        const start = await nativeCache.beginCacheImport({ song: publicSong(entry.meta), size: entry.blob.size,
            mime: entry.meta.mime || entry.blob.type });
        id = start.id;
        if (!id) return null;
        for (let offset = 0; offset < entry.blob.size; offset += IMPORT_CHUNK_BYTES) {
            assertActive(token);
            const bytes = new Uint8Array(await entry.blob.slice(offset, offset + IMPORT_CHUNK_BYTES).arrayBuffer());
            let binary = '';
            for (let i = 0; i < bytes.length; i++) binary += String.fromCharCode(bytes[i]);
            await nativeCache.appendCacheImport({ id, data: btoa(binary) });
        }
        assertActive(token);
        const result = await nativeCache.finishCacheImport({ id });
        if (!result.ok) return null;
        assertActive(token);
        const found = await nativeCache.findCachedSong({ song: publicSong(entry.meta), allowFallback: false });
        if (!found.song) return null;
        await deleteKeys([entry.meta.key]);
        return { ...found.song, __fromCache: true };
    } catch (error) {
        debug('Legacy cache import deferred', error);
        return null;
    } finally {
        if (id) await nativeCache.cancelCacheImport({ id }).catch(() => {});
    }
};

/** No URL/API call is required. The caller must release the returned Web blob URL. */
export const resolveCachedSong = async (song, { allowFallback = false } = {}) => {
    ensureListeners();
    if (!isEnabled() || !buildCacheKey(song)) return null;
    const token = generation;
    try {
        if (isAndroid()) {
            const found = await nativeCache.findCachedSong({ song: nativeSong(song), allowFallback });
            assertActive(token);
            if (found.song) return { ...song, ...found.song, __fromCache: true };
            // 只在锁内定位旧 IndexedDB 记录；迁移上传整首歌不能在变更锁里跑
            const entry = await serialize(async () => {
                assertActive(token);
                return findWebEntry(song, allowFallback);
            });
            if (!entry) return null;
            const migrated = await importLegacyEntry(entry, token);
            return migrated ? { ...song, ...migrated } : null;
        }
        return await serialize(async () => {
            assertActive(token);
            const entry = await findWebEntry(song, allowFallback);
            assertActive(token);
            if (!entry) return null;
            await putMeta({ ...entry.meta, lastAccessAt: Date.now() });
            assertActive(token);
            const url = URL.createObjectURL(entry.blob);
            liveObjectUrls.set(url, { key: entry.meta.key, size: entry.blob.size });
            return { ...song, ...publicSong(entry.meta), url, __fromCache: true };
        });
    } catch (error) {
        debug('Cache lookup unavailable', error);
        return null;
    }
};
export const acquireCachedAudioUrl = async (song) => (await resolveCachedSong(song))?.url || null;

const reserve = (key, bytes, token, signal) => serialize(async () => {
    assertActive(token, signal);
    const old = reservations.get(key) || 0;
    reservations.delete(key);
    try {
        if (!await evictToFit(bytes, key)) throw new Error('Cache capacity exceeded');
        assertActive(token, signal);
        reservations.set(key, bytes);
    } catch (error) {
        if (old && token === generation) reservations.set(key, old);
        throw error;
    }
});
const download = async (song, url, key, token) => {
    const controller = new AbortController();
    controllers.add(controller);
    let reader;
    try {
        assertActive(token, controller.signal);
        const exists = await serialize(() => findWebEntry(song, false));
        if (exists) return true;
        const response = await fetch(url, { signal: controller.signal, credentials: 'omit' });
        const { declared, mime } = responseLength(response);
        if (declared > getLimitBytes()) throw new Error('File exceeds total cache capacity');
        if (!response.body?.getReader) throw new Error('Streaming download unavailable');
        reader = response.body.getReader();
        if (declared) await reserve(key, declared, token, controller.signal);
        const chunks = [];
        let size = 0;
        await notify('downloading', { cacheKey: key });
        while (true) {
            assertActive(token, controller.signal);
            const { value, done } = await reader.read();
            if (done) break;
            size += value.byteLength;
            if (size > getLimitBytes() || (declared && size > declared)) throw new Error('Audio exceeds declared size or capacity');
            if (!declared) await reserve(key, size, token, controller.signal);
            chunks.push(value);
        }
        const blob = new Blob(chunks, { type: mime || 'application/octet-stream' });
        const media = await inspectBlob(blob, song, declared);
        return await serialize(async () => {
            assertActive(token, controller.signal);
            const item = songMetadata(song, media);
            const meta = { ...item, key, size: blob.size, mime: blob.type, complete: true, version: 2,
                createdAt: Date.now(), lastAccessAt: Date.now() };
            // Reservation already includes all bytes; mutation serialization prevents concurrent overflow.
            if (!await evictToFit(0, key)) throw new Error('Cache capacity changed');
            assertActive(token, controller.signal);
            await putEntry(meta, blob);
            if (token !== generation || !isEnabled()) {
                await deleteKeys([key]);
                return false;
            }
            reservations.delete(key);
            validated.add(key);
            return true;
        });
    } finally {
        controller.abort();
        await reader?.cancel().catch(() => {});
        controllers.delete(controller);
        reservations.delete(key);
    }
};
/** Resolves true only when a valid complete file exists. Failure never interrupts online playback. */
export const cacheAudioFromUrl = (song, url) => {
    ensureListeners();
    if (isAndroid() || !isEnabled() || typeof url !== 'string' || !/^https?:/i.test(url)) return Promise.resolve(false);
    const key = buildCacheKey(song);
    if (!key) return Promise.resolve(false);
    if (inflight.has(key)) return inflight.get(key);
    const token = generation;
    // One full fetch at a time. A key is reserved before queuing, so duplicate requests share the task.
    const task = downloadTail.catch(() => {}).then(() => download({ ...song }, url, key, token))
        .then(async ok => { await notify(ok ? 'cached' : 'cancelled', { cacheKey: key }); return ok; })
        .catch(async error => {
            const status = error?.name === 'AbortError' ? 'cancelled' : 'failed';
            debug('Complete audio cache not available', error);
            await notify(status, { cacheKey: key, reason: status === 'failed' ? 'not-offline' : 'cancelled' });
            return false;
        }).finally(() => { if (inflight.get(key) === task) inflight.delete(key); });
    inflight.set(key, task);
    downloadTail = task;
    return task;
};
export const releaseObjectUrl = (url) => {
    const lease = liveObjectUrls.get(url);
    if (!lease) return;
    URL.revokeObjectURL(url);
    liveObjectUrls.delete(url);
    if (!leased(lease.key)) pendingDeletes.delete(lease.key);
    void serialize(() => evictToFit()).then(() => notify('released')).catch(() => {});
};
// This is an explicit player teardown API, never called by clear/settings/eviction.
export const releaseAllObjectUrls = () => [...liveObjectUrls.keys()].forEach(releaseObjectUrl);

export const getCacheStats = async () => {
    ensureListeners();
    try {
        const legacy = await webStats().catch(() => ({ count: 0, totalSize: 0, pendingBytes: 0 }));
        if (!isAndroid()) return legacy;
        const stats = await nativeCache.getCacheStats();
        return { ...stats, enabled: isEnabled(), limitBytes: stats.limitBytes || getLimitBytes(),
            totalSize: (Number(stats.totalSize) || 0) + legacy.totalSize,
            legacyBytes: legacy.totalSize, pendingBytes: (Number(stats.pendingBytes) || 0) + legacy.pendingBytes };
    } catch (error) {
        debug('Cache stats unavailable', error);
        return { count: 0, totalSize: 0, limitBytes: getLimitBytes(), pendingBytes: 0, enabled: isEnabled(), unavailable: true };
    }
};
export const listCachedSongs = async () => {
    ensureListeners();
    try {
        if (isAndroid()) {
            const result = await nativeCache.listCachedSongs();
            const songs = result.songs || [];
            // v2 Web records already have ownership/completeness evidence. Import lazily on first listing.
            if (isEnabled()) {
                const token = generation;
                // 锁内只读+验证原始记录，真正的上传放到锁外，避免长事务卡住播放查询
                const entries = await serialize(async () => {
                    const ready = [];
                    for (const meta of (await allMeta()).filter(meta => meta.complete && visibleToAccount(meta))) {
                        const entry = await checkedEntry(meta, meta);
                        if (entry) ready.push(entry);
                    }
                    return ready;
                });
                for (const entry of entries) {
                    assertActive(token);
                    const found = await nativeCache.findCachedSong({ song: publicSong(entry.meta), allowFallback: false });
                    if (found.song) {
                        await deleteKeys([entry.meta.key]);
                        continue;
                    }
                    await importLegacyEntry(entry, token);
                }
                return (await nativeCache.listCachedSongs()).songs || songs;
            }
            return songs;
        }
        return await serialize(async () => {
            const songs = [];
            for (const meta of await allMeta()) {
                if (!meta.complete || !visibleToAccount(meta)) continue;
                const entry = await checkedEntry(meta, meta);
                if (entry) songs.push({ ...publicSong(entry.meta), lastAccessAt: entry.meta.lastAccessAt });
            }
            return songs.sort((a, b) => b.lastAccessAt - a.lastAccessAt);
        });
    } catch (error) { debug('Cache list unavailable', error); return []; }
};
const cancelDownloads = () => {
    generation++;
    controllers.forEach(controller => controller.abort());
    reservations.clear();
    inflight.clear();
};
export const clearAudioCache = async () => {
    cancelDownloads();
    try {
        if (isAndroid()) {
            const result = await nativeCache.clearCache();
            if (!result.ok) return false;
        }
        await serialize(async () => { await retire(await allMeta()); });
        await notify('cleared');
        return true;
    } catch (error) { debug('Cache clear failed', error); return false; }
};
export const invalidateCachedSong = async (songOrKey) => {
    const key = typeof songOrKey === 'string' ? songOrKey : songOrKey?.cacheKey || buildCacheKey(songOrKey);
    if (!key) return false;
    try {
        if (isAndroid()) await nativeCache.removeCachedSong({ key });
        await serialize(async () => { await retire((await allMeta()).filter(meta => meta.key === key)); });
        await notify('invalidated', { cacheKey: key });
        return true;
    } catch (error) { debug('Cache invalidation failed', error); return false; }
};
export const applyCacheSettings = async () => {
    ensureListeners();
    const signature = `${isEnabled()}|${getLimitBytes()}`;
    // 主题/语言等其他设置变化也会触发 settings-change，不能每次都打断正在进行的下载
    if (signature === appliedCacheSignature) return true;
    appliedCacheSignature = signature;
    cancelDownloads();
    try {
        if (isAndroid()) await nativeCache.configure({ cacheEnabled: isEnabled(), cacheLimitBytes: getLimitBytes() });
        // Turning off stops automatic reads/downloads but never removes retained songs.
        if (isEnabled() && !isAndroid()) await serialize(() => evictToFit());
        await notify(isEnabled() ? 'ready' : 'disabled');
        return true;
    } catch (error) {
        // 失败时清掉签名，下次设置变化或调用还能重试
        appliedCacheSignature = undefined;
        debug('Cache settings unavailable', error);
        return false;
    }
};

export const __internals = { buildCacheKey, isEnabled, getLimitBytes, inspectBlob, responseLength,
    openDb, allMeta, putEntry, serialize, evictToFit, identity, get generation() { return generation; },
    // 仅测试用：断开连接、取消下载并清空内存状态，数据库内容由调用方自行删除
    async reset() {
        controllers.forEach(controller => controller.abort());
        controllers.clear();
        reservations.clear();
        inflight.clear();
        pendingDeletes.clear();
        validated.clear();
        liveObjectUrls.clear();
        mutationTail = Promise.resolve();
        downloadTail = Promise.resolve();
        generation++;
        const db = await dbPromise?.catch(() => null);
        try { db?.close(); } catch { /* 已关闭 */ }
        dbPromise = undefined;
    } };
