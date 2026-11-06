package cn.MoeKoe.Music.playback;

import android.net.Uri;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Native replacement for the web URL resolution path.
 *
 * <p>Contracts copied from {@code OnlineMusicQueue}/{@code CloudMusicQueue}:
 * unauthenticated playback asks {@code /song/url?free_part=1}; authenticated playback walks the
 * quality ladder from the requested quality downwards, using {@code /privilege/lite} to learn
 * which hashes exist and skipping mp4 renditions. Cloud drive files use {@code /user/cloud/url}.
 *
 * <p>The API authorization header is attached to these calls only. Resolved CDN URLs are handed
 * to the player untouched and never receive that header.
 */
public final class SourceResolver {

    private static final String TAG = "MoeResolver";

    public static final String[] QUALITY_LEVELS = {"128", "320", "flac", "high", "viper_atmos", "viper_clear", "viper_tape"};
    private static final String[] QUALITY_LABELS = {"标准", "高品", "FLAC", "Hi-Res", "全景声", "超清", "母带"};

    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int READ_TIMEOUT_MS = 15000;
    private static final int MAX_BODY_BYTES = 4 * 1024 * 1024;
    private static final int NETWORK_ATTEMPTS = 3;

    public enum ErrorKind { NONE, NETWORK, AUTH, RISK, UNAVAILABLE, NOT_CONFIGURED, BAD_RESPONSE, NO_URL }

    public static final class Error {
        public final ErrorKind kind;
        public final String message;

        Error(ErrorKind kind, String message) {
            this.kind = kind;
            this.message = message;
        }
    }

    public static final class Resolution {
        public String url = "";
        public String playHash = "";
        public String resolvedQuality = "";
        public String qualityLabel = "";
        public JSONArray qualityOptions = new JSONArray();
        public long mixSongId = 0L;
        public long timeLengthMs = 0L;
        public boolean preview = false;
        public Error error;

        public boolean ok() {
            return error == null && !url.isEmpty();
        }
    }

    /** Resolves the stream for one song. Blocking: call from a worker thread. */
    public Resolution resolve(Song song, ConfigStore config) {
        if (song == null) return fail(ErrorKind.UNAVAILABLE, "没有可播放的歌曲");
        if (song.isCloud) return resolveCloud(song, config);

        String base = config.apiBaseUrl();
        if (base.isEmpty()) return fail(ErrorKind.NOT_CONFIGURED, "未配置 API 地址");
        String authorization = config.authenticated() ? config.authorization() : "";
        boolean authenticated = config.authenticated() && !authorization.isEmpty();

        Resolution result = new Resolution();
        if (!authenticated) {
            try {
                JSONObject response = getJson(base, "/song/url",
                        params("hash", song.hash, "free_part", "1"), authorization);
                return applySongUrlResponse(result, response, song.hash, "");
            } catch (ApiError error) {
                return fail(error.kind, error.getMessage());
            } catch (IOException error) {
                return fail(ErrorKind.NETWORK, networkMessage(error));
            }
        }

        String target = normalizeQuality(song.requestedQuality.isEmpty() ? config.quality() : song.requestedQuality);
        List<Candidate> candidates = new ArrayList<>();
        JSONArray options = new JSONArray();
        try {
            JSONObject privilege = getJson(base, "/privilege/lite", params("hash", song.hash), authorization);
            checkPayload(privilege);
            options = qualityOptions(privilege);
            long albumAudioId = privilege.optJSONArray("data") != null && privilege.optJSONArray("data").length() > 0
                    ? privilege.optJSONArray("data").optJSONObject(0).optLong("album_audio_id", 0L) : 0L;
            if (albumAudioId > 0) result.mixSongId = albumAudioId;
            candidates = privilegeCandidates(options, target, song.hash);
        } catch (ApiError error) {
            if (error.kind == ErrorKind.AUTH || error.kind == ErrorKind.RISK) return fail(error.kind, error.getMessage());
            Log.w(TAG, "privilege/lite unavailable, falling back to raw hash: " + error.getMessage());
            candidates = rawCandidates(target, song.hash);
        } catch (IOException error) {
            // A lost network is not "this song is unavailable": report it so the caller retries.
            return fail(ErrorKind.NETWORK, networkMessage(error));
        }

        JSONObject lastResponse = null;
        for (Candidate candidate : candidates) {
            try {
                JSONObject response = getJson(base, "/song/url", params(
                        "hash", candidate.hash,
                        "quality", candidate.quality,
                        "ppage_id", "356753938"), authorization);
                checkPayload(response);
                lastResponse = response;
                if (response.optInt("status", 0) != 1) continue;
                if ("mp4".equalsIgnoreCase(response.optString("extName", ""))) continue;
                JSONArray urls = response.optJSONArray("url");
                if (urls == null || urls.length() == 0 || urls.optString(0, "").isEmpty()) continue;
                Resolution resolved = applySongUrlResponse(result, response, candidate.hash, candidate.quality);
                resolved.qualityOptions = options;
                return resolved;
            } catch (ApiError error) {
                if (error.kind == ErrorKind.AUTH || error.kind == ErrorKind.RISK) return fail(error.kind, error.getMessage());
                Log.w(TAG, "candidate " + candidate.quality + " failed: " + error.getMessage());
            } catch (IOException error) {
                return fail(ErrorKind.NETWORK, networkMessage(error));
            }
        }
        return classifyUnavailable(lastResponse);
    }

