package cn.MoeKoe.Music.cache;

/**
 * Container sniffing for a downloaded audio file.
 *
 * <p>Serving code happily answers with an HTML error page or a JSON body, and a truncated
 * response has a plausible size. The first bytes plus a decodable track are the only cheap
 * evidence that what we stored is actually audio.
 */
public final class AudioHeader {

    public enum Kind { AUDIO, DOCUMENT, UNKNOWN }

    private AudioHeader() {}

    public static Kind sniff(byte[] head) {
        if (head == null || head.length < 4) return Kind.UNKNOWN;
        int offset = 0;
        // UTF-8 BOM
        if (head.length >= 3 && (head[0] & 0xFF) == 0xEF && (head[1] & 0xFF) == 0xBB && (head[2] & 0xFF) == 0xBF) {
            offset = 3;
        }
        int first = -1;
        for (int i = offset; i < head.length; i++) {
            int c = head[i] & 0xFF;
            if (c != ' ' && c != '\t' && c != '\r' && c != '\n') {
                first = c;
                break;
            }
        }
        if (first == '<' || first == '{' || first == '[') return Kind.DOCUMENT;

        String ascii = ascii(head, offset, 12);
        if (ascii.startsWith("ID3")) return Kind.AUDIO;
        if (ascii.startsWith("fLaC")) return Kind.AUDIO;
        if (ascii.startsWith("RIFF")) return Kind.AUDIO;
        if (ascii.startsWith("OggS")) return Kind.AUDIO;
        if (ascii.startsWith("FORM")) return Kind.AUDIO;
        if (ascii.startsWith("MAC ")) return Kind.AUDIO;
        if (ascii.startsWith("wvpk")) return Kind.AUDIO;
        if (ascii.startsWith("DSD ")) return Kind.AUDIO;
        if (ascii.startsWith("#!AMR")) return Kind.AUDIO;
        if (ascii.startsWith("TTA1")) return Kind.AUDIO;
        if (ascii.startsWith("MPCK") || ascii.startsWith("MP+")) return Kind.AUDIO;
        if (head.length >= 8 && ascii.regionMatches(4, "ftyp", 0, 4)) return Kind.AUDIO;
        if (head.length >= 2 && (head[offset] & 0xFF) == 0xFF) {
            int second = head[offset + 1] & 0xFF;
            // MPEG audio frame sync (0xFFEx/0xFFFx) or ADTS (0xFFF1/0xFFF9).
            if ((second & 0xE0) == 0xE0) return Kind.AUDIO;
        }
        return Kind.UNKNOWN;
    }

    /** True when the response itself declares "this is not audio". */
    public static boolean isRejectedMime(String mime) {
        if (mime == null) return false;
        String value = mime.toLowerCase(java.util.Locale.US).trim();
        if (value.isEmpty()) return false;
        return value.startsWith("text/")
                || value.startsWith("application/json")
                || value.contains("+json")
                || value.contains("xml")
                || value.contains("javascript")
                || value.startsWith("image/")
                || value.startsWith("video/");
    }

    private static String ascii(byte[] head, int offset, int length) {
        int end = Math.min(head.length, offset + length);
        StringBuilder builder = new StringBuilder(end - offset);
        for (int i = offset; i < end; i++) {
            builder.append((char) (head[i] & 0xFF));
        }
        return builder.toString();
    }
}
