package cn.MoeKoe.Music.cache;

/** Quality label lookup shared by cache rows so an offline list shows the same text as online. */
final class SourceLabels {

    private static final String[] QUALITIES = {"128", "320", "flac", "high", "viper_atmos", "viper_clear", "viper_tape"};
    private static final String[] LABELS = {"标准", "高品", "FLAC", "Hi-Res", "全景声", "超清", "母带"};

    private SourceLabels() {}

    static String labelFor(String quality) {
        if (quality == null) return "";
        for (int i = 0; i < QUALITIES.length; i++) {
            if (QUALITIES[i].equals(quality)) return LABELS[i];
        }
        if ("original".equals(quality)) return "原文件";
        return quality;
    }
}
