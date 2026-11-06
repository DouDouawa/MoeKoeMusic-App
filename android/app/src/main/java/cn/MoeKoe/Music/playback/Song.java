package cn.MoeKoe.Music.playback;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The native mirror of the web player's song descriptor.
 *
 * <p>The queue, resolver and cache all speak this single type so a song never has to be
 * re-hydrated through the WebView. Fields that only matter inside the process ({@code uri},
 * {@code url}) are deliberately kept out of the state JSON: signed CDN URLs are not something
 * the UI needs, and they expire anyway.
 */
public final class Song {

    public String itemId = "";
    public String hash = "";
    public String name = "";
    public String author = "";
    public String album = "";
    public String img = "";
    /** Track length in milliseconds, matching the JS field. */
    public long timeLength = 0L;
    public String playHash = "";
    /** Quality the UI explicitly asked for. Takes priority over the configured default. */
    public String requestedQuality = "";
    public String resolvedQuality = "";
    public String qualityLabel = "";
    public JSONArray qualityOptions = new JSONArray();
    public boolean isLocal = false;
    public boolean isCloud = false;
    public boolean isPreview = false;
    /** {@code content://} for SAF picks, {@code file://}/absolute path for cached audio. Internal. */
    public String uri = "";
    /** Last resolved remote URL. Internal: never serialized into state. */
    public String url = "";
    public long mixSongId = 0L;
    public String cacheKey = "";
    public double previewDuration = 0d;

    public static Song fromJson(JSONObject o) {
        if (o == null) return null;
        Song s = new Song();
        s.itemId = text(o, "itemId");
        s.hash = text(o, "hash");
        s.name = text(o, "name");
        s.author = text(o, "author");
        s.album = text(o, "album");
        s.img = text(o, "img");
        s.timeLength = number(o, "timeLength");
        s.playHash = text(o, "playHash");
        s.requestedQuality = text(o, "requestedQuality");
        s.resolvedQuality = text(o, "resolvedQuality");
        s.qualityLabel = text(o, "qualityLabel");
        JSONArray options = o.optJSONArray("qualityOptions");
        s.qualityOptions = options != null ? options : new JSONArray();
        s.isLocal = o.optBoolean("isLocal", false);
        s.isCloud = o.optBoolean("isCloud", false);
        s.isPreview = o.optBoolean("isPreview", false);
        s.uri = text(o, "uri");
        s.url = text(o, "url");
        s.mixSongId = number(o, "mixsongid");
        s.cacheKey = text(o, "cacheKey");
        s.previewDuration = o.optDouble("previewDuration", 0d);
        return s;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("itemId", itemId);
        o.put("hash", hash);
        o.put("name", name);
        o.put("author", author);
        o.put("album", album);
        o.put("img", img);
        o.put("timeLength", timeLength);
        o.put("playHash", playHash);
        o.put("requestedQuality", requestedQuality);
        o.put("resolvedQuality", resolvedQuality);
        o.put("qualityLabel", qualityLabel);
        o.put("qualityOptions", qualityOptions);
        o.put("isLocal", isLocal);
        o.put("isCloud", isCloud);
        o.put("isPreview", isPreview);
        o.put("mixsongid", mixSongId);
        o.put("cacheKey", cacheKey);
        o.put("uri", uri);
        o.put("url", url);
        o.put("previewDuration", previewDuration);
        return o;
    }

    /** Display metadata only: no signed URLs, no local absolute paths, no credentials. */
    public JSONObject toStateJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("itemId", itemId);
        o.put("hash", hash);
        o.put("name", name);
        o.put("author", author);
        o.put("album", album);
        o.put("img", img);
        o.put("timeLength", timeLength);
        o.put("playHash", playHash);
        o.put("requestedQuality", requestedQuality);
        o.put("resolvedQuality", resolvedQuality);
        o.put("qualityLabel", qualityLabel);
        o.put("qualityOptions", qualityOptions);
        o.put("isLocal", isLocal);
        o.put("isCloud", isCloud);
        o.put("isPreview", isPreview);
        o.put("mixsongid", mixSongId);
        o.put("cacheKey", cacheKey);
        if (previewDuration > 0) o.put("previewDuration", previewDuration);
        return o;
    }

    /** Persisted form: signed CDN URLs and cache paths are dropped, local URIs are kept. */
    public JSONObject toPersistedJson() throws JSONException {
        JSONObject o = toJson();
        if (!isLocal) {
            o.put("url", "");
            o.put("uri", "");
            o.put("cacheKey", "");
        }
        return o;
    }

    public Song copy() {
        Song s = new Song();
        s.itemId = itemId;
        s.hash = hash;
        s.name = name;
        s.author = author;
        s.album = album;
        s.img = img;
        s.timeLength = timeLength;
        s.playHash = playHash;
        s.requestedQuality = requestedQuality;
        s.resolvedQuality = resolvedQuality;
        s.qualityLabel = qualityLabel;
        try {
            s.qualityOptions = qualityOptions == null ? new JSONArray() : new JSONArray(qualityOptions.toString());
        } catch (JSONException error) {
            s.qualityOptions = new JSONArray();
        }
        s.isLocal = isLocal;
        s.isCloud = isCloud;
        s.isPreview = isPreview;
        s.uri = uri;
        s.url = url;
        s.mixSongId = mixSongId;
        s.cacheKey = cacheKey;
        s.previewDuration = previewDuration;
        return s;
    }

    /** Copies playable metadata (never the source) from {@code other} onto this entry. */
    public void mergeFrom(Song other) {
        if (!other.name.isEmpty()) name = other.name;
        if (!other.author.isEmpty()) author = other.author;
        if (!other.album.isEmpty()) album = other.album;
        if (!other.img.isEmpty()) img = other.img;
        if (other.timeLength > 0) timeLength = other.timeLength;
        if (!other.playHash.isEmpty()) playHash = other.playHash;
        if (!other.qualityLabel.isEmpty()) qualityLabel = other.qualityLabel;
        if (other.qualityOptions != null && other.qualityOptions.length() > 0) qualityOptions = other.qualityOptions;
        if (other.mixSongId > 0) mixSongId = other.mixSongId;
        if (!other.uri.isEmpty()) uri = other.uri;
        previewDuration = other.previewDuration > 0 ? other.previewDuration : previewDuration;
        isPreview = other.isPreview;
    }

    /** Same logical queue entry: identical item, ignoring transient quality/source changes. */
    public boolean sameEntry(Song other) {
        if (other == null) return false;
        if (!itemId.isEmpty() && !other.itemId.isEmpty() && itemId.equals(other.itemId)) return true;
        return hash.equals(other.hash) && isCloud == other.isCloud && isLocal == other.isLocal;
    }

    public String source() {
        return isCloud ? "cloud" : "online";
    }

    public boolean isCacheable() {
        return !isLocal && !hash.isEmpty();
    }

    private static String text(JSONObject o, String key) {
        String value = o.optString(key, "");
        return value == null ? "" : value;
    }

    private static long number(JSONObject o, String key) {
        double value = o.optDouble(key, 0d);
        return Double.isFinite(value) ? (long) value : 0L;
    }
}
