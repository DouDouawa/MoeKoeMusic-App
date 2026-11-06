package cn.MoeKoe.Music.cache;

import cn.MoeKoe.Music.playback.Song;

import org.json.JSONArray;

/**
 * The one cache identity used by native storage.
 *
 * <p>It is byte-for-byte the key the web facade builds with
 * {@code JSON.stringify(['v2', source, accountId, hash, playHash, resolvedQuality, isPreview])},
 * so records created by either side describe the same thing. Cloud drive entries are scoped to
 * the account currently configured, never to an account id carried by a list item.
 */
public final class CacheKeys {

    public static final String DEFAULT_QUALITY = "128";

    private CacheKeys() {}

    public static String build(Song song, String accountId, String fallbackQuality) {
        if (song == null || song.isLocal) return "";
        String source = song.isCloud ? "cloud" : "online";
        String hash = trim(song.hash);
        if (hash.isEmpty()) return "";
        String account = "cloud".equals(source) ? trim(accountId) : "public";
        if ("cloud".equals(source) && account.isEmpty()) return "";
        String playHash = trim(song.playHash);
        if (playHash.isEmpty()) playHash = hash;
        String quality = trim(song.resolvedQuality);
        if (quality.isEmpty()) quality = "cloud".equals(source) ? "original" : defaultQuality(fallbackQuality);
        JSONArray identity = new JSONArray();
        identity.put("v2");
        identity.put(source);
        identity.put(account);
        identity.put(hash);
        identity.put(playHash);
        identity.put(quality);
        identity.put(isPreview(song));
        return identity.toString();
    }

    /** Identity prefix used to find quality fallbacks for one logical song. */
    public static String logicalKey(Song song, String accountId) {
        if (song == null || song.isLocal) return "";
        String hash = trim(song.hash);
        if (hash.isEmpty()) return "";
        String source = song.isCloud ? "cloud" : "online";
        String account = "cloud".equals(source) ? trim(accountId) : "public";
        if ("cloud".equals(source) && account.isEmpty()) return "";
        return source + "|" + account + "|" + hash;
    }

    public static boolean isPreview(Song song) {
        return song != null && song.isPreview;
    }

    public static String defaultQuality(String fallbackQuality) {
        String value = trim(fallbackQuality);
        return value.isEmpty() ? DEFAULT_QUALITY : value;
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
