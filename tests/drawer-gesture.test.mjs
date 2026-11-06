import test from 'node:test';
import assert from 'node:assert/strict';

import {
    isDrawerCloseSwipe,
    DRAWER_SWIPE_PX,
    DRAWER_SWIPE_MS
} from '../src/utils/drawerGesture.js';

test('向左滑足够远即关闭', () => {
    assert.equal(isDrawerCloseSwipe({ dx: -80, dy: 0, durationMs: 300 }), true);
    assert.equal(isDrawerCloseSwipe({ dx: -DRAWER_SWIPE_PX, dy: 0, durationMs: 300 }), true);
});

test('向右滑不关闭，哪怕位移很大', () => {
    assert.equal(isDrawerCloseSwipe({ dx: 120, dy: 0, durationMs: 300 }), false);
});

test('位移不足阈值不关闭', () => {
    assert.equal(isDrawerCloseSwipe({ dx: -(DRAWER_SWIPE_PX - 1), dy: 0, durationMs: 300 }), false);
    assert.equal(isDrawerCloseSwipe({ dx: -20, dy: 0, durationMs: 300 }), false);
});

test('纵向为主的斜滑不关闭，避免滚动列表时误触', () => {
    // 横向 80 但纵向 100：明显是竖着滚
    assert.equal(isDrawerCloseSwipe({ dx: -80, dy: 100, durationMs: 300 }), false);
    // 横向 80、纵向 40：横向占优，应该关闭
    assert.equal(isDrawerCloseSwipe({ dx: -80, dy: 40, durationMs: 300 }), true);
});

test('拖得太慢不算滑动', () => {
    assert.equal(isDrawerCloseSwipe({ dx: -120, dy: 0, durationMs: DRAWER_SWIPE_MS + 1 }), false);
    assert.equal(isDrawerCloseSwipe({ dx: -120, dy: 0, durationMs: DRAWER_SWIPE_MS }), true);
});

test('纵向反方向也不影响判定，只看向对侧的纵向幅度', () => {
    assert.equal(isDrawerCloseSwipe({ dx: -80, dy: -40, durationMs: 300 }), true);
    assert.equal(isDrawerCloseSwipe({ dx: -80, dy: -100, durationMs: 300 }), false);
});
