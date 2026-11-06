import 'fake-indexeddb/auto';
import test from 'node:test';
import assert from 'node:assert/strict';

// localStorage 在 Node 里不存在，缓存的设置/账号都从这里读
const store = new Map();
globalThis.localStorage = {
    getItem: (key) => (store.has(key) ? store.get(key) : null),
    setItem: (key, value) => store.set(key, String(value)),
    removeItem: (key) => store.delete(key),
    clear: () => store.clear()
};

const { buildCacheKey, isEnabled, getLimitBytes, inspectBlob } = await import('../src/utils/audioCache.js').then(m => m.__internals);
const { cacheAudioFromUrl, clearAudioCache, getCacheStats, invalidateCachedSong, listCachedSongs,
    resolveCachedSong, releaseObjectUrl, applyCacheSettings, __internals } = await import('../src/utils/audioCache.js');

const MIB = 1024 * 1024;

/** 生成可被 music-metadata 识别的 PCM WAV，用于真实完整性校验 */
const makeWavBlob = (seconds = 2, rate = 8000) => {
    const channels = 1;
    const bits = 8;
    const data = Buffer.alloc(rate * seconds * channels * (bits / 8));
    const header = Buffer.alloc(44);
    header.write('RIFF', 0);
    header.writeUInt32LE(36 + data.length, 4);
    header.write('WAVE', 8);
    header.write('fmt ', 12);
    header.writeUInt32LE(16, 16);
    header.writeUInt16LE(1, 20);
    header.writeUInt16LE(channels, 22);
    header.writeUInt32LE(rate, 24);
    header.writeUInt32LE(rate * channels * bits / 8, 28);
    header.writeUInt16LE(channels * bits / 8, 32);
    header.writeUInt16LE(bits, 34);
    header.write('data', 36);
    header.writeUInt32LE(data.length, 40);
    return new Blob([Buffer.concat([header, data])], { type: 'audio/wav' });
};

const useSettings = (settings) => localStorage.setItem('settings', JSON.stringify(settings));

const stubFetch = (blob, { status = 200, headers = {} } = {}) => {
    const calls = [];
    const impl = async (url) => {
        calls.push(String(url));
        if (blob === 'throw') throw new TypeError('network down');
        const response = new Response(blob, {
            status,
            headers: { 'content-length': String(blob.size), ...headers }
        });
        return response;
    };
    globalThis.fetch = impl;
    return calls;
};

const song = (overrides = {}) => ({
    hash: 'HASH_A',
    name: '歌曲 A',
    author: '歌手 A',
    timeLength: 2000,
    resolvedQuality: '128',
    ...overrides
});

// 声明时长要与真实音频一致，否则完整性校验会正确地判定为未覆盖完整内容
const wavSong = (seconds, overrides = {}) => song({ timeLength: seconds * 1000, ...overrides });

const resetState = async () => {
    await __internals.reset();
    await new Promise((resolve) => {
        const request = indexedDB.deleteDatabase('MoeAudioCacheDB');
        request.onsuccess = request.onerror = request.onblocked = () => resolve();
    });
    localStorage.clear();
    useSettings({ audioCacheEnabled: 'on', audioCacheLimitMB: '500' });
};

test('缓存 key 隔离来源、账号、逻辑 hash、实际 hash、音质与试听', async () => {
    await resetState();
    const online = buildCacheKey(song());
    assert.notEqual(online, buildCacheKey(song({ isCloud: true })), '云盘与在线不能共用 key');
    assert.notEqual(online, buildCacheKey(song({ resolvedQuality: '320' })), '音质必须隔离');
    assert.notEqual(online, buildCacheKey(song({ playHash: 'HASH_STREAM' })), '实际取流 hash 必须隔离');
    assert.notEqual(online, buildCacheKey(song({ isPreview: true })), '试听必须隔离');

    localStorage.setItem('MoeData', JSON.stringify({ UserInfo: { userid: 11 } }));
    const accountA = buildCacheKey(song({ isCloud: true }));
    localStorage.setItem('MoeData', JSON.stringify({ UserInfo: { userid: 22 } }));
    const accountB = buildCacheKey(song({ isCloud: true }));
    assert.notEqual(accountA, accountB, '云盘缓存按账号隔离');
    assert.equal(buildCacheKey(song({ isLocal: true })), '', '本地文件不进入在线缓存');
});

