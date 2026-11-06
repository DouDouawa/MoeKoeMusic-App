<template>
    <div class="player-container">
        <div class="progress-bar" @pointerdown="onProgressDragStart"
            @mousemove="updateTimeTooltip" @mouseleave="hideTimeTooltip">
            <div class="progress" :style="{ width: progressWidth + '%' }"></div>
            <div class="progress-handle" :style="{ left: progressWidth + '%' }"></div>
            <div v-for="(point, index) in climaxPoints" :key="index" class="climax-point"
                :style="{ left: point.position + '%' }">
            </div>
            <div v-if="showTimeTooltip" class="time-tooltip" :style="{ left: tooltipPosition + 'px' }">
                {{ tooltipTime }}
            </div>
        </div>
        <div class="player-bar">
            <div class="album-art" @click="toggleLyrics(currentSong.hash, currentTime)">
                <img v-if="currentSong.img" :src="currentSong.img" alt="Album Art" />
                <i v-else class="fas fa-music"></i>
                <span class="album-art-hover-indicator" aria-hidden="true">
                    <i class="fas fa-angle-double-up"></i>
                </span>
            </div>
            <div class="song-info" @click="toggleLyrics(currentSong.hash, currentTime)">
                <div class="song-title-row">
                    <div ref="songTitleRef" class="song-title" :class="{ 'is-marquee': titleOverflow }"
                        :style="titleOverflow ? { '--marquee-distance': `-${titleOverflowPx}px` } : null"
                        @click.stop="searchSong(currentSong.name)">
                        <span class="song-title-track">
                            <span class="song-title-text">{{ currentSong?.name || "MoeKoeMusic" }}</span>
                            <span v-if="titleOverflow" class="song-title-text" aria-hidden="true">{{ currentSong?.name }}</span>
                        </span>
                    </div>
                    <div v-if="currentSong?.qualityLabel" class="quality-menu-wrapper" @click.stop>
                        <button
                            type="button"
                            :class="['quality-badge', { clickable: canSwitchQuality }]"
                            @click.stop="toggleQualityMenu"
                        >
                            {{ currentSong.qualityLabel }}
                        </button>
                        <transition name="player-menu">
                            <div v-if="qualityMenuOpen && canSwitchQuality" class="player-menu quality-menu">
                                <div v-if="!currentSong?.qualityOptions?.length" class="player-menu-item disabled">暂无可选音质</div>
                                <button
                                    v-for="option in currentSong.qualityOptions"
                                    :key="`${option.value}-${option.hash}`"
                                    type="button"
                                    class="player-menu-item"
                                    :class="{ active: isCurrentQualityOption(option) }"
                                    @click.stop="switchQuality(option)"
                                >
                                    {{ option.label }}
                                </button>
                            </div>
                        </transition>
                    </div>
                </div>
                <div class="artist" @click.stop="searchSong(currentSong.author)">{{ currentSong?.author || "MoeJue" }}</div>
            </div>
            <div class="controls">
                <button class="control-btn" :title="t('shang-yi-shou')" @click="playSongFromQueue('previous')">
                    <i class="fas fa-step-backward"></i>
                </button>
                <button class="control-btn" :title="t('zan-ting-bo-fang')" @click="togglePlayPause">
                    <i v-if="playbackWaiting" class="fas fa-spinner fa-spin"></i>
                    <i v-else :class="playing ? 'fas fa-pause' : 'fas fa-play'"></i>
                </button>
                <button class="control-btn" :title="t('xia-yi-shou')" @click="playSongFromQueue('next')">
                    <i class="fas fa-step-forward"></i>
                </button>
            </div>
            <div class="extra-controls">
                <button class="extra-btn" :title="t('zhuo-mian-ge-ci')" v-if="isElectron()" @click="desktopLyrics"><i
                        class="fas">词</i></button>
                <div class="player-more">
                    <button class="extra-btn" :title="t('geng-duo')" @click.stop="toggleMoreMenu">
                        <i class="fas fa-ellipsis"></i>
                    </button>
                    <transition name="more-menu">
                        <div v-if="showMoreMenu" class="player-menu more-menu">
                            <template v-if="isMobile">
                                <div class="more-section-title">{{ t('qie-huan-bo-fang-mo-shi') }}</div>
                                <div class="more-chip-grid more-mode-grid">
                                    <button
                                        v-for="(mode, index) in playbackModes"
                                        :key="mode.title"
                                        type="button"
                                        class="more-chip"
                                        :class="{ active: currentPlaybackModeIndex === index }"
                                        :title="mode.title"
                                        @click.stop="setPlaybackMode(index)"
                                    >
                                        <i v-if="index !== 2" :class="mode.icon"></i>
                                        <span v-else class="loop-icon">
                                            <i class="fas fa-repeat"></i>
                                            <sup>1</sup>
                                        </span>
                                        <span>{{ mode.title }}</span>
                                    </button>
                                </div>
                                <div class="more-divider"></div>
                            </template>
                            <div class="more-section-title">{{ t('bo-fang-su-du') }}</div>
                            <div class="more-chip-grid">
                                <button v-for="speed in playbackSpeeds" :key="speed" type="button"
                                    class="more-chip" :class="{ active: currentSpeed === speed }"
                                    @click.stop="changePlaybackSpeed(speed)">
                                    {{ speed }}x
                                </button>
                            </div>
                            <!-- 手机端不提供音量控制：系统音量键更自然，播放条和菜单里都去掉 -->
                            <div class="more-divider"></div>
                            <button type="button" class="player-menu-item more-action" @click.stop="onMoreShare">
                                <i class="fas fa-share"></i>
                                <span>{{ t('fen-xiang-ge-qu') }}</span>
                            </button>
                            <!-- 手机端一行放不下 4 个附加按钮，这些低频操作收进菜单 -->
                            <template v-if="isMobile">
                                <button type="button" class="player-menu-item more-action" @click.stop="onMoreLike">
                                    <i class="fas fa-heart"></i>
                                    <span>{{ t('wo-xi-huan') }}</span>
                                </button>
                                <button type="button" class="player-menu-item more-action" @click.stop="onMoreAddToPlaylist">
                                    <i class="fas fa-add"></i>
                                    <span>{{ t('shou-cang-zhi') }}</span>
                                </button>
                                <button type="button" class="player-menu-item more-action" @click.stop="onMoreOpenQueue">
                                    <i class="fas fa-list"></i>
                                    <span>{{ t('bo-fang-lie-biao') }}</span>
                                </button>
                            </template>
                        </div>
                    </transition>
                </div>
                <button v-if="!isMobile" class="extra-btn" :title="t('wo-xi-huan')" @click="playlistSelect.toLike()"><i
                        class="fas fa-heart"></i></button>
                <button v-if="!isMobile" class="extra-btn" :title="t('shou-cang-zhi')" @click="onMoreAddToPlaylist"><i
                        class="fas fa-add"></i></button>
                <!-- 播放顺序（桌面端；移动端收进“更多”菜单） -->
                <div v-if="!isMobile" class="playback-mode">
                    <button class="extra-btn" @click="togglePlaybackMode">
                        <i v-if="currentPlaybackModeIndex != '2'" :class="currentPlaybackMode.icon"
                            :title="currentPlaybackMode.title"></i>
                        <span v-else class="loop-icon" :title="currentPlaybackMode.title">
                            <i class="fas fa-repeat"></i>
                            <sup>1</sup>
                        </span>
                    </button>
                    <div class="player-menu playback-mode-menu">
                        <button
                            v-for="(mode, index) in playbackModes"
                            :key="mode.title"
                            type="button"
                            class="player-menu-item playback-mode-option"
                            :class="{ active: currentPlaybackModeIndex === index }"
                            :title="mode.title"
                            @click.stop="setPlaybackMode(index)"
                        >
                            <i v-if="index !== 2" :class="mode.icon"></i>
                            <span v-else class="loop-icon">
                                <i class="fas fa-repeat"></i>
                                <sup>1</sup>
                            </span>
                            <span>{{ mode.title }}</span>
                        </button>
                    </div>
                </div>
                <button v-if="!isMobile" class="extra-btn" :title="t('bo-fang-lie-biao')" @click="queueList.openQueue()"><i class="fas fa-list"></i></button>
                <!-- 音量控制只在桌面端出现；手机端交给系统音量键，播放条和更多菜单里都不再提供 -->
                <div v-if="!isMobile" class="volume-control" @wheel="handleVolumeScroll">
                    <i :class="isMuted ? 'fas fa-volume-mute' : 'fas fa-volume-up'" @click="toggleMute"></i>
                    <div class="volume-slider-wrap" :class="{ 'show-volume-value': isDragging }">
                        <div class="volume-value" :style="volumeValueStyle">{{ volumePercentText }}</div>
                        <div class="volume-slider" @pointerdown="onDragStart">
                            <div class="volume-progress" :style="{ width: volume + '%' }"></div>
                            <input type="range" min="0" max="100" v-model="volume" @input="changeVolume" />
                        </div>
                    </div>
                </div>
            </div>
        </div>
    </div>

    <!-- 播放队列 -->
    <QueueList :current-song="currentSong" @add-song-to-queue="onQueueSongAdd"
        @add-cloud-music-to-queue="onQueueCloudSongAdd" @add-local-music-to-queue="onQueueLocalSongAdd" ref="queueList" />

    <!-- 全屏歌词界面 -->
    <transition name="slide-up">
        <div v-if="showLyrics" class="lyrics-bg" :style="lyricsBackgroundStyle">
            <template v-if="lyricsBackground === 'cover'">
                <div
                    v-for="(image, index) in lyricsBackgroundImages"
                    :key="image"
                    class="lyrics-bg-image"
                    :class="{ active: index === lyricsBackgroundImageIndex }"
                    :style="{ backgroundImage: `url(${image})` }"
                ></div>
            </template>
            <div class="lyrics-screen" :class="{ 'cover-background': lyricsBackground === 'cover' }">
                <button class="close-btn" type="button" aria-label="关闭歌词" @click="toggleLyrics(currentSong.hash, currentTime)">
                    <i class="fas fa-chevron-down"></i>
                </button>
                <FullscreenLyricsSettings v-model="fullscreenLyricsSettings" @change="handleFullscreenLyricsSettingsChange" />

                <div class="lyrics-mode-btn" v-if="hasMultiLyricsMode" @click="switchLyricsMode" :title="lyricsMode === 'translation' ? t('qie-huan-dao-yin-yi') : t('qie-huan-dao-fan-yi')">
                    <i class="fas fa-language"></i>
                </div>

                <div class="left-section">
                    <div class="album-art-container" @click="toggleCoverMode">
                        <transition name="cover-fade" mode="out-in">
                            <div v-if="coverMode === 'vinyl'" key="vinyl" class="vinyl-player">
                                <!-- 唱片播放器模式 -->
                                <div class="vinyl-disc" :class="{ 'rotating': playing }">
                                    <img :src="currentSong?.img || './assets/images/!.png'" alt="Album Art" class="vinyl-cover" />
                                </div>
                                <div class="tonearm" :class="{ 'playing': playing }"></div>
                            </div>
                            <div v-else key="square" class="album-art-large">
                                <!-- 普通封面模式 -->
                                <img v-if="easterEggImage" :src="easterEggImage.src" :class="easterEggClass" alt="Easter Egg" />
                                <img :src="currentSong?.img || './assets/images/!.png'" alt="Album Art" />
                            </div>
                        </transition>
                    </div>
                    <div class="song-details">
                        <div class="song-title" @click="searchSong(currentSong.name)">{{ currentSong?.name }}</div>
                        <div class="artist" @click="searchSong(currentSong.author)">{{ currentSong?.author }}</div>
                    </div>

                    <!-- 播放进度条 -->
                    <div class="progress-bar-container">
                        <span class="current-time">{{ formattedCurrentTime }}</span>
                        <div class="progress-bar" @pointerdown="onProgressDragStart"
                            @mousemove="updateTimeTooltip" @mouseleave="hideTimeTooltip">
                            <div class="progress" :style="{ width: progressWidth + '%' }"></div>
                            <div class="progress-handle" :style="{ left: progressWidth + '%' }"></div>
                            <div v-for="(point, index) in climaxPoints" :key="index" class="climax-point"
                                :style="{ left: point.position + '%' }">
                            </div>
                            <div v-if="showTimeTooltip" class="time-tooltip" :style="{ left: tooltipPosition + 'px' }">
                                {{ tooltipTime }}
                            </div>
                        </div>
                        <span class="duration">{{ formattedDuration }}</span>
                    </div>

                    <div class="player-controls">
                        <button class="control-btn like-btn" :title="t('wo-xi-huan')" @click="playlistSelect.toLike()">
                            <i class="fas fa-heart"></i>
                        </button>
                        <button class="control-btn" :title="t('shang-yi-shou')" @click="playSongFromQueue('previous')">
                            <i class="fas fa-step-backward"></i>
                        </button>
                        <button class="control-btn" :title="t('zan-ting-bo-fang')" @click="togglePlayPause">
                            <i v-if="playbackWaiting" class="fas fa-spinner fa-spin"></i>
                            <i v-else :class="playing ? 'fas fa-pause' : 'fas fa-play'"></i>
                        </button>
                        <button class="control-btn" :title="t('xia-yi-shou')" @click="playSongFromQueue('next')">
                            <i class="fas fa-step-forward"></i>
                        </button>
                        <div class="playback-mode">
                            <button class="control-btn" :title="t('qie-huan-bo-fang-mo-shi')" @click="togglePlaybackMode">
                                <i v-if="currentPlaybackModeIndex != '2'" :class="currentPlaybackMode.icon" :title="currentPlaybackMode.title"></i>
                                <span v-else class="loop-icon" :title="currentPlaybackMode.title">
                                    <i class="fas fa-repeat"></i>
                                    <sup>1</sup>
                                </span>
                            </button>
                            <div class="player-menu playback-mode-menu">
                                <button
                                    v-for="(mode, index) in playbackModes"
                                    :key="mode.title"
                                    type="button"
                                    class="player-menu-item playback-mode-option"
                                    :class="{ active: currentPlaybackModeIndex === index }"
                                    :title="mode.title"
                                    @click.stop="setPlaybackMode(index)"
                                >
                                    <i v-if="index !== 2" :class="mode.icon"></i>
                                    <span v-else class="loop-icon">
                                        <i class="fas fa-repeat"></i>
                                        <sup>1</sup>
                                    </span>
                                    <span>{{ mode.title }}</span>
                                </button>
                            </div>
                        </div>
                    </div>
                </div>
                <div id="lyrics-container" @wheel="handleLyricsWheel" @pointerdown="onLyricsPointerDown"
                    @pointermove="onLyricsPointerMove" @pointerup="onLyricsPointerEnd"
                    @pointercancel="onLyricsPointerEnd">
                    <template v-if="lyricsData.length > 0">
                        <div v-if="lyricsDisplayMode === 'single'" id="lyrics" class="single-lyrics" :class="{ 'line-highlight-mode': lyricsHighlightMode === 'line', 'lyrics-align-left': lyricsAlign === 'left' }"
                            :style="{ fontSize: lyricsFontSize, fontFamily: lyricsFontFamily }">
                            <transition name="single-lyric-fade" appear>
                                <div class="line-group" v-if="currentSingleLyricsLine" :key="singleLyricsLineIndex">
                                <div class="line" @click="onLyricsLineClick(singleLyricsLineIndex)" :class="{ click: lyricsFlag, 'line-highlight': isCurrentLyricsLine(singleLyricsLineIndex), [lyricsAlign]: true }">
                                    <span v-for="(charData, charIndex) in currentSingleLyricsLine.characters" :key="charIndex" class="char">
                                        {{ charData.char }}
                                    </span>
                                </div>
                                    <div class="line translated" :class="{ 'line-highlight': isCurrentLyricsLine(singleLyricsLineIndex), [lyricsAlign]: true }" v-show="currentSingleLyricsLine.translated && lyricsMode === 'translation'">{{ currentSingleLyricsLine.translated }}</div>
                                    <div class="line romanized" :class="{ 'line-highlight': isCurrentLyricsLine(singleLyricsLineIndex), [lyricsAlign]: true }" v-show="currentSingleLyricsLine.romanized && lyricsMode === 'romanization'">{{ currentSingleLyricsLine.romanized }}</div>
                                </div>
                            </transition>
                        </div>
                        <div v-else id="lyrics" :class="{ 'line-highlight-mode': lyricsHighlightMode === 'line', 'lyrics-align-left': lyricsAlign === 'left' }"
                            :style="{ fontSize: lyricsFontSize, fontFamily: lyricsFontFamily, transform: `translateY(${scrollAmount ? scrollAmount + 'px' : '50%'})` }">
                            <div class="line-group" :class="{ 'current-line-group': currentLyricsLineIndex === lineIndex }" v-for="(lineData, lineIndex) in lyricsData" :key="lineIndex">
                                <div class="line" @click="onLyricsLineClick(lineIndex)" :class="{ click: lyricsFlag, 'line-highlight': isCurrentLyricsLine(lineIndex), [lyricsAlign]: true }">
                                    <span v-for="(charData, charIndex) in lineData.characters" :key="charIndex" class="char">
                                        {{ charData.char }}
                                    </span>
                                </div>
                                <div class="line translated" :class="{ 'line-highlight': isCurrentLyricsLine(lineIndex), [lyricsAlign]: true }" v-show="lineData.translated && lyricsMode === 'translation'">{{ lineData.translated }}</div>
                                <div class="line romanized" :class="{ 'line-highlight': isCurrentLyricsLine(lineIndex), [lyricsAlign]: true }" v-show="lineData.romanized && lyricsMode === 'romanization'">{{ lineData.romanized }}</div>
                            </div>
                        </div>
                    </template>
                    <div v-else class="no-lyrics" :style="{ fontFamily: lyricsFontFamily }">{{ SongTips }}</div>
                </div>
            </div>
        </div>
    </transition>

    <!-- 歌单选择模态框 -->
    <PlaylistSelectModal ref="playlistSelect" :current-song="currentSong" :playlists="playlists" />

