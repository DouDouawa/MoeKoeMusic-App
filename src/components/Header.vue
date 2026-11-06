<template>
    <header class="app-header">
        <nav class="navigation">
            <div class="navigation desktop-history">
                <button class="nav-arrow" @click="goBack" :disabled="!canGoBack">
                    <i class="fas fa-chevron-left"></i>
                </button>
                <button class="nav-arrow" @click="goForward" :disabled="!canGoForward">
                    <i class="fas fa-chevron-right"></i>
                </button>
                <button class="nav-arrow" @click="refreshPage">
                    <i class="fas fa-redo"></i>
                </button>
            </div>
            <div class="nav-links" @click="clearNavHover">
                <router-link to="/">{{ $t('shou-ye') }}</router-link>
                <router-link to="/discover">{{ $t('fa-xian') }}</router-link>
                <router-link to="/library">{{ $t('yin-le-ku') }}</router-link>
            </div>
            <div class="search-profile">
                <div class="profile" role="button" tabindex="0" aria-label="更多功能" :aria-expanded="showProfile" @click="toggleProfile" @keydown.enter.prevent="toggleProfile" @keydown.space.prevent="toggleProfile">
                    <img :src="MoeAuth.UserInfo?.pic || './assets/images/profile.jpg'" alt="Profile Picture">
                    <div v-if="showProfile" class="profile-menu" @click.stop="showProfile = false">
                        <ul>
                            <li class="mobile-menu-entry"><router-link to="/recognize"><i class="fas fa-microphone"></i>听歌识曲</router-link></li>
                            <li>
                                <router-link to="/settings">
                                    <i class="fas fa-cog"></i> {{ $t('she-zhi') }}
                                </router-link>
                            </li>
                            <li>
                                <a v-if="MoeAuth.isAuthenticated" @click="logout"><i class="fas fa-sign-out-alt"></i>{{ $t('tui-chu') }}</a>
                                <router-link v-else to="/login">
                                    <i class="fas fa-sign-in-alt"></i> {{ $t('deng-lu') }}
                                </router-link>
                            </li>
                            <li>
                                <a style="position: relative;" @click="handleUpdateEntryClick">
                                    <i class="fab fa-github"></i> {{ $t('geng-xin') }}
                                    <i v-if="showNewBadge" class="new-badge">new</i>
                                </a>
                            </li>
                            <li>
                                <a @click="Disclaimer()">
                                    <i class="fas fa-info-circle"></i> {{ $t('guan-yu') }}
                                </a>
                            </li>
                        </ul>
                    </div>
                </div>
            </div>
        </nav>
    </header>
    <div v-if="isDisclaimerVisible" class="modal-overlay" @click="Disclaimer">
        <div class="modal-content" @click.stop>
            <img class="modal-banner" :src="DISCLAIMER_BANNER_SRC" alt="Banner">
            <h2>{{ $t('mian-ze-sheng-ming') }}</h2>
            <p v-for="key in ABOUT_DISCLAIMER_KEYS" :key="key">{{ $t(key) }}</p>
            <button @click="Disclaimer">{{ $t('guan-bi-an-niu') }}</button>
            <div class="version-number">© MoeKoe Music <span v-if="appVersion">V{{ appVersion }} - {{ platform }}</span></div>
        </div>
    </div>
    <AppUpdateDialog ref="updateDialogRef" :app-version="appVersion" :platform="platform" @badge-change="showNewBadge = $event" />
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue';
import { useRouter, useRoute } from 'vue-router';
import { MoeAuthStore } from '../stores/store';
import { useI18n } from 'vue-i18n';
import AppUpdateDialog from './AppUpdateDialog.vue';
import { ABOUT_DISCLAIMER_KEYS, DISCLAIMER_BANNER_SRC } from '../config/disclaimer';
import { useDismissableOverlay } from '@/composables/useDismissableOverlay';