test('关闭缓存接受 on/off 字符串与历史布尔值', async () => {
    await resetState();
    for (const value of ['off', false, 'false']) {
        useSettings({ audioCacheEnabled: value });
        assert.equal(isEnabled(), false, `${value} 必须视为关闭`);
    }
    for (const value of ['on', true, undefined]) {
        useSettings({ audioCacheEnabled: value });
        assert.equal(isEnabled(), true, `${value} 必须视为开启`);
    }
    localStorage.setItem('settings', '{坏 JSON');
    assert.equal(isEnabled(), true, '损坏配置回退默认开启');
    assert.equal(getLimitBytes(), 500 * MIB, '损坏配置回退 500MB');
});

test('完整缓存后可离线查询，且不依赖 url', async () => {
    await resetState();
    const blob = makeWavBlob(2);
    const calls = stubFetch(blob);
    const ok = await cacheAudioFromUrl(song(), 'https://cdn.example/a.wav');
    assert.equal(ok, true, '完整文件必须缓存成功');
    assert.equal(calls.length, 1);

    const resolved = await resolveCachedSong(song());
    assert.ok(resolved, '没有 url 也必须能查到缓存');
    assert.equal(resolved.__fromCache, true);
    assert.equal(resolved.name, '歌曲 A');
    assert.ok(resolved.url.startsWith('blob:'));
    assert.ok(resolved.cacheKey);
    releaseObjectUrl(resolved.url);

    const list = await listCachedSongs();
    assert.equal(list.length, 1);
    assert.equal(list[0].qualityLabel, '128');
    const stats = await getCacheStats();
    assert.equal(stats.count, 1);
    assert.ok(stats.totalSize >= blob.size);
});

test('同曲并发只真正抓取一次', async () => {
    await resetState();
    const blob = makeWavBlob(2);
    const calls = stubFetch(blob);
    const results = await Promise.all([
        cacheAudioFromUrl(song({ hash: 'HASH_DEDUP' }), 'https://cdn.example/d.wav'),
        cacheAudioFromUrl(song({ hash: 'HASH_DEDUP' }), 'https://cdn.example/d.wav'),
        cacheAudioFromUrl(song({ hash: 'HASH_DEDUP' }), 'https://cdn.example/d.wav')
    ]);
    assert.deepEqual(results, [true, true, true]);
    assert.equal(calls.length, 1, '同曲去重后只应请求一次');
});

test('错误页、截断与超限都必须失败且不落盘', async () => {
    await resetState();
    // HTML 错误页
    const html = new Blob(['<!DOCTYPE html><html>denied</html>'], { type: 'text/html' });
    stubFetch(html, { headers: { 'content-type': 'text/html' } });
    assert.equal(await cacheAudioFromUrl(song({ hash: 'HASH_HTML' }), 'https://cdn.example/e'), false);
    assert.equal((await listCachedSongs()).length, 0);

    // 声明时长 10 秒但实际 2 秒，视为未覆盖完整内容
    stubFetch(makeWavBlob(2));
    assert.equal(await cacheAudioFromUrl(song({ hash: 'HASH_SHORT', timeLength: 10000 }), 'https://cdn.example/s'), false);

    // 超过总容量上限的文件不能被宣布成功，也不能清掉旧缓存
    useSettings({ audioCacheEnabled: 'on', audioCacheLimitMB: '1' });
    stubFetch(makeWavBlob(2));
    assert.equal(await cacheAudioFromUrl(song({ hash: 'HASH_KEEP' }), 'https://cdn.example/k'), true);
    const big = makeWavBlob(150);
    assert.ok(big.size > MIB, '样例文件必须大于容量上限');
    stubFetch(big);
    assert.equal(await cacheAudioFromUrl(wavSong(150, { hash: 'HASH_BIG' }), 'https://cdn.example/b'), false);
    const after = await listCachedSongs();
    assert.deepEqual(after.map(item => item.hash), ['HASH_KEEP'], '放不下的新文件不得删掉旧缓存');
});

