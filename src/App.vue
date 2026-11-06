<template>
    <div id="app">
        <TitleBar v-if="showTitleBar && !isLyricsRoute" />
        <RouterView />
        <Disclaimer v-if="!isLyricsRoute" />
        <StatusBarLyrics v-if="!isLyricsRoute" ref="statusBarLyricsRef" />
    </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import Disclaimer from '@/components/Disclaimer.vue';
import TitleBar from '@/components/TitleBar.vue';
import StatusBarLyrics from '@/components/StatusBarLyrics.vue';
import { MoeAuthStore } from '@/stores/store';
import { isNativePlayback, nativePlayback } from '@/utils/nativePlayback';
import { useNavigationDrawer } from '@/composables/useNavigationDrawer';
import logoImageSrc from '@/assets/images/tray/tray-icon@2x.png?url';

const route = useRoute();
const router = useRouter();
const { drawerOpen, open: openDrawer, close: closeDrawer } = useNavigationDrawer();
const isLyricsRoute = computed(() => route.path === '/lyrics');

// 状态栏歌词逻辑
const statusBarLyricsRef = ref(null);
let cleanupStatusBarIPC = null;

// 动态控制 TitleBar 的显示
const showTitleBar = ref(true);

// 安卓返回手势（左右侧滑）：站内还有上一页就返回，只有在主页才把应用收进后台。
let backButtonListener = null;

onMounted(async () => {
    const settings = JSON.parse(localStorage.getItem('settings')) || {};
    showTitleBar.value = settings.nativeTitleBar !== 'on'; // 如果值为 'on'，则不显示 TitleBar

    const MoeAuth = MoeAuthStore();
    await MoeAuth.initDevice();

    // 初始化状态栏歌词
    cleanupStatusBarIPC = statusBarLyricsRef.value?.initStatusBar(logoImageSrc, settings);

    if (isNativePlayback()) {
        backButtonListener = await nativePlayback.addListener('backButton', () => {
            // 先问最上层的弹层。useDismissableOverlay 用 app-back 派发关闭，
            // 谁消费掉了就 preventDefault —— 弹层开着时返回手势只该关弹层，
            // 不能继续往下走去切路由或隐藏应用。
            const dismissed = !window.dispatchEvent(new CustomEvent('app-back', { cancelable: true }));
            if (dismissed) return;

            // 主页（或冷启动直接落在深链、没有可回退的历史）：先把左侧列表拉出来，
            // 列表已经开着再滑才把应用收进后台。其他页面照旧回上一页。
            const hasPreviousPage = Boolean(window.history.state?.back);
            const atRoot = route.name === 'Index' || !hasPreviousPage;
            if (!atRoot) {
                router.back();
                return;
            }
            if (!drawerOpen.value) {
                openDrawer();
                return;
            }
            closeDrawer();
            nativePlayback.minimizeApp();
        });
    }
});

onUnmounted(() => {
    backButtonListener?.remove();
    backButtonListener = null;
    statusBarLyricsRef.value?.cleanupStatusBar();
    cleanupStatusBarIPC?.();
});
</script>

<style scoped>
.container {
    max-width: 1400px;
    margin: 0 auto;
    padding: 20px;
}
</style>