const MoeAuth = MoeAuthStore();
const isDisclaimerVisible = ref(false);
const router = useRouter();
const route = useRoute();
const canGoBack = ref(false);
const canGoForward = ref(false);
const forwardStack = ref([]);
const { t } = useI18n();
const showNewBadge = ref(false);
const appVersion = ref('');
const platform = ref('');
const showProfile = ref(false);
// 二级菜单和「关于」弹层都要进弹层栈：不然返回手势会穿透过去，
// 在首页直接走到「隐藏应用」，用户看到的却是界面卡住出不去。
useDismissableOverlay(showProfile, () => { showProfile.value = false; });
useDismissableOverlay(isDisclaimerVisible, () => { isDisclaimerVisible.value = false; });
const updateDialogRef = ref(null);
const removeAfterEach = router.afterEach(() => {
    updateNavigationStatus();
});

onMounted(() => {
    updateNavigationStatus();
    if (window.electron) {
        window.electron.ipcRenderer.on('version', (_event, version) => {
            appVersion.value = version;
            platform.value = window.electron.platform;
            localStorage.setItem('version', version);
        });
    } else {
        appVersion.value = __VERSION__ || '';
        platform.value = 'Web';
    }
    document.addEventListener('click', handleClickOutside);
});

onUnmounted(() => {
    document.removeEventListener('click', handleClickOutside);
    removeAfterEach();
});

const Disclaimer = () => {
    isDisclaimerVisible.value = !isDisclaimerVisible.value;
};

const updateNavigationStatus = () => {
    canGoBack.value = window.history.length > 1;
    canGoForward.value = forwardStack.value.length > 0;
};

const goBack = () => {
    if (canGoBack.value) {
        forwardStack.value.push(route.fullPath);
        router.back();
    }
    updateNavigationStatus();
};

const goForward = () => {
    if (canGoForward.value) {
        const forwardRoute = forwardStack.value.pop();
        router.push(forwardRoute);
    }
    updateNavigationStatus();
};

const refreshPage = () => {
    window.location.reload();
};

/**
 * 触摸屏上 tap 过的链接会一直保持 :hover / :focus，切换路由后粉色 active 底没了，
 * 那条灰底却留在旧按钮上。点击后主动把焦点和 hover 态清掉。
 */
const clearNavHover = (event) => {
    const link = event.target.closest('a');
    if (!link) return;
    link.blur();
    // 指针停在该位置时 :hover 不会自己消失，把它从命中测试里摘掉再放回去。
    const { pointerEvents } = link.style;
    link.style.pointerEvents = 'none';
    requestAnimationFrame(() => {
        link.style.pointerEvents = pointerEvents;
    });
};

const logout = async () => {
    const result = await window.$modal.confirm(t('ni-que-ren-yao-tui-chu-deng-lu-ma'));
    if (result) {
        MoeAuth.clearData();
        router.push({ path: '/' });
    }
};

const toggleProfile = () => {
    showProfile.value = !showProfile.value;
};


const handleClickOutside = (event) => {
    const queueProfile = document.querySelector('.profile-menu');
    if (queueProfile && !queueProfile.contains(event.target) && !event.target.closest('.profile')) {
        showProfile.value = false;
    }
};

const handleUpdateEntryClick = () => {
    showProfile.value = false;
    updateDialogRef.value?.handleEntryClick();
};
</script>

<style lang="scss" scoped>
.app-header {
    --header-surface: #fff;
    --header-shadow: 0 2px 10px rgba(0, 0, 0, 0.1);
    --header-nav-icon: #333;
    --header-nav-disabled: #ccc;
    --header-nav-hover-bg: #f0f0f0;

    &:is(.dark .app-header) {
        --header-surface: rgba(24, 24, 24, 0.96);
        --header-shadow: 0 2px 14px rgba(0, 0, 0, 0.28);
        --header-nav-icon: rgba(255, 255, 255, 0.72);
        --header-nav-disabled: #4a4a4a;
        --header-nav-hover-bg: rgba(255, 255, 255, 0.08);
    }
}

.navigation {
    display: flex;
    gap: 10px;
}

/* 手机专用入口：桌面端必须彻底隐藏，否则会多出一个汉堡按钮 */
.mobile-header-button,
.mobile-menu-entry {
    display: none;
}

.nav-arrow {
    background: none;
    border: none;
    cursor: pointer;
    padding: 10px;
    display: flex;
    align-items: center;
    justify-content: center;

    &:disabled i {
        color: var(--header-nav-disabled);
        cursor: not-allowed;
    }

    i {
        font-size: 24px;
        color: var(--header-nav-icon);
    }

    &:hover {
        background-color: var(--header-nav-hover-bg);
    }
}

