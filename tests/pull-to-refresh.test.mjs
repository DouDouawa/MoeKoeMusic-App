import test from 'node:test';
import assert from 'node:assert/strict';

import { pullProgress } from '../src/composables/usePullToRefresh.js';

const THRESHOLD = 72;

test('未达阈值时 ready 为假，位移等比跟手', () => {
    const p = pullProgress(40);
    assert.equal(p.ready, false);
    assert.equal(p.offset, 40);
    assert.equal(p.opacity, 40 / THRESHOLD);
});

test('刚好到阈值即可刷新', () => {
    const p = pullProgress(THRESHOLD);
    assert.equal(p.ready, true);
    assert.equal(p.offset, THRESHOLD);
    assert.equal(p.opacity, 1);
});

test('超过阈值后位移增长放缓，但 ready 保持为真', () => {
    const at = pullProgress(THRESHOLD);
    const beyond = pullProgress(THRESHOLD + 40);
    assert.equal(beyond.ready, true);
    // 多拉了 40px，位移只多 20px
    assert.equal(beyond.offset - at.offset, 20);
    assert.equal(beyond.opacity, 1);
});

test('不透明度封顶为 1，不会因为拉太远溢出', () => {
    assert.equal(pullProgress(500).opacity, 1);
});

test('负位移与零位移都视为没下拉', () => {
    for (const d of [-50, 0]) {
        const p = pullProgress(d);
        assert.equal(p.ready, false);
        assert.equal(p.offset, 0);
        assert.equal(p.opacity, 0);
    }
});
