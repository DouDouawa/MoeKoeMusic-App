<template>
    <transition name="btt">
        <button v-show="visible" class="back-to-top" type="button" :title="t('fan-hui-ding-bu')"
            :style="positionStyle" @click="scrollToTop">
            <i class="fas fa-arrow-up"></i>
        </button>
    </transition>
</template>

<script setup>
import { nextTick, computed, onActivated, onBeforeUnmount, onDeactivated, onMounted, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import { useMobileViewport } from '../composables/useMobileViewport';

const props = defineProps({
    // 监听的滚动容器选择器
    target: { type: String, default: '.app-main-scroll' },
    // 下滑多少距离后显示按钮
    threshold: { type: Number, default: 360 },
    // 桌面端定位，可按页面覆盖；手机端统一走 CSS 变量，避免各处传的 px 值把按钮压到播放器下面
    bottom: { type: String, default: '' },
    right: { type: String, default: '' }
});

const { t } = useI18n();
const isMobile = useMobileViewport();
const visible = ref(false);
let container = null;

const positionStyle = computed(() => isMobile.value ? {} : {
    ...(props.bottom ? { bottom: props.bottom } : {}),
    ...(props.right ? { right: props.right } : {})
});

const syncVisible = () => {
    visible.value = (container?.scrollTop ?? 0) > props.threshold;
};

const bind = () => {
    const next = document.querySelector(props.target);
    if (!next) return;
    if (next !== container) {
        container?.removeEventListener('scroll', syncVisible);
        container = next;
        container.addEventListener('scroll', syncVisible, { passive: true });
    }
    syncVisible();
};

const unbind = () => {
    container?.removeEventListener('scroll', syncVisible);
    container = null;
};

const scrollToTop = () => {
    container?.scrollTo({ top: 0, behavior: 'smooth' });
};

onMounted(() => nextTick(bind));
onActivated(() => nextTick(bind));
onDeactivated(unbind);
onBeforeUnmount(unbind);
</script>

<style lang="scss" scoped>
.back-to-top {
    position: fixed;
    z-index: 96;
    // 默认位置写在 CSS 里，手机断点才能覆盖；页面传的 bottom/right 只在桌面生效
    bottom: 172px;
    right: 22px;
    width: 44px;
    height: 44px;
    display: flex;
    align-items: center;
    justify-content: center;
    border: 1px solid rgba(var(--primary-color-rgb), 0.28);
    border-radius: 50%;
    background: rgba(255, 255, 255, 0.92);
    color: var(--primary-color);
    font-size: 16px;
    cursor: pointer;
    box-shadow: 0 10px 26px rgba(var(--primary-color-rgb), 0.28);
    backdrop-filter: blur(8px);
    transition: transform 0.22s ease, background-color 0.22s ease, color 0.22s ease, box-shadow 0.22s ease;

    .dark & {
        background: rgba(40, 40, 46, 0.92);
        border-color: rgba(var(--primary-color-rgb), 0.4);
    }

    &:hover {
        transform: translateY(-3px);
        background: var(--primary-color);
        color: #fff;
        box-shadow: 0 14px 30px rgba(var(--primary-color-rgb), 0.4);
    }

    &:active {
        transform: translateY(-1px);
    }
}

.btt-enter-active,
.btt-leave-active {
    transition: opacity 0.24s ease, transform 0.24s ease;
}

.btt-enter-from,
.btt-leave-to {
    opacity: 0;
    transform: translateY(14px) scale(0.85);
}

// 手机端：贴到播放器上沿，别再让页面各自传 100px/172px 把按钮压在播放栏底下
@media (max-width: 768px) {
    .back-to-top {
        bottom: calc(var(--app-player-height, 76px) + var(--app-safe-bottom, 0px) + 16px);
        right: 14px;
        width: 40px;
        height: 40px;
    }
}
</style>
