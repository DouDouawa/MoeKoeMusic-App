import { ref } from 'vue';
import { get } from '../../utils/request';
import { createClimaxPointLoader } from './ClimaxPoints';

export default function useProgressBar(audio, resetLyricsHighlight) {
    const progressWidth = ref(0);
    const isProgressDragging = ref(false);
    const isDraggingHandle = ref(false);
    const showTimeTooltip = ref(false);
    const tooltipPosition = ref(0);
    const tooltipTime = ref('0:00');
    const activeProgressBar = ref(null);
    const climaxPoints = ref([]);
    const climaxPointLoader = createClimaxPointLoader(hash => get(`/song/climax?hash=${hash}`));

    // 格式化时间
    const formatTime = (seconds) => {
        if (seconds > 1000) seconds = seconds / 1000;
        const minutes = Math.floor(seconds / 60);
        const secs = Math.floor(seconds % 60);
        return `${minutes}:${secs.toString().padStart(2, '0')}`;
    };

    // 获取歌曲高潮点
    const clearMusicHighlights = () => {
        climaxPointLoader.invalidate();
        climaxPoints.value = [];
    };

    const getMusicHighlights = async (hash, isCurrentSong = () => true) => {
        if (!isCurrentSong()) return;

        climaxPoints.value = [];

        const result = await climaxPointLoader.load(hash, {
            isCurrent: isCurrentSong,
            getDuration: () => audio.duration
        });

        if (result.apply) {
            climaxPoints.value = result.points;
        }
    };

    // 指针拖动：鼠标、触摸、手写笔共用一条路径。
    // 原来只挂 mousedown/mousemove，触屏上根本拖不动进度条。
    const previewFromClientX = (clientX) => {
        const bar = activeProgressBar.value;
        if (!bar || !audio.duration) return null;
        const rect = bar.getBoundingClientRect();
        const offsetX = Math.max(0, Math.min(clientX - rect.left, rect.width));
        const percentage = rect.width ? Math.max(0, Math.min((offsetX / rect.width) * 100, 100)) : 0;
        progressWidth.value = percentage;
        tooltipPosition.value = offsetX;
        tooltipTime.value = formatTime((percentage / 100) * audio.duration);
        return percentage;
    };

    const stopProgressDrag = () => {
        document.removeEventListener('pointermove', onProgressDrag);
        document.removeEventListener('pointerup', onProgressDragEnd);
        document.removeEventListener('pointercancel', onProgressDragEnd);
    };

    // 进度条拖动开始
    const onProgressDragStart = (event) => {
        const bar = event.target.closest('.progress-bar');
        if (!bar || !audio.duration) return;
        // 触摸时不让浏览器把这次滑动解释成页面滚动
        if (event.pointerType !== 'mouse') event.preventDefault();
        activeProgressBar.value = bar;
        isProgressDragging.value = true;
        isDraggingHandle.value = true;
        showTimeTooltip.value = true;
        previewFromClientX(event.clientX);
        document.addEventListener('pointermove', onProgressDrag);
        document.addEventListener('pointerup', onProgressDragEnd);
        document.addEventListener('pointercancel', onProgressDragEnd);
    };

    // 进度条拖动中
    const onProgressDrag = (event) => {
        if (!isProgressDragging.value) return;
        if (event.cancelable) event.preventDefault();
        previewFromClientX(event.clientX);
    };

    // 进度条拖动结束
    const onProgressDragEnd = (event) => {
        if (isProgressDragging.value) {
            const percentage = previewFromClientX(event.clientX);
            if (percentage !== null && audio.duration) {
                const newTime = (percentage / 100) * audio.duration;
                audio.currentTime = Math.max(0, Math.min(newTime, audio.duration));
                resetLyricsHighlight(audio.currentTime);
            }
        }
        isProgressDragging.value = false;
        isDraggingHandle.value = false;
        showTimeTooltip.value = false;
        activeProgressBar.value = null;
        stopProgressDrag();
    };

    const updateTimeTooltip = (event) => {
        const progressBar = event.target.closest('.progress-bar');
        if (!progressBar || !audio.duration) return;

        const rect = progressBar.getBoundingClientRect();
        const offsetX = Math.max(0, Math.min(event.clientX - rect.left, progressBar.offsetWidth));

        const tooltipWidth = 50;
        if (offsetX < tooltipWidth / 2) {
            tooltipPosition.value = tooltipWidth / 2;
        } else if (offsetX > progressBar.offsetWidth - tooltipWidth / 2) {
            tooltipPosition.value = progressBar.offsetWidth - tooltipWidth / 2;
        } else {
            tooltipPosition.value = offsetX;
        }

        const percentage = (offsetX / progressBar.offsetWidth);
        const time = percentage * audio.duration;
        tooltipTime.value = formatTime(time);

        showTimeTooltip.value = true;
    };

    // 隐藏时间提示
    const hideTimeTooltip = () => {
        if (!isProgressDragging.value) {
            showTimeTooltip.value = false;
        }
    };

    return {
        progressWidth,
        isProgressDragging,
        showTimeTooltip,
        tooltipPosition,
        tooltipTime,
        climaxPoints,
        formatTime,
        getMusicHighlights,
        clearMusicHighlights,
        onProgressDragStart,
        updateTimeTooltip,
        hideTimeTooltip
    };
}
