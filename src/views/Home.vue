<template>
    <div class="container">
        <HomeRecommendations :playerControl="playerControl" />
        <h2 id="daily-recommend" class="section-title">
            <img :src="`./assets/images/home/mama.png`" class="mama" @click="addAllSongsToQueue">
            {{ $t('mei-ri-tui-jian') }}
        </h2>
        <CommonSkeleton v-if="isLoading" variant="compact-grid" :count="20" />
        <div v-else class="song-list">
            <div class="song-item" v-for="(song, index) in songs" :key="index"
                @click="playSong(song['hash'], song.ori_audio_name, $getCover(song.sizable_cover, 480), song.author_name)"
                @contextmenu.prevent="showContextMenu($event, song)">
                <img :src="$getCover(song.sizable_cover, 64)" :alt="song.ori_audio_name" class="song-cover">
                <div class="song-info">
                    <div class="song-title">{{ song.ori_audio_name }}</div>
                    <div class="song-artist">{{ song.author_name }}</div>
                </div>
                <button class="mobile-more-button" :aria-label="`${song.ori_audio_name} 更多操作`" @click.stop="showContextMenu($event, song)"><i class="fas fa-ellipsis-h"></i></button>
            </div>
        </div>
        <h2 class="section-title">{{ $t('tui-jian-ge-dan') }}</h2>
        <div class="playlist-grid">
            <div class="playlist-item" v-for="(playlist, index) in special_list" :key="index">
                <router-link :to="{
                    path: '/PlaylistDetail',
                    query: { global_collection_id: playlist.global_collection_id }
                }">
                    <div class="playlist-cover-wrap">
                        <img :src="$getCover(playlist.flexible_cover, 240)" class="playlist-cover">
                    </div>
                    <div class="playlist-info">
                        <div class="playlist-title">{{ playlist.specialname }}</div>
                        <div class="playlist-description">{{ playlist.intro }}</div>
                    </div>
                </router-link>
            </div>
        </div>
        <ContextMenu ref="contextMenuRef" :playerControl="playerControl" />
        <BackToTop />
    </div>
</template>

<script setup>
import { ref, onMounted } from "vue";
import { get } from '../utils/request';
import ContextMenu from '../components/ContextMenu.vue';
import BackToTop from '../components/BackToTop.vue';
import CommonSkeleton from '../components/CommonSkeleton.vue';
import HomeRecommendations from '../components/home/HomeRecommendations.vue';
import { useRoute, useRouter } from 'vue-router';
import { getCover } from '../utils/utils';
import { useActivatedWatch } from '../composables/useActivatedWatch';

const router = useRouter();
const route = useRoute();
const songs = ref([]);
const special_list = ref([]);
const isLoading = ref(true);
const playSong = (hash, name, img, author) => {
    props.playerControl.addSongToQueue(hash, name, img, author);
};
const contextMenuRef = ref(null);
const showContextMenu = (event, song) => {
    if (contextMenuRef.value) {
        contextMenuRef.value.openContextMenu(event, {
            OriSongName: song.filename,
            FileHash: song.hash,
            cover: song.sizable_cover?.replace("{size}", 480) || './assets/images/ico.png',
            timeLength: song.time_length
        });
    }
};
const props = defineProps({
    playerControl: Object
});

onMounted(() => {
    recommend();
    playlist();
});

const handleShareRoute = async (onCleanup) => {
    if (window.electron) return;

    let cancelled = false;
    await new Promise(resolve => {
        const timer = setTimeout(resolve, 1000);
        onCleanup(() => {
            cancelled = true;
            clearTimeout(timer);
        });
    });

    if (cancelled) return;

    if (route.query.hash) {
        privilegeSong(route.query.hash).then(res => {
            if (cancelled) return;
            if (res.status == 1) {
                const songInfo = res.data[0];
                playSong(songInfo.hash, songInfo.albumname, getCover(songInfo.info.image, 480), songInfo.singername)
                router.push('/');
            }
        })
    } else if (route.query.listid) {
        router.push({
            path: '/PlaylistDetail',
            query: { global_collection_id: route.query.listid }
        });
    }
};

