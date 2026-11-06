import test from 'node:test';
import assert from 'node:assert/strict';

import { createPlaybackIntent } from '../src/components/player/PlaybackIntent.js';

// 一次播放请求会横跨「取地址 → 命中缓存 → prepare → 重试」多个 await。
// 只要中途用户点了别的歌，旧请求的 token 必须立刻失效，否则后到的旧结果会盖掉新歌。

test('新请求让旧 token 失效', () => {
    const intent = createPlaybackIntent();
    const first = intent.begin();
    assert.equal(intent.isCurrent(first), true);

    const second = intent.begin();
    assert.equal(intent.isCurrent(first), false, '旧请求必须在第二个 begin 后失效');
    assert.equal(intent.isCurrent(second), true);
});

test('cancel 作废全部在途 token，且不会让后续 begin 复用旧号', () => {
    const intent = createPlaybackIntent();
    const first = intent.begin();
    intent.cancel();
    assert.equal(intent.isCurrent(first), false);

    const next = intent.begin();
    assert.notEqual(next, first);
    assert.equal(intent.isCurrent(next), true);
    assert.equal(intent.isCurrent(first), false);
});

test('current() 返回的 token 只对当前 generation 有效', () => {
    const intent = createPlaybackIntent();
    intent.begin();
    const snapshot = intent.current();
    assert.equal(intent.isCurrent(snapshot), true);

    intent.cancel();
    assert.equal(intent.isCurrent(snapshot), false, '取消后旧快照不能继续被当成有效请求');
});

test('未 begin 过的 0 号 token 不会误判为有效', () => {
    const intent = createPlaybackIntent();
    assert.equal(intent.current(), 0);
    assert.equal(intent.isCurrent(0), true, '0 与初始 generation 相等，调用方必须先 begin');
    intent.begin();
    assert.equal(intent.isCurrent(0), false);
});