button {
    display: flex;
    justify-content: center;
    align-items: center;
    padding: 8px;
    background: transparent;
    margin: 4px;
    border-radius: 25%;
    transition: 0.2s;

    .svg-icon {
        color: var(--color-text);
        height: 16px;
        width: 16px;
    }

    &:first-child {
        margin-left: 0;
    }

    &:hover {
        background: var(--color-secondary-bg-for-transparent);
    }

    &:active {
        transform: scale(0.92);
    }
}

header {
    background-color: var(--header-surface);
    padding: 15px 0;
    box-shadow: var(--header-shadow);
    position: fixed;
    left: 0;
    right: 0;
    width: 100%;
    top: 0px;
    z-index: 9;
}

header::before {
    content: '';
    position: absolute;
    inset: 0 100px 0 0;
    -webkit-app-region: drag;
}

.nav-arrow,
.nav-links a,
.profile,
.profile img {
    -webkit-app-region: no-drag;
}

.navigation {
    position: relative;
    z-index: 1;
    display: flex;
    justify-content: space-between;
    align-items: center;
    max-width: 1200px;
    margin: 0 auto;
    padding: 0 20px;
}

.nav-links {
    display: flex;
    gap: 30px;
    justify-content: center;
    flex-grow: 1;

    a {
        text-decoration: none;
        color: var(--primary-color);
        -webkit-app-region: no-drag;
        font-size: 18px;
        font-weight: 700;
        border-radius: 6px;
        padding: 6px 10px;
        transition: 0.2s;
        -webkit-user-drag: none;
        margin-right: 12px;
        margin-left: 12px;

        /* 悬停底灰只给真正有指针的设备。触摸屏上 tap 过之后 :hover 会一直粘着，
           切换页面后粉色 active 底没了，这条灰底却留在刚才点的那个按钮上。 */
        @media (hover: hover) and (pointer: fine) {
            &:hover {
                background: var(--color-secondary-bg-for-transparent);
            }
        }

        &:active {
            transform: scale(0.92);
            transition: 0.2s;
        }

        &.active {
            color: var(--color-primary);
        }
    }
}

.search-profile {
    display: flex;
    align-items: center;
    gap: 20px;
}



.profile {
    width: 40px;
    height: 40px;
    // 圆角方块：用百分比让 40px(桌面)/44px(手机) 两档尺寸自动保持同样观感，
    // 不用为每个断点各写一个固定像素值
    border-radius: 30%;
    background-color: var(--secondary-color);
    cursor: pointer;
    position: relative;

    img {
        // 原先是 width: 41px 塞在 40px 容器里，圆形时看不出来，
        // 换成圆角后这 1px 错位会让两个圆角对不齐，改成撑满容器
        width: 100%;
        height: 100%;
        object-fit: cover;
        border-radius: inherit;
        display: block;
    }
}

.profile-menu {
    position: absolute;
    top: 50px;
    right: 0;
    background-color: #fff;
    box-shadow: 0 4px 8px rgba(0, 0, 0, 0.1);
    border-radius: 8px;
    padding: 10px;
    width: 150px;
    display: flex;
    flex-direction: column;
    gap: 10px;
    animation: fadeInOut 0.3s ease-in-out;

    ul {
        list-style: none;
        padding: 0;
        margin: 0;
    }

    li a {
        display: flex;
        align-items: center;
        gap: 15px;
        cursor: pointer;
        padding: 7px 5px;
        border-radius: 5px;
        color: #000;
        text-decoration: none;

        &:hover {
            background-color: var(--secondary-color);
        }
    }
}

@keyframes fadeInOut {
    0% {
        opacity: 0;
    }

    100% {
        opacity: 1;
    }
}

.modal-overlay {
    position: fixed;
    top: 0;
    left: 0;
    right: 0;
    bottom: 0;
    background: rgba(0, 0, 0, 0.5);
    display: flex;
    align-items: center;
    justify-content: center;
    z-index: 1000;
}