</template>

<script setup>
import { ref, computed, onMounted, onUnmounted, watch, nextTick } from 'vue';
import { useMusicQueueStore } from '../stores/musicQueue';
import { useListenTimeStore } from '../stores/listenTime';
import { MoeAuthStore } from '../stores/store';
import { useI18n } from 'vue-i18n';
import PlaylistSelectModal from './PlaylistSelectModal.vue';
import QueueList from './QueueList.vue';
import FullscreenLyricsSettings from './FullscreenLyricsSettings.vue';
import { useRouter } from 'vue-router';
import { getCover, getAudioOutputDeviceSignature, share } from '../utils/utils';
import { get } from '../utils/request';
import { isNetworkFailure, isOffline } from '../utils/networkError';
import { resolveCachedSong, cacheAudioFromUrl, invalidateCachedSong, releaseObjectUrl, releaseAllObjectUrls } from '../utils/audioCache';
import { isNativePlayback, nativePlayback, configureNativePlayback, toNativeSong, readPlayerSetting } from '../utils/nativePlayback';
import useNativeAudioController from './player/NativeAudioController';
import { createPlaybackIntent } from './player/PlaybackIntent';
import { createTeamEventPopup, actions as teamEventActions } from '@/utils/teamEvent';

// 从统一入口导入所有模块
import {
    useAudioController,
    useLyricsHandler,
    useProgressBar,
    usePlaybackMode,
    useMediaSession,
    useSongQueue,
    useHelpers,
    setAudioOutputDevice
} from './player';

