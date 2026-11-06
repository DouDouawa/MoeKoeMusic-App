package cn.MoeKoe.Music;

import android.Manifest;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;

import androidx.activity.OnBackPressedCallback;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.splashscreen.SplashScreen;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import com.getcapacitor.BridgeActivity;

/**
 * WebView shell plus the two things that have to live in the activity: the back gesture and the
 * single layer of system-bar inset compensation.
 */
public class MainActivity extends BridgeActivity {

    private static final String PREFS = "moe_shell";
    private static final String KEY_ASKED_NOTIFICATIONS = "askedNotifications";
    private static final int REQUEST_NOTIFICATIONS = 1001;

    @Override
    public void onResume() {
        super.onResume();
        requestNotificationPermissionOnce();
    }

    /**
     * The media notification is what keeps {@link cn.MoeKoe.Music.playback.PlaybackService} in the
     * foreground, and a foreground service is what keeps audio alive once the UI is gone.
     *
     * <p>WebView's {@code Notification.requestPermission()} is a no-op on Android, so the runtime
     * permission has to be requested here. Without it Android 13+ silently drops the playback
     * notification, the service never holds foreground status, and the system stops it - together
     * with the player - as soon as the app is hidden or the screen turns off.
     *
     * <p>Asked at most once, and playback never waits on the answer.
     */
    private void requestNotificationPermissionOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (prefs.getBoolean(KEY_ASKED_NOTIFICATIONS, false)) return;
        prefs.edit().putBoolean(KEY_ASKED_NOTIFICATIONS, true).apply();
        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                REQUEST_NOTIFICATIONS);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Must run before super.onCreate(): this is what applies postSplashScreenTheme.
        // Without it the activity stays on AppTheme.NoActionBarLaunch forever, and that
        // theme drags its splash background (and, on AppCompat, a default ActionBar titled
        // with the app name) along for the whole session.
        SplashScreen.installSplashScreen(this);
        // Draw edge to edge ourselves; the WebView below is padded exactly once.
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        registerPlugin(MoePlaybackPlugin.class);
        super.onCreate(savedInstanceState);
        applyWindowInsets();
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                MoePlaybackPlugin plugin = MoePlaybackPlugin.instance();
                if (plugin != null && plugin.handleBackPressed()) return;
                // Nothing in the WebView could consume it: hide the UI, keep playback alive.
                // Finishing here would tear down the activity and with it the whole session.
                moveTaskToBack(true);
            }
        });
    }

    /**
     * Applies {@code systemBars + displayCutout + ime} padding to the WebView and consumes the
     * insets. Consuming is what keeps this a *single* compensation: no parent view, and in
     * practice no CSS {@code env(safe-area-inset-*)} layer, adds a second gap on top of it.
     * Padding is always recomputed from the current insets, never accumulated.
     */
    private void applyWindowInsets() {
        View webView = getBridge() == null ? null : getBridge().getWebView();
        if (webView == null) return;
        ViewCompat.setOnApplyWindowInsetsListener(webView, (view, windowInsets) -> {
            Insets bars = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            Insets ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime());
            view.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
            return WindowInsetsCompat.CONSUMED;
        });
        ViewCompat.requestApplyInsets(webView);
    }
}