useActivatedWatch(() => [route.query.hash, route.query.listid], (_value, _oldValue, onCleanup) => {
    handleShareRoute(onCleanup);
}, { immediate: true });

const recommend = async () => {
    const response = await get('/everyday/recommend');
    if (response.status == 1) {
        songs.value = response.data.song_list.sort(() => Math.random() - 0.5);
    }
    isLoading.value = false;
}

const playlist = async () => {
    const response = await get(`/top/playlist?category_id=0`);
    if (response.status == 1) {
        special_list.value = response.data.special_list;
    }
}

const privilegeSong = async (hash) => {
    const response = await get(`/privilege/lite`, { hash: hash });
    return response;
}
const addAllSongsToQueue = () => {
    props.playerControl.addPlaylistToQueue(songs.value.map(song => ({
        hash: song.hash,
        name: song.ori_audio_name,
        cover: song.sizable_cover?.replace("{size}", 480),
        author: song.author_name,
        timelen: song.time_length
    })));
};

</script>

<style lang="scss" scoped>
.container {
    max-width: 1400px;
    margin: 0 auto;
    padding: 20px;
    --home-playlist-card-bg: #fff;
    --home-playlist-card-shadow: 0 8px 22px rgba(31, 41, 55, 0.08);
    --home-playlist-card-hover-shadow: 0 14px 30px rgba(31, 41, 55, 0.14);
    --home-playlist-title: var(--primary-color);
    --home-playlist-description: #666;
    --home-song-card-bg: #fff;
    --home-song-card-shadow: 0 6px 18px rgba(31, 41, 55, 0.07);
    --home-song-card-hover-shadow: 0 12px 26px rgba(31, 41, 55, 0.12);
    --home-song-title: var(--text-color);
    --home-song-artist: #666;
    --home-song-hover-bg: rgba(var(--primary-color-rgb), 0.06);

    &:is(.dark .container) {
        --home-playlist-card-bg: #1d1d1d;
        --home-playlist-card-shadow: 0 10px 24px rgba(0, 0, 0, 0.28);
        --home-playlist-card-hover-shadow: 0 16px 34px rgba(0, 0, 0, 0.34);
        --home-playlist-title: rgba(255, 255, 255, 0.86);
        --home-playlist-description: rgba(255, 255, 255, 0.62);
        --home-song-card-bg: #1d1d1d;
        --home-song-card-shadow: 0 10px 22px rgba(0, 0, 0, 0.24);
        --home-song-card-hover-shadow: 0 14px 30px rgba(0, 0, 0, 0.32);
        --home-song-title: rgba(255, 255, 255, 0.86);
        --home-song-artist: rgba(255, 255, 255, 0.58);
        --home-song-hover-bg: rgba(var(--primary-color-rgb), 0.12);
    }
}

.section-title {
    font-size: 26px;
    font-weight: bold;
    margin-bottom: 30px;
    color: var(--primary-color);

    .mama {
        position: absolute;
        height: 40px;
        margin-left: 117px;
        cursor: cell;
    }
}

.song-list {
    display: grid;
    grid-template-columns: repeat(5, minmax(0, 1fr));
    gap: 14px;
    margin-top: 20px;
    margin-bottom: 34px;
}

.song-item {
    position: relative;
    display: flex;
    align-items: center;
    gap: 10px;
    min-width: 0;
    background-color: var(--home-song-card-bg);
    padding: 8px 12px 8px 8px;
    border-radius: 8px;
    box-shadow: var(--home-song-card-shadow);
    transition: transform 0.25s ease, background-color 0.25s ease, box-shadow 0.25s ease;
    cursor: pointer;
    overflow: hidden;

    &::before {
        content: "";
        position: absolute;
        right: 21px;
        z-index: 1;
        width: 0;
        height: 0;
        border-top: 5px solid transparent;
        border-bottom: 5px solid transparent;
        border-left: 8px solid #fff;
        opacity: 0;
        transform: translateX(8px) scale(0.92);
        transition: opacity 0.25s ease, transform 0.25s ease;
    }

    &::after {
        content: "";
        position: absolute;
        right: 12px;
        width: 28px;
        height: 28px;
        display: flex;
        align-items: center;
        justify-content: center;
        color: #fff;
        background: var(--primary-color);
        border-radius: 50%;
        opacity: 0;
        transform: translateX(8px) scale(0.92);
        transition: opacity 0.25s ease, transform 0.25s ease;
    }

    &:hover {
        transform: translateY(-3px);
        background-color: var(--home-song-hover-bg);
        box-shadow: var(--home-song-card-hover-shadow);

        &::after {
            opacity: 1;
            transform: translateX(0) scale(1);
        }

        &::before {
            opacity: 1;
            transform: translateX(0) scale(1);
        }

        .song-info {
            padding-right: 34px;
        }
    }
}