    private Resolution resolveCloud(Song song, ConfigStore config) {
        String base = config.apiBaseUrl();
        if (base.isEmpty()) return fail(ErrorKind.NOT_CONFIGURED, "未配置 API 地址");
        String authorization = config.authenticated() ? config.authorization() : "";
        if (authorization.isEmpty()) return fail(ErrorKind.AUTH, "请先登录云盘账号");
        try {
            JSONObject response = getJson(base, "/user/cloud/url", params("hash", song.hash), authorization);
            checkPayload(response);
            if (response.optInt("status", 0) != 1) return classifyUnavailable(response);
            JSONObject data = response.optJSONObject("data");
            String url = data == null ? "" : data.optString("url", "");
            if (url.isEmpty()) return fail(ErrorKind.NO_URL, "云盘未返回可用地址");
            Resolution result = new Resolution();
            result.url = url;
            result.playHash = song.hash;
            result.resolvedQuality = "original";
            result.qualityLabel = "原文件";
            return result;
        } catch (ApiError error) {
            return fail(error.kind, error.getMessage());
        } catch (IOException error) {
            return fail(ErrorKind.NETWORK, networkMessage(error));
        }
    }

    private Resolution applySongUrlResponse(Resolution result, JSONObject response, String playHash, String quality) {
        if (response == null) return fail(ErrorKind.BAD_RESPONSE, "歌曲地址响应为空");
        JSONArray urls = response.optJSONArray("url");
        String url = urls == null || urls.length() == 0 ? "" : urls.optString(0, "");
        if (url.isEmpty()) return classifyUnavailable(response);
        result.url = url;
        result.playHash = playHash == null ? "" : playHash;
        result.resolvedQuality = quality == null ? "" : quality;
        result.qualityLabel = quality == null || quality.isEmpty() ? "" : qualityLabel(quality);
        double timeLength = response.optDouble("timeLength", 0d);
        if (Double.isFinite(timeLength) && timeLength > 0) result.timeLengthMs = (long) (timeLength * 1000);
        // Whether a stream is a preview is decided by the song descriptor, not guessed here.
        return result;
    }

    private Resolution classifyUnavailable(JSONObject response) {
        if (response == null) return fail(ErrorKind.UNAVAILABLE, "未获取到播放地址");
        int status = response.optInt("status", 0);
        if (status == 2) return fail(ErrorKind.AUTH, "登录已失效，请重新登录");
        String error = response.optString("error", "");
        if (error.contains("验证")) return fail(ErrorKind.RISK, "帐号触发风控，请稍后重试");
        if (status == 3) return fail(ErrorKind.UNAVAILABLE, "该歌曲暂无版权");
        return fail(ErrorKind.UNAVAILABLE, "未获取到播放地址");
    }

