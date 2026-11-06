<template>
    <Header v-if="isMobile || navigationMode === 'top'" />
    <div v-if="isMobile && drawerOpen" class="mobile-navigation-backdrop" @click="closeDrawer" />
    <SidebarNavigation v-if="(!isMobile && navigationMode === 'side') || (isMobile && drawerOpen)"
        :mobile-drawer="isMobile" @close="closeDrawer" />
    <main
        ref="mainScrollRef"
        class="app-main-scroll"
        :class="{ 'side-navigation-main-content': !isMobile && navigationMode === 'side', collapsed: sidebarCollapsed }"
        @scroll="handleMainScroll"
    >
        <div v-if="isMobile && (pullOffset > 0 || isRefreshing)"
            class="pull-refresh-indicator"
            :class="{ ready: isArmed, refreshing: isRefreshing }"
            :style="{ transform: `translate(-50%, ${pullOffset}px)`, opacity: isRefreshing ? 1 : pullOpacity }">
            <i class="fas" :class="isRefreshing ? 'fa-spinner fa-spin' : (isArmed ? 'fa-arrow-down' : 'fa-arrow-down')"></i>
        </div>
        <div v-if="!isOnline" class="network-status">
            网络连接已断开
        </div>
        <div class="main-content-shell">
            <router-view v-slot="{ Component, route: currentRoute }">
                <div
                    class="page-route-view"
                    :class="{ 'page-route-enter-active': isPageRouteEntering }"
                >
                    <KeepAlive :max="pageCacheMax">
                        <component
                            v-if="shouldCacheRoute(currentRoute)"
                            :is="Component"
                            :key="`${getRouteCacheKey(currentRoute)}#${refreshKey}`"
                            :playerControl="playerControl"
                        />
                    </KeepAlive>
                    <component
                        v-if="!shouldCacheRoute(currentRoute)"
                        :is="Component"
                        :key="`${currentRoute.fullPath}#${refreshKey}`"
                        :playerControl="playerControl"
                    />
                </div>
            </router-view>
        </div>
    </main>
    <PlayerControl ref="playerControl" />
    <OnboardingGuide />
</template>

<script setup>
import { ref, computed, watch, nextTick, onMounted, onUnmounted } from 'vue';
import { useRoute } from 'vue-router';
import Header from "@/components/Header.vue";
import SidebarNavigation from "@/components/SidebarNavigation.vue";
import PlayerControl from "@/components/PlayerControl.vue";
import OnboardingGuide from "@/components/OnboardingGuide.vue";
import { setTheme, applyColorTheme, applyCustomFont } from '../utils/utils';
import { useMobileViewport } from '@/composables/useMobileViewport';
import { useTabSwipe } from '@/composables/useTabSwipe';
import { usePullToRefresh, pullProgress } from '@/composables/usePullToRefresh';
import { useNavigationDrawer } from '@/composables/useNavigationDrawer';
import { Capacitor } from '@capacitor/core';
if (Capacitor.isNativePlatform() && Capacitor.getPlatform() === 'android') {
    document.documentElement.classList.add('native-android');
}

const isMobile = useMobileViewport();
// 抽屉状态放在共享模块里：返回手势在 App.vue 也要读它。
const { drawerOpen, close: closeDrawer } = useNavigationDrawer();

const route = useRoute();
const playerControl = ref(null);
const mainScrollRef = ref(null);
const isOnline = ref(navigator.onLine);
const navigationMode = ref('top');
const sidebarCollapsed = ref(localStorage.getItem('sidebarCollapsed') === '1');
const playerBarLayout = ref('full');
const isPageRouteEntering = ref(false);
// const routeViewKey = computed(() => route.fullPath);
const routeViewKey = computed(() => route.name);
const cacheableRouteNames = new Set([
    'Index',
    'Share',
    'Discover',
    'Library',
    'Search',
    'RecommendedSearch',
    'Ranking'
]);
const pageCacheMax = cacheableRouteNames.size;
const routeScrollPositions = new Map();
let pageRouteAnimationFrame = null;

