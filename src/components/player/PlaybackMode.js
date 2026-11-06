import { ref, computed } from 'vue';
import { isNativePlayback, nativePlayback } from '../../utils/nativePlayback';

export default function usePlaybackMode(t, audio) {
  const playbackModes = ref([
    { icon: 'fas fa-random', title: t('sui-ji-bo-fang') },
    { icon: 'fas fa-refresh', title: t('lie-biao-xun-huan') },
    { icon: '', title: t('dan-qu-xun-huan') },
    { icon: 'fas fa-bars-staggered', title: t('shun-xu-bo-fang') }
  ]);
  
  const currentPlaybackModeIndex = ref(1); 
  const currentPlaybackMode = computed(() => playbackModes.value[currentPlaybackModeIndex.value]);
  const playedSongsStack = ref([]);
  const currentStackIndex = ref(-1);
  
  // 初始化播放模式
  const initPlaybackMode = () => {
    const savedMode = localStorage.getItem('player_playback_mode');
    const parsed = Number.parseInt(savedMode, 10);
    currentPlaybackModeIndex.value = Number.isInteger(parsed) && parsed >= 0 && parsed < 4 ? parsed : 1;
    // Re-enter the cache-first source path at the end of each iteration.
    audio.loop = false;
    console.log('[PlaybackMode] 初始化播放模式:', currentPlaybackModeIndex.value);
  };
  
  const setPlaybackMode = (index) => {
    if (!Number.isInteger(index) || index < 0 || index >= playbackModes.value.length) return;
    if (isNativePlayback()) {
      void nativePlayback.setOptions({ mode: index }).catch(error => window.$message?.error?.(error.message));
    }
    currentPlaybackModeIndex.value = index;
    // Re-enter the cache-first source path at the end of each iteration.
    audio.loop = false;
    playedSongsStack.value = [];
    currentStackIndex.value = -1;
    localStorage.setItem('player_playback_mode', currentPlaybackModeIndex.value.toString());
    console.log('[PlaybackMode] 切换播放模式:', currentPlaybackModeIndex.value);
  };

  // 切换播放模式
  const togglePlaybackMode = () => {
    setPlaybackMode((currentPlaybackModeIndex.value + 1) % playbackModes.value.length);
  };
  
  return {
    playbackModes,
    currentPlaybackModeIndex,
    currentPlaybackMode,
    playedSongsStack,
    currentStackIndex,
    initPlaybackMode,
    togglePlaybackMode,
    setPlaybackMode
  };
} 
