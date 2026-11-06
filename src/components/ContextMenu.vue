<template>
    <Teleport to="body">
        <div v-if="showContextMenu && isMobile" class="context-sheet-overlay" @click.self="hideContextMenu">
            <section ref="menuRef" class="context-menu context-menu--sheet" role="dialog" aria-modal="true" aria-label="歌曲操作">
                <header class="sheet-header">
                    <button v-if="activeView === 'playlists'" class="sheet-header-btn" aria-label="返回歌曲操作" @click="showPlaylistView = false">
                        <i class="fas fa-chevron-left"></i>
                    </button>
                    <div class="sheet-heading">
                        <strong>{{ activeView === 'playlists' ? '添加到歌单' : sheetTitle }}</strong>
                        <small>{{ activeView === 'playlists' ? '选择一个歌单' : sheetSubtitle }}</small>
                    </div>
                    <button class="sheet-header-btn" aria-label="关闭" @click="hideContextMenu"><i class="fas fa-times"></i></button>
                </header>
                <div class="sheet-body">
                    <ul v-if="activeView === 'main'">
                        <li>
                            <button class="sheet-action" @click="openPlaylistView">
                                <i class="fa-solid fa-plus"></i>
                                <span>{{ MoeAuth.isAuthenticated ? $t('tian-jia-ge-dan') : $t('qing-xian-deng-lu') }}</span>
                                <i class="fa-solid fa-chevron-right sheet-chevron"></i>
                            </button>
                        </li>
                        <li v-if="contextSong.mvhash">
                            <button class="sheet-action" @click="playMV(contextSong.mvhash)"><i class="fa-solid fa-video"></i><span>播放MV</span></button>
                        </li>
                        <li>
                            <button class="sheet-action" @click="shareSong(contextSong)"><i class="fa-solid fa-share-nodes"></i><span>分享</span></button>
                        </li>
                        <li v-if="MoeAuth.isAuthenticated && listId && contextSong.userid === MoeAuth.UserInfo.userid">
                            <button class="sheet-action" @click="cancel()"><i class="fa-solid fa-heart"></i><span>取消收藏</span></button>
                        </li>
                        <li v-if="MoeAuth.isAuthenticated">
                            <button class="sheet-action" @click="addToNext(contextSong)"><i class="fa-solid fa-arrow-right"></i><span>添加到下一首</span></button>
                        </li>
                    </ul>
                    <ul v-else>
                        <li v-if="!playlists.length" class="sheet-empty">暂无可用歌单</li>
                        <li v-for="playlist in playlists" :key="playlist.listid">
                            <button class="sheet-action" :title="playlist.name" @click="addToPlaylist(playlist.listid, contextSong)">
                                <i class="fa-solid fa-music"></i><span>{{ playlist.name }}</span>
                            </button>
                        </li>
                    </ul>
                </div>
            </section>
        </div>
        <div v-else-if="showContextMenu" ref="menuRef" class="context-menu"
            :style="{ top: `${menuPosition.y}px`, left: `${menuPosition.x}px` }"
            :class="{ 'submenu-left': submenuLeft }" role="menu">
            <ul>
                <li @mouseenter="fetchPlaylists" @mouseleave="hideSubMenu" @click="fetchPlaylists">
                    <i class="fa-solid fa-plus"></i>
                    {{ MoeAuth.isAuthenticated ? $t('tian-jia-ge-dan') : $t('qing-xian-deng-lu') }} <i
                        class="fa-solid fa-chevron-right"></i>
                    <ul v-if="MoeAuth.isAuthenticated && showSubMenu" class="submenu">
                        <li v-for="playlist in playlists" :key="playlist.listid" :title="playlist.name"
                            @click="addToPlaylist(playlist.listid, contextSong)">
                            {{ playlist.name }}
                        </li>
                    </ul>
                </li>
                <li v-if="contextSong.mvhash" @click="playMV(contextSong.mvhash)"><i class="fa-solid fa-video"></i> 播放MV
                </li>
                <li @click="shareSong(contextSong)"><i class="fa-solid fa-share-nodes"></i> 分享</li>
                <li v-if="MoeAuth.isAuthenticated && listId && contextSong.userid === MoeAuth.UserInfo.userid"
                    @click="cancel()"><i class="fa-solid fa-heart"></i> 取消收藏</li>
                <li v-if="MoeAuth.isAuthenticated" @click="addToNext(contextSong)"><i class="fa-solid fa-arrow-right"></i>
                    添加到下一首</li>
            </ul>
        </div>
    </Teleport>
</template>

<script setup>
import { computed, ref, onMounted, onBeforeUnmount, nextTick, watch } from 'vue';
import { useRouter } from 'vue-router';
import { get } from '../utils/request';
import { MoeAuthStore } from '../stores/store';
import i18n from '@/utils/i18n';
import { openMvPlayer, share } from '@/utils/utils';
import { useMobileViewport } from '@/composables/useMobileViewport';
import { useDismissableOverlay } from '@/composables/useDismissableOverlay';

