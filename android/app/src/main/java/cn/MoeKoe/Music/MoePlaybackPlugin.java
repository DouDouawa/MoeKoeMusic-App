package cn.MoeKoe.Music;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.activity.result.ActivityResult;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

import cn.MoeKoe.Music.playback.AudioPicker;
import cn.MoeKoe.Music.playback.PcmRecorder;
import cn.MoeKoe.Music.playback.PlaybackController;
import cn.MoeKoe.Music.playback.PlaybackService;
import cn.MoeKoe.Music.playback.Song;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Local bridge for the native playback backend.
 *
 * <p>Every stateful decision (queue, mode, resolution, cache, recording) happens in Java; the
 * WebView only sends commands and mirrors the returned state. Seconds are used for all positions
 * and durations, volume is 0..1, matching the JS contract.
 *
 * <p>Capacitor dispatches plugin methods on its own handler thread, while ExoPlayer and the media
 * session are bound to the main looper, so all player work is marshalled through {@link #onMain}.
 */
@CapacitorPlugin(
        name = "MoePlayback",
        permissions = {
                @Permission(alias = "microphone", strings = {Manifest.permission.RECORD_AUDIO})
        }
)
public class MoePlaybackPlugin extends Plugin implements PlaybackController.Observer {

    private static final String TAG = "MoePlaybackPlugin";

    private static WeakReference<MoePlaybackPlugin> current = new WeakReference<>(null);

    private final Handler main = new Handler(Looper.getMainLooper());
    private final PcmRecorder recorder = new PcmRecorder();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "moe-plugin-worker");
        thread.setDaemon(true);
        return thread;
    });

    private PlaybackController bound;

    /** Used by the activity for the system back gesture. */
    public static MoePlaybackPlugin instance() {
        return current.get();
    }

    @Override
    public void load() {
        current = new WeakReference<>(this);
    }

    // ---- configuration ----------------------------------------------------

    @PluginMethod
    public void configure(PluginCall call) {
        final JSONObject patch = json(call.getData());
        onMain(() -> {
            attach().config().apply(patch);
            resolve(call, new JSONObject());
        });
    }

    // ---- state ------------------------------------------------------------

    @PluginMethod
    public void getState(PluginCall call) {
        onMain(() -> resolve(call, attach().state()));
    }

    @Override
    public void onPlaybackState() {
        PlaybackController controller = bound;
        if (controller == null) return;
        onMain(() -> {
            try {
                notifyListeners("state", JSObject.fromJSONObject(controller.state()));
            } catch (JSONException error) {
                Log.w(TAG, "state event could not be serialized", error);
            }
        });
    }

    @Override
    public void onPlaybackTick() {
        PlaybackController controller = bound;
        if (controller == null) return;
        onMain(() -> {
            JSONObject state = controller.state();
            JSObject payload = new JSObject();
            payload.put("position", state.optDouble("position", 0d));
            payload.put("duration", state.optDouble("duration", 0d));
            payload.put("playing", state.optBoolean("playing", false));
            payload.put("revision", state.optInt("revision", 0));
            notifyListeners("position", payload);
        });
    }

    // ---- commands ---------------------------------------------------------

    @PluginMethod
    public void play(PluginCall call) {
        final Song song = Song.fromJson(json(call.getObject("song")));
        if (song == null) {
            call.reject("song is required");
            return;
        }
        final double position = number(call.getDouble("position"), 0d);
        final boolean autoPlay = !Boolean.FALSE.equals(call.getBoolean("autoPlay", Boolean.TRUE));
        onMain(() -> {
            if (autoPlay) PlaybackService.start(getContext());
            attach().play(song, position, autoPlay);
            resolve(call, new JSONObject());
        });
    }

    @PluginMethod
    public void setQueue(PluginCall call) {
        final List<Song> songs = new ArrayList<>();
        JSONArray array = call.getArray("songs");
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                Song song = Song.fromJson(array.optJSONObject(i));
                if (song != null) songs.add(song);
            }
        }
        final boolean append = Boolean.TRUE.equals(call.getBoolean("append", Boolean.FALSE));
        final Integer startIndex = call.getInt("startIndex");
        final boolean autoPlay = Boolean.TRUE.equals(call.getBoolean("autoPlay", Boolean.FALSE));
        onMain(() -> {
            if (startIndex != null && autoPlay) PlaybackService.start(getContext());
            attach().setQueue(songs, append, startIndex, autoPlay);
            resolve(call, new JSONObject());
        });
    }

    @PluginMethod
    public void pause(PluginCall call) {
        onMain(() -> {
            attach().pause();
            resolve(call, new JSONObject());
        });
    }

    @PluginMethod
    public void resume(PluginCall call) {
        onMain(() -> {
            PlaybackService.start(getContext());
            attach().resume();
            resolve(call, new JSONObject());
        });
    }

    @PluginMethod
    public void stop(PluginCall call) {
        onMain(() -> {
            attach().stop();
            resolve(call, new JSONObject());
        });
    }

    @PluginMethod
    public void next(PluginCall call) {
        onMain(() -> {
            PlaybackService.start(getContext());
            attach().next();
            resolve(call, new JSONObject());
        });
    }

    @PluginMethod
    public void previous(PluginCall call) {
        onMain(() -> {
            PlaybackService.start(getContext());
            attach().previous();
            resolve(call, new JSONObject());
        });
    }

    @PluginMethod
    public void seek(PluginCall call) {
        final double position = number(call.getDouble("position"), 0d);
        onMain(() -> {
            attach().seek(position);
            resolve(call, new JSONObject());
        });
    }

    @PluginMethod
    public void setOptions(PluginCall call) {
        final Integer mode = call.getInt("mode");
        final Double speed = call.getDouble("speed");
        final Double volume = call.getDouble("volume");
        final Boolean muted = call.getBoolean("muted");
        onMain(() -> {
            attach().setOptions(mode, speed == null ? null : speed.floatValue(),
                    volume == null ? null : volume.floatValue(), muted);
            resolve(call, new JSONObject());
        });
    }

    @PluginMethod
    public void remove(PluginCall call) {
        final String itemId = call.getString("itemId", "");
        onMain(() -> {
            attach().remove(itemId);
            resolve(call, new JSONObject());
        });
    }

    @PluginMethod
    public void insertNext(PluginCall call) {
        final Song song = Song.fromJson(json(call.getObject("song")));
        if (song == null) {
            call.reject("song is required");
            return;
        }
        onMain(() -> {
            attach().insertNext(song);
            resolve(call, new JSONObject());
        });
    }

    @PluginMethod
    public void finishBack(PluginCall call) {
        onMain(() -> {
            Activity activity = getActivity();
            if (activity != null) activity.finish();
            resolve(call, new JSONObject());
        });
    }

    /**
     * Sends the app to the background instead of destroying it, so the playback service keeps
     * running. This is what the back gesture does once the WebView has nothing left to pop.
     */
    @PluginMethod
    public void minimizeApp(PluginCall call) {
        onMain(() -> {
            Activity activity = getActivity();
            if (activity != null) activity.moveTaskToBack(true);
            resolve(call, new JSONObject());
        });
    }

    /** Called by the activity when the system back gesture arrives. */
    public boolean handleBackPressed() {
        if (!hasListeners("backButton")) return false;
        JSObject payload = new JSObject();
        payload.put("canGoBack", true);
        notifyListeners("backButton", payload);
        return true;
    }

    // ---- cache ------------------------------------------------------------

    @PluginMethod
    public void getCacheStats(PluginCall call) {
        onMain(() -> attach().cache().stats(stats -> resolve(call, stats)));
    }

    @PluginMethod
    public void listCachedSongs(PluginCall call) {
        onMain(() -> attach().cache().list(songs -> {
            JSArray array = new JSArray();
            for (JSONObject song : songs) array.put(song);
            JSObject result = new JSObject();
            result.put("songs", array);
            call.resolve(result);
        }));
    }

    @PluginMethod
    public void findCachedSong(PluginCall call) {
        final Song song = Song.fromJson(json(call.getObject("song")));
        final boolean allowFallback = Boolean.TRUE.equals(call.getBoolean("allowFallback", Boolean.FALSE));
        if (song == null) {
            JSObject result = new JSObject();
            result.put("song", JSONObject.NULL);
            call.resolve(result);
            return;
        }
        onMain(() -> attach().cache().songJson(song, allowFallback, cached -> {
            JSObject result = new JSObject();
            result.put("song", cached == null ? JSONObject.NULL : cached);
            call.resolve(result);
        }));
    }

    @PluginMethod
    public void clearCache(PluginCall call) {
        onMain(() -> attach().cache().clear(result -> resolve(call, result)));
    }

    @PluginMethod
    public void removeCachedSong(PluginCall call) {
        final String key = call.getString("key", "");
        onMain(() -> attach().cache().remove(key, removed -> {
            JSObject result = new JSObject();
            result.put("ok", removed);
            call.resolve(result);
        }));
    }

    @PluginMethod
    public void beginCacheImport(PluginCall call) {
        final Song song = Song.fromJson(json(call.getObject("song")));
        final long size = number(call.getLong("size"), 0L);
        final String mime = call.getString("mime", "");
        if (song == null) {
            JSObject result = new JSObject();
            result.put("id", "");
            call.resolve(result);
            return;
        }
        onMain(() -> attach().cache().beginImport(song, size, mime, id -> {
            JSObject result = new JSObject();
            result.put("id", id == null ? "" : id);
            call.resolve(result);
        }));
    }

    @PluginMethod
    public void appendCacheImport(PluginCall call) {
        final String id = call.getString("id", "");
        final String data = call.getString("data", "");
        onMain(() -> attach().cache().appendImport(id, data, ok -> {
            JSObject result = new JSObject();
            result.put("ok", ok);
            call.resolve(result);
        }));
    }

    @PluginMethod
    public void finishCacheImport(PluginCall call) {
        final String id = call.getString("id", "");
        onMain(() -> attach().cache().finishImport(id, ok -> {
            JSObject result = new JSObject();
            result.put("ok", ok);
            call.resolve(result);
        }));
    }

    @PluginMethod
    public void cancelCacheImport(PluginCall call) {
        final String id = call.getString("id", "");
        onMain(() -> attach().cache().cancelImport(id, ok -> {
            JSObject result = new JSObject();
            result.put("ok", true);
            call.resolve(result);
        }));
    }

    // ---- local files ------------------------------------------------------

    @PluginMethod
    public void pickAudioFiles(PluginCall call) {
        onMain(() -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("audio/*")
                    .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            startActivityForResult(call, intent, "filesPicked");
        });
    }

    @PluginMethod
    public void pickAudioDirectory(PluginCall call) {
        onMain(() -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            startActivityForResult(call, intent, "directoryPicked");
        });
    }

    @ActivityCallback
    private void filesPicked(PluginCall call, ActivityResult result) {
        if (call == null) return;
        if (result.getResultCode() != Activity.RESULT_OK) {
            resolveSongs(call, new ArrayList<>());
            return;
        }
        Intent data = result.getData();
        List<Uri> uris = new ArrayList<>();
        if (data != null) {
            ClipData clip = data.getClipData();
            if (clip != null) {
                for (int i = 0; i < clip.getItemCount(); i++) {
                    Uri uri = clip.getItemAt(i).getUri();
                    if (uri != null) uris.add(uri);
                }
            } else if (data.getData() != null) {
                uris.add(data.getData());
            }
        }
        persistReadGrants(uris);
        worker.execute(() -> {
            List<Song> songs = new ArrayList<>();
            for (Uri uri : uris) songs.add(AudioPicker.fromUri(getContext(), uri));
            resolveSongs(call, songs);
        });
    }

    @ActivityCallback
    private void directoryPicked(PluginCall call, ActivityResult result) {
        if (call == null) return;
        if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null
                || result.getData().getData() == null) {
            resolveSongs(call, new ArrayList<>());
            return;
        }
        Uri treeUri = result.getData().getData();
        persistReadGrants(java.util.Collections.singletonList(treeUri));
        worker.execute(() -> resolveSongs(call, AudioPicker.fromTree(getContext(), treeUri)));
    }

    private void persistReadGrants(List<Uri> uris) {
        for (Uri uri : uris) {
            try {
                getContext().getContentResolver().takePersistableUriPermission(uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException error) {
                // Some providers only grant a transient read permission; playback still works now.
                Log.d(TAG, "provider does not offer a persisted read grant");
            }
        }
    }

    private void resolveSongs(PluginCall call, List<Song> songs) {
        JSArray array = new JSArray();
        for (Song song : songs) {
            try {
                array.put(song.toJson());
            } catch (JSONException error) {
                Log.w(TAG, "local song could not be serialized", error);
            }
        }
        JSObject result = new JSObject();
        result.put("songs", array);
        call.resolve(result);
    }

    // ---- recording --------------------------------------------------------

    @PluginMethod
    public void startRecording(PluginCall call) {
        onMain(() -> {
            if (getPermissionState("microphone") != PermissionState.GRANTED) {
                requestPermissionForAlias("microphone", call, "microphonePermission");
                return;
            }
            startRecordingNow(call);
        });
    }

    @PermissionCallback
    private void microphonePermission(PluginCall call) {
        if (call == null) return;
        if (getPermissionState("microphone") != PermissionState.GRANTED) {
            call.reject("需要麦克风权限");
            return;
        }
        onMain(() -> startRecordingNow(call));
    }

    private void startRecordingNow(PluginCall call) {
        if (recorder.isRecording()) {
            resolve(call, new JSONObject());
            return;
        }
        try {
            recorder.start();
            resolve(call, new JSONObject());
        } catch (Exception error) {
            call.reject(error.getMessage() == null ? "录音启动失败" : error.getMessage());
        }
    }

    @PluginMethod
    public void stopRecording(PluginCall call) {
        final boolean cancel = Boolean.TRUE.equals(call.getBoolean("cancel", Boolean.FALSE));
        worker.execute(() -> {
            PcmRecorder.Result result = recorder.stop(cancel);
            JSObject payload = new JSObject();
            payload.put("pcmBase64", result.pcmBase64);
            payload.put("sampleRate", result.sampleRate);
            payload.put("durationMs", result.durationMs);
            payload.put("cancelled", result.cancelled);
            call.resolve(payload);
        });
    }

    // ---- lifecycle --------------------------------------------------------

    @Override
    protected void handleOnDestroy() {
        recorder.cancel();
        worker.shutdownNow();
        if (bound != null) {
            bound.removeObserver(this);
            bound = null;
        }
        super.handleOnDestroy();
    }

    // ---- helpers ----------------------------------------------------------

    private void onMain(Runnable work) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            work.run();
        } else {
            main.post(work);
        }
    }

    private PlaybackController attach() {
        PlaybackController controller = PlaybackController.get(getContext());
        if (bound != controller) {
            if (bound != null) bound.removeObserver(this);
            bound = controller;
            controller.addObserver(this);
            controller.cache().setListener(this::onCacheEvent);
        }
        return controller;
    }

    private void onCacheEvent(String status, String cacheKey, String reason) {
        onMain(() -> {
            JSObject payload = new JSObject();
            payload.put("status", status);
            payload.put("cacheKey", cacheKey == null ? "" : cacheKey);
            if (reason != null) payload.put("reason", reason);
            notifyListeners("cacheChange", payload);
        });
    }

    private void resolve(PluginCall call, JSONObject payload) {
        try {
            call.resolve(JSObject.fromJSONObject(payload == null ? new JSONObject() : payload));
        } catch (JSONException error) {
            call.reject(error.getMessage());
        }
    }

    private static JSONObject json(JSONObject object) {
        if (object == null) return null;
        try {
            return new JSONObject(object.toString());
        } catch (JSONException error) {
            return null;
        }
    }

    private static double number(Double value, double fallback) {
        return value == null || !Double.isFinite(value) ? fallback : value;
    }

    private static long number(Long value, long fallback) {
        return value == null ? fallback : value;
    }
}
