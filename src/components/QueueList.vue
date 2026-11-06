<template>
    <transition name="fade">
        <div v-if="showQueue" class="queue-popup">
            <div class="queue-header">
                <h3>
                    <span>{{ $t('bo-fang-lie-biao') }}</span> ({{ musicQueueStore.queue.length }})
                    <i class="fas fa-trash-alt close-store" @click="musicQueueStore.clearQueue()" title="close"></i>
                </h3>
            </div>

            <RecycleScroller :items="musicQueueStore.queue" :item-size="50" key-field="id" :buffer="200"
                :items-limit="2000" :prerender="Math.min(10, musicQueueStore.queue.length)" ref="queueScroller"
                class="queue-list">
                <template #default="{ item, index }">
                    <li class="queue-item" :class="{ 'playing': currentSong.hash == item.hash }" :key="item.id">
                        <div class="queue-song-info">
                            <span class="queue-song-title">{{ item.name }}</span>
                            <span class="queue-artist">{{ $formatMilliseconds(item.timeLength) }}</span>
                        </div>
                        <div class="queue-actions">
                            <button v-if="currentSong.hash == item.hash" class="queue-play-btn fas fa-music"></button>
                            <template v-else>
                                <button class="queue-play-btn" @click="playQueueItem(item)"><i
                                        class="fas fa-play"></i></button>
                                <i class="fas fa-times close-store" @click="removeSongFromQueue(index);"></i>
                            </template>
                        </div>
                    </li>
                </template>
            </RecycleScroller>
        </div>
    </transition>
</template>

<script setup>
import { ref, nextTick, onMounted, onUnmounted } from 'vue';
import { RecycleScroller } from 'vue3-virtual-scroller';
import { useMusicQueueStore } from '../stores/musicQueue';
import 'vue3-virtual-scroller/dist/vue3-virtual-scroller.css';

const props = defineProps({
    currentSong: {
        type: Object,
        required: true
    }
});

const emit = defineEmits(['update:showQueue', 'addSongToQueue', 'addCloudMusicToQueue', 'addLocalMusicToQueue']);

const musicQueueStore = useMusicQueueStore();
const queueScroller = ref(null);
const showQueue = ref(false);

const isLocalSong = (song) => !!song?.isLocal || String(song?.hash || '').startsWith('local_');

// 从队列中删除歌曲
const removeSongFromQueue = (index) => {
    const updatedQueue = [...musicQueueStore.queue];
    updatedQueue.splice(index, 1);
    updatedQueue.forEach((song, i) => {
        song.id = i + 1;
    });
    musicQueueStore.setQueue(updatedQueue);
};

// 播放队列中的歌曲项
const playQueueItem = (item) => {
    console.log('[QueueList] 点击播放队列中的歌曲:', item.name);
    showQueue.value = false; // 点击后关闭播放队列面板
    if (item.isCloud) {
        emit('addCloudMusicToQueue', item.hash, item.name, item.author, item.timeLength, item.img);
    } else if (isLocalSong(item)) {
        emit('addLocalMusicToQueue', {
            ...item,
            isLocal: true
        });
    } else {
        emit('addSongToQueue', item.hash, item.name, item.img, item.author);
    }
};

// 滚动到当前播放歌曲位置
const openQueue = async () => {
    showQueue.value = !showQueue.value;
    if (showQueue.value) {
        await nextTick();
        setTimeout(() => {
            const currentIndex = musicQueueStore.queue.findIndex(song => song.hash === props.currentSong.hash);
            queueScroller.value.scrollToItem(currentIndex - 3);
        }, 100);
    }
};

const handleClickOutside = (event) => {
    const queuePopup = document.querySelector('.queue-popup');
    if (queuePopup && !queuePopup.contains(event.target) && !event.target.closest('.extra-btn')) {
        showQueue.value = false;
    }
};

onMounted(() => {
    document.addEventListener('click', handleClickOutside);
});

onUnmounted(() => {
    document.removeEventListener('click', handleClickOutside);
});

defineExpose({
    openQueue,
    removeSongFromQueue
});
</script>
<style lang="scss" scoped>
.queue-popup {
    position: fixed;
    right: 20px;
    bottom: 100px;
    width: 300px;
    max-height: 400px;
    background: #fff;
    box-shadow: 0 8px 32px rgba(0, 0, 0, 0.2);
    border-radius: 10px;
    padding: 10px;
    z-index: 2;
    overflow-y: auto;
}

.queue-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-bottom: 10px;
    position: sticky;
    top: 0px;
    z-index: 1;
    border-radius: 5px;
    padding: 5px;
    box-shadow: 0 2px 4px rgba(0, 0, 0, 0.1);

    h3 {
        margin: 0;
        font-size: 1.2em;
        color: var(--text-color);
    }
}

.queue-list {
    list-style: none;
    padding: 0;
    margin: 0;
    flex: 1;
    overflow-y: auto;
    height: 350px;
    scroll-behavior: smooth;
}

.queue-item {
    display: flex;
    justify-content: space-between;
    align-items: center;
    border-bottom: 1px solid #ddd;

    &.playing {
        .queue-song-title {
            color: var(--primary-color);
            font-weight: bold;
        }

        .queue-artist {
            color: var(--primary-color);
        }
    }
}

.queue-song-info {
    display: flex;
    flex-direction: column;
}

.queue-song-title {
    font-weight: bold;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    width: 250px;
}

.queue-artist {
    font-size: 0.9em;
    color: #666;
}

.queue-actions {
    display: flex;
    align-items: center;
}

.queue-play-btn {
    background: none;
    border: none;
    font-size: 16px;
    color: var(--primary-color);
    cursor: pointer;
}

.close-store {
    margin-left: 8px;
    cursor: pointer;
    font-size: 14px;
}

// 手机端：300px 固定宽度 + right:20px 在 320px 屏上直接溢出，
// 改成贴住播放器上沿的底部抽屉；标题列不再写死 250px，避免把按钮挤出屏幕。
@media (max-width: 768px) {
    .queue-popup {
        left: 0;
        right: 0;
        bottom: calc(var(--app-player-height) + var(--app-safe-bottom));
        width: auto;
        max-height: 62dvh;
        padding: 8px 12px 12px;
        border-radius: 16px 16px 0 0;
        overflow: hidden;
        z-index: 97;
        box-shadow: 0 -8px 28px rgba(0, 0, 0, 0.22);
    }

    .queue-header {
        margin-bottom: 6px;
    }

    .queue-list {
        height: min(52dvh, 350px);
    }

    .queue-item {
        min-height: 48px;
    }

    .queue-song-info {
        flex: 1;
        min-width: 0;
        margin-right: 10px;
    }

    .queue-song-title {
        width: auto;
    }

    .queue-actions {
        flex: 0 0 auto;
    }

    .queue-play-btn,
    .close-store {
        display: inline-flex;
        align-items: center;
        justify-content: center;
        min-width: 40px;
        min-height: 40px;
        margin-left: 0;
    }
}
</style>