const props = defineProps({
    playerControl: Object
});
const emit = defineEmits(['songRemoved']);

const router = useRouter();
const MoeAuth = MoeAuthStore();
const isMobile = useMobileViewport();
const showContextMenu = ref(false);
const showSubMenu = ref(false);
const showPlaylistView = ref(false);
const menuPosition = ref({ x: 0, y: 0 });
const menuRef = ref(null);
const submenuLeft = ref(false);
const playlists = ref([]);
const listId = ref(0);
const contextSong = ref({});

const MENU_GAP = 8;
const SUBMENU_WIDTH = 170;
const isAuthenticated = computed(() => MoeAuth.isAuthenticated);
const activeView = computed(() => (showPlaylistView.value && isAuthenticated.value ? 'playlists' : 'main'));

const sheetTitle = computed(() => contextSong.value?.OriSongName || '歌曲操作');
const sheetSubtitle = computed(() => contextSong.value?.SingerName || contextSong.value?.author || '');

const hideContextMenu = () => {
    showContextMenu.value = false;
    showSubMenu.value = false;
    showPlaylistView.value = false;
};

const adjustMenuPosition = () => {
    const menu = menuRef.value;
    if (!menu || isMobile.value) return;

    const rect = menu.getBoundingClientRect();
    const maxX = window.innerWidth - rect.width - MENU_GAP;
    const maxY = window.innerHeight - rect.height - MENU_GAP;
    const x = Math.max(MENU_GAP, Math.min(menuPosition.value.x, maxX));
    const y = Math.max(MENU_GAP, Math.min(menuPosition.value.y, maxY));

    menuPosition.value = { x, y };
    submenuLeft.value = window.innerWidth - (x + rect.width) < SUBMENU_WIDTH && x > SUBMENU_WIDTH;
};

// 右键与触屏「更多」共用同一入口；手机忽略坐标，改用底部菜单。
const openContextMenu = (event, song, Id) => {
    if (!song) return;
    event?.preventDefault?.();
    event?.stopPropagation?.();
    showSubMenu.value = false;
    showPlaylistView.value = false;
    listId.value = Id;
    contextSong.value = song;
    menuPosition.value = { x: event?.clientX || 0, y: event?.clientY || 0 };
    showContextMenu.value = true;
    nextTick(adjustMenuPosition);
};

const openPlaylistView = () => {
    if (!MoeAuth.isAuthenticated) {
        showContextMenu.value = false;
        router.push('/login');
        return;
    }
    showPlaylistView.value = true;
    void fetchPlaylists();
};

const fetchPlaylists = async () => {
    if (!MoeAuth.isAuthenticated) return;
    showSubMenu.value = true;
    try {
        const playlistResponse = await get('/user/playlist', { pagesize: 100 });
        if (playlistResponse.status === 1) {
            playlists.value = playlistResponse.data.info.filter(playlist => playlist.list_create_userid === MoeAuth.UserInfo.userid);
        }
    } catch {
        $message.error(i18n.global.t('huo-qu-ge-dan-shi-bai'));
    }
};

const shareSong = (song) => {
    if (!song) return;
    share(song.OriSongName, song.FileHash);
    hideContextMenu();
};

const addToPlaylist = async (listid, song) => {
    try {
        await get(`/playlist/tracks/add?listid=${listid}&data=${encodeURIComponent(song.OriSongName.replace(',', ''))}|${song.FileHash}`);
        hideContextMenu();
        $message.success(i18n.global.t('cheng-gong-tian-jia-dao-ge-dan'));
    } catch {
        $message.error(i18n.global.t('tian-jia-dao-ge-dan-shi-bai'));
    }
};

const cancel = async () => {
    try {
        await get(`/playlist/tracks/del?listid=${listId.value}&fileids=${contextSong.value.fileid}`);
        emit('songRemoved', contextSong.value.fileid);
        hideContextMenu();
        $message.success(i18n.global.t('cheng-gong-qu-xiao-shou-cang'));
    } catch {
        $message.error(i18n.global.t('qu-xiao-shou-cang-shi-bai'));
    }
};

const addToNext = (song) => {
    const songNameParts = song?.OriSongName.split(' - ');
    props.playerControl.addToNext(song.FileHash, songNameParts[1], song.cover, songNameParts[0], song.timeLength);
    $message.success(i18n.global.t('tian-jia-cheng-gong'));
    hideContextMenu();
};

const hideSubMenu = () => {
    showSubMenu.value = false;
};

