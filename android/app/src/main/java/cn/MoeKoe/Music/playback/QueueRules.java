package cn.MoeKoe.Music.playback;

/**
 * The playback order rules, kept free of Android types so they can be unit tested.
 *
 * <p>Mode numbers match the web UI: 0 random, 1 list loop, 2 single loop, 3 sequential.
 * Manual skip is intentionally not the same thing as automatic advance: single loop repeats
 * automatically but a user pressing next must still move on.
 */
public final class QueueRules {

    public static final int MODE_RANDOM = 0;
    public static final int MODE_LIST = 1;
    public static final int MODE_SINGLE = 2;
    public static final int MODE_SEQUENTIAL = 3;
    public static final int DEFAULT_MODE = MODE_LIST;

    private QueueRules() {}

    public static int clampMode(int mode) {
        return mode >= MODE_RANDOM && mode <= MODE_SEQUENTIAL ? mode : DEFAULT_MODE;
    }

    /**
     * Automatic advance after a track ends.
     *
     * @return the next index, or {@link #STOP} when playback should fall silent
     */
    public static final int STOP = -1;

    public static int nextAfterEnd(int mode, int index, int size) {
        if (size <= 0) return STOP;
        if (index < 0 || index >= size) return STOP;
        if (mode == MODE_SINGLE) return index;
        if (mode == MODE_SEQUENTIAL) return index + 1 < size ? index + 1 : STOP;
        return (index + 1) % size;
    }

    /** Manual "next" button. Random is resolved by the caller with history. */
    public static int nextManual(int mode, int index, int size) {
        if (size <= 0) return STOP;
        if (index < 0 || index >= size) return 0;
        if (mode == MODE_SEQUENTIAL && index + 1 >= size) return STOP;
        return (index + 1) % size;
    }

    /** Manual "previous" button. Random is resolved by the caller with history. */
    public static int previousManual(int mode, int index, int size) {
        if (size <= 0) return STOP;
        if (index < 0 || index >= size) return 0;
        return (index - 1 + size) % size;
    }

    /** Where a song inserted through "play next" belongs. */
    public static int insertNextIndex(int index, int size) {
        if (size <= 0) return 0;
        if (index < 0 || index >= size) return size;
        return index + 1;
    }

    /** Index to keep after removing {@code removed} from a queue of {@code sizeBefore}. */
    public static int indexAfterRemove(int currentIndex, int removedIndex, int sizeBefore) {
        if (sizeBefore <= 0) return STOP;
        if (removedIndex < 0) return currentIndex;
        if (removedIndex < currentIndex) return currentIndex - 1;
        if (removedIndex > currentIndex) return currentIndex;
        // The playing entry disappeared: keep the slot, which is now the following song.
        int sizeAfter = sizeBefore - 1;
        if (sizeAfter <= 0) return STOP;
        return Math.min(currentIndex, sizeAfter - 1);
    }

    /**
     * A random pick that avoids the current entry and recently played ones.
     *
     * @param recent itemId history, most recent last
     * @return index, or {@link #STOP} for an empty queue
     */
    public static int randomPick(int size, int currentIndex, java.util.List<String> itemIds,
                                 java.util.List<String> recent, java.util.Random random) {
        if (size <= 0) return STOP;
        if (size == 1) return 0;
        int window = Math.min(10, recent == null ? 0 : recent.size());
        for (int attempt = 0; attempt < size * 2; attempt++) {
            int candidate = random.nextInt(size);
            if (candidate == currentIndex) continue;
            boolean seenRecently = false;
            if (window > 0 && itemIds != null) {
                String candidateId = itemIds.get(candidate);
                for (int i = recent.size() - window; i < recent.size(); i++) {
                    if (candidateId != null && candidateId.equals(recent.get(i))) {
                        seenRecently = true;
                        break;
                    }
                }
            }
            if (!seenRecently) return candidate;
        }
        // Everything has been played lately: any other entry is still better than repeating.
        int fallback = random.nextInt(size);
        return fallback == currentIndex ? (currentIndex + 1) % size : fallback;
    }
}
