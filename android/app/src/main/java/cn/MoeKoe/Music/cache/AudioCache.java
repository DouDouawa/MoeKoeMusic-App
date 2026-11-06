package cn.MoeKoe.Music.cache;

import android.content.Context;
import android.media.MediaMetadataRetriever;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;

import cn.MoeKoe.Music.playback.ConfigStore;
import cn.MoeKoe.Music.playback.Song;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Complete offline audio in app-private storage, indexed by SQLite.
 *
 * <p>Rules that matter for correctness:
 * <ul>
 *   <li>Only verified complete files are published; a partial download is a temp file nobody sees.</li>
 *   <li>Capacity, LRU eviction, removal and clearing are serialized on one lock, so two downloads
 *       cannot jointly exceed the budget.</li>
 *   <li>Clearing advances a generation first; a task from the old generation can never publish.</li>
 *   <li>A file currently open by the player is leased: it is not evicted, and removal of it is
 *       deferred until the lease is released.</li>
 * </ul>
 */
public final class AudioCache {

    private static final String TAG = "MoeAudioCache";
    private static final String DIR_NAME = "audio_cache";
    private static final long MAX_IMPORT_CHUNK = 1024L * 1024L;
    private static final int MAX_IMPORT_SESSIONS = 2;

    public interface Listener {
        void onCacheEvent(String status, String cacheKey, String reason);
    }

    public interface Callback<T> {
        void onResult(T value);
    }

    public static final class Hit {
        public final Song song;
        public final File file;
        public final String key;

        Hit(Song song, File file, String key) {
            this.song = song;
            this.file = file;
            this.key = key;
        }
    }

    private static final class PendingDelete {
        final File file;
        final long size;

        PendingDelete(File file, long size) {
            this.file = file;
            this.size = size;
        }
    }

    private static final class ImportSession {
        String id;
        File file;
        String key;
        Song song;
        String mime;
        long expectedSize;
        long written;
        int generation;
    }

    private final Context context;
    private final File dir;
    private final File tmpDir;
    private final CacheDb db;
    private final ConfigStore config;
    private final Handler main = new Handler(Looper.getMainLooper());