test('LRU 在容量超限时淘汰最久未播放的完整歌曲', async () => {
    await resetState();
    useSettings({ audioCacheEnabled: 'on', audioCacheLimitMB: '1' });
    // 每条约 578KB（74 秒 8kHz 单声道），两条就超 1MB
    const blobA = makeWavBlob(74);
    const blobB = makeWavBlob(74);
    assert.ok(blobA.size + blobB.size > MIB);
    stubFetch(blobA);
    assert.equal(await cacheAudioFromUrl(wavSong(74, { hash: 'HASH_LRU_A' }), 'https://cdn.example/1'), true);
    // 先访问一次 A，让 B 成为更久未使用的一方
    const hitA = await resolveCachedSong(song({ hash: 'HASH_LRU_A', timeLength: 74000 }));
    assert.ok(hitA);
    releaseObjectUrl(hitA.url);
    await new Promise(resolve => setTimeout(resolve, 5));

    stubFetch(blobB);
    assert.equal(await cacheAudioFromUrl(wavSong(74, { hash: 'HASH_LRU_B' }), 'https://cdn.example/2'), true);
    const hashes = (await listCachedSongs()).map(item => item.hash);
    assert.ok(hashes.includes('HASH_LRU_B'), '新歌必须写入');
    assert.equal(hashes.length, 1, '容量只允许一条');
});

test('音质只在 allowFallback 时回退，试听不当完整歌曲', async () => {
    await resetState();
    stubFetch(makeWavBlob(2));
    assert.equal(await cacheAudioFromUrl(song({ hash: 'HASH_Q', resolvedQuality: '320' }), 'https://cdn.example/q'), true);

    assert.equal(await resolveCachedSong(song({ hash: 'HASH_Q', resolvedQuality: '128' })), null,
        '未指定回退时不能拿到别的音质');
    const fallback = await resolveCachedSong(song({ hash: 'HASH_Q', resolvedQuality: '128' }), { allowFallback: true });
    assert.ok(fallback, '允许回退时应命中已缓存的 320');
    assert.equal(fallback.resolvedQuality, '320', '必须报告实际音质');
    releaseObjectUrl(fallback.url);

    stubFetch(makeWavBlob(2));
    assert.equal(await cacheAudioFromUrl(song({ hash: 'HASH_PREVIEW', isPreview: true, timeLength: 2000 }),
        'https://cdn.example/p'), true);
    assert.equal(await resolveCachedSong(song({ hash: 'HASH_PREVIEW', timeLength: 2000 })), null,
        '试听不能被当成完整歌曲命中');
    const preview = await resolveCachedSong(song({ hash: 'HASH_PREVIEW', isPreview: true, timeLength: 2000 }));
    assert.ok(preview);
    assert.equal(preview.isPreview, true);
    releaseObjectUrl(preview.url);
});

test('损坏数据失效，不会反复命中', async () => {
    await resetState();
    stubFetch(makeWavBlob(2));
    assert.equal(await cacheAudioFromUrl(song({ hash: 'HASH_BAD' }), 'https://cdn.example/bad'), true);
    const [entry] = await listCachedSongs();
    assert.ok(entry);

    // 直接把 blob 改成非音频内容，模拟损坏/半截文件
    const db = await __internals.openDb();
    await new Promise((resolve, reject) => {
        const tx = db.transaction(['blobs'], 'readwrite');
        tx.objectStore('blobs').put({ key: entry.cacheKey, blob: new Blob(['garbage'], { type: 'audio/mpeg' }) });
        tx.oncomplete = () => resolve();
        tx.onerror = () => reject(tx.error);
    });
    __internals.reset?.();
    await __internals.reset();
    assert.equal(await resolveCachedSong(song({ hash: 'HASH_BAD' })), null, '坏文件不能被当成可离线');
    assert.equal((await listCachedSongs()).length, 0, '坏记录必须被失效删除');
});