.song-cover {
    flex: 0 0 auto;
    width: 54px;
    height: 54px;
    object-fit: cover;
    border-radius: 6px;
}

.song-info {
    display: flex;
    flex-direction: column;
    flex: 1;
    min-width: 0;
    transition: padding-right 0.25s ease;
}

.song-title {
    font-size: 15px;
    font-weight: 600;
    color: var(--home-song-title);
    line-height: 1.35;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
}

.song-artist {
    margin-top: 4px;
    font-size: 13px;
    color: var(--home-song-artist);
    line-height: 1.3;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
}

@media screen and (max-width: 768px) {
    .song-list {
        grid-template-columns: repeat(2, minmax(0, 1fr));
        gap: 10px;
        margin-bottom: 28px;
    }

    .song-item {
        padding: 7px 10px 7px 7px;
    }

    .song-cover {
        width: 48px;
        height: 48px;
    }

    .song-title {
        font-size: 14px;
    }

    .song-artist {
        font-size: 12px;
    }
}

@media screen and (max-width: 576px) {
    .song-list {
        grid-template-columns: 1fr;
    }
}

.playlist-grid {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(186px, 1fr));
    gap: 18px;
}

.playlist-item {
    min-width: 0;
    padding: 10px;
    background-color: var(--home-playlist-card-bg);
    border-radius: 8px;
    transition: transform 0.3s ease, box-shadow 0.3s ease;
    cursor: pointer;
    box-shadow: var(--home-playlist-card-shadow);

    &:hover {
        transform: translateY(-5px);
        box-shadow: var(--home-playlist-card-hover-shadow);

        .playlist-cover {
            transform: scale(1.04);
        }
    }

    a {
        display: block;
        color: inherit;
        text-decoration: none;
    }
}

.playlist-cover {
    width: 100%;
    height: 100%;
    object-fit: cover;
    display: block;
    transition: transform 0.35s ease;
}

.playlist-cover-wrap {
    width: 100%;
    aspect-ratio: 1;
    overflow: hidden;
    border-radius: 6px;
}

.playlist-info {
    padding: 12px 2px 2px;
}

.playlist-title {
    font-weight: bold;
    margin-bottom: 6px;
    font-size: 16px;
    color: var(--home-playlist-title);
    display: -webkit-box;
    -webkit-line-clamp: 2;
    -webkit-box-orient: vertical;
    overflow: hidden;
    text-overflow: ellipsis;
    line-height: 1.35;
    min-height: 43px;

    @media screen and (max-width: 768px) {
        font-size: 14px;
        min-height: 38px;
    }
}

.playlist-description {
    color: var(--home-playlist-description);
    font-size: 13px;
    display: -webkit-box;
    -webkit-line-clamp: 2;
    -webkit-box-orient: vertical;
    overflow: hidden;
    text-overflow: ellipsis;
    line-height: 1.55;
    min-height: 40px;

    @media screen and (max-width: 768px) {
        font-size: 12px;
        min-height: 37px;
    }
}

@media screen and (max-width: 768px) {
    .playlist-grid {
        grid-template-columns: repeat(auto-fill, minmax(140px, 1fr));
        gap: 12px;
    }

    .playlist-item {
        padding: 8px;
    }
}