.modal-content {
    position: relative;
    background: #fff;
    padding: 20px;
    border-radius: 8px;
    max-width: 700px;
    width: 90%;
    box-shadow: 0 4px 8px rgba(0, 0, 0, 0.2);
    text-align: left;
    animation: fadeIn 0.3s ease;

    .modal-banner {
        position: absolute;
        top: 0;
        right: 15px;
        width: 180px;
        max-width: 35%;
        pointer-events: none;
        user-select: none;
        -webkit-user-drag: none;
    }

    h2 {
        margin-top: 20px;
        color: var(--primary-color);
    }

    p {
        margin: 10px 0;
        line-height: 1.6;
    }

    button {
        margin-top: 15px;
        padding: 8px 12px;
        background-color: var(--primary-color);
        color: #fff;
        border: none;
        border-radius: 5px;
        cursor: pointer;
    }
}

@keyframes fadeIn {
    from {
        opacity: 0;
        transform: scale(0.95);
    }

    to {
        opacity: 1;
        transform: scale(1);
    }
}

.new-badge {
    position: absolute;
    top: 1px;
    left: 67px;
    background-color: red;
    color: white;
    padding: 0px 4px;
    border-radius: 5px;
    font-size: 14px;
}

.version-number {
    position: absolute;
    bottom: 10px;
    right: 10px;
    font-size: 12px;
    color: #666;
}
/* ===== 移动端顶栏：压缩为单行紧凑布局 ===== */
@media (max-width: 768px) {
    header {
        /* 上下 6px + 44px 触控区 = 56px，正好等于 --app-header-height，
           滚动容器的 padding-top 依赖这个值，两边必须一致。 */
        padding: 6px 0;
    }

    .navigation {
        gap: 8px;
        padding: 0 12px;
    }

    /* 汉堡：打开导航抽屉；搜索：直达推荐搜索页 */
    .mobile-header-button {
        display: inline-flex;
        align-items: center;
        justify-content: center;
        flex: 0 0 44px;
        width: 44px;
        height: 44px;
        margin: 0;
        padding: 0;
        border: 0;
        border-radius: 10px;
        background: transparent;
        color: var(--header-nav-icon);
        font-size: 17px;
        cursor: pointer;

        &:active {
            background: var(--header-nav-hover-bg);
        }
    }

    /* 手机顶栏只保留核心导航；历史前进/后退交给抽屉与系统手势 */
    .desktop-history {
        display: none;
    }

    .mobile-menu-entry {
        display: block;

        > button {
            width: 100%;
            margin: 0;
            justify-content: flex-start;
            gap: 10px;
            padding: 8px 6px;
            border: 0;
            border-radius: 5px;
            background: transparent;
            color: inherit;
            font-size: 14px;
            text-align: left;
            min-height: 44px;
        }

        > button:disabled {
            opacity: 0.45;
        }
    }

    /* 前进/后退是桌面浏览器式控件，手机上有系统手势替代；
       但「刷新」要保留 —— 改完 RPC 地址需要刷新才生效 */
    .navigation > .navigation {
        flex: 0 0 auto;
        gap: 0;
        padding: 0;

        .nav-arrow:nth-child(1),
        .nav-arrow:nth-child(2) {
            display: none;
        }

        .nav-arrow {
            padding: 4px;

            i {
                font-size: 15px;
            }
        }
    }

    .nav-links {
        flex: 0 0 auto;
        gap: 2px;
        justify-content: flex-start;

        a {
            /* 触控目标 44px：只加内边距会撑高顶栏，用 min-height + 垂直居中来
               保证高度，同时保持药丸底色的形状。 */
            display: inline-flex;
            align-items: center;
            justify-content: center;
            min-height: 44px;
            margin: 0;
            padding: 0 10px;
            font-size: 14px;
            font-weight: 600;
            white-space: nowrap;
            border-radius: 999px;
        }

        /* router-link 加的是这个类，原来的 &.active 永远不会命中 */
        a.router-link-exact-active {
            color: var(--color-primary);
            background: rgba(var(--primary-color-rgb), 0.12);
        }
    }

    .search-profile {
        flex: 1;
        min-width: 0;
        gap: 10px;
        justify-content: flex-end;
    }



    .profile {
        flex: 0 0 auto;
        width: 44px;
        height: 44px;
    }

    .profile-menu {
        /* 头像移到 44px 后菜单要跟着下移，否则会盖住头像 */
        top: 52px;
        width: 136px;
        padding: 6px;
        gap: 4px;

        li a {
            gap: 10px;
            padding: 8px 6px;
            font-size: 14px;
        }
    }
}
</style>
