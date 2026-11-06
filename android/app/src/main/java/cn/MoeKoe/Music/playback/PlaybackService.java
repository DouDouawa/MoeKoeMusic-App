package cn.MoeKoe.Music.playback;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.media3.common.Player;
import androidx.media3.session.CommandButton;
import androidx.media3.session.DefaultMediaNotificationProvider;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;

import cn.MoeKoe.Music.MainActivity;
import cn.MoeKoe.Music.R;

import java.util.Arrays;

/**
 * Hosts the ExoPlayer and its media session outside the WebView.
 *
 * <p>Media3 promotes this service to the foreground while something is playing, so locking the
 * screen or leaving the app does not tear the player down. The session is only handed to trusted
 * controllers (system media controls, the media notification, this app); a random third-party app
 * connecting as a controller gets nothing.
 */
public class PlaybackService extends MediaSessionService {

    private static final String TAG = "MoePlaybackService";
    private static final String SESSION_ID = "MoeKoeMusic";

    private MediaSession session;

    public static void start(Context context) {
        try {
            context.startService(new Intent(context, PlaybackService.class));
        } catch (IllegalStateException error) {
            // Only reachable when nothing is playing and the app is fully backgrounded; in that
            // state there is no playback to protect, so refusing to start is harmless.
            Log.w(TAG, "service start rejected by background limits", error);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        PlaybackController controller = PlaybackController.get(this);
        DefaultMediaNotificationProvider provider = new DefaultMediaNotificationProvider.Builder(this)
                .setChannelName(R.string.playback_channel_name)
                .build();
        provider.setSmallIcon(R.drawable.ic_stat_moe);
        setMediaNotificationProvider(provider);

        Intent openApp = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent sessionActivity = PendingIntent.getActivity(this, 0, openApp,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        // The player only holds one media item, so the queue-aware buttons are declared here and
        // routed by the forwarding player into the native queue.
        CommandButton previous = new CommandButton.Builder(CommandButton.ICON_PREVIOUS)
                .setDisplayName(getString(R.string.playback_previous))
                .setPlayerCommand(Player.COMMAND_SEEK_TO_PREVIOUS)
                .build();
        CommandButton next = new CommandButton.Builder(CommandButton.ICON_NEXT)
                .setDisplayName(getString(R.string.playback_next))
                .setPlayerCommand(Player.COMMAND_SEEK_TO_NEXT)
                .build();
        session = new MediaSession.Builder(this, controller.player())
                .setId(SESSION_ID)
                .setSessionActivity(sessionActivity)
                .setMediaButtonPreferences(Arrays.asList(previous, next))
                .build();
        // MediaSessionService only manages sessions it learned about through onGetSession(), which
        // only runs when an external controller connects. Nothing ever connects here - the WebView
        // talks to PlaybackController directly - so without this the service's session map stays
        // empty, updateNotification() is never reached, startForeground() is never called, and the
        // media notification never appears. Registering the session explicitly is what makes
        // media3 take ownership of the notification and the foreground state.
        addSession(session);
    }

    @Override
    public void onDestroy() {
        if (session != null) {
            removeSession(session);
            session.release();
            session = null;
        }
        PlaybackController controller = PlaybackController.peek();
        if (controller != null) controller.release();
        super.onDestroy();
    }

    @Nullable
    @Override
    public MediaSession onGetSession(MediaSession.ControllerInfo controllerInfo) {
        if (session == null) return null;
        // Standard playback commands only, and only for trusted controllers.
        return controllerInfo.isTrusted() ? session : null;
    }

    /**
     * Hiding or swiping the task away is not a stop request.
     *
     * <p>The media3 default tears the service down as soon as the task is gone, which would take
     * the player with it. Only honour that when nothing is playing; otherwise keep the session and
     * the foreground notification so audio continues.
     */
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        PlaybackController controller = PlaybackController.peek();
        if (controller != null && controller.player().isPlaying()) {
            Log.i(TAG, "task removed while playing; keeping playback alive");
            return;
        }
        super.onTaskRemoved(rootIntent);
    }
}