    private void checkPayload(JSONObject response) throws ApiError {
        if (response == null) throw new ApiError(ErrorKind.BAD_RESPONSE, "响应不是 API 数据");
        int status = response.optInt("status", -1);
        if (status < 0) throw new ApiError(ErrorKind.BAD_RESPONSE, "响应不是 API 数据");
        if (status == 2) throw new ApiError(ErrorKind.AUTH, "登录已失效，请重新登录");
        String error = response.optString("error", "");
        if (error.contains("验证")) throw new ApiError(ErrorKind.RISK, "帐号触发风控，请稍后重试");
    }

    // ---- quality handling -------------------------------------------------

    public static String normalizeQuality(String quality) {
        if (quality != null) {
            for (String level : QUALITY_LEVELS) {
                if (level.equals(quality)) return quality;
            }
        }
        return QUALITY_LEVELS[0];
    }

    public static String qualityLabel(String quality) {
        for (int i = 0; i < QUALITY_LEVELS.length; i++) {
            if (QUALITY_LEVELS[i].equals(quality)) return QUALITY_LABELS[i];
        }
        return "";
    }

    /** Requested quality first, then everything below it. Never upgrade silently. */
    public static List<String> fallbackChain(String quality) {
        String normalized = normalizeQuality(quality);
        int index = 0;
        for (int i = 0; i < QUALITY_LEVELS.length; i++) {
            if (QUALITY_LEVELS[i].equals(normalized)) {
                index = i;
                break;
            }
        }
        List<String> chain = new ArrayList<>(index + 1);
        for (int i = index; i >= 0; i--) chain.add(QUALITY_LEVELS[i]);
        return chain;
    }

    /** {@code {quality: {hash,label}}} from a privilege response, highest quality first. */
    public static JSONArray qualityOptions(JSONObject privilege) {
        Map<String, JSONObject> byQuality = new LinkedHashMap<>();
        JSONArray data = privilege == null ? null : privilege.optJSONArray("data");
        if (data != null) {
            for (int i = 0; i < data.length(); i++) {
                JSONObject item = data.optJSONObject(i);
                if (item == null) continue;
                collectVariant(item, byQuality);
                JSONArray related = item.optJSONArray("relate_goods");
                if (related == null) continue;
                for (int j = 0; j < related.length(); j++) collectVariant(related.optJSONObject(j), byQuality);
            }
        }
        JSONArray options = new JSONArray();
        for (String quality : QUALITY_LEVELS) {
            JSONObject option = byQuality.get(quality);
            if (option != null) options.put(option);
        }
        // QUALITY_LEVELS ascends, so reverse to present the highest first like the web UI does.
        JSONArray sorted = new JSONArray();
        for (int i = options.length() - 1; i >= 0; i--) sorted.put(options.optJSONObject(i));
        return sorted;
    }

    private static void collectVariant(JSONObject variant, Map<String, JSONObject> target) {
        if (variant == null) return;
        String hash = variant.optString("hash", "");
        String quality = variant.optString("quality", "");
        if (hash.isEmpty() || variant.optInt("level", 1) == 0) return;
        if (!isQuality(quality) || target.containsKey(quality)) return;
        try {
            target.put(quality, new JSONObject()
                    .put("value", quality)
                    .put("hash", hash)
                    .put("label", qualityLabel(quality)));
        } catch (JSONException ignored) {
            // Values are strings; this cannot fail in practice.
        }
    }

    private static boolean isQuality(String quality) {
        for (String level : QUALITY_LEVELS) {
            if (level.equals(quality)) return true;
        }
        return false;
    }

