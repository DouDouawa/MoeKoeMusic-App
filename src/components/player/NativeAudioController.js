import { ref } from 'vue';
import { nativePlayback, configureNativePlayback } from '../../utils/nativePlayback';

// UI adapter only. Queue advancement, retries and playback belong to PlaybackService.
// The small media-like read interface lets the existing lyric renderer and seek bar
// consume the same clock without creating an HTMLAudioElement on Android.
export default function useNativeAudioController({ updateCurrentTime, onState }) {
    const playing = ref(false);
    const isMuted = ref(false);
    const volume = ref(66);
    const playbackRate = ref(1);
    const audio = new EventTarget();
    let snapshot = { position: 0, duration: 0, speed: 1, volume: 0.4356, playing: false };
    let sampledAt = performance.now();
    let listener;
    let disposed = false;
    let pending = false;
    const amplitudeToSlider = value => Math.round(Math.sqrt(Math.max(0, Math.min(1, Number(value) || 0))) * 100);
    const reportError = error => window.$message?.error?.(error?.message || '原生播放器操作失败');
    const command = task => task.catch(reportError);
    const position = () => {
        const elapsed = snapshot.playing && !snapshot.buffering ? (performance.now() - sampledAt) / 1000 * snapshot.speed : 0;
        return Math.max(0, Math.min(snapshot.duration || Infinity, snapshot.position + elapsed));
    };
    Object.defineProperties(audio, {
        currentTime: {
            get: position,
            set: value => {
                const next = Math.max(0, Math.min(snapshot.duration || Infinity, Number(value) || 0));
                snapshot = { ...snapshot, position: next };
                sampledAt = performance.now();
                command(nativePlayback.seek({ position: next }));
            }
        },
        duration: { get: () => snapshot.duration || 0 },
        paused: { get: () => !snapshot.playing },
        src: { get: () => snapshot.currentSong?.uri || snapshot.currentSong?.url || (snapshot.currentSong?.hash ? `native:${snapshot.currentSong.hash}` : '') },
        readyState: { get: () => snapshot.buffering ? 1 : snapshot.currentSong?.hash ? 4 : 0 },
        volume: {
            get: () => snapshot.volume ?? 0.4356,
            set: value => { snapshot.volume = Math.max(0, Math.min(1, Number(value) || 0)); command(nativePlayback.setOptions({ volume: snapshot.volume })); }
        },
        muted: {
            get: () => !!snapshot.muted,
            set: value => { snapshot.muted = !!value; command(nativePlayback.setOptions({ muted: !!value })); }
        },
        playbackRate: {
            get: () => snapshot.speed || 1,
            set: value => command(nativePlayback.setOptions({ speed: Number(value) || 1 }))
        },
        loop: { get: () => snapshot.mode === 2, set: () => {} }
    });
    audio.play = () => nativePlayback.resume();
    audio.pause = () => command(nativePlayback.pause());
    audio.load = () => {};

    const applyState = state => {
        if (disposed || !state) return;
        const wasPlaying = playing.value;
        const previousHash = snapshot.currentSong?.hash;
        snapshot = { ...state, position: Number(state.position) || 0, duration: Number(state.duration) || 0, speed: Number(state.speed) || 1 };
        sampledAt = performance.now();
        playing.value = !!state.playing;
        isMuted.value = !!state.muted;
        volume.value = amplitudeToSlider(state.volume ?? 0.4356);
        playbackRate.value = snapshot.speed;
        onState?.(snapshot);
        if (previousHash !== state.currentSong?.hash) audio.dispatchEvent(new Event('loadedmetadata'));
        if (wasPlaying !== playing.value) audio.dispatchEvent(new Event(playing.value ? 'play' : 'pause'));
        updateCurrentTime();
    };
    const refresh = async () => {
        if (disposed || pending || document.hidden) return;
        pending = true;
        try { applyState(await nativePlayback.getState()); } catch (error) { reportError(error); }
        finally { pending = false; }
    };
    const initAudio = async () => {
        await configureNativePlayback(true);
        listener = await nativePlayback.addListener('state', applyState);
        if (disposed) { await listener.remove(); return; }
        document.addEventListener('visibilitychange', refresh);
        applyState(await nativePlayback.getState());
    };
    const setPlaybackRate = speed => {
        playbackRate.value = Number(speed) || 1;
        audio.playbackRate = playbackRate.value;
        localStorage.setItem('player_speed', playbackRate.value);
    };
    const changeVolume = () => {
        audio.volume = Math.pow(Math.max(0, Math.min(100, volume.value)) / 100, 2);
        isMuted.value = volume.value === 0;
        audio.muted = isMuted.value;
        localStorage.setItem('player_volume', volume.value);
    };
    return {
        audio, playing, isMuted, volume, playbackRate, amplitudeToSlider,
        initAudio, applyState, changeVolume, setPlaybackRate,
        setCurrentTime: time => { audio.currentTime = time; },
        togglePlayPause: () => playing.value ? nativePlayback.pause() : nativePlayback.resume(),
        toggleMute: () => { audio.muted = !audio.muted; },
        ensureAudioContextRunning: async () => {}, applyLoudnessNormalization: () => {},
        toggleLoudnessNormalization: () => {}, loudnessNormalizationEnabled: ref(false),
        currentLoudnessGain: ref(1), webAudioInitialized: ref(false),
        destroy: () => {
            disposed = true;
            listener?.remove();
            document.removeEventListener('visibilitychange', refresh);
            // Deliberately do not pause or release the service when the UI disappears.
        }
    };
}
