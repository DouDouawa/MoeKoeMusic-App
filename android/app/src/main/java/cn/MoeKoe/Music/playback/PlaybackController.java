package cn.MoeKoe.Music.playback;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;

import cn.MoeKoe.Music.cache.AudioCache;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The single owner of playback state in the process.
 *
 * <p>Queue, play mode, history, insert-next and the "play intent" generation all live here, not in
 * the WebView. Resolution and cache reads are asynchronous but every stale completion is rejected
 * by comparing its generation with the current intent, so a slow A can never steal playback from a
 * newer B.
 */
public final class PlaybackController implements Player.Listener {

    private static final String TAG = "MoePlayback";
    private static final String STATE_FILE = "playback_state.json";
    private static final int MAX_HISTORY = 50;
    private static final int MAX_QUEUE = 5000;
    private static final int MAX_AUTO_SKIPS = 5;
    private static final long PERSIST_DELAY_MS = 800L;

    public interface Observer {
        void onPlaybackState();

        /** Lightweight periodic tick while playing; carries position only. */
        default void onPlaybackTick() {}
    }

    private static volatile PlaybackController instance;

    /**
     * Returns the process-wide controller, constructed on the main looper.
     *
     * <p>ExoPlayer is bound to the thread that creates it and every later call must come from that
     * same thread. Capacitor invokes plugin methods on its own handler thread, so this method
     * marshals construction to the main thread instead of trusting the caller.
     */
    public static PlaybackController get(Context context) {
        PlaybackController local = instance;
        if (local != null) return local;
        synchronized (PlaybackController.class) {
            if (instance != null) return instance;
            if (Looper.myLooper() == Looper.getMainLooper()) {
                instance = new PlaybackController(context.getApplicationContext());
                return instance;
            }
            final Context app = context.getApplicationContext();
            final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            new Handler(Looper.getMainLooper()).post(() -> {
                try {
                    if (instance == null) instance = new PlaybackController(app);
                } finally {
                    latch.countDown();
                }
            });
            try {
                latch.await(10L, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
            return instance;
        }
    }

    public static PlaybackController peek() {
        return instance;
    }

    private final Context context;
    private final ExoPlayer exo;
    private final QueuePlayer player;
    private final ConfigStore config;
    private final AudioCache cache;
    private final SourceResolver resolver = new SourceResolver();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService network = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "playback-resolve");
        thread.setDaemon(true);
        return thread;
    });
    private final ExecutorService persist = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "playback-persist");
        thread.setDaemon(true);
        return thread;
    });
    private final CopyOnWriteArrayList<Observer> observers = new CopyOnWriteArrayList<>();
    private final ArrayList<Song> queue = new ArrayList<>();
    private final ArrayDeque<String> history = new ArrayDeque<>();
    private final Random random = new Random();
    private final AtomicInteger intentGeneration = new AtomicInteger();

    private int index = -1;
    private int mode = QueueRules.DEFAULT_MODE;
    private float speed = 1f;
    private float volume = 1f;
    private boolean muted = false;
    private boolean userWantsPlay = false;
    private boolean prepared = false;
    private boolean playing = false;
    private boolean buffering = false;
    private boolean resolving = false;
    private String error = "";
    private int revision = 0;
    private boolean restored = false;
    private double pendingPosition = 0d;
    private long listenedMs = 0L;
    private long listeningSince = 0L;
    private String leasedKey = "";
    private boolean released = false;
    private int networkRetry = 0;
    private int errorRetry = 0;
    private int autoSkip = 0;
    private final Runnable persistTask = this::writeStateNow;
    private boolean ticking = false;
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            ticking = false;
            if (released) return;
            for (Observer observer : observers) observer.onPlaybackTick();
            if (playing || resolving) ensureTicker();
        }
    };

    private PlaybackController(Context context) {
        this.context = context;
        this.config = new ConfigStore(context);
        this.cache = new AudioCache(context, config);
        this.player = new QueuePlayer(exo = new ExoPlayer.Builder(context)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(), true)
                .setHandleAudioBecomingNoisy(true)
                .build());
        this.player.setSkipActions(this::next, this::previous,
                () -> queue.size() > 1, () -> queue.size() > 1);
        exo.setWakeMode(C.WAKE_MODE_NETWORK);
        exo.addListener(this);
        restoreState();
    }

    /** Player handed to the media session; skip commands are mapped onto the native queue. */
    public Player player() {
        return player;
    }

    public ConfigStore config() {
        return config;
    }

    public AudioCache cache() {
        return cache;
    }

    public void addObserver(Observer observer) {
        if (observer != null && !observers.contains(observer)) observers.add(observer);
    }

    public void removeObserver(Observer observer) {
        observers.remove(observer);
    }

    // ---- commands ---------------------------------------------------------

    /** Plays {@code song}, inserting or updating its queue entry. */
    public void play(Song incoming, double positionSeconds, boolean autoPlay) {
        if (incoming == null || (incoming.hash.isEmpty() && incoming.uri.isEmpty())) return;
        int gen = intentGeneration.incrementAndGet();
        Song song = incoming.copy();
        if (song.itemId.isEmpty()) song.itemId = defaultItemId(song);
        int existing = indexOf(song);
        if (existing >= 0) {
            Song current = queue.get(existing);
            song.itemId = current.itemId;
            song.mergeFrom(current);
            // Keep the caller's source hints for this play (quality switch passes an empty url).
            song.requestedQuality = incoming.requestedQuality;
            song.resolvedQuality = incoming.resolvedQuality;
            song.playHash = incoming.playHash;
            try {
                song.qualityOptions = new JSONArray(incoming.qualityOptions.toString());
            } catch (JSONException ignored) {
                song.qualityOptions = new JSONArray();
            }
            song.uri = incoming.uri.isEmpty() ? existingLocalUri(incoming) : incoming.uri;
            song.url = incoming.url;
            song.cacheKey = incoming.cacheKey;
            queue.set(existing, song);
            index = existing;
        } else {
            int insertAt = QueueRules.insertNextIndex(index, queue.size());
            queue.add(insertAt, song);
            index = insertAt;
            if (queue.size() > MAX_QUEUE) {
                queue.remove(0);
                if (index > 0) index--;
            }
        }
        error = "";
        buffering = true;
        resolving = true;
        pendingPosition = Math.max(0d, positionSeconds);
        restored = false;
        networkRetry = 0;
        errorRetry = 0;
        haltCurrent();
        bump();
        resolveCurrent(gen, pendingPosition, autoPlay);
    }

    public void setQueue(List<Song> songs, boolean append, Integer startIndex, boolean autoPlay) {
        List<Song> incoming = normalize(songs == null ? new ArrayList<>() : songs);
        inheritLocalSources(incoming);
        if (append) {
            for (Song song : incoming) {
                if (indexOf(song) < 0) queue.add(song);
            }
            if (startIndex != null && startIndex >= 0 && startIndex < queue.size()) {
                switchTo(startIndex, 0d, autoPlay);
            } else {
                bump();
            }
            return;
        }

        Song playingSong = current();
        queue.clear();
        queue.addAll(incoming);
        error = "";
        restored = false;
        int nextIndex;
        if (startIndex != null) {
            nextIndex = startIndex >= 0 && startIndex < queue.size() ? startIndex : (queue.isEmpty() ? -1 : 0);
        } else if (playingSong != null) {
            nextIndex = indexOf(playingSong);
            if (nextIndex < 0) nextIndex = queue.isEmpty() ? -1 : 0;
        } else {
            nextIndex = queue.isEmpty() ? -1 : 0;
        }
        boolean lostCurrent = startIndex != null || playingSong == null || indexOf(playingSong) < 0;
        index = nextIndex;
        if (lostCurrent) {
            cancelIntent();
            exo.stop();
            exo.clearMediaItems();
            prepared = false;
            playing = false;
            buffering = false;
            pendingPosition = 0d;
            releaseLease();
        }
        bump();
        if (startIndex != null) switchTo(nextIndex, 0d, autoPlay);
    }

    public void pause() {
        intentGeneration.incrementAndGet();
        userWantsPlay = false;
        resolving = false;
        buffering = false;
        exo.pause();
        restored = false;
        notifyState();
        persistSoon();
    }

    public void resume() {
        if (queue.isEmpty()) return;
        restored = false;
        if (prepared && exo.getMediaItemCount() > 0) {
            userWantsPlay = true;
            exo.play();
            notifyState();
            persistSoon();
            return;
        }
        int gen = intentGeneration.incrementAndGet();
        userWantsPlay = true;
        resolveCurrent(gen, pendingPosition, true);
    }

    public void stop() {
        intentGeneration.incrementAndGet();
        userWantsPlay = false;
        resolving = false;
        buffering = false;
        playing = false;
        prepared = false;
        pendingPosition = 0d;
        exo.stop();
        exo.clearMediaItems();
        releaseLease();
        error = "";
        bump();
        persistNow();
        context.stopService(new android.content.Intent(context, PlaybackService.class));
    }

    public void next() {
        manualSkip(true);
    }

    public void previous() {
        manualSkip(false);
    }

    public void seek(double seconds) {
        double target = Math.max(0d, seconds);
        pendingPosition = target;
        if (prepared && exo.getMediaItemCount() > 0) {
            exo.seekTo((long) (target * 1000));
        }
        notifyState();
        persistSoon();
    }

    public void setOptions(Integer newMode, Float newSpeed, Float newVolume, Boolean newMuted) {
        boolean structural = false;
        if (newMode != null) {
            int value = QueueRules.clampMode(newMode);
            if (value != mode) {
                mode = value;
                structural = true;
            }
        }
        if (newSpeed != null && Float.isFinite(newSpeed)) {
            float value = Math.max(0.25f, Math.min(4f, newSpeed));
            if (Math.abs(value - speed) > 0.001f) {
                speed = value;
                exo.setPlaybackSpeed(value);
                structural = true;
            }
        }
        if (newVolume != null && Float.isFinite(newVolume)) {
            float value = Math.max(0f, Math.min(1f, newVolume));
            if (Math.abs(value - volume) > 0.001f) {
                volume = value;
                exo.setVolume(muted ? 0f : value);
                structural = true;
            }
        }
        if (newMuted != null && newMuted != muted) {
            muted = newMuted;
            exo.setVolume(muted ? 0f : volume);
            structural = true;
        }
        if (structural) {
            bump();
        } else {
            notifyState();
        }
    }

    public void remove(String itemId) {
        int removed = indexOfItemId(itemId);
        if (removed < 0) return;
        int sizeBefore = queue.size();
        boolean wasCurrent = removed == index;
        queue.remove(removed);
        index = QueueRules.indexAfterRemove(index, removed, sizeBefore);
        if (queue.isEmpty()) {
            index = -1;
            intentGeneration.incrementAndGet();
            exo.stop();
            exo.clearMediaItems();
            prepared = false;
            playing = false;
            buffering = false;
            pendingPosition = 0d;
            releaseLease();
            bump();
            return;
        }
        if (wasCurrent) {
            int gen = intentGeneration.incrementAndGet();
            pendingPosition = 0d;
            prepared = false;
            networkRetry = 0;
            errorRetry = 0;
            resolveCurrent(gen, 0d, userWantsPlay);
        }
        bump();
    }

    public void insertNext(Song song) {
        if (song == null || (song.hash.isEmpty() && song.uri.isEmpty())) return;
        Song entry = song.copy();
        if (entry.itemId.isEmpty()) entry.itemId = defaultItemId(entry);
        if (entry.isLocal && entry.uri.isEmpty()) entry.uri = existingLocalUri(entry);
        int existing = indexOf(entry);
        if (existing >= 0) queue.remove(existing);
        if (index >= queue.size()) index = queue.size() - 1;
        int at = QueueRules.insertNextIndex(index, queue.size());
        queue.add(Math.min(at, queue.size()), entry);
        bump();
    }

    public JSONObject state() {
        if (released) return emptyState();
        JSONObject state = new JSONObject();
        try {
            JSONArray songs = new JSONArray();
            for (Song song : queue) songs.put(song.toStateJson());
            state.put("queue", songs);
            Song current = current();
            state.put("currentSong", current == null ? JSONObject.NULL : current.toStateJson());
            state.put("index", index);
            state.put("position", positionSeconds());
            state.put("duration", durationSeconds());
            state.put("playing", playing);
            state.put("buffering", buffering || resolving);
            state.put("mode", mode);
            state.put("speed", speed);
            state.put("volume", volume);
            state.put("muted", muted);
            state.put("error", error);
            state.put("revision", revision);
            state.put("restored", restored);
            state.put("listenedMs", listenedMs + liveListeningMs());
        } catch (JSONException error) {
            Log.w(TAG, "state serialization failed", error);
        }
        return state;
    }

    /** True while a real track is playing or being prepared; used to keep the service alive. */
    public boolean isPlaybackOngoing() {
        return userWantsPlay || playing;
    }

    private JSONObject emptyState() {
        JSONObject state = new JSONObject();
        try {
            state.put("queue", new JSONArray());
            state.put("currentSong", JSONObject.NULL);
            state.put("index", -1);
            state.put("position", 0d);
            state.put("duration", 0d);
            state.put("playing", false);
            state.put("buffering", false);
            state.put("mode", mode);
            state.put("speed", speed);
            state.put("volume", volume);
            state.put("muted", muted);
            state.put("error", "");
            state.put("revision", revision);
            state.put("restored", false);
            state.put("listenedMs", listenedMs);
        } catch (JSONException ignored) {
            // Primitive values only.
        }
        return state;
    }

    // ---- player callbacks -------------------------------------------------

    @Override
    public void onPlaybackStateChanged(int playbackState) {
        if (released) return;
        buffering = playbackState == Player.STATE_BUFFERING || resolving;
        if (playbackState == Player.STATE_ENDED) {
            handleEnded();
            return;
        }
        notifyState();
    }

    @Override
    public void onIsPlayingChanged(boolean isPlaying) {
        if (released) return;
        long now = SystemClock.elapsedRealtime();
        if (isPlaying && listeningSince == 0L) {
            listeningSince = now;
            errorRetry = 0;
            networkRetry = 0;
            autoSkip = 0;
            error = "";
        } else if (!isPlaying && listeningSince != 0L) {
            listenedMs += Math.max(0L, now - listeningSince);
            listeningSince = 0L;
            persistSoon();
        }
        playing = isPlaying;
        notifyState();
    }

    @Override
    public void onPlayerError(PlaybackException error) {
        if (released) return;
        Log.w(TAG, "player error: " + error.getErrorCodeName());
        // A bad cached file must be invalidated, otherwise every retry hits the same bytes.
        Song song = current();
        if (song != null && !song.cacheKey.isEmpty()) {
            String key = song.cacheKey;
            song.cacheKey = "";
            song.uri = "";
            cache.remove(key, removed -> { });
        }
        errorRetry++;
        if (errorRetry > 2) {
            errorRetry = 0;
            this.error = "播放出错，跳过该歌曲";
            notifyState();
            skipAfterFailure();
            return;
        }
        this.error = "播放出错，正在重试";
        buffering = true;
        notifyState();
        final int gen = intentGeneration.get();
        main.postDelayed(() -> {
            if (released || gen != intentGeneration.get()) return;
            resolveCurrent(gen, pendingPosition, userWantsPlay);
        }, 1200L);
    }

    // ---- resolution -------------------------------------------------------

    private void resolveCurrent(int gen, double position, boolean autoPlay) {
        if (index < 0 || index >= queue.size()) {
            buffering = false;
            resolving = false;
            notifyState();
            return;
        }
        final Song song = queue.get(index);
        resolving = true;
        buffering = true;
        notifyState();
        boolean explicit = !song.requestedQuality.isEmpty();
        if (!config.cacheEnabled()) {
            // Cache turned off: keep existing files on disk but neither read nor write them.
            resolveFromSource(gen, song, position, autoPlay);
            return;
        }
        cache.find(song, !explicit, hit -> {
            if (released || gen != intentGeneration.get()) return;
            if (hit != null) {
                Song cached = song.copy();
                cached.uri = hit.file.getAbsolutePath();
                cached.cacheKey = hit.key;
                cached.url = "";
                if (!hit.song.playHash.isEmpty()) cached.playHash = hit.song.playHash;
                if (!hit.song.resolvedQuality.isEmpty()) cached.resolvedQuality = hit.song.resolvedQuality;
                if (!hit.song.qualityLabel.isEmpty()) cached.qualityLabel = hit.song.qualityLabel;
                if (hit.song.timeLength > 0) cached.timeLength = hit.song.timeLength;
                if (index >= 0 && index < queue.size()) queue.set(index, cached);
                prepare(cached, position, autoPlay);
                return;
            }
            resolveFromSource(gen, song, position, autoPlay);
        });
    }

    /** Local URI, then the native resolver. Never touches a stale path left from a previous play. */
    private void resolveFromSource(int gen, Song song, double position, boolean autoPlay) {
        if (song.isLocal) {
            if (song.uri.isEmpty()) {
                fail("本地文件不可访问");
            } else {
                prepare(song, position, autoPlay);
            }
            return;
        }
        // A path from an earlier cached play may already be gone; the resolver owns the source now.
        song.uri = "";
        song.cacheKey = "";
        network.execute(() -> {
            SourceResolver.Resolution resolution = resolver.resolve(song, config);
            main.post(() -> {
                if (released || gen != intentGeneration.get()) return;
                if (!resolution.ok()) {
                    handleResolveFailure(resolution, gen);
                    return;
                }
                Song target = queue.get(index);
                applyResolution(target, resolution);
                prepare(target, position, autoPlay);
                if (config.cacheEnabled()) cache.download(target, resolution.url);
            });
        });
    }

    private void applyResolution(Song song, SourceResolver.Resolution resolution) {
        song.url = resolution.url;
        if (!resolution.playHash.isEmpty()) song.playHash = resolution.playHash;
        song.resolvedQuality = resolution.resolvedQuality;
        song.qualityLabel = resolution.qualityLabel;
        if (resolution.qualityOptions != null && resolution.qualityOptions.length() > 0) {
            song.qualityOptions = resolution.qualityOptions;
        }
        if (resolution.mixSongId > 0) song.mixSongId = resolution.mixSongId;
        if (resolution.timeLengthMs > 0) song.timeLength = resolution.timeLengthMs;
        // A one-shot quality request must not stick to the entry for later plays.
        song.requestedQuality = "";
        song.cacheKey = "";
        song.uri = "";
    }

    private void handleResolveFailure(SourceResolver.Resolution resolution, int gen) {
        SourceResolver.Error failure = resolution.error;
        SourceResolver.ErrorKind kind = failure == null ? SourceResolver.ErrorKind.UNAVAILABLE : failure.kind;
        if (kind == SourceResolver.ErrorKind.NETWORK) {
            // Keep retrying: a lost network is not a broken song. Back off, stay paused.
            error = "网络连接失败，正在重试";
            resolving = true;
            buffering = true;
            notifyState();
            networkRetry = Math.min(networkRetry + 1, 6);
            long delay = Math.min(30000L, 1000L * (1L << Math.min(5, networkRetry - 1)));
            main.postDelayed(() -> {
                if (released || gen != intentGeneration.get()) return;
                resolveCurrent(gen, pendingPosition, userWantsPlay);
            }, delay);
            return;
        }
        if (kind == SourceResolver.ErrorKind.AUTH || kind == SourceResolver.ErrorKind.RISK) {
            // Wait for the foreground to fix credentials; do not skip the song or bypass auth.
            error = failure == null ? "需要重新登录" : failure.message;
            resolving = false;
            buffering = false;
            userWantsPlay = false;
            notifyState();
            return;
        }
        if (kind == SourceResolver.ErrorKind.NOT_CONFIGURED) {
            error = failure == null ? "未配置 API 地址" : failure.message;
            resolving = false;
            buffering = false;
            notifyState();
            return;
        }
        error = failure == null ? "未获取到播放地址" : failure.message;
        resolving = false;
        buffering = false;
        notifyState();
        // Content is genuinely unavailable: follow the normal mode rules to the next song.
        skipAfterFailure();
    }

    private void skipAfterFailure() {
        Song song = current();
        if (queue.size() <= 1) {
            resolving = false;
            buffering = false;
            userWantsPlay = false;
            error = song == null ? "没有可播放的歌曲" : "该歌曲暂时无法播放";
            notifyState();
            return;
        }
        if (++autoSkip > MAX_AUTO_SKIPS) {
            autoSkip = 0;
            resolving = false;
            buffering = false;
            userWantsPlay = false;
            error = "已达到最大重试次数，请手动选择歌曲";
            notifyState();
            return;
        }
        final int gen = intentGeneration.get();
        main.postDelayed(() -> {
            if (released || gen != intentGeneration.get()) return;
            // A failed song must move on even in single-loop mode, otherwise it retries forever.
            int target = mode == QueueRules.MODE_RANDOM
                    ? randomNextIndex()
                    : QueueRules.nextManual(mode, index, queue.size());
            if (target == QueueRules.STOP || target == index) {
                resolving = false;
                buffering = false;
                userWantsPlay = false;
                notifyState();
                return;
            }
            switchTo(target, 0d, true);
        }, 2000L);
    }

    private void fail(String message) {
        resolving = false;
        buffering = false;
        error = message;
        notifyState();
    }

    // ---- preparations -----------------------------------------------------

    private void prepare(Song song, double position, boolean autoPlay) {
        if (released) return;
        releaseLease();
        Uri uri;
        if (song.isLocal) {
            uri = Uri.parse(song.uri);
        } else if (song.uri != null && !song.uri.isEmpty()) {
            uri = Uri.parse(song.uri.startsWith("/") ? Uri.fromFile(new File(song.uri)).toString() : song.uri);
        } else if (song.url != null && !song.url.isEmpty()) {
            uri = Uri.parse(song.url);
        } else {
            fail("未获取到播放地址");
            return;
        }
        if (!song.cacheKey.isEmpty()) {
            cache.lease(song.cacheKey);
            leasedKey = song.cacheKey;
        }
        MediaMetadata.Builder metadata = new MediaMetadata.Builder()
                .setTitle(song.name)
                .setArtist(song.author)
                .setAlbumTitle(song.album)
                .setIsBrowsable(false)
                .setIsPlayable(true);
        if (!song.img.isEmpty() && song.img.startsWith("http")) {
            metadata.setArtworkUri(Uri.parse(song.img.replace("{size}", "480")));
        }
        MediaItem item = new MediaItem.Builder()
                .setMediaId(song.itemId)
                .setUri(uri)
                .setMediaMetadata(metadata.build())
                .build();
        prepared = true;
        resolving = false;
        pendingPosition = Math.max(0d, position);
        exo.setMediaItem(item, (long) (pendingPosition * 1000));
        exo.setPlaybackSpeed(speed);
        exo.setVolume(muted ? 0f : volume);
        exo.prepare();
        userWantsPlay = autoPlay;
        restoringDone();
        if (autoPlay) exo.play();
        notifyState();
        persistSoon();
    }

    private void restoringDone() {
        if (restored) {
            restored = false;
            bump();
        }
    }

    // ---- order ------------------------------------------------------------

    private void manualSkip(boolean forward) {
        if (queue.isEmpty()) return;
        int gen = intentGeneration.incrementAndGet();
        rememberCurrent();
        int target;
        if (mode == QueueRules.MODE_RANDOM) {
            target = forward ? randomNextIndex() : randomPreviousIndex();
        } else {
            target = forward
                    ? QueueRules.nextManual(mode, index, queue.size())
                    : QueueRules.previousManual(mode, index, queue.size());
        }
        if (target == QueueRules.STOP) {
            pause();
            return;
        }
        switchTo(gen, target, 0d, true);
    }

    private void switchTo(int target, double position, boolean autoPlay) {
        switchTo(intentGeneration.incrementAndGet(), target, position, autoPlay);
    }

    private void switchTo(int gen, int target, double position, boolean autoPlay) {
        if (target < 0 || target >= queue.size()) return;
        index = target;
        error = "";
        resolving = true;
        buffering = true;
        pendingPosition = Math.max(0d, position);
        networkRetry = 0;
        errorRetry = 0;
        haltCurrent();
        bump();
        resolveCurrent(gen, pendingPosition, autoPlay);
    }

    /** Immediately silences the previous track; the new one is prepared after resolution. */
    private void haltCurrent() {
        prepared = false;
        try {
            exo.stop();
        } catch (Exception error) {
            Log.w(TAG, "player stop failed", error);
        }
        releaseLease();
    }

    private void handleEnded() {
        if (queue.isEmpty()) return;
        int gen = intentGeneration.incrementAndGet();
        networkRetry = 0;
        errorRetry = 0;
        int target = nextIndexForEnd();
        if (target == QueueRules.STOP) {
            userWantsPlay = false;
            playing = false;
            buffering = false;
            prepared = false;
            pendingPosition = 0d;
            notifyState();
            persistNow();
            return;
        }
        if (target == index && mode == QueueRules.MODE_SINGLE) {
            // Re-enter the cache-first path so the second loop plays the stored file.
            resolveCurrent(gen, 0d, true);
            return;
        }
        index = target;
        error = "";
        resolveCurrent(gen, 0d, true);
        bump();
    }

    private int nextIndexForEnd() {
        if (mode == QueueRules.MODE_RANDOM) {
            rememberCurrent();
            return randomNextIndex();
        }
        return QueueRules.nextAfterEnd(mode, index, queue.size());
    }

    private int randomNextIndex() {
        List<String> ids = new ArrayList<>(queue.size());
        for (Song song : queue) ids.add(song.itemId);
        return QueueRules.randomPick(queue.size(), index, ids, new ArrayList<>(history), random);
    }

    private int randomPreviousIndex() {
        while (!history.isEmpty()) {
            String id = history.pollLast();
            int found = indexOfItemId(id);
            if (found >= 0 && found != index) return found;
        }
        return randomNextIndex();
    }

    private void rememberCurrent() {
        Song song = current();
        if (song == null || song.itemId.isEmpty()) return;
        if (history.peekLast() != null && history.peekLast().equals(song.itemId)) return;
        history.addLast(song.itemId);
        while (history.size() > MAX_HISTORY) history.pollFirst();
    }

    // ---- state plumbing ---------------------------------------------------

    private void bump() {
        revision++;
        notifyState();
        persistSoon();
    }

    private void notifyState() {
        for (Observer observer : observers) observer.onPlaybackState();
        ensureTicker();
    }

    /** One position tick per second while something is actually happening. */
    private void ensureTicker() {
        if (ticking || released || observers.isEmpty()) return;
        ticking = true;
        main.postDelayed(tick, 1000L);
    }

    private Song current() {
        return index >= 0 && index < queue.size() ? queue.get(index) : null;
    }

    private int indexOf(Song song) {
        if (song == null) return -1;
        if (!song.itemId.isEmpty()) {
            for (int i = 0; i < queue.size(); i++) {
                if (song.itemId.equals(queue.get(i).itemId)) return i;
            }
        }
        for (int i = 0; i < queue.size(); i++) {
            if (queue.get(i).sameEntry(song)) return i;
        }
        return -1;
    }

    private int indexOfItemId(String itemId) {
        if (itemId == null || itemId.isEmpty()) return -1;
        for (int i = 0; i < queue.size(); i++) {
            if (itemId.equals(queue.get(i).itemId)) return i;
        }
        return -1;
    }

    /** SAF grants are never part of state, so a replayed local song inherits its existing URI. */
    private String existingLocalUri(Song song) {
        if (!song.isLocal) return "";
        for (Song existing : queue) {
            if (!existing.isLocal || existing.uri.isEmpty()) continue;
            if (!song.itemId.isEmpty() && song.itemId.equals(existing.itemId)) return existing.uri;
            if (!song.hash.isEmpty() && song.hash.equals(existing.hash)) return existing.uri;
        }
        return "";
    }

    /**
     * State deliberately omits source URIs, so a queue echoed back by the UI would lose the SAF
     * grants that make local files playable. Re-attach them from the entries we already hold.
     */
    private void inheritLocalSources(List<Song> incoming) {
        if (queue.isEmpty()) return;
        java.util.HashMap<String, String> byItemId = new java.util.HashMap<>();
        java.util.HashMap<String, String> byHash = new java.util.HashMap<>();
        for (Song existing : queue) {
            if (!existing.isLocal || existing.uri.isEmpty()) continue;
            byItemId.put(existing.itemId, existing.uri);
            byHash.put(existing.hash, existing.uri);
        }
        if (byItemId.isEmpty()) return;
        for (Song song : incoming) {
            if (!song.isLocal || !song.uri.isEmpty()) continue;
            String uri = byItemId.get(song.itemId);
            if (uri == null) uri = byHash.get(song.hash);
            if (uri != null) song.uri = uri;
        }
    }

    private List<Song> normalize(List<Song> songs) {
        List<Song> result = new ArrayList<>();
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        for (Song song : songs) {
            if (song == null || (song.hash.isEmpty() && song.uri.isEmpty())) continue;
            Song entry = song.copy();
            if (entry.itemId.isEmpty()) entry.itemId = defaultItemId(entry);
            String baseId = entry.itemId;
            int suffix = 2;
            while (seen.contains(entry.itemId)) entry.itemId = baseId + "#" + suffix++;
            seen.add(entry.itemId);
            result.add(entry);
        }
        if (result.size() > MAX_QUEUE) return new ArrayList<>(result.subList(0, MAX_QUEUE));
        return result;
    }

    private static String defaultItemId(Song song) {
        String prefix = song.isCloud ? "cloud" : song.isLocal ? "local" : "online";
        String key = song.hash.isEmpty() ? song.uri : song.hash;
        return prefix + ":" + key;
    }

    private double positionSeconds() {
        if (prepared && exo.getMediaItemCount() > 0) {
            long position = exo.getCurrentPosition();
            if (position >= 0) return position / 1000d;
        }
        return pendingPosition;
    }

    private double durationSeconds() {
        if (prepared) {
            long duration = exo.getDuration();
            if (duration > 0 && duration != C.TIME_UNSET) return duration / 1000d;
        }
        Song song = current();
        return song == null ? 0d : song.timeLength / 1000d;
    }

    private long liveListeningMs() {
        return listeningSince == 0L ? 0L : Math.max(0L, SystemClock.elapsedRealtime() - listeningSince);
    }

    private void cancelIntent() {
        intentGeneration.incrementAndGet();
    }

    private void releaseLease() {
        if (leasedKey.isEmpty()) return;
        cache.release(leasedKey);
        leasedKey = "";
    }

    // ---- persistence ------------------------------------------------------

    private void restoreState() {
        File file = new File(context.getFilesDir(), STATE_FILE);
        if (!file.isFile()) return;
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[(int) Math.min(file.length(), 8L * 1024L * 1024L)];
            int read = input.read(buffer);
            if (read <= 0) return;
            JSONObject state = new JSONObject(new String(buffer, 0, read, StandardCharsets.UTF_8));
            JSONArray songs = state.optJSONArray("queue");
            if (songs != null) {
                for (int i = 0; i < songs.length() && i < MAX_QUEUE; i++) {
                    Song song = Song.fromJson(songs.optJSONObject(i));
                    if (song != null && !(song.hash.isEmpty() && song.uri.isEmpty())) queue.add(song);
                }
            }
            int savedIndex = state.optInt("index", queue.isEmpty() ? -1 : 0);
            index = savedIndex >= 0 && savedIndex < queue.size() ? savedIndex : (queue.isEmpty() ? -1 : 0);
            mode = QueueRules.clampMode(state.optInt("mode", QueueRules.DEFAULT_MODE));
            speed = (float) clamp(state.optDouble("speed", 1d), 0.25d, 4d);
            volume = (float) clamp(state.optDouble("volume", 1d), 0d, 1d);
            muted = state.optBoolean("muted", false);
            pendingPosition = Math.max(0d, state.optDouble("position", 0d));
            listenedMs = Math.max(0L, state.optLong("listenedMs", 0L));
            exo.setPlaybackSpeed(speed);
            exo.setVolume(muted ? 0f : volume);
            restored = !queue.isEmpty();
        } catch (Exception error) {
            Log.w(TAG, "saved playback state could not be read", error);
            queue.clear();
            index = -1;
            restored = false;
        }
    }

    private static double clamp(double value, double min, double max) {
        if (!Double.isFinite(value)) return min;
        return Math.max(min, Math.min(max, value));
    }

    private void persistSoon() {
        main.removeCallbacks(persistTask);
        main.postDelayed(persistTask, PERSIST_DELAY_MS);
    }

    private void persistNow() {
        main.removeCallbacks(persistTask);
        writeStateNow();
    }

    private void writeStateNow() {
        if (released) return;
        final JSONObject state = new JSONObject();
        try {
            JSONArray songs = new JSONArray();
            for (Song song : queue) songs.put(song.toPersistedJson());
            state.put("queue", songs);
            state.put("index", index);
            state.put("position", positionSeconds());
            state.put("mode", mode);
            state.put("speed", speed);
            state.put("volume", volume);
            state.put("muted", muted);
            state.put("listenedMs", listenedMs + liveListeningMs());
        } catch (JSONException error) {
            Log.w(TAG, "playback state could not be serialized", error);
            return;
        }
        final String payload = state.toString();
        persist.execute(() -> {
            File target = new File(context.getFilesDir(), STATE_FILE);
            File temp = new File(context.getFilesDir(), STATE_FILE + ".tmp");
            try (FileOutputStream output = new FileOutputStream(temp)) {
                output.write(payload.getBytes(StandardCharsets.UTF_8));
                output.flush();
            } catch (Exception error) {
                Log.w(TAG, "playback state could not be written", error);
                return;
            }
            if (!temp.renameTo(target)) {
                //noinspection ResultOfMethodCallIgnored
                target.delete();
                //noinspection ResultOfMethodCallIgnored
                temp.renameTo(target);
            }
        });
    }

    // ---- teardown ---------------------------------------------------------

    /** Releases the player. Called by the service when it is destroyed. */    public void release() {
        if (released) return;
        long now = SystemClock.elapsedRealtime();
        if (listeningSince != 0L) {
            listenedMs += Math.max(0L, now - listeningSince);
            listeningSince = 0L;
        }
        persistNow();
        released = true;
        observers.clear();
        releaseLease();
        try {
            exo.removeListener(this);
            exo.release();
        } catch (Exception error) {
            Log.w(TAG, "player release failed", error);
        }
        network.shutdownNow();
        persist.shutdown();
        cache.close();
        instance = null;
    }
}
