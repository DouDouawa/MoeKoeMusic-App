import { ref, onMounted, onUnmounted } from 'vue';

/**
 * 顶部下拉触发刷新（移动端）。
 *
 * 只从「滚动区已经在最顶上」开始算，否则正常往上滚动列表时会被误触。
 * 位移在 touchmove 里持续记录 —— 部分 WebView 的 touchend 拿不到 changedTouches，
 * 判定全部依赖这两个值，读不到就彻底静默失效。
 */
const PULL_THRESHOLD_PX = 72;
const MAX_PULL_PX = 120;
// 横向位移压过纵向就不算下拉，交给横向手势（切页 / 抽屉）
const AXIS_RATIO = 1.2;
// 刷新指示器最短可见时长，避免一闪而过看不出发生了什么
const MIN_SPIN_MS = 600;

/** 下拉距离换算成指示器的位移与不透明度，超出阈值后增长放缓。 */
export function pullProgress(distance) {
    const clamped = Math.max(0, Math.min(distance, MAX_PULL_PX));
    // 超过阈值后只再走剩余的一半，手感上「拉到底了」
    const eased = clamped <= PULL_THRESHOLD_PX
        ? clamped
        : PULL_THRESHOLD_PX + (clamped - PULL_THRESHOLD_PX) * 0.5;
    return {
        offset: eased,
        opacity: Math.min(1, clamped / PULL_THRESHOLD_PX),
        ready: clamped >= PULL_THRESHOLD_PX
    };
}

export function usePullToRefresh(elementRef, onRefresh) {
    const pullOffset = ref(0);
    const isRefreshing = ref(false);
    const isArmed = ref(false);

    let startX = 0;
    let startY = 0;
    let lastX = 0;
    let lastY = 0;
    let tracking = false;
    let cleanup = null;

    const reset = () => {
        tracking = false;
        pullOffset.value = 0;
        isArmed.value = false;
    };

    const onTouchStart = (event) => {
        reset();
        if (isRefreshing.value || event.touches.length !== 1) return;
        const element = elementRef.value;
        if (!element) return;
        // 只有在顶格时才参与，往下滚到一半拉不动
        if (element.scrollTop > 0) return;

        const touch = event.touches[0];
        startX = lastX = touch.clientX;
        startY = lastY = touch.clientY;
        tracking = true;
    };

    const onTouchMove = (event) => {
        if (!tracking) return;
        const touch = event.touches[0];
        if (!touch) return;
        lastX = touch.clientX;
        lastY = touch.clientY;

        const dx = lastX - startX;
        const dy = lastY - startY;
        // 往上推或者横向为主：都不是下拉
        if (dy <= 0) { pullOffset.value = 0; isArmed.value = false; return; }
        if (Math.abs(dx) > Math.abs(dy) * AXIS_RATIO) { pullOffset.value = 0; isArmed.value = false; return; }

        const { offset, ready } = pullProgress(dy);
        pullOffset.value = offset;
        isArmed.value = ready;
    };

    const onTouchEnd = () => {
        if (!tracking) return;
        const armed = isArmed.value;
        reset();
        if (!armed) return;

        isRefreshing.value = true;
        const startedAt = Date.now();
        Promise.resolve()
            .then(() => onRefresh?.())
            .catch(() => {})
            .finally(() => {
                const elapsed = Date.now() - startedAt;
                const wait = Math.max(0, MIN_SPIN_MS - elapsed);
                setTimeout(() => { isRefreshing.value = false; }, wait);
            });
    };

    onMounted(() => {
        const element = elementRef.value;
        if (!element) return;
        element.addEventListener('touchstart', onTouchStart, { passive: true });
        element.addEventListener('touchmove', onTouchMove, { passive: true });
        element.addEventListener('touchend', onTouchEnd, { passive: true });
        element.addEventListener('touchcancel', reset, { passive: true });
        cleanup = () => {
            element.removeEventListener('touchstart', onTouchStart);
            element.removeEventListener('touchmove', onTouchMove);
            element.removeEventListener('touchend', onTouchEnd);
            element.removeEventListener('touchcancel', reset);
        };
    });

    onUnmounted(() => {
        cleanup?.();
        cleanup = null;
    });

    return { pullOffset, isRefreshing, isArmed };
}