/* ===== 移动端：仿网易云首页布局 ===== */
@media screen and (max-width: 768px) {
    .container {
        padding: 10px 14px 24px;
    }

    .section-title {
        display: flex;
        align-items: center;
        gap: 8px;
        font-size: 18px;
        font-weight: 700;
        margin-top: 22px;
        margin-bottom: 12px;

        .mama {
            position: static;
            margin-left: 0;
            height: 22px;
            width: auto;
            flex: 0 0 auto;
        }
    }

    /* 每日推荐：横向滑动卡片 */
    .song-list {
        display: flex;
        grid-template-columns: none;
        gap: 12px;
        margin: 0 0 22px;
        overflow-x: auto;
        overflow-y: hidden;
        scroll-snap-type: x proximity;
        -webkit-overflow-scrolling: touch;
        padding-bottom: 6px;

        &::-webkit-scrollbar {
            display: none;
        }
    }

    .song-item {
        flex: 0 0 104px;
        flex-direction: column;
        align-items: stretch;
        gap: 6px;
        padding: 0;
        background-color: transparent;
        border-radius: 0;
        box-shadow: none;
        scroll-snap-align: start;

        &::before,
        &::after {
            display: none;
        }

        &:hover {
            transform: none;
            background-color: transparent;
            box-shadow: none;
        }
    }

    .song-cover {
        width: 100%;
        height: auto;
        aspect-ratio: 1 / 1;
        border-radius: 10px;
    }

    .song-info {
        padding-right: 0;
    }

    .song-title {
        font-size: 13px;
        font-weight: 500;
    }

    .song-artist {
        margin-top: 2px;
        font-size: 11px;
        opacity: 0.72;
    }

    /* 推荐歌单：3 列 */
    .playlist-grid {
        grid-template-columns: repeat(3, minmax(0, 1fr));
        gap: 10px;
    }

    .playlist-item {
        padding: 0;
        background-color: transparent;
        border-radius: 0;
        box-shadow: none;

        &:hover {
            transform: none;
            box-shadow: none;

            .playlist-cover {
                transform: none;
            }
        }
    }

    .playlist-cover-wrap {
        border-radius: 10px;
    }

    .playlist-info {
        padding: 6px 0 0;
    }

    .playlist-title {
        margin-bottom: 0;
        font-size: 12px;
        font-weight: 500;
        line-height: 1.35;
        min-height: 0;
        color: var(--home-song-title);
        -webkit-line-clamp: 2;
    }

    /* 网易云首页不显示歌单长简介，省空间 */
    .playlist-description {
        display: none;
    }
}
/* 手机：歌曲卡片改为「封面 + 两行文本 + 常显更多」，行内不再靠 hover 才出现操作；
   播放按钮的 hover 装饰在触屏上没意义，隐藏它腾出宽度。 */
@media screen and (max-width: 768px) {
    .song-item {
        gap: 8px;
        padding-right: 4px;

        &::before,
        &::after {
            display: none;
        }

        &:hover .song-info {
            padding-right: 0;
        }
    }

    .song-title {
        white-space: normal;
        display: -webkit-box;
        -webkit-line-clamp: 2;
        -webkit-box-orient: vertical;
        word-break: break-word;
    }

    .mobile-more-button {
        /* .song-item 在手机上是 flex-direction: column 的横向卡片，按钮作为
           第三个子项会被排到封面和歌名下面、和文字之间空出一大截。
           改成贴卡片右上角，符合卡片式列表的惯例，也不占垂直空间。 */
        position: absolute;
        top: 2px;
        right: 2px;
        flex: 0 0 auto;
        width: 32px;
        height: 32px;
        margin: 0;
        padding: 0;
        background: transparent;
        border: 0;
    }

    .section-title .mama {
        position: static;
        height: 26px;
        margin-left: 0;
    }
}

@media screen and (max-width: 420px) {
    /* 封面尺寸交给上面 768px 断点（width:100% + aspect-ratio）统一处理：
       这里再写死 42px 会把横向卡片里的封面缩成小方块，右侧空出一大片。 */

    .playlist-grid {
        grid-template-columns: repeat(auto-fill, minmax(126px, 1fr));
        gap: 10px;
    }
}
</style>
