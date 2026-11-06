<template>
    <Teleport to="body">
        <transition name="fade">
            <div v-if="isOpen" class="modal" @click.self="isOpen = false">
                <div class="modal-content" role="dialog" aria-modal="true" :aria-label="t('shou-cang-dao')">
                    <h3>{{ t('shou-cang-dao') }}</h3>
                    <ul class="playlist-select-list" v-if="playlists.length > 0">
                        <li v-for="playlist in playlists" :key="playlist.list_id" role="button" tabindex="0"
                            @click="addToPlaylist(playlist.listid, currentSong); isOpen = false"
                            @keydown.enter.prevent="addToPlaylist(playlist.listid, currentSong); isOpen = false">
                            {{ playlist.name }}
                        </li>
                    </ul>
                    <div v-else>{{ t('mei-you-ge-dan') }}</div>
                    <button class="close-btn-modal" @click="isOpen = false">{{ t('guan-bi-an-niu') }}</button>
                </div>
            </div>
        </transition>
    </Teleport>
</template>

<script setup>
import { ref } from 'vue';
import { get } from '../utils/request';
import { useI18n } from 'vue-i18n';
import { MoeAuthStore } from '../stores/store';
import { useDismissableOverlay } from '@/composables/useDismissableOverlay';

const MoeAuth = MoeAuthStore();
const { t } = useI18n();

const props = defineProps({
    currentSong: {
        type: Object,
        required: true
    },
    playlists: {
        type: Array,
        default: () => []
    }
});
const playlists = ref([]);
const isOpen = ref(false);
useDismissableOverlay(isOpen, () => { isOpen.value = false; });

const validateUserAndSong = () => {
    if (!MoeAuth.isAuthenticated) {
        window.$modal.alert(t('qing-xian-deng-lu'));
        return false;
    }
    if (Array.isArray(props.currentSong)) {
        if (!props.currentSong[0].hash) {
            window.$modal.alert('没有选择正确的歌曲');
            return false;
        }
    } else if (!props.currentSong.hash) {
        window.$modal.alert(t('mei-you-zheng-zai-bo-fang-de-ge-qu'));
        return false;
    }
    if (props.currentSong.isCloud) {
        window.$modal.alert('云盘音乐不支持添加到歌单');
        return false;
    }
    if (props.currentSong.isLocal) {
        window.$modal.alert('本地音乐不支持添加到歌单');
        return false;
    }
    return true;
};

const fetchPlaylists = async () => {
    try {
        const playlistResponse = await get('/user/playlist', {
            pagesize: 100
        });
        if (playlistResponse.status !== 1) {
            $message.error(t('huo-qu-ge-dan-shi-bai'));
            return;
        }
        playlists.value = playlistResponse.data.info.filter(
            playlist => playlist.list_create_userid === MoeAuth.UserInfo.userid
        );
        isOpen.value = true;
    } catch (error) {
        $message.error(t('huo-qu-ge-dan-shi-bai'));
        isOpen.value = false;
    }
};

const addToPlaylist = async (listid, song) => {
    if (!validateUserAndSong()) return;
    let song_data = '';
    if (Array.isArray(song)) {
        song_data = song.map(s => `${encodeURIComponent(s.name.replace(',', ''))}|${s.hash}`).join(',');
    } else {
        song_data = `${encodeURIComponent(song.name.replace(',', ''))}|${song.hash}`;
    }
    try {
        await get(`/playlist/tracks/add?listid=${listid}&data=${song_data}`);
        $message.success(t('cheng-gong-tian-jia-dao-ge-dan'));
    } catch (error) {
        $message.error(t('tian-jia-dao-ge-dan-shi-bai'));
    }
    isOpen.value = false;
};

const toLike = () => {
    const like_id = localStorage.getItem('like');
    if (!like_id) { window.$modal.alert('先去看看你的收藏夹吧'); return; }
    addToPlaylist(like_id, props.currentSong);
};

defineExpose({
    toLike,
    fetchPlaylists,
    validateUserAndSong,
    addToPlaylist
});
</script>

<style lang="scss" scoped>
.modal {
    position: fixed;
    top: 0;
    left: 0;
    width: 100%;
    height: 100%;
    padding: var(--app-safe-top, 0px) 12px calc(var(--app-safe-bottom, 0px) + 12px);
    box-sizing: border-box;
    background-color: rgba(0, 0, 0, 0.5);
    display: flex;
    justify-content: center;
    align-items: center;
    z-index: 1000;

    .close-btn-modal {
        width: 100%;
        padding: 8px;
        background-color: var(--primary-color);
        color: white;
        border: none;
        border-radius: 4px;
        cursor: pointer;
        margin-top: 10px;

        &:hover {
            opacity: 0.9;
        }
    }
}

.modal-content {
    background-color: var(--background-color);
    padding: 20px;
    border-radius: 8px;
    min-width: 300px;
    max-width: 90%;
    max-height: 90vh;
    max-height: 90dvh;
    overflow-y: auto;
    box-sizing: border-box;
    overscroll-behavior: contain;
}

@media (max-width: 768px) {
    .modal { align-items: flex-end; padding-left: 0; padding-right: 0; padding-bottom: var(--app-safe-bottom, 0px); }
    .modal-content {
        width: 100%;
        min-width: 0;
        max-width: 100%;
        max-height: calc(88dvh - var(--app-safe-top, 0px));
        border-radius: 16px 16px 0 0;
        padding: 18px 16px 16px;
    }
    .modal-content h3 { margin: 0 0 6px; }
    .playlist-select-list { max-height: none; margin: 10px 0; }
    .playlist-select-list li { min-height: 44px; display: flex; align-items: center; overflow-wrap: anywhere; }
    .close-btn-modal { min-height: 44px; font-size: 15px; }
}

.playlist-select-list {
    list-style: none;
    padding: 0;
    margin: 15px 0;
    max-height: 300px;
    overflow-y: auto;

    li {
        padding: 10px;
        cursor: pointer;
        transition: background-color 0.2s;
        border-radius: 8px;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;

        &:hover {
            background-color: var(--secondary-color);
        }
    }
}
</style>