// 一级导航页之间横向滑动切换；起手落在能横向滚动/拖拽的控件上时不生效。
useTabSwipe(mainScrollRef);

// 顶部下拉刷新：换一次 key 让当前页面重新挂载，各页面自己的 onMounted 会重新取数。
const refreshKey = ref(0);
const { pullOffset, isRefreshing, isArmed } = usePullToRefresh(mainScrollRef, () => {
    refreshKey.value += 1;
});
const pullOpacity = computed(() => pullProgress(pullOffset.value).opacity);

// 监听网络状态变化
const handleNetworkChange = (online) => {
    isOnline.value = online;

    const title = online ? '网络已连接' : '网络已断开';
    const body = online ? '您已恢复网络连接' : '请检查网络设置';

    if (typeof Notification === 'undefined' || Notification.permission !== 'granted') return;

    new Notification(title, {
        body,
        icon: './assets/images/logo.png'
    });
};

const handleOnline = () => handleNetworkChange(true);
const handleOffline = () => handleNetworkChange(false);
const readSettings = () => {
    try { return JSON.parse(localStorage.getItem('settings')) || {}; } catch { return {}; }
};
const loadNavigationMode = (settings = readSettings()) => {
    navigationMode.value = settings.navigationMode === 'side' ? 'side' : 'top';
    playerBarLayout.value = settings.playerBarLayout === 'content' ? 'content' : 'full';
    applyPlayerBarLayout();
};
const handleSettingsChange = (event) => {
    loadNavigationMode(event.detail?.settings);
};
const handleSidebarCollapseChange = (event) => {
    sidebarCollapsed.value = event.detail?.collapsed === true;
    applyPlayerBarLayout();
};
const applyPlayerBarLayout = () => {
    const enabled = !isMobile.value && navigationMode.value === 'side' && playerBarLayout.value === 'content';
    document.body.classList.toggle('player-bar-content-layout', enabled);
    document.documentElement.style.setProperty('--side-navigation-width', sidebarCollapsed.value ? '64px' : '226px');
};

const shouldCacheRoute = (currentRoute) => cacheableRouteNames.has(String(currentRoute?.name || ''));

const getRouteCacheKey = (currentRoute) => String(currentRoute?.name || currentRoute?.path || '');

const getScrollRouteKey = (currentRoute) => String(currentRoute?.fullPath || currentRoute?.path || '');

const saveRouteScrollPosition = (currentRoute, scrollTop) => {
    routeScrollPositions.set(getScrollRouteKey(currentRoute), scrollTop);
};

const restoreRouteScrollPosition = (currentRoute) => {
    nextTick(() => {
        if (!mainScrollRef.value) return;
        const scrollTop = routeScrollPositions.get(getScrollRouteKey(currentRoute)) ?? 0;
        mainScrollRef.value.scrollTop = scrollTop;
    });
};

const handleMainScroll = (event) => {
    saveRouteScrollPosition(route, event.target.scrollTop);
};

const stopPageRouteAnimation = () => {
    if (pageRouteAnimationFrame !== null) {
        window.cancelAnimationFrame(pageRouteAnimationFrame);
        pageRouteAnimationFrame = null;
    }
};

const replayPageRouteAnimation = () => {
    stopPageRouteAnimation();
    isPageRouteEntering.value = false;
    pageRouteAnimationFrame = window.requestAnimationFrame(() => {
        isPageRouteEntering.value = true;
        pageRouteAnimationFrame = null;
    });
};

watch(routeViewKey, (to, from) => {
    if (from && mainScrollRef.value) {
        saveRouteScrollPosition({ fullPath: from }, mainScrollRef.value.scrollTop);
    }
    replayPageRouteAnimation();
    restoreRouteScrollPosition(route);
});

watch(isMobile, () => { closeDrawer(); applyPlayerBarLayout(); });
watch(() => route.fullPath, () => { closeDrawer(); });