    /** Short file/DB operations; never runs a network transfer. */
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> named(r, "cache-io"));
    /** One complete download at a time. */
    private final ExecutorService transfers = Executors.newSingleThreadExecutor(r -> named(r, "cache-transfer"));

    private final Object lock = new Object();
    private final Set<String> leased = new CopyOnWriteArraySet<>();
    private final Map<String, PendingDelete> pendingDeletes = new ConcurrentHashMap<>();
    private final Map<String, Long> reservations = new ConcurrentHashMap<>();
    private final Set<String> activeKeys = ConcurrentHashMap.newKeySet();
    private final Map<String, ImportSession> imports = new ConcurrentHashMap<>();
    private final Set<HttpURLConnection> connections = ConcurrentHashMap.newKeySet();
    private final AtomicInteger generation = new AtomicInteger(1);

    private volatile Listener listener;
    private volatile boolean closed;

    public AudioCache(Context context, ConfigStore config) {
        this.context = context.getApplicationContext();
        this.config = config;
        this.dir = new File(this.context.getFilesDir(), DIR_NAME);
        this.tmpDir = new File(this.dir, "tmp");
        // Files must survive; they are not in the cacheDir the system may clear.
        if (!dir.exists() && !dir.mkdirs()) Log.w(TAG, "cache directory could not be created");
        if (!tmpDir.exists() && !tmpDir.mkdirs()) Log.w(TAG, "cache temp directory could not be created");
        this.db = new CacheDb(this.context);
        purgeStaleTempFiles();
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public File directory() {
        return dir;
    }

    // ---- playback lease ---------------------------------------------------

    /** Marks a file as in use so eviction/removal cannot delete it under the player. */
    public void lease(String key) {
        if (key == null || key.isEmpty()) return;
        leased.add(key);
    }

    /** Releases the lease and finishes any deletion that was waiting for it. */
    public void release(String key) {
        if (key == null || key.isEmpty()) return;
        if (!leased.remove(key)) return;
        io.execute(() -> {
            PendingDelete pending;
            synchronized (lock) {
                // A new play of the same file may have re-leased it before this ran.
                if (leased.contains(key)) return;
                pending = pendingDeletes.remove(key);
            }
            if (pending != null && pending.file.delete()) {
                Log.d(TAG, "deferred cache file released");
            }
        });
    }

    // ---- lookups ----------------------------------------------------------

    /** Cache-first lookup. Never touches the network, so it is safe to call before resolving. */
    public void find(Song song, boolean allowFallback, Callback<Hit> callback) {
        if (!submit(() -> {
            Hit hit;
            synchronized (lock) {
                hit = findLocked(song, allowFallback);
            }
            Hit result = hit;
            main.post(() -> callback.onResult(result));
        })) {
            main.post(() -> callback.onResult(null));
        }
    }

    public void songJson(Song song, boolean allowFallback, Callback<JSONObject> callback) {
        find(song, allowFallback, hit -> {
            if (hit == null) {
                callback.onResult(null);
                return;
            }
            JSONObject json = null;
            try {
                json = hit.song.toStateJson();
                json.put("size", hit.file.length());
                json.put("complete", true);
            } catch (JSONException error) {
                Log.w(TAG, "cached song JSON failed", error);
            }
            JSONObject value = json;
            callback.onResult(value);
        });
    }

    public void list(Callback<List<JSONObject>> callback) {
        if (!submit(() -> {
            List<JSONObject> songs = new ArrayList<>();
            synchronized (lock) {
                for (CacheEntry entry : db.all(config.accountId())) {
                    if (!isUsable(entry)) {
                        discardEntry(entry);
                        continue;
                    }
                    try {
                        songs.add(entry.toJson());
                    } catch (JSONException error) {
                        Log.w(TAG, "cache row could not be serialized", error);
                    }
                }
            }
            main.post(() -> callback.onResult(songs));
        })) {
            main.post(() -> callback.onResult(new ArrayList<>()));
        }
    }

    public void stats(Callback<JSONObject> callback) {
        if (!submit(() -> {
            JSONObject stats = new JSONObject();
            synchronized (lock) {
                long pending = pendingBytesLocked();
                int count = 0;
                for (CacheEntry entry : db.all(config.accountId())) {
                    if (isUsable(entry)) count++;
                }
                try {
                    stats.put("count", count);
                    stats.put("totalSize", db.totalSize());
                    stats.put("limitBytes", config.cacheLimitBytes());
                    stats.put("pendingBytes", pending);
                    stats.put("enabled", config.cacheEnabled());
                    stats.put("status", "ready");
                } catch (JSONException ignored) {
                    // Primitive values only.
                }
            }
            JSONObject value = stats;
            main.post(() -> callback.onResult(value));
        })) {
            main.post(() -> callback.onResult(emptyStats()));
        }
    }

    private JSONObject emptyStats() {
        JSONObject stats = new JSONObject();
        try {
            stats.put("count", 0);
            stats.put("totalSize", 0);
            stats.put("limitBytes", config.cacheLimitBytes());
            stats.put("pendingBytes", 0);
            stats.put("enabled", config.cacheEnabled());
            stats.put("status", "closed");
        } catch (JSONException ignored) {
            // Primitive values only.
        }
        return stats;
    }

    // ---- downloads --------------------------------------------------------

    /** Fetches the complete file for {@code song}. Failure never affects online playback. */
    public void download(Song song, String url) {
        if (song == null || url == null || url.isEmpty()) return;
        if (!config.cacheEnabled() || !song.isCacheable()) return;
        final String key = CacheKeys.build(song, config.accountId(), config.quality());
        if (key.isEmpty()) return;
        final int gen = generation.get();
        final Song snapshot = song.copy();
        transfers.execute(() -> runDownload(snapshot, url, key, gen));
    }

    private void runDownload(Song song, String url, String key, int gen) {
        if (gen != generation.get() || !config.cacheEnabled()) return;
        if (!activeKeys.add(key)) return;
        File temp = new File(tmpDir, digest(key) + ".part");
        HttpURLConnection connection = null;
        long reserved = 0L;
        boolean published = false;
        try {
            synchronized (lock) {
                CacheEntry existing = db.get(key);
                if (existing != null && isUsable(existing)) return;
                if (existing != null) discardEntry(existing);
            }
            connection = (HttpURLConnection) new URL(url).openConnection();
            connections.add(connection);
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(30000);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept-Encoding", "identity");
            // A CDN must never see the API authorization header.
            int code = connection.getResponseCode();
            if (code == 401 || code == 403) throw new CacheFailure("HTTP " + code, "denied");
            if (code != 200 && code != 206) throw new CacheFailure("HTTP " + code, "failed");
            String mime = connection.getContentType();
            if (AudioHeader.isRejectedMime(mime)) throw new CacheFailure("not audio", "not-audio");
            long declared = declaredLength(connection, code);
            long limit = config.cacheLimitBytes();
            if (declared > limit) throw new CacheFailure("file larger than cache limit", "capacity");
            notifyEvent("caching", key, null);
            reserved = declared > 0 ? declared : 0L;
            if (reserved > 0) {
                synchronized (lock) {
                    if (!ensureCapacityLocked(reserved, key)) throw new CacheFailure("capacity", "capacity");
                    reservations.put(key, reserved);
                }
            }
            long written = copyToFile(connection.getInputStream(), temp, key, gen, declared, limit);
            if (declared > 0 && written != declared) throw new CacheFailure("size mismatch", "incomplete");
            verify(temp, song);
            synchronized (lock) {
                if (gen != generation.get() || !config.cacheEnabled()) throw new CacheFailure("cancelled", "cancelled");
                // The reservation for this key covered the bytes we just wrote; drop it before the
                // capacity check so the incoming file is not counted twice.
                reservations.remove(key);
                if (!ensureCapacityLocked(written, key)) throw new CacheFailure("capacity", "capacity");
                CacheEntry entry = CacheEntry.fromSong(song, key, "", written, mime);
                entry.accountId = song.isCloud ? config.accountId() : "public";
                File target = new File(dir, digest(key) + ".audio");
                move(temp, target);
                entry.path = target.getAbsolutePath();
                db.upsert(entry);
                published = true;
            }
            notifyEvent("cached", key, null);
        } catch (CacheFailure failure) {
            Log.d(TAG, "cache skipped: " + failure.getMessage());
            notifyEvent("cancelled".equals(failure.reason) ? "cancelled" : "failed", key,
                    "capacity".equals(failure.reason) || "not-audio".equals(failure.reason) ? failure.reason : "not-offline");
        } catch (IOException error) {
            Log.d(TAG, "cache download failed: " + error.getClass().getSimpleName());
            notifyEvent("failed", key, "not-offline");
        } catch (Exception error) {
            Log.w(TAG, "cache download error", error);
            notifyEvent("failed", key, "not-offline");
        } finally {
            synchronized (lock) {
                reservations.remove(key);
            }
            if (connection != null) {
                connections.remove(connection);
                connection.disconnect();
            }
            if (!published) {
                //noinspection ResultOfMethodCallIgnored
                temp.delete();
            }
            activeKeys.remove(key);
        }
    }

    private long copyToFile(InputStream stream, File temp, String key, int gen, long declared, long limit)
            throws IOException, CacheFailure {
        long written = 0L;
        long nextReservation = declared > 0 ? declared : 1024L * 1024L;
        try (InputStream input = stream; FileOutputStream output = new FileOutputStream(temp)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (gen != generation.get() || !config.cacheEnabled()) throw new CacheFailure("cancelled", "cancelled");
                written += read;
                if (written > limit) throw new CacheFailure("capacity", "capacity");
                if (declared > 0 && written > declared) throw new CacheFailure("oversized", "incomplete");
                if (declared <= 0 && written > nextReservation) {
                    long extra = 1024L * 1024L;
                    synchronized (lock) {
                        if (!ensureCapacityLocked(extra, key)) throw new CacheFailure("capacity", "capacity");
                        reservations.put(key, nextReservation + extra);
                    }
                    nextReservation += extra;
                }
                output.write(buffer, 0, read);
            }
            output.flush();
        }
        return written;
    }

    /** A full 200 or a 206 that starts at 0 and reaches the end. Anything else is partial. */
    private long declaredLength(HttpURLConnection connection, int code) throws CacheFailure {
        long contentLength = parseLong(connection.getHeaderField("Content-Length"));
        String encoding = connection.getHeaderField("Content-Encoding");
        if (encoding != null && !encoding.equalsIgnoreCase("identity")) contentLength = 0L;
        if (code == 206) {
            String range = connection.getHeaderField("Content-Range");
            if (range == null) throw new CacheFailure("partial response", "incomplete");
            String[] parts = range.replace("bytes", "").trim().split("/");
            if (parts.length != 2) throw new CacheFailure("partial response", "incomplete");
            String[] span = parts[0].split("-");
            long start = parseLong(span.length > 0 ? span[0].trim() : "");
            long end = parseLong(span.length > 1 ? span[1].trim() : "");
            long total = parseLong(parts[1].trim());
            if (start != 0L || end + 1 != total) throw new CacheFailure("partial response", "incomplete");
            if (contentLength > 0 && contentLength != total) throw new CacheFailure("range mismatch", "incomplete");
            return total;
        }
        return contentLength;
    }

    // ---- verification -----------------------------------------------------

    private void verify(File file, Song song) throws CacheFailure {
        if (!file.isFile() || file.length() <= 0) throw new CacheFailure("empty file", "not-audio");
        AudioHeader.Kind kind = AudioHeader.Kind.UNKNOWN;
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] head = new byte[64];
            int read = input.read(head);
            kind = AudioHeader.sniff(read > 0 ? trimTo(head, read) : new byte[0]);
        } catch (IOException error) {
            throw new CacheFailure("unreadable file", "not-audio");
        }
        if (kind == AudioHeader.Kind.DOCUMENT) throw new CacheFailure("error document", "not-audio");

        long durationMs = 0L;
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(file.getAbsolutePath());
            String value = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (value != null) durationMs = parseLong(value);
        } catch (Exception error) {
            if (kind != AudioHeader.Kind.AUDIO) throw new CacheFailure("no audio track", "not-audio");
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
                // Nothing useful to do if the platform retriever refuses to close.
            }
        }
        long expected = expectedDurationMs(song);
        if (expected > 0 && durationMs > 0) {
            long tolerance = Math.max(2000L, expected * 3 / 100);
            if (Math.abs(expected - durationMs) > tolerance) {
                throw new CacheFailure("duration mismatch", "incomplete");
            }
        }
    }

    private static long expectedDurationMs(Song song) {
        if (song.isPreview && song.previewDuration > 0) return (long) (song.previewDuration * 1000);
        return song.timeLength;
    }

    // ---- capacity ---------------------------------------------------------

    private boolean ensureCapacityLocked(long incoming, String preserveKey) {
        long limit = config.cacheLimitBytes();
        long used = db.totalSize();
        long pending = pendingBytesLocked();
        if (used + pending + incoming <= limit) return true;
        for (CacheEntry entry : db.lru(64)) {
            if (used + pending + incoming <= limit) break;
            if (entry.key.equals(preserveKey) || leased.contains(entry.key)) continue;
            long size = entry.size;
            db.delete(entry.key);
            //noinspection ResultOfMethodCallIgnored
            new File(entry.path).delete();
            used -= size;
        }
        return used + pending + incoming <= limit;
    }

    private long pendingBytesLocked() {
        long pending = 0L;
        for (PendingDelete delete : pendingDeletes.values()) pending += delete.size;
        for (Long size : reservations.values()) pending += size == null ? 0L : size;
        for (ImportSession session : imports.values()) pending += session.written;
        return pending;
    }

    // ---- clear / remove ---------------------------------------------------

    public void clear(Callback<JSONObject> callback) {
        generation.incrementAndGet();
        for (HttpURLConnection connection : connections) {
            try {
                connection.disconnect();
            } catch (Exception ignored) {
                // Already closed.
            }
        }
        connections.clear();
        boolean queued = submit(() -> {
            long pending;
            synchronized (lock) {
                reservations.clear();
                for (ImportSession session : imports.values()) {
                    //noinspection ResultOfMethodCallIgnored
                    session.file.delete();
                }
                imports.clear();
                for (String key : db.allKeys()) {
                    CacheEntry entry = db.get(key);
                    db.delete(key);
                    if (entry == null) continue;
                    File file = new File(entry.path);
                    if (leased.contains(key)) {
                        pendingDeletes.put(key, new PendingDelete(file, entry.size));
                    } else {
                        //noinspection ResultOfMethodCallIgnored
                        file.delete();
                    }
                }
                pending = pendingBytesLocked();
            }
            notifyEvent("cleared", "", null);
            JSONObject result = new JSONObject();
            try {
                result.put("ok", true);
                result.put("pendingBytes", pending);
            } catch (JSONException ignored) {
                // Primitive values only.
            }
            JSONObject value = result;
            main.post(() -> callback.onResult(value));
        });
        if (!queued) {
            JSONObject result = new JSONObject();
            try {
                result.put("ok", false);
                result.put("pendingBytes", 0);
            } catch (JSONException ignored) {
                // Primitive values only.
            }
            main.post(() -> callback.onResult(result));
        }
    }

    public void remove(String key, Callback<Boolean> callback) {
        if (key == null || key.isEmpty()) {
            main.post(() -> callback.onResult(false));
            return;
        }
        boolean queued = submit(() -> {
            boolean removed;
            synchronized (lock) {
                CacheEntry entry = db.get(key);
                db.delete(key);
                if (entry == null) {
                    removed = false;
                } else {
                    File file = new File(entry.path);
                    if (leased.contains(key)) {
                        pendingDeletes.put(key, new PendingDelete(file, entry.size));
                    } else {
                        //noinspection ResultOfMethodCallIgnored
                        file.delete();
                    }
                    removed = true;
                }
            }
            if (removed) notifyEvent("removed", key, null);
            boolean value = removed;
            main.post(() -> callback.onResult(value));
        });
        if (!queued) main.post(() -> callback.onResult(false));
    }

    // ---- legacy IndexedDB migration --------------------------------------

    public void beginImport(Song song, long size, String mime, Callback<String> callback) {
        io.execute(() -> {
            String id = "";
            synchronized (lock) {
                String key = CacheKeys.build(song, config.accountId(), config.quality());
                boolean allowed = !key.isEmpty() && size > 0 && size <= config.cacheLimitBytes()
                        && imports.size() < MAX_IMPORT_SESSIONS;
                if (allowed) {
                    ImportSession session = new ImportSession();
                    session.id = UUID.randomUUID().toString();
                    session.file = new File(tmpDir, "import-" + session.id + ".part");
                    session.key = key;
                    session.song = song.copy();
                    session.song.resolvedQuality = song.resolvedQuality;
                    session.mime = mime == null ? "" : mime;
                    session.expectedSize = size;
                    session.generation = generation.get();
                    imports.put(session.id, session);
                    id = session.id;
                }
            }
            String value = id;
            main.post(() -> callback.onResult(value));
        });
    }

    public void appendImport(String id, String base64, Callback<Boolean> callback) {
        io.execute(() -> {
            boolean ok = false;
            synchronized (lock) {
                ImportSession session = id == null ? null : imports.get(id);
                if (session != null && session.generation == generation.get()) {
                    try {
                        byte[] chunk = Base64.decode(base64 == null ? "" : base64, Base64.DEFAULT);
                        if (chunk.length > 0 && chunk.length <= MAX_IMPORT_CHUNK
                                && session.written + chunk.length <= session.expectedSize) {
                            try (FileOutputStream output = new FileOutputStream(session.file, true)) {
                                output.write(chunk);
                            }
                            session.written += chunk.length;
                            ok = true;
                        }
                    } catch (Exception error) {
                        Log.w(TAG, "cache import chunk rejected", error);
                    }
                }
            }
            boolean value = ok;
            main.post(() -> callback.onResult(value));
        });
    }

    public void finishImport(String id, Callback<Boolean> callback) {
        io.execute(() -> {
            boolean ok = false;
            synchronized (lock) {
                ImportSession session = id == null ? null : imports.remove(id);
                if (session != null) {
                    try {
                        if (session.generation != generation.get()) throw new CacheFailure("cancelled", "cancelled");
                        if (!config.cacheEnabled()) throw new CacheFailure("disabled", "cancelled");
                        if (session.written != session.expectedSize) throw new CacheFailure("size mismatch", "incomplete");
                        verify(session.file, session.song);
                        if (!ensureCapacityLocked(session.expectedSize, session.key)) {
                            throw new CacheFailure("capacity", "capacity");
                        }
                        CacheEntry entry = CacheEntry.fromSong(session.song, session.key, "",
                                session.expectedSize, session.mime);
                        entry.accountId = session.song.isCloud ? config.accountId() : "public";
                        File target = new File(dir, digest(session.key) + ".audio");
                        move(session.file, target);
                        entry.path = target.getAbsolutePath();
                        db.upsert(entry);
                        ok = true;
                        notifyEvent("cached", session.key, "migrated");
                    } catch (Exception error) {
                        Log.d(TAG, "cache import not published: " + error.getMessage());
                        //noinspection ResultOfMethodCallIgnored
                        session.file.delete();
                        notifyEvent("failed", session.key, "not-offline");
                    }
                }
            }
            boolean value = ok;
            main.post(() -> callback.onResult(value));
        });
    }

    public void cancelImport(String id, Callback<Boolean> callback) {
        io.execute(() -> {
            synchronized (lock) {
                ImportSession session = id == null ? null : imports.remove(id);
                if (session != null) {
                    //noinspection ResultOfMethodCallIgnored
                    session.file.delete();
                }
            }
            main.post(() -> callback.onResult(true));
        });
    }

    // ---- internals --------------------------------------------------------

    private Hit findLocked(Song song, boolean allowFallback) {
        String account = config.accountId();
        String exact = CacheKeys.build(song, account, config.quality());
        if (!exact.isEmpty()) {
            CacheEntry entry = db.get(exact);
            Hit hit = hitFor(entry);
            if (hit != null) {
                db.touch(entry.key, System.currentTimeMillis());
                return hit;
            }
            if (entry != null) discardEntry(entry);
        }
        if (!allowFallback) return null;
        List<CacheEntry> candidates = db.byHash(song.hash, account, song.isCloud);
        List<CacheEntry> matching = new ArrayList<>();
        for (CacheEntry entry : candidates) {
            if (entry.isPreview != CacheKeys.isPreview(song)) continue;
            if (!isUsable(entry)) {
                discardEntry(entry);
                continue;
            }
            matching.add(entry);
        }
        matching.sort((a, b) -> {
            boolean aMatch = a.quality.equals(song.resolvedQuality);
            boolean bMatch = b.quality.equals(song.resolvedQuality);
            if (aMatch != bMatch) return aMatch ? -1 : 1;
            return Long.compare(b.lastAccessAt, a.lastAccessAt);
        });
        for (CacheEntry entry : matching) {
            Hit hit = hitFor(entry);
            if (hit != null) {
                db.touch(entry.key, System.currentTimeMillis());
                return hit;
            }
        }
        return null;
    }

    private Hit hitFor(CacheEntry entry) {
        if (entry == null || !entry.complete || entry.path.isEmpty()) return null;
        File file = new File(entry.path);
        if (!file.isFile() || (entry.size > 0 && file.length() != entry.size)) return null;
        Song song = entry.toSong();
        song.uri = file.getAbsolutePath();
        return new Hit(song, file, entry.key);
    }

    private boolean isUsable(CacheEntry entry) {
        if (entry == null || !entry.complete) return false;
        File file = new File(entry.path);
        return file.isFile() && entry.size > 0 && file.length() == entry.size;
    }

    private void discardEntry(CacheEntry entry) {
        db.delete(entry.key);
        //noinspection ResultOfMethodCallIgnored
        new File(entry.path).delete();
        leased.remove(entry.key);
    }

    private void purgeStaleTempFiles() {
        File[] files = tmpDir.listFiles();
        if (files == null) return;
        for (File file : files) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    private void notifyEvent(String status, String key, String reason) {
        Listener current = listener;
        if (current == null) return;
        main.post(() -> {
            Listener active = listener;
            if (active != null) active.onCacheEvent(status, key, reason);
        });
    }

    private static void move(File from, File to) throws IOException {
        if (to.exists()) {
            //noinspection ResultOfMethodCallIgnored
            to.delete();
        }
        if (!from.renameTo(to)) {
            try (InputStream input = new FileInputStream(from); FileOutputStream output = new FileOutputStream(to)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
                output.flush();
            }
            //noinspection ResultOfMethodCallIgnored
            from.delete();
        }
    }

    private static byte[] trimTo(byte[] source, int length) {
        byte[] target = new byte[length];
        System.arraycopy(source, 0, target, 0, length);
        return target;
    }

    private static long parseLong(String value) {
        if (value == null) return 0L;
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException error) {
            return 0L;
        }
    }

    private static String digest(String key) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(key.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte value : bytes) builder.append(String.format(Locale.US, "%02x", value));
            return builder.toString();
        } catch (Exception error) {
            return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString();
        }
    }

    /** Submits short work unless the repository has already been closed with its controller. */
    private boolean submit(Runnable work) {
        if (closed) return false;
        try {
            io.execute(work);
            return true;
        } catch (java.util.concurrent.RejectedExecutionException error) {
            return false;
        }
    }

    private static Thread named(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }

    /** Stops background work and closes the index; the next controller builds a fresh instance. */
    public void close() {
        closed = true;
        try {
            transfers.shutdownNow();
            io.shutdownNow();
        } catch (Exception ignored) {
            // Already shut down.
        }
        try {
            db.close();
        } catch (Exception error) {
            Log.w(TAG, "cache database close failed", error);
        }
    }

    private static final class CacheFailure extends Exception {
        final String reason;

        CacheFailure(String message, String reason) {
            super(message);
            this.reason = reason;
        }
    }
}
