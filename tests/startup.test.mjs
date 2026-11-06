import test from 'node:test';
import assert from 'node:assert/strict';
import { STARTUP_PAGES, getLyricsRedirect, getStartupRedirect, normalizeStartupPage,
    readStoredSettings, startupPageOptions } from '../src/config/startupPage.js';

const store = new Map();
globalThis.localStorage = {
    getItem: (key) => (store.has(key) ? store.get(key) : null),
    setItem: (key, value) => store.set(key, String(value)),
    removeItem: (key) => store.delete(key),
    clear: () => store.clear()
};

const COLD = { matched: [] };
const WARM = { matched: [{ path: '/' }] };
const at = (path = '/', query = {}, hash = '') => ({ path, query, hash });
const settings = (value) => {
    localStorage.setItem('settings', JSON.stringify({ startupPage: value }));
    return readStoredSettings();
};

test('启动页白名单固定为七个入口', () => {
    assert.deepEqual(STARTUP_PAGES.map(page => page.value),
        ['Index', 'Discover', 'Library', 'LocalMusic', 'CachedMusic', 'Ranking', 'Settings']);
    // 已缓存映射到现有本地音乐路由，不新增伪路由
    const cached = STARTUP_PAGES.find(page => page.value === 'CachedMusic');
    assert.deepEqual(cached.route, { name: 'LocalMusic', query: { tab: 'cached' } });
});

test('未知值与损坏配置回退首页', () => {
    assert.equal(normalizeStartupPage('Nope'), 'Index');
    assert.equal(normalizeStartupPage(undefined), 'Index');
    assert.equal(normalizeStartupPage('Ranking'), 'Ranking');

    localStorage.setItem('settings', '{不是 JSON');
    assert.deepEqual(readStoredSettings(), {});
    assert.equal(getStartupRedirect(at('/'), COLD, readStoredSettings()), null);

    localStorage.setItem('settings', '[1,2,3]');
    assert.deepEqual(readStoredSettings(), {});
    localStorage.removeItem('settings');
    assert.deepEqual(readStoredSettings(), {});
});

test('只对无业务参数的根入口做冷启动重定向', () => {
    const current = settings('Library');
    const redirect = getStartupRedirect(at('/'), COLD, current);
    assert.deepEqual(redirect, { name: 'Library', replace: true });

    // 普通页面切换（已有 matched）不重复重定向
    assert.equal(getStartupRedirect(at('/'), WARM, current), null);
    // 非根路径不重定向
    assert.equal(getStartupRedirect(at('/settings'), COLD, current), null);
    // 带业务参数或 hash 的显式深链优先
    assert.equal(getStartupRedirect(at('/', { listid: '123' }), COLD, current), null);
    assert.equal(getStartupRedirect(at('/', {}, '#lyrics'), COLD, current), null);
});

test('Index 不需要重定向，已缓存带 tab 参数', () => {
    assert.equal(getStartupRedirect(at('/'), COLD, settings('Index')), null);
    assert.deepEqual(getStartupRedirect(at('/'), COLD, settings('CachedMusic')),
        { name: 'LocalMusic', query: { tab: 'cached' }, replace: true });
});

test('每个白名单入口都能生成有效重定向', () => {
    for (const page of STARTUP_PAGES) {
        const redirect = getStartupRedirect(at('/'), COLD, settings(page.value));
        if (page.value === 'Index') {
            assert.equal(redirect, null, 'Index 是首页本身');
            continue;
        }
        assert.ok(redirect, `${page.value} 必须能重定向`);
        assert.equal(redirect.name, page.route.name);
        assert.equal(redirect.replace, true);
    }
});

test('非 Electron 的 /lyrics 转主布局并打开全屏歌词', () => {
    const redirect = getLyricsRedirect(at('/lyrics', { hash: 'HASH' }), false);
    assert.deepEqual(redirect, { name: 'Index', query: { hash: 'HASH', openLyrics: '1' }, replace: true });
    // Electron 桌面悬浮歌词保持原样
    assert.equal(getLyricsRedirect(at('/lyrics'), true), null);
    // 其他路由不受影响
    assert.equal(getLyricsRedirect(at('/LocalMusic'), false), null);
});

test('设置选项与白名单长度一致，且都有可显示文案', () => {
    const options = startupPageOptions((key) => `t:${key}`);
    assert.equal(options.length, STARTUP_PAGES.length);
    options.forEach((option, index) => {
        assert.equal(option.value, STARTUP_PAGES[index].value);
        assert.ok(option.displayText.startsWith('t:'));
    });
});
