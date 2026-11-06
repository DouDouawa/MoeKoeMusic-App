import { onMounted, onUnmounted } from 'vue';
import { useRouter } from 'vue-router';

/**
 * 一级导航页之间用横向滑动切换。
 *
 * 两件事必须避开，否则会和已有交互打架：
 *  1. 屏幕左右边缘属于系统返回手势区，从那里起手不能算切页；
 *  2. 页面里本来就能横向滚动的容器（歌单横排、滚动条）和进度条这类拖拽控件，
 *     它们自己消费横向位移，起手落在上面就不能切页。
 *
 * 右滑去下一个导航页，左滑回上一个，和导航栏里从左到右的顺序一致。
 */
const NAV_ROUTES = ['/', '/discover', '/library'];

// 边缘留白：系统返回手势从屏幕边缘起手，这个区间交给系统。
const EDGE_GUARD_PX = 32;
// 触发阈值：太灵敏会在纵向滚动时误判，太小又滑不动。
const MIN_SWIPE_PX = 64;
// 横向位移必须明显压过纵向，否则是斜着滚列表。
const AXIS_RATIO = 1.6;
// 慢拖不算滑动，避免和长按、选择文本混淆。
const MAX_SWIPE_MS = 700;

// 自己处理横向位移的元素，起手落在它们身上一律不切页。
const IGNORE_SELECTOR = [
    'input',
    'textarea',
    'select',
    '[role="slider"]',
    '[data-tab-swipe-ignore]',
    '.progress-bar',
    '.volume-slider',
    '.lyrics-container'
].join(',');

const isHorizontallyScrollable = (element) => {
    if (!element || element.nodeType !== 1) return false;
    if (element.scrollWidth <= element.clientWidth + 1) return false;
    const { overflowX } = window.getComputedStyle(element);
    return overflowX === 'auto' || overflowX === 'scroll';
};

/**
 * 全屏浮层（新手引导、弹窗遮罩）打开时手势归它，切页要停下来，
 * 否则用户在引导里滑动会莫名其妙换页。
 */
const hasFullScreenOverlay = (target) => {
    let node = target;
    while (node && node.nodeType === 1) {
        const { position } = window.getComputedStyle(node);
        if (position === 'fixed') {
            const rect = node.getBoundingClientRect();
            if (rect.width >= window.innerWidth * 0.9 && rect.height >= window.innerHeight * 0.9) return true;
        }
        node = node.parentElement;
    }
    return false;
};

/** 从触摸点往上找，看这一块区域是不是已经有人在管横向滑动了。 */
const hasOwnHorizontalHandler = (target) => {
    let node = target;
    while (node && node.nodeType === 1) {
        if (node.matches?.(IGNORE_SELECTOR)) return true;
        if (isHorizontallyScrollable(node)) return true;
        node = node.parentElement;
    }
    return false;
};

export function useTabSwipe(elementRef) {
    const router = useRouter();

    let startX = 0;
    let startY = 0;
    let lastX = 0;
    let lastY = 0;
    let startAt = 0;
    let tracking = false;
    let cleanup = null;

    const onTouchStart = (event) => {
        tracking = false;
        if (event.touches.length !== 1) return;
        // 只有一个导航页或者不在导航页上，直接不参与。
        if (!NAV_ROUTES.includes(router.currentRoute.value.path)) return;

        const touch = event.touches[0];
        if (touch.clientX < EDGE_GUARD_PX || touch.clientX > window.innerWidth - EDGE_GUARD_PX) return;
        if (hasOwnHorizontalHandler(event.target)) return;
        if (hasFullScreenOverlay(event.target)) return;

        startX = lastX = touch.clientX;
        startY = lastY = touch.clientY;
        startAt = Date.now();
        tracking = true;
    };

    // 位移在 touchmove 里持续记下，而不是在 touchend 里读 changedTouches：
    // 部分 WebView 下 touchend 的 changedTouches 是空的，读不到落点。
    const onTouchMove = (event) => {
        if (!tracking) return;
        const touch = event.touches[0];
        if (!touch) return;
        lastX = touch.clientX;
        lastY = touch.clientY;
    };

    const onTouchEnd = () => {
        if (!tracking) return;
        tracking = false;

        const dx = lastX - startX;
        const dy = lastY - startY;
        const elapsed = Date.now() - startAt;

        if (elapsed > MAX_SWIPE_MS) return;
        if (Math.abs(dx) < MIN_SWIPE_PX) return;
        if (Math.abs(dx) < Math.abs(dy) * AXIS_RATIO) return;

        const currentIndex = NAV_ROUTES.indexOf(router.currentRoute.value.path);
        if (currentIndex === -1) return;

        // 手指往左滑 -> 内容跟着往左走，把右边那一页带进来（下一个）；
        // 手指往右滑 -> 回到左边那一页（上一个）。和横向轮播的手感一致。
        const targetIndex = dx < 0 ? currentIndex + 1 : currentIndex - 1;
        if (targetIndex < 0 || targetIndex >= NAV_ROUTES.length) return;

        router.push(NAV_ROUTES[targetIndex]);
    };

    const onTouchCancel = () => {
        tracking = false;
    };

    onMounted(() => {
        const element = elementRef.value;
        if (!element) return;
        element.addEventListener('touchstart', onTouchStart, { passive: true });
        element.addEventListener('touchmove', onTouchMove, { passive: true });
        element.addEventListener('touchend', onTouchEnd, { passive: true });
        element.addEventListener('touchcancel', onTouchCancel, { passive: true });
        cleanup = () => {
            element.removeEventListener('touchstart', onTouchStart);
            element.removeEventListener('touchmove', onTouchMove);
            element.removeEventListener('touchend', onTouchEnd);
            element.removeEventListener('touchcancel', onTouchCancel);
        };
    });

    onUnmounted(() => {
        cleanup?.();
        cleanup = null;
    });
}
