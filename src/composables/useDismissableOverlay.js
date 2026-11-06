import { nextTick, onBeforeUnmount, unref, watch } from 'vue';

// 所有弹层共用一份后进先出栈，返回一次只处理最上层。
const overlays = [];
const focusable = 'button:not([disabled]), a[href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex="0"]';

function handleDismiss(event) {
    if (event.defaultPrevented || (event.type === 'keydown' && event.key !== 'Escape')) return;
    const top = overlays.at(-1);
    if (!top) return;
    event.preventDefault();
    top.close();
}

function bindListeners() {
    window.addEventListener('app-back', handleDismiss);
    window.addEventListener('keydown', handleDismiss);
}

function unbindListeners() {
    window.removeEventListener('app-back', handleDismiss);
    window.removeEventListener('keydown', handleDismiss);
}

export function useDismissableOverlay(open, close, options = {}) {
    const entry = { close, previousFocus: null };
    const remove = (restore = true) => {
        const index = overlays.indexOf(entry);
        if (index < 0) return;
        const wasTop = index === overlays.length - 1;
        overlays.splice(index, 1);
        if (!overlays.length) unbindListeners();
        if (restore && wasTop && entry.previousFocus?.isConnected) {
            nextTick(() => entry.previousFocus?.focus?.({ preventScroll: true }));
        }
    };
    const stop = watch(() => typeof open === 'function' ? open() : unref(open), (visible) => {
        if (!visible) return remove();
        if (overlays.includes(entry)) return;
        entry.previousFocus = document.activeElement;
        if (!overlays.length) bindListeners();
        overlays.push(entry);
        if (options.element) {
            nextTick(() => unref(options.element)?.querySelector(focusable)?.focus({ preventScroll: true }));
        }
    }, { immediate: true, flush: 'sync' });
    onBeforeUnmount(() => { stop(); remove(); });
}
