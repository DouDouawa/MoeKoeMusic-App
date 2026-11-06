package cn.MoeKoe.Music.playback;

import android.content.Context;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Log;
import android.webkit.MimeTypeMap;

import androidx.documentfile.provider.DocumentFile;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Turns Storage Access Framework selections into queue-ready local songs.
 *
 * <p>A {@code content://} URI with a persisted read grant is what ExoPlayer can actually open;
 * a {@code blob:} URL from the WebView never reaches this class.
 */
public final class AudioPicker {

    private static final String TAG = "MoeAudioPicker";
    private static final String[] AUDIO_EXTENSIONS = {
            "mp3", "flac", "m4a", "aac", "wav", "ogg", "oga", "opus", "wma", "ape", "aiff", "aif", "dsf", "dff", "mka"
    };
    private static final int MAX_FILES = 2000;
    private static final int MAX_DEPTH = 8;

    private AudioPicker() {}

    public static Song fromUri(Context context, Uri uri) {
        String displayName = displayName(context, uri);
        String hash = "local-" + digest(uri.toString());
        Song song = new Song();
        song.hash = hash;
        song.itemId = "local:" + hash;
        song.name = stripExtension(displayName);
        song.isLocal = true;
        song.uri = uri.toString();
        song.playHash = hash;
        song.resolvedQuality = "local";
        song.qualityLabel = "本地";
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(context, uri);
            String title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
            String artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
            String duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (title != null && !title.trim().isEmpty()) song.name = title.trim();
            if (artist != null) song.author = artist.trim();
            if (duration != null) {
                try {
                    song.timeLength = Long.parseLong(duration.trim());
                } catch (NumberFormatException ignored) {
                    song.timeLength = 0L;
                }
            }
        } catch (Exception error) {
            Log.d(TAG, "metadata unavailable for a picked file");
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
                // Nothing to do.
            }
        }
        if (song.name.isEmpty()) song.name = displayName;
        return song;
    }

    public static List<Song> fromTree(Context context, Uri treeUri) {
        List<Song> songs = new ArrayList<>();
        DocumentFile root = DocumentFile.fromTreeUri(context, treeUri);
        if (root == null) return songs;
        walk(context, root, songs, 0);
        return songs;
    }

    private static void walk(Context context, DocumentFile node, List<Song> songs, int depth) {
        if (node == null || depth > MAX_DEPTH || songs.size() >= MAX_FILES) return;
        if (node.isDirectory()) {
            DocumentFile[] children = node.listFiles();
            if (children == null) return;
            for (DocumentFile child : children) {
                if (songs.size() >= MAX_FILES) return;
                walk(context, child, songs, depth + 1);
            }
            return;
        }
        if (!isAudio(context, node)) return;
        songs.add(fromUri(context, node.getUri()));
    }

    private static boolean isAudio(Context context, DocumentFile file) {
        String type = file.getType();
        if (type != null && type.startsWith("audio/")) return true;
        String name = file.getName();
        if (name == null) return false;
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot + 1 >= name.length()) return false;
        String extension = name.substring(dot + 1).toLowerCase(Locale.US);
        for (String candidate : AUDIO_EXTENSIONS) {
            if (candidate.equals(extension)) return true;
        }
        String guess = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
        return guess != null && guess.startsWith("audio/");
    }

    private static String displayName(Context context, Uri uri) {
        String name = "";
        try (Cursor cursor = context.getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (column >= 0) name = cursor.getString(column);
            }
        } catch (Exception error) {
            Log.d(TAG, "display name lookup failed");
        }
        if (name == null || name.isEmpty()) {
            name = uri.getLastPathSegment();
        }
        return name == null ? "" : name;
    }

    private static String stripExtension(String name) {
        if (name == null || name.isEmpty()) return "";
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String digest(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte item : bytes) builder.append(String.format(Locale.US, "%02x", item));
            return builder.toString();
        } catch (Exception error) {
            return Integer.toHexString(value.hashCode());
        }
    }
}