    private static List<Candidate> privilegeCandidates(JSONArray options, String target, String originalHash) {
        Map<String, String> hashByQuality = new LinkedHashMap<>();
        for (int i = 0; i < options.length(); i++) {
            JSONObject option = options.optJSONObject(i);
            if (option == null) continue;
            hashByQuality.put(option.optString("value", ""), option.optString("hash", ""));
        }
        List<Candidate> candidates = new ArrayList<>();
        for (String quality : fallbackChain(target)) {
            String hash = hashByQuality.get(quality);
            if (hash != null && !hash.isEmpty()) candidates.add(new Candidate(hash, quality));
        }
        if (candidates.isEmpty()) return rawCandidates(target, originalHash);
        return candidates;
    }

    private static List<Candidate> rawCandidates(String target, String hash) {
        List<Candidate> candidates = new ArrayList<>();
        for (String quality : fallbackChain(target)) candidates.add(new Candidate(hash, quality));
        return candidates;
    }

    // ---- HTTP -------------------------------------------------------------

    private static Map<String, String> params(String... keyValues) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            if (keyValues[i + 1] != null) map.put(keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    private static JSONObject getJson(String base, String path, Map<String, String> query, String authorization)
            throws IOException, ApiError {
        IOException lastNetworkError = null;
        for (int attempt = 0; attempt < NETWORK_ATTEMPTS; attempt++) {
            try {
                return getJsonOnce(base, path, query, authorization);
            } catch (IOException error) {
                lastNetworkError = error;
                try {
                    Thread.sleep(300L * (attempt + 1));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw error;
                }
            }
        }
        throw lastNetworkError == null ? new IOException("request failed") : lastNetworkError;
    }

    private static JSONObject getJsonOnce(String base, String path, Map<String, String> query, String authorization)
            throws IOException, ApiError {
        Uri.Builder builder = Uri.parse(stripTrailingSlash(base) + path).buildUpon();
        for (Map.Entry<String, String> entry : query.entrySet()) {
            builder.appendQueryParameter(entry.getKey(), entry.getValue());
        }
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(builder.build().toString()).openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "application/json");
            if (authorization != null && !authorization.isEmpty()) {
                connection.setRequestProperty("Authorization", authorization);
            }
            int code = connection.getResponseCode();
            InputStream stream = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String body = readBody(stream);
            if (code == 401 || code == 403) throw new ApiError(ErrorKind.AUTH, "登录已失效，请重新登录");
            if (code >= 400) throw new ApiError(ErrorKind.BAD_RESPONSE, "服务返回 HTTP " + code);
            try {
                return new JSONObject(body);
            } catch (JSONException error) {
                // A captive portal / proxy page is a link failure, not a content verdict.
                throw new ApiError(ErrorKind.BAD_RESPONSE, "响应不是 JSON 数据");
            }
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String readBody(InputStream stream) throws IOException {
        if (stream == null) return "";
        StringBuilder builder = new StringBuilder();
        try (InputStream input = stream;
             BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            char[] buffer = new char[4096];
            int total = 0;
            int read;
            while ((read = reader.read(buffer)) != -1) {
                total += read;
                if (total > MAX_BODY_BYTES) break;
                builder.append(buffer, 0, read);
            }
        }
        return builder.toString();
    }

    private static String stripTrailingSlash(String base) {
        String value = base.trim();
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return value;
    }

    private static String networkMessage(IOException error) {
        String message = error.getMessage();
        return message == null || message.isEmpty() ? "网络连接失败" : "网络连接失败";
    }

    private Resolution fail(ErrorKind kind, String message) {
        Resolution result = new Resolution();
        result.error = new Error(kind, message);
        return result;
    }

    private static final class Candidate {
        final String hash;
        final String quality;

        Candidate(String hash, String quality) {
            this.hash = hash;
            this.quality = quality;
        }
    }

    private static final class ApiError extends Exception {
        final ErrorKind kind;

        ApiError(ErrorKind kind, String message) {
            super(message);
            this.kind = kind;
        }
    }
}
