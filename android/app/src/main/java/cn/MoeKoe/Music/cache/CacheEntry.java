package cn.MoeKoe.Music.cache;

import cn.MoeKoe.Music.playback.Song;

import org.json.JSONException;
import org.json.JSONObject;

/** One verified, complete offline file plus the metadata needed to list it offline. */
public final class CacheEntry {

    public String key = "";
    public String source = "online";
    public String accountId = "public";
    public String hash = "";
    public String playHash = "";
    public String quality = "";
    public boolean isPreview = false;
    public String name = "";
    public String author = "";
    public String album = "";
    public String img = "";
    public long timeLength = 0L;
    public double previewDuration = 0d;
    public String mime = "";
    public long size = 0L;
    public String path = "";
    public boolean complete = true;
    public long createdAt = 0L;
    public long lastAccessAt = 0L;

    public static CacheEntry fromSong(Song song, String key, String path, long size, String mime) {
        CacheEntry entry = new CacheEntry();
        entry.key = key;
        entry.source = song.isCloud ? "cloud" : "online";
        entry.accountId = song.isCloud ? entry.accountId : "public";
        entry.hash = song.hash;
        entry.playHash = song.playHash == null || song.playHash.isEmpty() ? song.hash : song.playHash;
        entry.quality = song.resolvedQuality == null ? "" : song.resolvedQuality;
        entry.isPreview = song.isPreview;
        entry.name = song.name;
        entry.author = song.author;
        entry.album = song.album;
        entry.img = song.img;
        entry.timeLength = song.timeLength;
        entry.previewDuration = song.previewDuration;
        entry.mime = mime == null ? "" : mime;
        entry.size = size;
        entry.path = path;
        entry.complete = true;
        entry.createdAt = System.currentTimeMillis();
        entry.lastAccessAt = entry.createdAt;
        return entry;
    }

    public Song toSong() {
        Song song = new Song();
        song.hash = hash;
        song.playHash = playHash;
        song.resolvedQuality = quality;
        song.qualityLabel = SourceLabels.labelFor(quality);
        song.isCloud = "cloud".equals(source);
        song.isPreview = isPreview;
        song.name = name;
        song.author = author;
        song.album = album;
        song.img = img;
        song.timeLength = timeLength;
        song.previewDuration = previewDuration;
        song.cacheKey = key;
        song.uri = path;
        return song;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("hash", hash);
        o.put("name", name);
        o.put("author", author);
        o.put("album", album);
        o.put("img", img);
        o.put("timeLength", timeLength);
        o.put("playHash", playHash);
        o.put("resolvedQuality", quality);
        o.put("qualityLabel", SourceLabels.labelFor(quality));
        o.put("isCloud", "cloud".equals(source));
        o.put("isLocal", false);
        o.put("isPreview", isPreview);
        o.put("cacheKey", key);
        o.put("itemId", ("cloud".equals(source) ? "cloud:" : "online:") + hash);
        o.put("size", size);
        o.put("complete", complete);
        if (previewDuration > 0) o.put("previewDuration", previewDuration);
        return o;
    }
}