onMounted(() => {
    const savedConfig = readSettings();
    if (savedConfig) {
        applyColorTheme(savedConfig['themeColor']);
        applyCustomFont(savedConfig.font || '');
    }
    loadNavigationMode(savedConfig || {});
    const savedTheme = localStorage.getItem('theme');
    if (savedTheme) {
        setTheme(savedTheme);
    }

    // 添加网络状态监听
    window.addEventListener('online', handleOnline);
    window.addEventListener('offline', handleOffline);
    window.addEventListener('settings-change', handleSettingsChange);
    window.addEventListener('sidebar-collapse-change', handleSidebarCollapseChange);

    // WebView/离线环境可能没有 Notification；通知授权不是进入音乐库的前提。
    if (typeof Notification !== 'undefined' && Notification.permission === 'default') {
        Promise.resolve(Notification.requestPermission()).catch(() => {});
    }

    replayPageRouteAnimation();
});

// 组件卸载时移除事件监听
onUnmounted(() => {
    stopPageRouteAnimation();
    window.removeEventListener('online', handleOnline);
    window.removeEventListener('offline', handleOffline);
    window.removeEventListener('settings-change', handleSettingsChange);
    window.removeEventListener('sidebar-collapse-change', handleSidebarCollapseChange);
    document.body.classList.remove('player-bar-content-layout');
    document.documentElement.style.removeProperty('--side-navigation-width');
});
</script>

<style>
:root {
    /* 粉红色主色调 - 用于主要按钮、强调元素 */
    --primary-color: #FF69B4;
    /* 粉红色主色调的RGB值 - 用于需要RGB格式的样式 */
    --primary-color-rgb: '255, 105, 180';
    /* 浅粉红色辅助色 - 用于次要按钮、提示信息 */
    --secondary-color: #FFB6C1;
    /* 文本颜色 - 用于正文内容 */
    --text-color: #333;
    /* 浅粉色背景 - 用于页面主背景 */
    --background-color: #FFF0F5;
    /* 次要背景色 - 用于卡片、侧边栏背景 */
    --background-color-secondary: #FFE6F0;
    /* 高亮色 - 用于交互元素如按钮、链接 */
    --color-primary: #ea33e4;
    /* 高亮色的浅色版本 - 用于选中状态背景 */
    --color-primary-light: rgba(255, 105, 180, 0.1);
    /* 边框颜色 - 用于分隔线、边框 */
    --border-color: #FFD9E6;
    /* 悬停颜色 - 用于元素悬停状态 */
    --hover-color: #FFE9F2;
    /* 半透明背景 - 用于覆盖层、提示框 */
    --color-secondary-bg-for-transparent: rgba(209, 209, 214, 0.28);
    /* 阴影颜色 - 用于卡片、弹窗阴影 */
    --color-box-shadow: rgba(255, 105, 180, 0.2);
}

* {
    user-select: none;
}

body,
html {
    margin: 0;
    padding: 0;
    font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Oxygen, Ubuntu, Cantarell, 'Open Sans', 'Helvetica Neue', sans-serif;
    background-color: #FFF;
    color: var(--text-color);
    height: 100%;
}

body {
    -ms-overflow-style: none;
    scrollbar-width: none;
}

::-webkit-scrollbar {
    width: 0;
    height: 0;
}

main.app-main-scroll {
    height: 100vh;
    height: 100dvh;
    width: 100%;
    margin: 0;
    padding-top: 80px;
    padding-bottom: 150px;
    box-sizing: border-box;
    overflow-y: auto;
    /* 保留桌面端的横向裁剪作为兜底；手机端各页已改为真实可达的窄屏布局 */
    overflow-x: hidden;
    overscroll-behavior: contain;
}

/* 移动端顶栏压缩为单行，内容区上边距同步收窄，避免顶部留白 */
@media (max-width: 768px) {
    main {
        padding-top: 58px;
    }
}

.main-content-shell {
    width: min(1200px, 100%);
    margin: 0 auto;
    position: relative;
    overflow: visible;
}

.page-route-view {
    width: 100%;
}

.page-route-enter-active {
    animation: page-route-enter 0.45s ease-out;
    will-change: opacity;
}