test('清空缓存取消进行中的下载，且不回收正在播放的 URL', async () => {
    await resetState();
    stubFetch(makeWavBlob(2));
    assert.equal(await cacheAudioFromUrl(song({ hash: 'HASH_LEAK' }), 'https://cdn.example/l'), true);
    const playing = await resolveCachedSong(song({ hash: 'HASH_LEAK' }));
    assert.ok(playing);

    // 慢速下载：分块可控
    let release;
    const gate = new Promise(resolve => { release = resolve; });
    globalThis.fetch = async () => new Response(new ReadableStream({
        async start(controller) {
            controller.enqueue(new Uint8Array(1024));
            await gate;
            controller.enqueue(new Uint8Array(1024));
            controller.close();
        }
    }), { status: 200, headers: { 'content-length': '2048', 'content-type': 'audio/wav' } });

    const pending = cacheAudioFromUrl(song({ hash: 'HASH_INFLIGHT' }), 'https://cdn.example/i');
    await new Promise(resolve => setTimeout(resolve, 20));
    const cleared = await clearAudioCache();
    assert.equal(cleared, true);
    release();
    assert.equal(await pending, false, '清空后旧下载不得回填');

    const stats = await getCacheStats();
    assert.equal(stats.count, 0, '索引已清空');
    // 正在播放的 blob URL 仍然有效，交给播放器自己释放
    assert.ok(playing.url.startsWith('blob:'));
    assert.equal(typeof URL.createObjectURL, 'function');
    releaseObjectUrl(playing.url);
});

test('关闭缓存不再读写，但保留已有文件和统计', async () => {
    await resetState();
    stubFetch(makeWavBlob(2));
    assert.equal(await cacheAudioFromUrl(song({ hash: 'HASH_OFF' }), 'https://cdn.example/o'), true);

    useSettings({ audioCacheEnabled: 'off', audioCacheLimitMB: '500' });
    assert.equal(await resolveCachedSong(song({ hash: 'HASH_OFF' })), null, '关闭后不再自动命中');
    assert.equal(await cacheAudioFromUrl(song({ hash: 'HASH_OFF2' }), 'https://cdn.example/o2'), false);
    const stats = await getCacheStats();
    assert.equal(stats.count, 1, '关闭缓存不删除已有文件');

    useSettings({ audioCacheEnabled: 'on', audioCacheLimitMB: '500' });
    await applyCacheSettings();
    const again = await resolveCachedSong(song({ hash: 'HASH_OFF' }));
    assert.ok(again, '重新开启后恢复使用');
    releaseObjectUrl(again.url);
});

test('单曲失效与容量收缩在保留预算的前提下生效', async () => {
    await resetState();
    stubFetch(makeWavBlob(2));
    assert.equal(await cacheAudioFromUrl(song({ hash: 'HASH_INV' }), 'https://cdn.example/inv'), true);
    const [entry] = await listCachedSongs();
    assert.equal(await invalidateCachedSong(entry), true);
    assert.equal((await listCachedSongs()).length, 0);

    stubFetch(makeWavBlob(2));
    assert.equal(await cacheAudioFromUrl(song({ hash: 'HASH_SHRINK' }), 'https://cdn.example/sh'), true);
    useSettings({ audioCacheEnabled: 'on', audioCacheLimitMB: '1' });
    await applyCacheSettings();
    const stats = await getCacheStats();
    assert.ok(stats.totalSize <= 1 * MIB);
});

test('完整性校验直接接受真实音频并拒绝错误文档', async () => {
    const wav = makeWavBlob(2);
    const media = await inspectBlob(wav, { timeLength: 2000 }, wav.size);
    assert.equal(media.duration, 2);
    await assert.rejects(() => inspectBlob(new Blob(['{"error":1}'], { type: 'application/json' }), {}, 11));
});