const playMV = async (mvhash) => {
    try {
        hideContextMenu();
        props.playerControl?.pause?.();
        const title = contextSong.value?.OriSongName || '视频播放';
        await openMvPlayer(router, mvhash, title);
    } catch {
        $message.error('打开视频播放器失败');
    }
};

const handleClickOutside = (event) => {
    if (!showContextMenu.value || isMobile.value) return;
    if (!event.target.closest('.context-menu')) hideContextMenu();
};

// 手机底部菜单打开期间接管返回键/Escape，关闭时恢复焦点。
useDismissableOverlay(showContextMenu, hideContextMenu);

watch(activeView, (view) => {
    if (view === 'playlists' && !playlists.value.length) void fetchPlaylists();
});

onMounted(() => {
    document.addEventListener('click', handleClickOutside);
    document.addEventListener('scroll', hideContextMenu, true);
});
onBeforeUnmount(() => {
    document.removeEventListener('click', handleClickOutside);
    document.removeEventListener('scroll', hideContextMenu, true);
});

defineExpose({ openContextMenu, hideContextMenu });
</script>

<style lang="scss" scoped>
.context-menu {
    position: fixed;
    background-color: var(--app-surface, #fff);
    border: 1px solid #ddd;
    border-radius: 10px;
    box-shadow: 0 2px 5px rgba(0, 0, 0, 0.15);
    z-index: 1000;
    min-width: 120px;
    max-width: calc(100vw - 16px);
    box-sizing: border-box;

    ul {
        list-style: none;
        padding: 0;
        margin: 0;
    }

    li {
        padding: 8px 14px;
        cursor: pointer;
        position: relative;
        border-radius: 10px;
        white-space: nowrap;

        &:hover {
            background-color: var(--background-color);
        }
    }
}

.context-menu.submenu-left .submenu {
    left: auto;
    right: 100%;
}

.submenu {
    position: absolute;
    left: 100%;
    top: 0;
    background-color: var(--app-surface, #fff);
    border: 1px solid #ddd;
    border-radius: 10px;
    box-shadow: 0 2px 5px rgba(0, 0, 0, 0.15);
    padding: 5px 0;
    max-height: 320px;
    overflow-y: auto;
    overflow-x: hidden;
    scrollbar-width: none;

    li {
        width: 150px;
        overflow: hidden;
        text-overflow: ellipsis;
    }
}

.context-sheet-overlay {
    position: fixed;
    inset: 0;
    z-index: 1500;
    display: flex;
    align-items: flex-end;
    background: rgba(0, 0, 0, 0.45);
}

.context-menu--sheet {
    position: relative;
    display: flex;
    flex-direction: column;
    width: 100%;
    max-width: 640px;
    max-height: min(78dvh, 560px);
    margin: 0 auto;
    padding-bottom: var(--app-safe-bottom, 0px);
    border: 0;
    border-radius: 16px 16px 0 0;
    overflow: hidden;
    background: var(--app-surface, #fff);
    color: var(--text-color);
    animation: sheet-rise 0.22s ease-out;
}

.sheet-header {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 8px 8px 8px 12px;
    border-bottom: 1px solid var(--border-color);
    flex-shrink: 0;
}

.sheet-heading {
    flex: 1;
    min-width: 0;
    display: flex;
    flex-direction: column;

    strong, small { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    strong { font-size: 15px; }
    small { font-size: 12px; opacity: 0.65; }
}

.sheet-header-btn {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 44px;
    height: 44px;
    flex: 0 0 44px;
    padding: 0;
    border: 0;
    border-radius: 10px;
    background: transparent;
    color: inherit;
    font-size: 16px;
    cursor: pointer;
}

.sheet-body {
    flex: 1;
    min-height: 0;
    overflow-y: auto;
    overscroll-behavior: contain;
    padding: 6px 8px;

    ul { list-style: none; margin: 0; padding: 0; }
    li { white-space: normal; }
}

.sheet-action {
    display: flex;
    align-items: center;
    gap: 12px;
    width: 100%;
    min-height: 48px;
    padding: 10px 12px;
    border: 0;
    border-radius: 10px;
    background: transparent;
    color: inherit;
    font-size: 15px;
    text-align: left;
    cursor: pointer;

    > i:first-child { width: 20px; flex-shrink: 0; text-align: center; color: var(--primary-color); }
    span { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .sheet-chevron { flex: 0 0 auto; opacity: 0.5; }

    &:active { background: var(--color-secondary-bg-for-transparent); }
}

.sheet-empty {
    padding: 18px 12px;
    text-align: center;
    opacity: 0.6;
}

@keyframes sheet-rise {
    from { transform: translateY(14px); opacity: 0.6; }
    to { transform: translateY(0); opacity: 1; }
}

@media (max-width: 768px) {
    .context-menu:not(.context-menu--sheet) { display: none; }
}
</style>