@keyframes page-route-enter {
    from {
        opacity: 0;
        transform: translate3d(0, 6px, 0);
    }

    to {
        opacity: 1;
        transform: translate3d(0, 0, 0);
    }
}

main.side-navigation-main-content {
    --side-main-width: 226px;
    width: calc(100% - var(--side-main-width));
    margin-left: var(--side-main-width);
    margin-right: 0;
    padding-top: 52px;
}

main.side-navigation-main-content.collapsed {
    --side-main-width: 64px;
}

a {
    text-decoration: none;
    color: inherit;
    display: block;
}

.network-status {
    position: fixed;
    top: 80px;
    left: 0;
    right: 0;
    background-color: #ff4757;
    color: white;
    text-align: center;
    padding: 8px;
    z-index: 1000;
}

/* 下拉刷新指示器：跟手位移，松手后回到原位 */
.pull-refresh-indicator {
    position: absolute;
    top: calc(var(--app-header-height) + var(--app-safe-top) - 44px);
    left: 50%;
    width: 32px;
    height: 32px;
    display: flex;
    align-items: center;
    justify-content: center;
    border-radius: 50%;
    background: var(--app-surface, #fff);
    color: var(--primary-color);
    box-shadow: 0 2px 8px rgba(0, 0, 0, 0.15);
    font-size: 13px;
    pointer-events: none;
    z-index: 4;
    transition: opacity 0.15s ease;
}

.pull-refresh-indicator.ready {
    color: var(--color-primary);
}

.pull-refresh-indicator.refreshing {
    top: calc(var(--app-header-height) + var(--app-safe-top) - 4px);
    transition: top 0.2s ease, opacity 0.15s ease;
}

body.player-bar-content-layout .side-navigation {
    bottom: 0;
}

body:not(.player-bar-content-layout) .side-navigation {
    z-index: 98;
}

body.player-bar-content-layout .player-container {
    left: var(--side-navigation-width);
    width: calc(100% - var(--side-navigation-width));
}
/* Insets are CSS-only on browsers; Android already insets the WebView itself. */
:root {
    --app-header-height: 80px;
    --app-player-height: 80px;
    --app-safe-top: env(safe-area-inset-top, 0px);
    --app-safe-bottom: env(safe-area-inset-bottom, 0px);
    --app-surface: #fff;
    /* 粘性头的 top 基线。桌面端滚动容器没有顶部内边距，需要自己让开固定顶栏；
       移动端 .app-main-scroll 已经用 padding-top 让出了同样的距离，再让一次会重叠。 */
    --app-sticky-base: var(--app-header-height);
}
.dark { --app-surface: #202020; }
.mobile-more-button { display: none; }
.mobile-navigation-backdrop { position: fixed; inset: 0; background: #0007; z-index: 1199; }
@media (max-width: 768px) {
    :root { --app-header-height: 56px; --app-player-height: 76px; --app-sticky-base: 0px; }
    main.app-main-scroll {
        width: 100%; margin: 0;
        padding-top: calc(var(--app-header-height) + var(--app-safe-top));
        padding-bottom: calc(var(--app-player-height) + var(--app-safe-bottom) + 20px);
        scroll-padding-bottom: calc(var(--app-player-height) + var(--app-safe-bottom) + 20px);
    }
    .main-content-shell, .page-route-view { min-width: 0; }
    .network-status { position: sticky; top: 0; box-sizing: border-box; z-index: 5; }
    body.player-bar-content-layout .player-container { left: 0; width: 100%; }
    .mobile-more-button {
        display: inline-flex; align-items: center; justify-content: center;
        flex: 0 0 44px; width: 44px; height: 44px; padding: 0; border: 0;
        border-radius: 10px; background: transparent; color: inherit; cursor: pointer;
    }
    .mobile-more-button:active { background: var(--color-secondary-bg-for-transparent); }
    input, textarea, select { font-size: 16px; }
    button, a, [role='button'] { -webkit-tap-highlight-color: transparent; touch-action: manipulation; }
}
</style>
