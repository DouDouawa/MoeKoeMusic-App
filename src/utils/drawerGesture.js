/**
 * 侧边抽屉「左滑关闭」的判定。
 *
 * 单独放在这里而不是塞进组件，是为了能脱离 DOM 直接验证：
 * 浏览器里构造不出移动端的 mobile-drawer 状态，真机又不方便每次都试。
 */
export const DRAWER_SWIPE_PX = 56;
export const DRAWER_SWIPE_MS = 700;
export const DRAWER_SWIPE_AXIS_RATIO = 1.6;

/**
 * @param {{ dx: number, dy: number, durationMs: number }} gesture 手指的位移与耗时
 * @returns {boolean} 是否构成一次「左滑关闭抽屉」
 */
export function isDrawerCloseSwipe({ dx, dy, durationMs }) {
    if (durationMs > DRAWER_SWIPE_MS) return false;
    // 只认往左滑：dx 为负且足够远
    if (dx > -DRAWER_SWIPE_PX) return false;
    // 横向位移要明显压过纵向，避免斜着滚列表时误关
    return Math.abs(dx) >= Math.abs(dy) * DRAWER_SWIPE_AXIS_RATIO;
}
