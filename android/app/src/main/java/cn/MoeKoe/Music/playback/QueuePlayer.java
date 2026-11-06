package cn.MoeKoe.Music.playback;

import androidx.media3.common.ForwardingPlayer;
import androidx.media3.common.Player;

import java.util.function.BooleanSupplier;

/**
 * Presents the single media item owned by ExoPlayer as if it were a normal playlist.
 *
 * <p>The queue lives in {@link PlaybackController}, not in the player, because entries may not have
 * a URL yet. Without this adapter the notification and headset "next/previous" buttons would be
 * meaningless no-ops against a one-item playlist, so they are routed to the controller instead.
 */
public final class QueuePlayer extends ForwardingPlayer {

    private volatile Runnable onNext = () -> { };
    private volatile Runnable onPrevious = () -> { };
    private volatile BooleanSupplier hasNext = () -> false;
    private volatile BooleanSupplier hasPrevious = () -> false;

    public QueuePlayer(Player player) {
        super(player);
    }

    public void setSkipActions(Runnable next, Runnable previous,
                               BooleanSupplier nextAvailable, BooleanSupplier previousAvailable) {
        onNext = next == null ? () -> { } : next;
        onPrevious = previous == null ? () -> { } : previous;
        hasNext = nextAvailable == null ? () -> false : nextAvailable;
        hasPrevious = previousAvailable == null ? () -> false : previousAvailable;
    }

    @Override
    public void seekToNext() {
        onNext.run();
    }

    @Override
    public void seekToNextMediaItem() {
        onNext.run();
    }

    @Override
    public void seekToPrevious() {
        onPrevious.run();
    }

    @Override
    public void seekToPreviousMediaItem() {
        onPrevious.run();
    }

    @Override
    public boolean hasNextMediaItem() {
        return hasNext.getAsBoolean();
    }

    @Override
    public boolean hasPreviousMediaItem() {
        return hasPrevious.getAsBoolean();
    }

    @Override
    public Commands getAvailableCommands() {
        return super.getAvailableCommands().buildUpon()
                .addAll(Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                        Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                .build();
    }
}