// 基础设置
const queueList = ref(null);
const playlistSelect = ref(null);
const qualityMenuOpen = ref(false);
const { t } = useI18n();
const router = useRouter();
const nativePlayer = isNativePlayback();
const playbackIntent = createPlaybackIntent();
const musicQueueStore = useMusicQueueStore();
const listenTimeStore = useListenTimeStore();
const playlists = ref([]);
const currentTime = ref(0);
const fullscreenLyricsDefaultSettings = {
    background: 'on',
    fontSize: '24px',
    align: 'center',
    highlightMode: 'char',
    displayMode: 'scroll'
};
const fullscreenLyricsSettings = ref({ ...fullscreenLyricsDefaultSettings });
const lyricsFontSize = computed(() => fullscreenLyricsSettings.value.fontSize);
const desktopLyricsFont = ref('');
const fontFamilyFallback = "-apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Microsoft YaHei', sans-serif";
const escapeFontFamily = (fontFamily) => String(fontFamily).replace(/\\/g, '\\\\').replace(/"/g, '\\"');
const lyricsFontFamily = computed(() => {
    return desktopLyricsFont.value
        ? `"${escapeFontFamily(desktopLyricsFont.value)}", ${fontFamilyFallback}`
        : fontFamilyFallback;
});
const lyricsAlign = computed(() => fullscreenLyricsSettings.value.align);
const lyricsBackground = computed(() => fullscreenLyricsSettings.value.background);
const lyricsHighlightMode = computed(() => fullscreenLyricsSettings.value.highlightMode || 'char');
const lyricsDisplayMode = computed(() => fullscreenLyricsSettings.value.displayMode || 'scroll');
const lyricsDefaultBackgroundImage = computed(() => currentSong.value?.img || 'https://random.MoeJue.cn/randbg');
const lyricsCoverImages = ref([]);
const lyricsBackgroundImageIndex = ref(0);
const lyricsBackgroundImages = computed(() => {
    if (lyricsBackground.value !== 'cover') return [];
    return lyricsCoverImages.value.length ? lyricsCoverImages.value : [lyricsDefaultBackgroundImage.value];
});
const lyricsBackgroundStyle = computed(() => {
    if (lyricsBackground.value === 'off' || lyricsBackground.value === 'cover') {
        return { background: 'var(--secondary-color)' };
    }

    return { backgroundImage: `url(${lyricsDefaultBackgroundImage.value})` };
});
const sliderElement = ref(null);
const coverMode = ref(localStorage.getItem('lyrics-cover-mode') || 'square');

const isDragging = ref(false);
const lyricsFlag = ref(false);

// 平板以下（手机）：隐藏歌曲信息、音量收进“更多”菜单
const MOBILE_QUERY = '(max-width: 768px)';
let mobileQuery = null;
const isMobile = ref(typeof window !== 'undefined' && window.matchMedia(MOBILE_QUERY).matches);
const onMobileQueryChange = (e) => {
    isMobile.value = e.matches;
};

// 辅助函数
const { isElectron, throttle, getVip, desktopLyrics } = useHelpers(t);

// Easter Egg 相关
const easterEggImages = [
    { src: './assets/images/miku.png', class: 'miku' },
    { src: './assets/images/miku2.png', class: 'miku2' },
    { src: './assets/images/miku3.png', class: 'miku3' }
];

const easterEggImage = computed(() => {
    const author = currentSong.value?.author || '';
    if (author.includes('初音') || author.includes('Miku')) {
        const randomIndex = Math.floor(Math.random() * easterEggImages.length);
        return easterEggImages[randomIndex];
    }
    return null;
});

const easterEggClass = computed(() => easterEggImage.value?.class || '');

// 歌名跑马灯：只有文字真的超出可视宽度时才滚动。
// 位移量取「文字宽 - 容器宽 + 一点留白」，让末尾字符能完整露出来。
const songTitleRef = ref(null);
const titleOverflowPx = ref(0);
const titleOverflow = computed(() => titleOverflowPx.value > 0);
const measureTitleOverflow = () => {
    const el = songTitleRef.value;
    if (!el) return;
    // 量的是内部文字节点，不是容器：容器本身受 max-width 约束永远不溢出
    const textEl = el.querySelector('.song-title-text');
    const textWidth = textEl?.scrollWidth || textEl?.getBoundingClientRect().width || 0;
    const boxWidth = el.clientWidth;
    const overflow = Math.ceil(textWidth - boxWidth);
    // 位移 = 溢出量 + 与第二段之间的 32px 间距 + 16px 尾部留白，
    // 少算间距会让循环接缝处跳一下。
    titleOverflowPx.value = overflow > 1 ? overflow + 32 + 16 : 0;
};

const canSwitchQuality = computed(() => {
    return !!currentSong.value?.hash && !currentSong.value?.isLocal && !currentSong.value?.isCloud;
});
const isCurrentQualityOption = (option) => {
    return currentSong.value?.resolvedQuality === option.value && currentSong.value?.playHash === option.hash;
};
const toggleQualityMenu = () => {
    if (!canSwitchQuality.value) return;

    qualityMenuOpen.value = !qualityMenuOpen.value;
};
const switchQuality = async (option) => {
    qualityMenuOpen.value = false;
    if (!canSwitchQuality.value || isCurrentQualityOption(option)) return;
    await requestSongPlayback(currentSong.value, {
        position: audio.currentTime || 0,
        autoPlay: playing.value,
        quality: option.value
    });
};

// 初始化事件回调
const onSongEnd = () => {
    if (nativePlayer) return; // Native service advances even when this view is gone.
    tickListenSession(false);
    settleListenSession('end');
    if (currentPlaybackModeIndex.value === 2) {
        void requestSongPlayback(currentSong.value);
        return;
    }
    if (currentPlaybackModeIndex.value === 3) {
        const index = musicQueueStore.queue.findIndex(song => song.hash === currentSong.value.hash);
        if (index === musicQueueStore.queue.length - 1) {
            playing.value = false;
            return;
        }
    }
    void playSongFromQueue('next');
};

// 用于记录上次发送的歌词，避免重复发送
let lastSentLyric = '';
let lastSentTime = 0;

// 节流处理的时间更新函数
const updateCurrentTime = throttle(() => {
    currentTime.value = audio.currentTime;
    if (!isProgressDragging.value) {
        progressWidth.value = (currentTime.value / audio.duration) * 100;
    }

    // 更新SMTC位置状态
    if (audio.duration && currentSong.value?.hash) {
        mediaSession.updatePositionState(audio.currentTime, audio.duration, currentSpeed.value);
    }

    const savedConfig = readPlayerSetting('settings', {});
    const hasLyricsData = Array.isArray(lyricsData.value) && lyricsData.value.length > 0;
    
    const statusBarLyricsEnabled = savedConfig?.statusBarLyrics === 'on';
    const desktopLyricsEnabled = savedConfig?.desktopLyrics === 'on';

    if (audio) {
        // 歌词染色不再跟在 200ms 的 timeupdate 后面走，改由独立 rAF 循环驱动
        // （见 syncLyricsFrame），保证跟音频逐帧对齐。
        if (hasLyricsData) {
            scheduleLyricsFrame();
        }

        // 只在有歌曲且正在播放时才发送 IPC
        if (isElectron() && audio.src && playing.value && (desktopLyricsEnabled || statusBarLyricsEnabled)) {
            const currentLine = hasLyricsData ? getCurrentLineText(audio.currentTime) : '';
            
            // 只有歌词真正变化时才发送（防抖）
            const currentTimeMs = Date.now();
            if (currentLine !== lastSentLyric || currentTimeMs - lastSentTime > 1000) {
                lastSentLyric = currentLine;
                lastSentTime = currentTimeMs;
                
                // 使用 JSON 序列化确保对象可以被克隆
                try {
                    const lyricsPayload = hasLyricsData ? JSON.parse(JSON.stringify(lyricsData.value)) : [];
                    window.electron.ipcRenderer.send('lyrics-data', {
                        currentTime: audio.currentTime,
                        playing: playing.value,
                        lyricsData: lyricsPayload,
                        currentSongHash: currentSong.value?.hash || '',
                        currentLyric: currentLine
                    });
                } catch (e) {
                    // 如果序列化失败，只发送必要的数据
                    window.electron.ipcRenderer.send('lyrics-data', {
                        currentTime: audio.currentTime,
                        playing: playing.value,
                        lyricsData: [],
                        currentSongHash: currentSong.value?.hash || '',
                        currentLyric: currentLine
                    });
                }
            }
        }
        
        if (isElectron() && audio.src && playing.value && savedConfig?.apiMode === 'on') {
            try {
                const serverLyricsPayload = hasLyricsData && originalLyrics.value ? JSON.parse(JSON.stringify(originalLyrics.value)) : [];
                const currentSongPayload = currentSong.value ? JSON.parse(JSON.stringify(currentSong.value)) : null;
                window.electron.ipcRenderer.send('server-lyrics', {
                    currentTime: audio.currentTime,
                    lyricsData: serverLyricsPayload,
                    currentSong: currentSongPayload,
                    duration: audio.duration
                });
            } catch (e) {
                // 序列化失败时跳过
            }
        }
        
        if (isElectron() && audio.src && playing.value && window.electron.platform == 'darwin' && savedConfig?.touchBar == 'on') {
            const currentLine = hasLyricsData ? getCurrentLineText(audio.currentTime) : '';
            window.electron.ipcRenderer.send("update-current-lyrics", currentLine);
        }
    }

    if (!hasLyricsData && isElectron() && (desktopLyricsEnabled || statusBarLyricsEnabled || savedConfig?.apiMode === 'on')) {
        retryMissingLyrics();
    }

    localStorage.setItem('player_progress', audio.currentTime);
}, 200);

// 初始化各个模块
const audioController = nativePlayer
    ? useNativeAudioController({ updateCurrentTime, onState: applyNativeState })
    : useAudioController({ onSongEnd, updateCurrentTime });
const { playing, isMuted, volume, changeVolume, audio, playbackRate, setPlaybackRate, applyLoudnessNormalization, ensureAudioContextRunning, toggleLoudnessNormalization, loudnessNormalizationEnabled, currentLoudnessGain, webAudioInitialized, amplitudeToSlider } = audioController;
const volumePercentText = computed(() => `${Math.round(volume.value)}%`);
const volumeValueStyle = computed(() => {
    const percent = Math.round(volume.value);
    const offset = percent < 16 ? 0 : percent > 84 ? -100 : -50;
    const arrowOffset = percent < 16 ? '10px' : percent > 84 ? 'calc(100% - 10px)' : '50%';

    return {
        left: `${percent}%`,
        '--volume-value-offset': `${offset}%`,
        '--volume-arrow-offset': arrowOffset
    };
});

const lyricsHandler = useLyricsHandler(t);
const { lyricsData, originalLyrics, showLyrics, scrollAmount, SongTips, lyricsMode, toggleLyrics, getLyrics, highlightCurrentChar, resetLyricsHighlight, getCurrentLineText, scrollToCurrentLine, findActiveLineIndex, countHighlightedChars, toggleLyricsMode } = lyricsHandler;

// 当前行索引由歌词 rAF 循环驱动（见 syncLyricsFrame）。
// 不能用 currentTime 的 computed：那只有 5Hz，行切换会明显滞后于声音。
const currentLyricsLineIndex = ref(-1);

const singleLyricsLineIndex = computed(() => {
    if (!lyricsData.value || lyricsData.value.length === 0) return -1;
    return currentLyricsLineIndex.value >= 0 ? currentLyricsLineIndex.value : 0;
});
const currentSingleLyricsLine = computed(() => {
    if (singleLyricsLineIndex.value < 0) return null;
    return lyricsData.value[singleLyricsLineIndex.value] || null;
});
const isCurrentLyricsLine = (lineIndex) => lyricsHighlightMode.value === 'line' && currentLyricsLineIndex.value === lineIndex;

// ---- 逐字染色：rAF + 直接改 DOM ----
// 为什么不用响应式：整首歌词动辄上千个 .char，任何一次 charData 改写都会让
// 这个 2400 行的组件整体重渲染，5Hz 时代就已经在抖，提到 60Hz 会直接卡死。
// 这里只碰"当前这一行正在变化的几个 span"，Vue 完全不参与，也不产生代理写入。
let lyricsFrameId = 0;
let lyricsSpanCache = { lineIndex: -1, spans: [] };
let lyricsAppliedCount = 0;

const clearLyricsSpanCache = () => {
    lyricsSpanCache = { lineIndex: -1, spans: [] };
    lyricsAppliedCount = 0;
};

// 滚动边界缓存：必须在这里声明，下面的 watch(immediate) 会在 setup 期间就调用到它
let lyricsBounds = null; // { min, max }
let lyricsBoundsDirty = true;
const markLyricsBoundsDirty = () => { lyricsBoundsDirty = true; };

// 把某一行的已亮字数从 applied 改成 target，只动差值范围内的 span
const applyCharHighlight = (lineIndex, target) => {
    const root = document.getElementById('lyrics');
    if (!root) return;

    if (lyricsHighlightMode.value !== 'char') {
        // 行模式由 CSS 负责，确保逐字状态被清掉不会残留
        if (lyricsSpanCache.spans.length) {
            for (const span of lyricsSpanCache.spans) span.classList.remove('highlight');
            lyricsSpanCache = { lineIndex: -1, spans: [] };
            lyricsAppliedCount = 0;
        }
        return;
    }

    // 单行模式只挂载一行，且换行时整块重建，缓存没有意义，直接整体重设
    if (lyricsDisplayMode.value === 'single') {
        if (lyricsSpanCache.lineIndex !== lineIndex) {
            const lineEl = root.querySelector('.line-group');
            lyricsSpanCache = {
                lineIndex,
                spans: lineEl ? Array.from(lineEl.querySelectorAll('.char')) : []
            };
            lyricsAppliedCount = -1; // 强制重建
        }
        for (let i = 0; i < lyricsSpanCache.spans.length; i++) {
            lyricsSpanCache.spans[i].classList.toggle('highlight', i < target);
        }
        lyricsAppliedCount = target;
        return;
    }

    if (lyricsSpanCache.lineIndex !== lineIndex) {
        // 换行：先清掉上一行残留，再取新行的 span
        if (lyricsSpanCache.spans.length) {
            for (const span of lyricsSpanCache.spans) span.classList.remove('highlight');
        }
        const lineEl = root.querySelectorAll('.line-group')[lineIndex];
        const spans = lineEl ? Array.from(lineEl.querySelectorAll('.line .char')) : [];
        lyricsSpanCache = { lineIndex, spans };
        lyricsAppliedCount = 0;
    }

    const spans = lyricsSpanCache.spans;
    if (!spans.length) return;

    if (target === lyricsAppliedCount) return;

    if (target > lyricsAppliedCount) {
        for (let i = Math.max(0, lyricsAppliedCount); i < target && i < spans.length; i++) {
            spans[i].classList.add('highlight');
        }
    } else {
        for (let i = Math.max(0, target); i < lyricsAppliedCount && i < spans.length; i++) {
            spans[i].classList.remove('highlight');
        }
    }
    lyricsAppliedCount = Math.min(target, spans.length);
};

const syncLyricsFrame = () => {
    lyricsFrameId = 0;

    if (!showLyrics.value || !lyricsData.value?.length) return;

    const timeMs = audio.currentTime * 1000;
    const lineIndex = findActiveLineIndex(timeMs);

    if (lineIndex !== currentLyricsLineIndex.value) {
        currentLyricsLineIndex.value = lineIndex;
        // 用户正在翻阅时不要抢滚动位置
        if (lineIndex >= 0 && !lyricsFlag.value) scrollToCurrentLine(lineIndex);
    }

    if (lineIndex >= 0) {
        applyCharHighlight(lineIndex, countHighlightedChars(lineIndex, timeMs));
    }

    scheduleLyricsFrame();
};

const scheduleLyricsFrame = () => {
    if (lyricsFrameId) return;
    if (typeof requestAnimationFrame !== 'function') return;
    lyricsFrameId = requestAnimationFrame(syncLyricsFrame);
};

const stopLyricsFrame = () => {
    if (lyricsFrameId) {
        cancelAnimationFrame(lyricsFrameId);
        lyricsFrameId = 0;
    }
    clearLyricsSpanCache();
};

// 歌词数据/显示状态一变，span 缓存立刻失效
watch([lyricsData, showLyrics, () => lyricsHighlightMode.value, () => lyricsDisplayMode.value], () => {
    clearLyricsSpanCache();
    markLyricsBoundsDirty();
    stopLyricsFrame();
    scheduleLyricsFrame();
}, { immediate: true });


// 获取当前播放时间的歌词行索引
const getCurrentLineIndex = (currentTime) => {
    if (!lyricsData.value || lyricsData.value.length === 0) return -1;
    for (let i = 0; i < lyricsData.value.length; i++) {
        const line = lyricsData.value[i];
        if (line.characters && line.characters.length > 0) {
            const startTime = line.characters[0].startTime / 1000;
            if (startTime > currentTime) {
                return Math.max(0, i - 1);
            }
        }
    }
    return lyricsData.value.length - 1;
};

const progressBar = useProgressBar(audio, resetLyricsHighlight);
const { progressWidth, isProgressDragging, showTimeTooltip, tooltipPosition, tooltipTime, climaxPoints, formatTime, getMusicHighlights, clearMusicHighlights, onProgressDragStart, updateTimeTooltip, hideTimeTooltip } = progressBar;

const playbackMode = usePlaybackMode(t, audio);
const { playbackModes, currentPlaybackModeIndex, currentPlaybackMode, playedSongsStack, currentStackIndex, togglePlaybackMode, setPlaybackMode } = playbackMode;

const mediaSession = useMediaSession();

const songQueue = useSongQueue(t, musicQueueStore, queueList);
const { currentSong, NextSong, addSongToQueue, addCloudMusicToQueue, addLocalMusicToQueue, addLocalPlaylistToQueue, addToNext, getPlaylistAllSongs, addPlaylistToQueue, addCloudPlaylistToQueue, restoreLocalSongCover } = songQueue;

const resetTrackTimeline = () => {
    currentTime.value = 0;
    progressWidth.value = 0;
    clearMusicHighlights();
    resetLyricsHighlight(0);
    localStorage.setItem('player_progress', '0');
};

watch(
    () => currentSong.value?.hash || '',
    (hash, previousHash) => {
        if (previousHash && hash !== previousHash) resetTrackTimeline();
    }
);

let lyricsBackgroundCarouselTimer = null;
let lyricsCoverImagesRequestId = 0;

const normalizeLyricsCoverImageUrl = (url) => {
    if (typeof url !== 'string' || !/^https?:\/\//.test(url)) return '';
    return url.replace(/\{size\}/g, '1080');
};

const collectLyricsCoverImageUrls = (imgs) => {
    const urls = [];
    const visit = (value) => {
        if (!value) return;

        if (Array.isArray(value)) {
            value.forEach(visit);
            return;
        }

        if (typeof value === 'object') {
            const url = normalizeLyricsCoverImageUrl(
                value.sizable_portrait ||
                value.sizable_cover ||
                value.url ||
                value.img ||
                value.cover ||
                value.portrait
            );
            if (url) {
                urls.push(url);
                return;
            }

            Object.values(value).forEach(visit);
        }
    };

    visit(imgs);
    return urls;
};

const extractLyricsCoverImages = (response) => {
    const images = [];
    const groups = Array.isArray(response?.data) ? response.data : [];

    groups.forEach((group) => {
        const authors = Array.isArray(group?.author) ? group.author : [];
        const albums = Array.isArray(group?.album) ? group.album : [];

        authors.forEach((author) => {
            images.push(...collectLyricsCoverImageUrls(author?.imgs));
        });
        albums.forEach((album) => {
            images.push(...collectLyricsCoverImageUrls(album?.imgs));
        });
    });

    return [...new Set(images)];
};

const stopLyricsBackgroundCarousel = () => {
    if (lyricsBackgroundCarouselTimer) {
        clearInterval(lyricsBackgroundCarouselTimer);
        lyricsBackgroundCarouselTimer = null;
    }
};

const startLyricsBackgroundCarousel = () => {
    stopLyricsBackgroundCarousel();
    lyricsBackgroundImageIndex.value = 0;

    if (lyricsBackground.value !== 'cover' || !showLyrics.value || lyricsBackgroundImages.value.length <= 1) return;

    lyricsBackgroundCarouselTimer = setInterval(() => {
        const total = lyricsBackgroundImages.value.length;
        if (total <= 1) {
            stopLyricsBackgroundCarousel();
            return;
        }
        lyricsBackgroundImageIndex.value = (lyricsBackgroundImageIndex.value + 1) % total;
    }, 4000);
};

const loadLyricsCoverImages = async () => {
    const hash = currentSong.value?.hash;
    lyricsCoverImages.value = [];
    lyricsBackgroundImageIndex.value = 0;

    if (lyricsBackground.value !== 'cover' || !showLyrics.value || !hash || String(hash).startsWith('local_')) {
        startLyricsBackgroundCarousel();
        return;
    }

    const requestId = ++lyricsCoverImagesRequestId;
    try {
        const response = await get(`/images?hash=${encodeURIComponent(hash)}`);
        if (requestId !== lyricsCoverImagesRequestId || currentSong.value?.hash !== hash || lyricsBackground.value !== 'cover') return;

        lyricsCoverImages.value = extractLyricsCoverImages(response);
    } catch (error) {
        console.warn('[PlayerControl] 获取歌词背景封面失败:', error);
        lyricsCoverImages.value = [];
    } finally {
        if (requestId === lyricsCoverImagesRequestId) startLyricsBackgroundCarousel();
    }
};

// 添加自动切换定时器引用
let autoSwitchTimer = null;
// 恢复歌词正常滚动计时器
let lyricScrollTimer = null;
// 自动切换计数器和最大重试次数
let autoSwitchCount = 0;
const maxAutoSwitchRetries = 5;

// 处理自动切换逻辑的函数
const handleAutoSwitch = () => {
    // 断网时切歌毫无意义：换一首照样拿不到地址，连切几首只会撞上次数上限弹「请手动选择歌曲」。
    // 这里是所有切歌路径的唯一收敛点，在此拦住，上游谁误报都无所谓。
    if (isOffline()) {
        console.log('[PlayerControl] 当前离线，不切歌，改为原地等待网络恢复');
        handlePlaybackFailure('离线状态下的切歌请求');
        return false;
    }

    console.log('[PlayerControl] 检查自动切换重试次数:', autoSwitchCount, '/', maxAutoSwitchRetries);
    if (autoSwitchCount < maxAutoSwitchRetries) {
        autoSwitchCount++;
        console.log(`[PlayerControl] 自动切换尝试 ${autoSwitchCount}/${maxAutoSwitchRetries}`);
        autoSwitchTimer = setTimeout(() => {
            playSongFromQueue('next');
        }, 3000);
        return true;
    } else {
        console.log('[PlayerControl] 已达到最大重试次数，停止自动切换');
        window.$modal.alert('已达到最大重试次数，请手动选择歌曲');
        autoSwitchCount = 0;
        return false;
    }
};

// 清除自动切换定时器的函数
const clearAutoSwitchTimer = () => {
    if (autoSwitchTimer) {
        console.log('[PlayerControl] 取消自动切换到下一首');
        clearTimeout(autoSwitchTimer);
        autoSwitchTimer = null;
    }
};

// ---- 播放失败重试（弱网关键路径）----
// 从四种各自放弃的路径收敛到一处，且**不设重试上限**：
// 网络类失败会一直重试直到成功，期间不切歌、不弹窗打断，
// 网络恢复（online 事件）时立即重试而不是干等退避。
// UI 上表现为「原地等待」：播放态停止、进度条停住、图标转圈，绝不假装在播。
// 唯一的例外是「这首歌唱不了」（shouldPlayNext，版权/风控）——那与网络无关，重试一万年也没用，仍然换歌。
const PLAYBACK_RETRY_BASE_DELAY = 1000;
const PLAYBACK_RETRY_MAX_DELAY = 30000; // 退避封顶，避免无限期高频请求
let playbackRetryAttempt = 0;
let playbackRetryTimer = null;
// 重试进行中：阻止「内部暂停」被当成用户暂停处理
let playbackRetrying = false;
// 等待网络/取地址中：UI 明确显示为「等待」，而不是假装在播
const playbackWaiting = ref(false);
// 待重试的音质降级提示（格式不支持时用）
let playbackRetryQuality = '';

const clearPlaybackRetryTimer = () => {
    if (playbackRetryTimer) {
        clearTimeout(playbackRetryTimer);
        playbackRetryTimer = null;
    }
};

// 播放真正成功（play 事件）/ 换歌 / 用户主动暂停时清零
const resetPlaybackRetry = () => {
    playbackRetryAttempt = 0;
    playbackRetrying = false;
    playbackWaiting.value = false;
    playbackRetryQuality = '';
    clearPlaybackRetryTimer();
};

/**
 * 统一的播放失败处理：一直重试，不放弃，网络恢复即继续
 * @param {string} reason 失败原因，仅用于日志
 * @param {Object} [options]
 * @param {string} [options.quality] 重试时指定音质（格式不支持时降级用）
 */
const handlePlaybackFailure = (reason, { quality = '' } = {}) => {
    if (nativePlayer || playbackRetryTimer) return;
    const song = requestedSong || currentSong.value;
    if (!song?.hash) return;
    if (quality) playbackRetryQuality = quality;
    const token = playbackIntent.current();
    const resumeAt = audio.currentTime || 0;
    playbackRetrying = true;
    playbackWaiting.value = true;
    playing.value = false;
    audio.pause();
    if (isOffline()) return; // Resume from the online event, not a background polling loop.
    if (++playbackRetryAttempt > 6) {
        resetPlaybackRetry();
        window.$message?.error?.('播放失败，请检查网络后重试');
        return;
    }
    const delay = Math.min(PLAYBACK_RETRY_BASE_DELAY * 2 ** (playbackRetryAttempt - 1), PLAYBACK_RETRY_MAX_DELAY);
    console.warn(`[PlayerControl] ${reason}，等待重试 ${playbackRetryAttempt}/6`);
    playbackRetryTimer = setTimeout(async () => {
        playbackRetryTimer = null;
        if (!playbackIntent.isCurrent(token)) return;
        try {
            const result = await resolvePlaybackSource(song, { quality: playbackRetryQuality, token });
            if (!playbackIntent.isCurrent(token) || result?.stale) return;
            if (result?.song) {
                await playSong(result.song, { token, position: resumeAt });
                return;
            }
            if (result?.shouldPlayNext) {
                resetPlaybackRetry();
                handleAutoSwitch();
                return;
            }
            handlePlaybackFailure('重新获取播放来源失败');
        } catch {
            if (playbackIntent.isCurrent(token)) handlePlaybackFailure('重新获取播放来源失败');
        }
    }, delay);
};

// 网络恢复：跳过剩余退避，立刻重试
const retryPlaybackNow = () => {
    if (!playbackRetrying) return;
    console.log('[PlayerControl] 网络已恢复，立即重试播放');
    // 重置退避阶梯，让恢复后的首次尝试用最短等待
    playbackRetryAttempt = 0;
    clearPlaybackRetryTimer();
    void handlePlaybackFailure('网络恢复');
};

const handleNetworkOffline = () => {
    if (playbackRetrying) {
        console.log('[PlayerControl] 网络已断开，保持重试等待');
    }
};

// 恢复歌词正常滚动的节流函数
const restoreLyricsScroll = throttle(() => {
    if (lyricScrollTimer) clearTimeout(lyricScrollTimer);
    lyricScrollTimer = setTimeout(() => {
        console.log('[PlayerControl] 恢复歌词正常滚动');
        lyricScrollTimer = null;
        lyricsFlag.value = false;
        // 这里原来传的是歌词文本（getCurrentLineText 返回字符串），
        // scrollToCurrentLine 要的是行索引，所以回正一直是空转。改用索引。
        const lineIndex = findActiveLineIndex(audio.currentTime * 1000);
        if (lineIndex >= 0) scrollToCurrentLine(lineIndex, true);
    }, 5000);
}, 1000);

// 获取歌词的节流函数
let isLyrics;
let pendingLyricsHash = '';
let pendingLyricsPromise = null;
let lastLyricsRetryAt = 0;
const getCurrentLyrics = async () => {
    const hash = currentSong.value.hash;
    if (!hash) return false;
    if (String(hash).startsWith('local_')) {
        SongTips.value = t('zan-wu-ge-ci');
        return false;
    }

    if (pendingLyricsHash === hash && pendingLyricsPromise) {
        return pendingLyricsPromise;
    }

    pendingLyricsHash = hash;
    pendingLyricsPromise = (async () => {
        isLyrics = await getLyrics(hash);
        return isLyrics;
    })();

    try {
        return await pendingLyricsPromise;
    } finally {
        if (pendingLyricsHash === hash) {
            pendingLyricsHash = '';
            pendingLyricsPromise = null;
        }
    }
};
const retryMissingLyrics = () => {
    if (isLyrics === false || pendingLyricsPromise) return;

    const now = Date.now();
    if (now - lastLyricsRetryAt < 1000) return;

    lastLyricsRetryAt = now;
    getCurrentLyrics();
};

// 计算属性
const formattedCurrentTime = computed(() => formatTime(currentTime.value));
const formattedDuration = computed(() => formatTime(currentSong.value?.timeLength || 0));

watch(() => currentSong.value.hash, () => {
    qualityMenuOpen.value = false;
    loadLyricsCoverImages();
});

// 歌名变化或容器宽度变化（旋转屏幕 / 断点切换）都要重新判断是否需要滚动
watch(
    () => [currentSong.value.name, currentSong.value.qualityLabel, isMobile.value],
    () => nextTick(measureTitleOverflow),
    { immediate: true }
);

watch(() => [lyricsBackground.value, showLyrics.value], () => {
    if (lyricsBackground.value === 'cover' && showLyrics.value) {
        loadLyricsCoverImages();
        return;
    }

    lyricsCoverImagesRequestId++;
    lyricsCoverImages.value = [];
    lyricsBackgroundImageIndex.value = 0;
    stopLyricsBackgroundCarousel();
});

// 判断是否有多种歌词模式（同时有翻译和音译）
const hasMultiLyricsMode = computed(() => {
    if (!lyricsData.value || lyricsData.value.length === 0) return false;
    
    // 检查是否至少有一行同时包含翻译和音译
    return lyricsData.value.some(line => line.translated && line.romanized);
});

const handleFullscreenLyricsSettingsChange = () => {
    currentTime.value = audio.currentTime || 0;
    resetLyricsHighlight(currentTime.value);
    if (lyricsDisplayMode.value !== 'scroll') return;

    if (typeof requestAnimationFrame === 'function') {
        requestAnimationFrame(() => {
            const currentLineIndex = getCurrentLineIndex(audio.currentTime);
            if (currentLineIndex >= 0) scrollToCurrentLine(currentLineIndex);
        });
    }
};

// 切换歌词显示模式（翻译/音译）
const switchLyricsMode = () => {
    toggleLyricsMode();
};

// 切换封面模式（正方形/唱片）
const toggleCoverMode = () => {
    coverMode.value = coverMode.value === 'square' ? 'vinyl' : 'square';
    localStorage.setItem('lyrics-cover-mode', coverMode.value);
};

const isBlobUrl = (url) => typeof url === 'string' && url.startsWith('blob:');

// 当前正在使用的缓存 blob URL（切歌后释放，防内存泄漏）
let currentCacheObjectUrl = null;
const releasePreviousCacheUrl = (nextUrl) => {
    if (currentCacheObjectUrl && currentCacheObjectUrl !== nextUrl) {
        releaseObjectUrl(currentCacheObjectUrl);
    }
    currentCacheObjectUrl = nextUrl || null;
};

const isLocalSong = (song) => !!song?.isLocal || String(song?.hash || '').startsWith('local_');

const toPlayerSong = (song) => {
    if (!isLocalSong(song)) return song;
    const { file, handle, ...playerSong } = song;
    return playerSong;
};

// ===== 听歌时长(CSCC)上报 =====
// 会话结构：{ hash, mixsongid, timeLength, accumulatedMs, lastTick, running }
let listenSession = null;
// 上报请求串行队列：保证同一首(及跨首)歌曲的 end/switch 先于下一次 start 到达服务器，避免乱序被拒
let listenReportChain = Promise.resolve();
// 两次上报之间的最小间隔(毫秒)：酷狗 CSCC 有频控，间隔过短会返回 errcode=1203 拒绝 start
const LISTEN_REPORT_MIN_INTERVAL = 2000;
let lastListenReportDoneAt = 0;
const enqueueListenReport = (task) => {
    listenReportChain = listenReportChain.then(async () => {
        // 与上一次上报完成时间拉开最小间隔，触发频控时静默失败不重试(API 侧要求不重复上报)
        const waitMs = LISTEN_REPORT_MIN_INTERVAL - (Date.now() - lastListenReportDoneAt);
        if (waitMs > 0) await new Promise((resolve) => setTimeout(resolve, waitMs));
        await task();
        lastListenReportDoneAt = Date.now();
    }).catch((error) => {
        lastListenReportDoneAt = Date.now();
        console.warn('[PlayerControl] 听歌时长上报队列执行出错:', error);
    });
    return listenReportChain;
};

// 仅上报酷狗在线歌曲且已登录的场景（需要 mixsongid）
const isListenReportableSong = (song) => {
    if (!song?.hash) return false;
    if (isLocalSong(song) || song.isCloud) return false;
    const mixsongid = Number(song.mixsongid);
    if (!Number.isSafeInteger(mixsongid) || mixsongid <= 0) return false;
    try {
        return !!MoeAuthStore().isAuthenticated;
    } catch (error) {
        return false;
    }
};

// 结算会话的播放中时间片段（开始累计/停止累计）
const tickListenSession = (isPlaying) => {
    if (!listenSession) return;
    if (isPlaying && !listenSession.running) {
        listenSession.running = true;
        listenSession.lastTick = Date.now();
    } else if (!isPlaying && listenSession.running) {
        listenSession.accumulatedMs += Date.now() - listenSession.lastTick;
        listenSession.running = false;
    }
};

// 结算并上报当前会话：end=自然完整播放, switch=被切歌等中断
const settleListenSession = (type) => {
    const session = listenSession;
    if (!session) return;
    listenSession = null;
    // 上报前补结算仍在播放中的时间片段
    const elapsedMs = session.accumulatedMs + (session.running ? Date.now() - session.lastTick : 0);
    let duration;
    if (type === 'end') {
        // 完整播放：按歌曲全长上报；若经历过单曲循环(loop 不触发 ended，圈数时长会持续累计)，取累计值以免丢失
        const fullSeconds = (Number.isFinite(audio.duration) && audio.duration > 0)
            ? audio.duration
            : (session.timeLength || 0);
        duration = Math.max(Math.round(fullSeconds * 1000), Math.round(elapsedMs));
    } else {
        // 中断播放：按实际聆听时长上报（含单曲循环各圈累计）
        duration = Math.round(elapsedMs);
    }
    if (!Number.isSafeInteger(duration) || duration <= 0) return;
    const mixsongid = session.mixsongid;
    enqueueListenReport(() => listenTimeStore.reportCsccEvent(type, {
        mixsongid,
        duration,
        diff_sec: Math.round(duration / 1000)
    }));
};

// 歌曲开始播放成功后调用：按中断结算上一首会话，并为新歌开启会话
const handleListenSessionOnSongStarted = (song) => {
    if (listenSession && listenSession.hash === song?.hash) {
        // 同一首歌重播（切音质/失败重试等），保留原会话不打断统计
        tickListenSession(!audio.paused);
        return;
    }
    settleListenSession('switch');
    if (!isListenReportableSong(song)) return;
    listenSession = {
        hash: song.hash,
        mixsongid: Number(song.mixsongid),
        timeLength: song.timeLength || 0,
        accumulatedMs: 0,
        lastTick: Date.now(),
        running: !audio.paused
    };
    const mixsongid = listenSession.mixsongid;
    // 入队串行执行：若存在前一首歌的 end/switch 上报，start 会在其完成后才发出
    enqueueListenReport(() => listenTimeStore.reportCsccEvent('start', { mixsongid }));
};

// 播放歌曲
let lastNativeRevision = -1;
let lastNativeError = '';
let requestedSong = null;

function applyNativeState(state) {
    if (state.revision !== lastNativeRevision) {
        musicQueueStore.queue = (state.queue || []).map((song, index) => ({ ...song, id: song.itemId || song.id || index + 1 }));
        lastNativeRevision = state.revision;
    }
    const next = state.currentSong || { name: '', author: '', hash: '', url: '' };
    const changed = currentSong.value.hash !== next.hash || currentSong.value.resolvedQuality !== next.resolvedQuality;
    currentSong.value = { ...next, timeLength: next.timeLength || state.duration * 1000 || 0 };
    currentPlaybackModeIndex.value = Number.isInteger(state.mode) ? state.mode : 1;
    currentSpeed.value = state.speed || 1;
    playbackWaiting.value = !!state.buffering;
    if (changed) {
        lyricsData.value = [];
        originalLyrics.value = '';
        isLyrics = undefined;
        clearMusicHighlights();
        document.title = next.name ? `${next.name} - ${next.author || 'MoeKoe Music'}` : 'MoeKoe Music';
        if (!isOffline()) void getCurrentLyrics();
    }
    if (state.error && String(state.error) !== lastNativeError) window.$message?.error?.(String(state.error));
    lastNativeError = state.error ? String(state.error) : '';
}

const cancelPendingPlayback = () => {
    playbackIntent.cancel();
    songQueue.cancelPending?.();
    clearAutoSwitchTimer();
    resetPlaybackRetry();
};

const resolvePlaybackSource = async (song, { quality = '', token } = {}) => {
    if (isLocalSong(song)) return addLocalMusicToQueue(song, false);
    const preferences = readPlayerSetting('settings', {});
    const query = { ...song, resolvedQuality: quality || song.resolvedQuality || preferences.quality || '128' };
    const cached = await resolveCachedSong(query, { allowFallback: !quality && isOffline() });
    if (!playbackIntent.isCurrent(token)) {
        if (cached) releaseObjectUrl(cached.url);
        return { stale: true };
    }
    if (cached) return { song: cached };
    if (isOffline()) return { error: true, offlineMiss: true };
    const result = song.isCloud
        ? await addCloudMusicToQueue(song.hash, song.name, song.author, song.timeLength, song.img, false)
        : await addSongToQueue(song.hash, song.name, song.img, song.author, false, quality, song.qualityOptions || []);
    if (!playbackIntent.isCurrent(token)) return { stale: true };
    if (result?.networkError && !quality) {
        const fallback = await resolveCachedSong(query, { allowFallback: true });
        if (!playbackIntent.isCurrent(token)) {
            if (fallback) releaseObjectUrl(fallback.url);
            return { stale: true };
        }
        if (fallback) return { song: fallback };
    }
    return result;
};

const requestSongPlayback = async (song, { position = 0, autoPlay = true, quality = '' } = {}) => {
    if (!song?.hash) return { error: true };
    cancelPendingPlayback();
    const token = playbackIntent.begin();
    requestedSong = { ...song };
    playbackWaiting.value = autoPlay;
    try {
        if (nativePlayer) {
            await configureNativePlayback();
            // Migrate a legacy WebView cache before delegating source selection to native.
            const cached = await resolveCachedSong({ ...song, resolvedQuality: quality || song.resolvedQuality }, { allowFallback: !quality && isOffline() });
            if (!playbackIntent.isCurrent(token)) return { stale: true };
            await nativePlayback.play({ song: toNativeSong({ ...song, ...cached, ...(quality ? { requestedQuality: quality, resolvedQuality: quality, playHash: song.qualityOptions?.find(option => option.value === quality)?.hash || song.hash, url: '' } : {}) }), position, autoPlay });
            return { song };
        }
        audio.pause();
        playing.value = false;
        const result = await resolvePlaybackSource(song, { quality, token });
        if (!playbackIntent.isCurrent(token) || result?.stale) {
            if (result?.song?.__fromCache) releaseObjectUrl(result.song.url);
            return { stale: true };
        }
        if (result?.song) {
            const ok = await playSong(result.song, { token, position, autoPlay });
            return ok ? result : { error: true };
        }
        playbackWaiting.value = false;
        currentSong.value = toPlayerSong(song);
        if (result?.offlineMiss) window.$message?.warning?.('该歌曲尚未完整缓存，无法离线播放');
        else if (result?.shouldPlayNext) handleAutoSwitch();
        else if (!result?.stale) handlePlaybackFailure('获取播放来源失败', { quality });
        return result || { error: true };
    } catch (error) {
        if (!playbackIntent.isCurrent(token)) return { stale: true };
        playbackWaiting.value = false;
        window.$message?.error?.(error?.message || t('bo-fang-shi-bai'));
        return { error: true };
    }
};
const playSong = async (song, { token = playbackIntent.current(), position = 0, autoPlay = true } = {}) => {
    if (!playbackIntent.isCurrent(token)) {
        if (song?.__fromCache) releaseObjectUrl(song.url);
        return false;
    }
    if (!song?.url) return false;
    try {
        resetTrackTimeline();
        currentSong.value = structuredClone(toPlayerSong(song));
        const index = musicQueueStore.queue.findIndex(item => item.hash === song.hash && !!item.isCloud === !!song.isCloud && !!item.isLocal === !!song.isLocal);
        if (index < 0) musicQueueStore.addSong({ ...song, id: `${song.isCloud ? 'cloud' : song.isLocal ? 'local' : 'online'}:${song.hash}` });
        else musicQueueStore.queue[index] = { ...musicQueueStore.queue[index], ...song, id: musicQueueStore.queue[index].id };
        applyLoudnessNormalization(song.loudnessNormalization || null);
        audio.src = song.url;
        releasePreviousCacheUrl(song.__fromCache ? song.url : null);
        await ensureAudioContextRunning();
        if (!playbackIntent.isCurrent(token)) return false;
        audio.playbackRate = currentSpeed.value;
        const restorePosition = () => {
            if (playbackIntent.isCurrent(token) && position > 0) audio.currentTime = Math.min(position, Number.isFinite(audio.duration) ? audio.duration : position);
        };
        if (audio.readyState >= 1) restorePosition();
        else audio.addEventListener('loadedmetadata', restorePosition, { once: true });
        mediaSession.changeMediaSession(currentSong.value);
        if (autoPlay) {
            await audio.play();
            if (!playbackIntent.isCurrent(token)) return false;
            playing.value = true;
            handleListenSessionOnSongStarted(currentSong.value);
            if (!isLocalSong(song) && !song.__fromCache) void cacheAudioFromUrl(song, song.url);
        }
        playbackWaiting.value = false;
        document.title = song.name ? `${song.name} - ${song.author || 'MoeKoe Music'}` : 'MoeKoe Music';
        lyricsData.value = [];
        originalLyrics.value = '';
        isLyrics = undefined;
        lastLyricsRetryAt = 0;
        const { file, handle, ...persistedSong } = toPlayerSong(currentSong.value);
        if (isBlobUrl(persistedSong.url) || isLocalSong(song)) persistedSong.url = '';
        if (isBlobUrl(persistedSong.img)) persistedSong.img = './assets/images/ico.png';
        localStorage.setItem('current_song', JSON.stringify(persistedSong));
        window.electron?.ipcRenderer?.send('current-song-updated');
        if (!isLocalSong(song) && !isOffline()) {
            void getCurrentLyrics();
            const hash = song.hash;
            void getMusicHighlights(hash, () => currentSong.value?.hash === hash);
        }
        return true;
    } catch (error) {
        if (!playbackIntent.isCurrent(token) || error?.name === 'AbortError') return false;
        playing.value = false;
        playbackWaiting.value = false;
        if (error?.name === 'NotAllowedError') window.$message?.warning?.('请点击播放按钮开始播放');
        else handlePlaybackFailure('音频播放失败');
        return false;
    }
};

// 恢复播放（不创建新 Audio，复用现有 src / 队列 URL 的恢复逻辑）
const resumePlayback = async () => {
    if (nativePlayer) {
        cancelPendingPlayback();
        try { await nativePlayback.resume(); return true; }
        catch (error) { window.$message?.error?.(error.message); return false; }
    }
    if (!audio.paused) return true;
    if (!currentSong.value.hash) {
        await playSongFromQueue('next');
        return false;
    }
    if (!audio.src || audio.error || audio.ended) {
        const saved = readPlayerSetting('player_progress', 0);
        const song = musicQueueStore.queue.find(item => item.hash === currentSong.value.hash) || currentSong.value;
        const result = await requestSongPlayback(song, { position: audio.ended ? 0 : Number(saved) || 0 });
        return !!result?.song;
    }
    cancelPendingPlayback();
    const token = playbackIntent.begin();
    try {
        await ensureAudioContextRunning();
        if (!playbackIntent.isCurrent(token)) return false;
        await audio.play();
        if (!playbackIntent.isCurrent(token)) return false;
        playing.value = true;
        handleListenSessionOnSongStarted(currentSong.value);
        return true;
    } catch (error) {
        if (!playbackIntent.isCurrent(token)) return false;
        if (error?.name === 'NotAllowedError') window.$message?.warning?.('请点击播放按钮开始播放');
        else handlePlaybackFailure('恢复播放失败');
        return false;
    }
};

// 切换播放/暂停（状态以 audio.paused 为准）
const togglePlayPause = async () => {
    if (audio.paused) {
        console.log('[PlayerControl] 开始播放');
        await resumePlayback();
    } else {
        console.log('[PlayerControl] 暂停播放');
        pausePlayback();
    }
};

// 从队列中播放歌曲
const playSongFromQueue = async (direction) => {
    if (nativePlayer) {
        cancelPendingPlayback();
        try { await nativePlayback[direction === 'previous' ? 'previous' : 'next'](); }
        catch (error) { window.$message?.error?.(error.message); }
        return;
    }
    const queue = musicQueueStore.queue;
    if (!queue.length) {
        window.$message?.info?.(t('ni-huan-mei-you-tian-jia-ge-quo-kuai-qu-tian-jia-ba'));
        return;
    }
    if (direction === 'next' && NextSong.value.length) {
        await requestSongPlayback(NextSong.value.shift());
        return;
    }
    const index = queue.findIndex(song => song.hash === currentSong.value.hash);
    let target;
    if (index < 0) target = 0;
    else if (currentPlaybackModeIndex.value === 0) target = handleRandomPlayback(direction, index);
    else if (currentPlaybackModeIndex.value === 3 && direction !== 'previous' && index === queue.length - 1) {
        pausePlayback();
        return;
    } else target = (index + (direction === 'previous' ? -1 : 1) + queue.length) % queue.length;
    await requestSongPlayback(queue[target]);
};

// 处理随机播放逻辑
const handleRandomPlayback = (direction, currentIndex) => {
    if (direction === 'previous' && currentStackIndex.value > 0) {
        // 返回上一首随机歌曲
        currentStackIndex.value--;
        return playedSongsStack.value[currentStackIndex.value];
    } else if (direction === 'previous') {
        // 向前随机一首新歌曲
        let newIndex;
        let attempts = 0;
        const maxAttempts = musicQueueStore.queue.length * 2; // 防止死循环
        
        do {
            newIndex = Math.floor(Math.random() * musicQueueStore.queue.length);
            attempts++;
            
            // 如果尝试次数过多，直接返回
            if (attempts >= maxAttempts) {
                break;
            }
        } while (playedSongsStack.value.length > 0 && 
                 (newIndex === playedSongsStack.value[currentStackIndex.value] ||
                  (musicQueueStore.queue.length >= 10 && playedSongsStack.value.length > 0 && 
                   playedSongsStack.value.slice(-Math.min(10, playedSongsStack.value.length)).includes(newIndex))));

        playedSongsStack.value.unshift(newIndex);
        return newIndex;
    } else if (direction === 'next' && currentStackIndex.value < playedSongsStack.value.length - 1) {
        // 前进到下一首已随机过的歌曲
        currentStackIndex.value++;
        return playedSongsStack.value[currentStackIndex.value];
    } else if (direction === 'next') {
        // 随机一首新歌曲
        let newIndex;
        let attempts = 0;
        const maxAttempts = musicQueueStore.queue.length * 2; // 防止死循环
        
        do {
            newIndex = Math.floor(Math.random() * musicQueueStore.queue.length);
            attempts++;
            
            // 如果尝试次数过多，直接返回
            if (attempts >= maxAttempts) {
                break;
            }
        } while (playedSongsStack.value.length > 0 && 
                 (newIndex === playedSongsStack.value[currentStackIndex.value] ||
                  (musicQueueStore.queue.length >= 10 && playedSongsStack.value.length > 0 && 
                   playedSongsStack.value.slice(-Math.min(10, playedSongsStack.value.length)).includes(newIndex))));

        // 截断未来的历史记录
        if (currentStackIndex.value < playedSongsStack.value.length - 1) {
            playedSongsStack.value = playedSongsStack.value.slice(0, currentStackIndex.value + 1);
        }

        // 添加新歌曲到历史记录
        playedSongsStack.value.push(newIndex);
        currentStackIndex.value = playedSongsStack.value.length - 1;
        return newIndex;
    }
};

// 音量拖动相关函数（Pointer Events：鼠标与触摸通用）
const setVolumeFromClientX = (clientX) => {
    if (!sliderElement.value) return;
    const rect = sliderElement.value.getBoundingClientRect();
    if (!rect.width) return;
    const ratio = (clientX - rect.left) / rect.width;
    volume.value = Math.max(0, Math.min(100, Math.round(ratio * 100)));
    changeVolume();
};

const onDragStart = (event) => {
    sliderElement.value = event.target.closest('.volume-slider');
    if (!sliderElement.value) return;
    isDragging.value = true;
    setVolumeFromClientX(event.clientX);
    document.addEventListener('pointermove', onDrag);
    document.addEventListener('pointerup', onDragEnd);
    document.addEventListener('pointercancel', onDragEnd);
};
const onDrag = (event) => {
    if (!isDragging.value) return;
    setVolumeFromClientX(event.clientX);
};
const onDragEnd = () => {
    isDragging.value = false;
    sliderElement.value = null;
    document.removeEventListener('pointermove', onDrag);
    document.removeEventListener('pointerup', onDragEnd);
    document.removeEventListener('pointercancel', onDragEnd);
};

// 音量滚轮事件
const handleVolumeScroll = (event) => {
    event.preventDefault();
    const delta = Math.sign(event.deltaY) * -1;
    volume.value = Math.min(Math.max(volume.value + delta * 10, 0), 100);
    changeVolume();
    console.log('[PlayerControl] 滚轮设置音量:', volume.value, '实际audio.volume:', audio.volume);
};

// 歌词滚轮控制播放进度
// 歌词滚动：桌面滚轮与手机拖拽共用同一套边界计算

// 边界测量只在拖拽开始/歌词变化时做一次。
// 原来每个 pointermove 都读 offsetTop/offsetHeight，等于每次移动强制同步布局一次，
// 手机上 move 事件能到 120Hz，这就是"拖不动、很沉"的主要来源。
const measureLyricsBounds = () => {
    const lyricsContainer = document.getElementById('lyrics-container');
    if (!lyricsContainer) return null;
    const lineGroups = document.querySelectorAll('.line-group');
    const firstLineElement = lineGroups[0];
    const lastLineElement = lineGroups[lineGroups.length - 1];
    if (!firstLineElement || firstLineElement === lastLineElement) return null;

    const containerHeight = lyricsContainer.offsetHeight;
    const lineHeight = lastLineElement.offsetHeight;
    const max = (containerHeight - firstLineElement.offsetHeight) / 2;
    const min = -lastLineElement.offsetTop + (containerHeight / 2) - (lineHeight / 2);
    return { min, max };
};

const getLyricsBounds = () => {
    if (lyricsBoundsDirty || !lyricsBounds) {
        lyricsBounds = measureLyricsBounds();
        lyricsBoundsDirty = false;
    }
    return lyricsBounds;
};

const applyLyricsScrollDelta = (delta) => {
    if (!audio.duration || !currentSong.value?.hash) return false;
    const bounds = getLyricsBounds();
    if (!bounds) return false;

    const target = (scrollAmount.value ?? 0) - delta;
    if (target > bounds.max) scrollAmount.value = bounds.max;
    else if (target < bounds.min) scrollAmount.value = bounds.min;
    else scrollAmount.value = target;

    lyricsFlag.value = true;
    restoreLyricsScroll();
    return true;
};

// ---- 拖拽惯性 ----
// 松手后按速度继续滑一小段再停，手感才接近原生滚动。
let lyricsMomentumId = 0;

const stopLyricsMomentum = () => {
    if (lyricsMomentumId) {
        cancelAnimationFrame(lyricsMomentumId);
        lyricsMomentumId = 0;
    }
};

const startLyricsMomentum = (velocity) => {
    stopLyricsMomentum();
    if (typeof requestAnimationFrame !== 'function') return;

    const bounds = getLyricsBounds();
    if (!bounds) return;

    let v = Math.max(-3.2, Math.min(3.2, velocity)); // px/ms
    let last = performance.now();

    const step = (now) => {
        lyricsMomentumId = 0;
        const dt = Math.min(48, now - last);
        last = now;
        if (Math.abs(v) < 0.02) { markLyricsBoundsDirty(); return; }

        const next = (scrollAmount.value ?? 0) + v * dt;
        const clamped = Math.max(bounds.min, Math.min(bounds.max, next));
        scrollAmount.value = clamped;
        // 撞到边界就停，避免"贴住还在滑"的橡皮感
        if (clamped === bounds.min || clamped === bounds.max) { markLyricsBoundsDirty(); return; }

        v *= Math.pow(0.985, dt / 16.7);
        lyricsMomentumId = requestAnimationFrame(step);
    };

    lyricsMomentumId = requestAnimationFrame(step);
};


const handleLyricsWheel = (event) => {
    if (!audio.duration || !currentSong.value?.hash) return;
    event.preventDefault();
    applyLyricsScrollDelta(event.deltaY * 1.5);
};

// 手机端上下拖拽歌词，与桌面滚轮等效。
// 歌词列表没有原生滚动（靠 scrollAmount 算 transform），所以触摸本身拖不动，需要手动实现。
const LYRICS_DRAG_THRESHOLD = 6;
let lyricsDragState = null;
let suppressLyricsClick = false;

const onLyricsPointerDown = (event) => {
    // 鼠标继续走滚轮，避免和拖拽重复响应
    if (event.pointerType === 'mouse') return;
    if (!audio.duration || !currentSong.value?.hash) return;
    stopLyricsMomentum();
    // 拖拽期间必须重新量一次边界（行高会随当前行放大而变化）
    markLyricsBoundsDirty();
    lyricsDragState = {
        pointerId: event.pointerId,
        startY: event.clientY,
        lastY: event.clientY,
        lastT: performance.now(),
        velocity: 0,
        dragged: false
    };
    // 指针捕获可能因指针已释放等原因抛异常，失败也不影响后续 pointermove
    try {
        event.currentTarget.setPointerCapture?.(event.pointerId);
    } catch { /* 忽略 */ }
};

const onLyricsPointerMove = (event) => {
    if (!lyricsDragState || event.pointerId !== lyricsDragState.pointerId) return;
    const totalDy = lyricsDragState.startY - event.clientY;
    // 小于阈值视为点击，不进入拖拽
    if (!lyricsDragState.dragged && Math.abs(totalDy) < LYRICS_DRAG_THRESHOLD) return;

    if (!lyricsDragState.dragged) {
        lyricsDragState.dragged = true;
        // 进入拖拽：关掉 transform 过渡，否则每一步都被 0.6s 缓动吃掉，
        // 手指和内容之间始终隔着一层"阻力"。
        document.getElementById('lyrics')?.classList.add('is-dragging');
    }

    const stepDy = lyricsDragState.lastY - event.clientY;
    lyricsDragState.lastY = event.clientY;

    const now = performance.now();
    const dt = now - lyricsDragState.lastT;
    if (dt > 0) {
        // 指数平滑，避免最后一帧抖动把惯性带飞
        const instant = stepDy / dt;
        lyricsDragState.velocity = lyricsDragState.velocity * 0.6 + instant * 0.4;
        lyricsDragState.lastT = now;
    }

    if (stepDy) applyLyricsScrollDelta(stepDy);
};

const onLyricsPointerEnd = (event) => {
    if (!lyricsDragState || event.pointerId !== lyricsDragState.pointerId) return;
    const { dragged, velocity } = lyricsDragState;
    lyricsDragState = null;
    try {
        event.currentTarget.releasePointerCapture?.(event.pointerId);
    } catch { /* 忽略 */ }

    if (dragged) {
        document.getElementById('lyrics')?.classList.remove('is-dragging');
        // 松手带一点惯性，手感接近原生滚动
        if (Math.abs(velocity) > 0.12) startLyricsMomentum(velocity * 16);
        // 拖拽结束后吞掉紧随其后的 click，避免误触发行跳转
        suppressLyricsClick = true;
        setTimeout(() => {
            suppressLyricsClick = false;
        }, 300);
    }
};

const onLyricsLineClick = (index) => {
    if (suppressLyricsClick) return;
    handleLyricsClick(index);
};

// 窗口尺寸变化后重新居中当前歌词行（scrollAmount 基于旧容器高度计算，需重算）
let lyricsResizeTimer = null;
const handleWindowResize = () => {
    if (lyricsResizeTimer) clearTimeout(lyricsResizeTimer);
    lyricsResizeTimer = setTimeout(() => {
        lyricsResizeTimer = null;
        if (!showLyrics.value || lyricsFlag.value || lyricsDisplayMode.value !== 'scroll') return;
        const lyricsContainer = document.getElementById('lyrics-container');
        const lineIndex = getCurrentLineIndex(audio.currentTime);
        const lineElement = document.querySelectorAll('.line-group')[lineIndex];
        if (!lyricsContainer || !lineElement) return;
        scrollAmount.value = -lineElement.offsetTop + (lyricsContainer.offsetHeight / 2) - (lineElement.offsetHeight / 2) - 32;
    }, 150);
    // 断点切换会改变歌名可用宽度，重新判断跑马灯
    nextTick(measureTitleOverflow);
};

const handleLyricsClick = (lineIndex) => {
    // if (!lyricsFlag.value) return;
    console.log('[PlayerControl] 点击歌词:', lineIndex);
    const lineStartTime = lyricsData.value[lineIndex].characters[0].startTime;
    audio.currentTime = lineStartTime / 1000;
    clearLyricsSpanCache();
    // 跳到指定行时必须强制回正：行号可能没变，但位置需要重新居中
    scrollToCurrentLine(lineIndex, true);
    lyricsFlag.value = false;
    if (lyricScrollTimer) clearTimeout(lyricScrollTimer);
    lyricScrollTimer = null;
    // 如果音乐暂停了，自动开始播放
    if (!playing.value) {
        audio.play();
    }
}

// 复制全部歌词到剪贴板
const copyLyricsToClipboard = async () => {
    if (!showLyrics.value || !lyricsData.value || lyricsData.value.length === 0) {
        return;
    }
    try {
        let lyricsText = '';
        lyricsData.value.forEach((lineData) => {
            const originalLine = lineData.characters.map(char => char.char).join('');
            lyricsText += originalLine + '\n';
            if (lineData.translated) {
                lyricsText += lineData.translated + '\n';
            }
            if (lineData.romanized) {
                lyricsText += lineData.romanized + '\n';
            }
            if (lineData.translated || lineData.romanized) {
                lyricsText += '\n';
            }
        });
        await navigator.clipboard.writeText(lyricsText.trim());
        $message.success('歌词已复制到剪贴板');
    } catch (error) {
        $message.error('复制歌词失败');
    }
};

// 键盘快捷键
const handleKeyDown = (event) => {
    const isInputFocused = ['INPUT', 'TEXTAREA'].includes(document.activeElement.tagName);
    if (isInputFocused) return;

    switch (event.code) {
        case 'Space':
            event.preventDefault();
            togglePlayPause();
            break;
        case 'ArrowLeft':
            playSongFromQueue('previous');
            break;
        case 'ArrowRight':
            playSongFromQueue('next');
            break;
        case 'Escape':
            if (showLyrics.value) toggleLyrics(currentSong.value.hash, audio.currentTime);
            break;
        case 'KeyC':
            // Ctrl+C 或 Cmd+C 复制歌词（仅在全屏歌词界面）
            if ((event.ctrlKey || event.metaKey) && showLyrics.value) {
                event.preventDefault();
                copyLyricsToClipboard();
            }
            break;
    }
};

// 初始化系统媒体快捷键
const setupMediaShortcuts = () => {
    if (!isElectron()) return;

    window.electron.ipcRenderer.on('play-previous-track', () => playSongFromQueue('previous'));
    window.electron.ipcRenderer.on('play-next-track', () => playSongFromQueue('next'));
    window.electron.ipcRenderer.on('volume-up', () => {
        volume.value = Math.min(volume.value + 10, 100);
        changeVolume();
    });
    window.electron.ipcRenderer.on('volume-down', () => {
        volume.value = Math.max(volume.value - 10, 0);
        changeVolume();
    });
    window.electron.ipcRenderer.on('toggle-play-pause', togglePlayPause);
    window.electron.ipcRenderer.on('toggle-mute', toggleMute);
    window.electron.ipcRenderer.on('toggle-like', () => playlistSelect.value.toLike());
    window.electron.ipcRenderer.on('toggle-mode', togglePlaybackMode);
    window.electron.ipcRenderer.on('url-params', async (_event, data) => {
        console.log('[PlayerControl] 接收到URL参数:', data);

        // 处理歌曲哈希参数
        if (data.hash) {
            console.log('[PlayerControl] 从URL启动播放歌曲:', data.hash);
            songQueue.privilegeSong(data.hash).then(res => {
                if (res.status == 1) {
                    const songInfo = res.data[0];
                    addSongToQueue(songInfo.hash, songInfo.albumname, getCover(songInfo.info.image, 480), songInfo.singername)
                }
            })
        }else if (data.listid) {
            // 处理歌单ID参数
            console.log('[PlayerControl] 从URL启动跳转到歌单:', data.listid);
            router.push({
                path: '/PlaylistDetail',
                query: { global_collection_id: data.listid }
            });
        } else if(data.teamcode) {
            console.log('[PlayerControl] 从URL启动打开组队活动并加入队伍:', data.teamcode);
            await createTeamEventPopup();
            await teamEventActions.joinTeam(data.teamcode);
        }
    });
};

// 切换静音
const toggleMute = () => {
    isMuted.value = !isMuted.value;
    audio.muted = isMuted.value;
    if (isMuted.value) volume.value = 0;
    else volume.value = amplitudeToSlider(audio.volume);
    localStorage.setItem('player_volume', volume.value);
    console.log('[PlayerControl] 切换静音:', isMuted.value, '音量:', volume.value, '实际audio.volume:', audio.volume);
};

const pausePlayback = (reason) => {
    cancelPendingPlayback();
    requestedSong = null;
    if (nativePlayer) {
        void nativePlayback.pause().catch(error => window.$message?.error?.(error.message));
    } else if (!audio.paused) audio.pause();
    playing.value = false;
    mediaSession.clearPositionState?.();
    if (reason) console.log('[PlayerControl] 暂停播放:', reason);
};

const showMoreMenu = ref(false);
const currentSpeed = ref(1.0);
const playbackSpeeds = [0.5, 0.75, 1.0, 1.25, 1.5, 2.0];

// 监听音频输出设备变化（例如插拔耳机/切换声卡），变化时暂停播放
let cleanupAudioOutputDeviceWatcher = null;
let lastAudioOutputDeviceSignature = null;
let audioOutputDeviceChangeHandler = null;

const setupAudioOutputDeviceWatcher = () => {
    if (cleanupAudioOutputDeviceWatcher) return;
    if (typeof navigator === 'undefined' || !navigator.mediaDevices) return;

    void getAudioOutputDeviceSignature().then(signature => {
        lastAudioOutputDeviceSignature = signature;
    }).catch(() => {
        lastAudioOutputDeviceSignature = null;
    });

    const handler = throttle(() => {
        void (async () => {
            try {
                const signature = await getAudioOutputDeviceSignature();
                if (signature === null) return;
                if (lastAudioOutputDeviceSignature === null) {
                    lastAudioOutputDeviceSignature = signature;
                    return;
                }
                if (signature !== lastAudioOutputDeviceSignature) {
                    lastAudioOutputDeviceSignature = signature;
                    if (!audio.paused) pausePlayback('检测到音频输出设备变化');
                }
            } catch (error) {
                console.warn('[PlayerControl] 获取音频输出设备信息失败:', error);
            }
        })();
    }, 800);

    if (navigator.mediaDevices.addEventListener) {
        navigator.mediaDevices.addEventListener('devicechange', handler);
        cleanupAudioOutputDeviceWatcher = () => {
            navigator.mediaDevices.removeEventListener('devicechange', handler);
        };
    } else if ('ondevicechange' in navigator.mediaDevices) {
        const previous = navigator.mediaDevices.ondevicechange;
        navigator.mediaDevices.ondevicechange = handler;
        cleanupAudioOutputDeviceWatcher = () => {
            navigator.mediaDevices.ondevicechange = previous;
        };
    }
};

const setAudioOutputDeviceWatcherEnabled = (enabled) => {
    if (enabled) {
        setupAudioOutputDeviceWatcher();
        return;
    }
    cleanupAudioOutputDeviceWatcher?.();
    cleanupAudioOutputDeviceWatcher = null;
    lastAudioOutputDeviceSignature = null;
};

let audioOutputDeviceWatchChangeHandler = null;

const applyAudioOutputDevice = async (deviceId) => {
    const result = await setAudioOutputDevice(audio, deviceId);
    if (result.ok) return true;

    if (result.reason === 'UNSUPPORTED') {
        console.warn('[PlayerControl] 当前环境不支持切换音频输出设备（setSinkId不可用）');
    } else if (result.requested !== 'default') {
        console.error('[PlayerControl] 切换音频输出设备失败:', result.error);
        window.$modal.alert('切换音频输出设备失败,请刷新页面后重试');
    } else {
        console.warn('[PlayerControl] 切回默认音频输出设备失败:', result.error);
    }

    return false;
};

// 切换「更多」菜单（倍速 / 收藏至 / 分享 合并入口）
const toggleMoreMenu = () => {
    showMoreMenu.value = !showMoreMenu.value;
    if (showMoreMenu.value) qualityMenuOpen.value = false;
};

const onMoreAddToPlaylist = () => {
    showMoreMenu.value = false;
    playlistSelect.value?.fetchPlaylists();
};

const onMoreLike = () => {
    showMoreMenu.value = false;
    playlistSelect.toLike();
};

const onMoreShare = () => {
    showMoreMenu.value = false;
    share(currentSong.value.name, currentSong.value.hash);
};

const onMoreOpenQueue = () => {
    showMoreMenu.value = false;
    queueList.openQueue();
};

// 改变播放速度
const changePlaybackSpeed = (speed) => {
    currentSpeed.value = speed;
    setPlaybackRate(speed);
    showMoreMenu.value = false;
    
    // 更新SMTC位置状态以反映新的播放速率
    if (audio.duration && currentSong.value?.hash) {
        mediaSession.updatePositionState(audio.currentTime, audio.duration, speed);
    }
};

const handleDocumentClick = (event) => {
    const target = event.target;
    if (!(target instanceof Element)) return;
    
    if (!target.closest('.quality-menu-wrapper')) {
        qualityMenuOpen.value = false;
    }
    if (!target.closest('.player-more')) {
        showMoreMenu.value = false;
    }
};

const syncDesktopLyricsFont = (settings = JSON.parse(localStorage.getItem('settings') || '{}')) => {
    desktopLyricsFont.value = settings?.desktopLyricsFont || '';
};

// 设置里改音质后，正在播放的这首歌不会自动换源，徽章停留在旧音质。
// 这里对比「设置里的期望音质」和「当前实际解析到的音质」，不一致就按新音质重新解析。
const lastRequestedQuality = ref('');
const refreshQualityAfterSettingsChange = (settings) => {
    const song = currentSong.value;
    if (!song?.hash || song?.isLocal || song?.isCloud) return;

    const wanted = settings?.quality || '';
    if (!wanted || wanted === lastRequestedQuality.value) return;
    lastRequestedQuality.value = wanted;
    if (song.resolvedQuality === wanted) return;

    // 暂停状态也要换源：autoPlay 跟随当前 playing，不会突然出声，
    // 但徽章和下次继续播放用的都是新音质。
    void requestSongPlayback(song, {
        position: audio.currentTime || 0,
        autoPlay: playing.value,
        quality: wanted
    }).catch(error => console.warn('[PlayerControl] 按新音质重新解析失败:', error?.message));
};

const handleSettingsChange = (event) => {
if (nativePlayer) void configureNativePlayback().catch(error => console.warn('同步播放设置失败', error.message));
    syncDesktopLyricsFont(event.detail?.settings);
    refreshQualityAfterSettingsChange(event.detail?.settings);
};

// 跳转到搜索页面搜索歌曲
const searchSong = (songName) => {
    // 关闭全屏歌词
    if (showLyrics.value) {
        toggleLyrics(currentSong.value.hash, audio.currentTime);
    }
    if (!songName) return;
    router.push({
        path: '/search',
        query: { q: songName }
    });
};

// 组件挂载
let cleanupNativeAuth = null;
let handleLoudnessChange = null;
let cacheRecoveryPending = false;
const onPlaybackPause = () => {
    if (playbackRetrying) return;
    playing.value = false;
    tickListenSession(false);
    mediaSession.clearPositionState();
};
const onPlaybackPlaying = () => {
    playing.value = true;
    autoSwitchCount = 0;
    resetPlaybackRetry();
    tickListenSession(true);
    if (!lyricsData.value.length && !isOffline()) void getCurrentLyrics();
};
const onPlaybackError = async () => {
    if (nativePlayer || !currentSong.value.hash || audio.error?.code === 1 || cacheRecoveryPending) return;
    const token = playbackIntent.current();
    if (currentSong.value.__fromCache) {
        cacheRecoveryPending = true;
        try {
            await invalidateCachedSong(currentSong.value);
            if (!playbackIntent.isCurrent(token)) return;
            currentSong.value.__fromCache = false;
            if (isOffline()) {
                pausePlayback();
                window.$message?.error?.('本地歌曲缓存已损坏，请联网重新播放');
                return;
            }
        } finally { cacheRecoveryPending = false; }
    }
    if (playbackIntent.isCurrent(token)) handlePlaybackFailure('音频加载失败');
};
const openLyricsFromRoute = () => {
    if (router.currentRoute.value.query.openLyrics !== '1') return;
    if (!showLyrics.value) toggleLyrics(currentSong.value.hash, audio.currentTime);
    const query = { ...router.currentRoute.value.query };
    delete query.openLyrics;
    void router.replace({ path: router.currentRoute.value.path, query });
};
watch(() => router.currentRoute.value.query.openLyrics, openLyricsFromRoute);

onMounted(async () => {
    mobileQuery = window.matchMedia(MOBILE_QUERY);
    mobileQuery.addEventListener('change', onMobileQueryChange);
    document.addEventListener('keydown', handleKeyDown);
    document.addEventListener('click', handleDocumentClick);
    window.addEventListener('settings-change', handleSettingsChange);
    window.addEventListener('resize', handleWindowResize);
    const savedSettings = readPlayerSetting('settings', {});
    syncDesktopLyricsFont(savedSettings);
    openLyricsFromRoute();

    if (nativePlayer) {
        const oldQueue = musicQueueStore.queue.map(toNativeSong);
        const oldSong = readPlayerSetting('current_song', null);
        try {
            await audioController.initAudio();
            const state = await nativePlayback.getState();
            if (!state.queue?.length && localStorage.getItem('native-playback-migrated') !== '1') {
                if (oldQueue.length) await nativePlayback.setQueue({ songs: oldQueue, autoPlay: false });
                if (oldSong?.hash) {
                    const cached = await resolveCachedSong(oldSong, { allowFallback: isOffline() });
                    await nativePlayback.play({ song: toNativeSong(cached || oldSong), position: Number(readPlayerSetting('player_progress', 0)) || 0, autoPlay: false });
                }
                await nativePlayback.setOptions({ mode: Number(readPlayerSetting('player_playback_mode', 1)), speed: Number(readPlayerSetting('player_speed', 1)) || 1, volume: Math.pow((Number(readPlayerSetting('player_volume', 66)) || 0) / 100, 2) });
            }
            localStorage.setItem('native-playback-migrated', '1');
            audioController.applyState(await nativePlayback.getState());
            const auth = MoeAuthStore();
            cleanupNativeAuth = watch(() => [auth.UserInfo, auth.Device], () => {
                void configureNativePlayback().catch(error => console.warn('同步播放配置失败', error.message));
            }, { deep: true });
        } catch (error) {
            window.$message?.error?.(error.message || '无法连接原生播放服务');
        }
        return;
    }

    window.addEventListener('online', retryPlaybackNow);
    window.addEventListener('offline', handleNetworkOffline);
    audioController.initAudio();
    setAudioOutputDeviceWatcherEnabled(savedSettings.pauseOnAudioOutputChange === 'on');
    void applyAudioOutputDevice(savedSettings.audioOutputDevice);
    audioOutputDeviceWatchChangeHandler = event => setAudioOutputDeviceWatcherEnabled(!!event.detail?.enabled);
    audioOutputDeviceChangeHandler = event => void applyAudioOutputDevice(event.detail?.deviceId || 'default');
    handleLoudnessChange = event => toggleLoudnessNormalization(!!event.detail?.enabled);
    window.addEventListener('audio-output-device-watch-change', audioOutputDeviceWatchChangeHandler);
    window.addEventListener('audio-output-device-change', audioOutputDeviceChangeHandler);
    window.addEventListener('loudness-normalization-change', handleLoudnessChange);
    const savedSong = readPlayerSetting('current_song', null);
    if (savedSong?.hash) {
        currentSong.value = { ...savedSong, url: '' };
        if (isBlobUrl(currentSong.value.img)) currentSong.value.img = './assets/images/ico.png';
        currentTime.value = Number(readPlayerSetting('player_progress', 0)) || 0;
        // Don't preload an expiring remote URL. Resume resolves local cache first.
    }
    playbackMode.initPlaybackMode();
    currentSpeed.value = Number(readPlayerSetting('player_speed', 1)) || 1;
    setPlaybackRate(currentSpeed.value);
    mediaSession.initMediaSession({
        play: resumePlayback, pause: pausePlayback,
        playPrevious: () => playSongFromQueue('previous'),
        playNext: () => playSongFromQueue('next'),
        seekBackward: offset => { audio.currentTime = Math.max(0, audio.currentTime - offset); },
        seekForward: offset => { audio.currentTime = Math.min(audio.duration || 0, audio.currentTime + offset); },
        seekTo: time => { if (time >= 0 && time <= audio.duration) audio.currentTime = time; }
    });
    setupMediaShortcuts();
    if (!isOffline()) void getVip();
    audio.addEventListener('pause', onPlaybackPause);
    audio.addEventListener('playing', onPlaybackPlaying);
    audio.addEventListener('error', onPlaybackError);
});

// 监听歌词数据变化，同步歌词到当前播放进度
watch(lyricsData, (newLyrics) => {
    if (newLyrics && newLyrics.length > 0 && audio.currentTime > 0) {
        console.log('[PlayerControl] 歌词数据加载完成，同步到当前播放进度:', audio.currentTime);
        // 不再逐字写响应式字段（模板已不绑定），交给 rAF 重新同步即可
        clearLyricsSpanCache();
        markLyricsBoundsDirty();
        scheduleLyricsFrame();
        const lineIndex = findActiveLineIndex(audio.currentTime * 1000);
        if (lineIndex >= 0) scrollToCurrentLine(lineIndex, true);
    }
});

// 组件卸载清理
onUnmounted(() => {
    cancelPendingPlayback();
    cleanupNativeAuth?.();
    onDragEnd();
    // 关闭前把仍在进行中的听歌会话按中断上报一次
    settleListenSession('switch');
    // 解除移动端断点监听
    if (mobileQuery) {
        mobileQuery.removeEventListener('change', onMobileQueryChange);
        mobileQuery = null;
    }
    // 清除自动切换定时器
    clearAutoSwitchTimer();
    clearPlaybackRetryTimer();
    // 停掉歌词 rAF 与惯性滑动，避免组件卸载后还在跑
    stopLyricsFrame();
    stopLyricsMomentum();
    releaseAllObjectUrls();
    window.removeEventListener('online', retryPlaybackNow);
    window.removeEventListener('offline', handleNetworkOffline);
    stopLyricsBackgroundCarousel();

    if (audioOutputDeviceWatchChangeHandler) {
        window.removeEventListener('audio-output-device-watch-change', audioOutputDeviceWatchChangeHandler);
        audioOutputDeviceWatchChangeHandler = null;
    }
    if (audioOutputDeviceChangeHandler) {
        window.removeEventListener('audio-output-device-change', audioOutputDeviceChangeHandler);
        audioOutputDeviceChangeHandler = null;
    }

    cleanupAudioOutputDeviceWatcher?.();
    cleanupAudioOutputDeviceWatcher = null;

    // 移除响度规格化事件监听
    if (handleLoudnessChange) window.removeEventListener('loudness-normalization-change', handleLoudnessChange);

    // 使用AudioController的销毁方法清理基本监听器
    audioController.destroy();

    // 清理组件特定的监听器
    audio.removeEventListener('pause', onPlaybackPause);
    audio.removeEventListener('playing', onPlaybackPlaying);
    audio.removeEventListener('error', onPlaybackError);

    // 清理系统媒体快捷键
    if (isElectron()) {
        window.electron.ipcRenderer.removeAllListeners('play-previous-track');
        window.electron.ipcRenderer.removeAllListeners('play-next-track');
        window.electron.ipcRenderer.removeAllListeners('volume-up');
        window.electron.ipcRenderer.removeAllListeners('volume-down');
        window.electron.ipcRenderer.removeAllListeners('toggle-play-pause');
        window.electron.ipcRenderer.removeAllListeners('toggle-mute');
        window.electron.ipcRenderer.removeAllListeners('toggle-like');
        window.electron.ipcRenderer.removeAllListeners('toggle-mode');
    }

    // 清理键盘事件
    document.removeEventListener('keydown', handleKeyDown);
    document.removeEventListener('click', handleDocumentClick);
    window.removeEventListener('settings-change', handleSettingsChange);
    window.removeEventListener('resize', handleWindowResize);
    if (lyricsResizeTimer) {
        clearTimeout(lyricsResizeTimer);
        lyricsResizeTimer = null;
    }
});

// One entry point for page, queue and offline-library commands.
const onQueueSongAdd = (hash, name, img, author) => {
    const existing = musicQueueStore.queue.find(song => song.hash === hash && !song.isCloud && !song.isLocal);
    return requestSongPlayback({ ...existing, hash, name, img, author });
};
const onQueueCloudSongAdd = (hash, name, author, timeLength, img) => requestSongPlayback({ hash, name, author, timeLength, img, isCloud: true });
const onQueueLocalSongAdd = song => requestSongPlayback({ ...song, isLocal: true });

const enqueuePlaylist = async (items, source, append = false) => {
    cancelPendingPlayback();
    const token = playbackIntent.begin();
    if (nativePlayer) {
        await configureNativePlayback();
        if (!playbackIntent.isCurrent(token)) return [];
        const songs = items.map(item => toNativeSong({ ...item, isLocal: source === 'local', isCloud: source === 'cloud', img: item.img || item.cover, timeLength: item.timeLength || item.timelen || Number(item.duration) * 1000 || 0 }));
        const options = append ? { append: true } : { startIndex: currentPlaybackModeIndex.value === 0 && songs.length ? Math.floor(Math.random() * songs.length) : 0, autoPlay: songs.length > 0 };
        await nativePlayback.setQueue({ songs, ...options });
        return songs;
    }
    let songs;
    if (source === 'local') songs = await addLocalPlaylistToQueue(items, append);
    else if (source === 'cloud') songs = await addCloudPlaylistToQueue(items, append);
    else if (source === 'cached') {
        songs = items.map((item, index) => ({ ...item, id: item.cacheKey || `cached:${index}:${item.hash}` }));
        musicQueueStore.setQueue(append ? [...musicQueueStore.queue, ...songs] : songs);
    } else songs = await addPlaylistToQueue(items, append);
    if (!playbackIntent.isCurrent(token)) return [];
    if (!append && songs?.length) {
        const index = currentPlaybackModeIndex.value === 0 ? Math.floor(Math.random() * songs.length) : 0;
        await requestSongPlayback(songs[index]);
    }
    return songs || [];
};

const insertNextSong = async (hash, name, img, author, timeLength) => {
    if (!nativePlayer) return addToNext(hash, name, img, author, timeLength);
    await configureNativePlayback();
    return nativePlayback.insertNext({ song: toNativeSong({ hash, name, img, author, timeLength }) });
};
const removeQueueSong = async (index) => {
    const song = musicQueueStore.queue[index];
    if (!song) return;
    if (nativePlayer) await nativePlayback.remove({ itemId: song.itemId || String(song.id) });
    else {
        musicQueueStore.removeSong(index);
        playedSongsStack.value = [];
        currentStackIndex.value = -1;
    }
};
const clearQueue = async () => {
    if (!await window.$modal.confirm('清空播放队列？')) return;
    pausePlayback();
    if (nativePlayer) await nativePlayback.setQueue({ songs: [], autoPlay: false });
    else {
        musicQueueStore.clearQueue();
        NextSong.value = [];
        playedSongsStack.value = [];
        currentStackIndex.value = -1;
    }
};

defineExpose({
    playing, playbackWaiting, currentSong,
    pause: pausePlayback,
    addSongToQueue: onQueueSongAdd,
    addCloudMusicToQueue: onQueueCloudSongAdd,
    addLocalMusicToQueue: onQueueLocalSongAdd,
    addLocalPlaylistToQueue: (songs, append = false) => enqueuePlaylist(songs, 'local', append),
    addCloudPlaylistToQueue: (songs, append = false) => enqueuePlaylist(songs, 'cloud', append),
    addPlaylistToQueue: (songs, append = false) => enqueuePlaylist(songs, 'online', append),
    playCachedSong: song => requestSongPlayback(song),
    playCachedPlaylist: songs => enqueuePlaylist(songs, 'cached'),
    getPlaylistAllSongs,
    addToNext: insertNextSong
});
</script>

<style scoped>
@import '@/assets/style/PlayerControl.scss';
</style>
