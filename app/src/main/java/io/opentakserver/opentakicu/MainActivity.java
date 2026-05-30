package io.opentakserver.opentakicu;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.preference.PreferenceManager;

import io.opentakserver.opentakicu.contants.Preferences;

public class MainActivity extends AppCompatActivity {
    private static final String LOGTAG = "MainActivity";
    private final ArrayList<String> PERMISSIONS = new ArrayList<>();
    private final ExecutorService importExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /** Timestamp of the last VOLUME_DOWN press — used to detect a double-press emergency-stop. */
    private long lastVolumeDownAtMs = 0;
    /** Max gap between presses to count as a double-press. */
    private static final long DOUBLE_PRESS_WINDOW_MS = 500;

    /**
     * True until the first {@link MainActivity} created in this process resets the video source.
     * Static fields die with the process, so this flips exactly once per cold start — i.e. only
     * after the app was *fully* closed (task swiped away → process killed) and relaunched, not on
     * an activity recreation while the process is still alive. We use it to default back to the
     * phone camera so the app never reopens straight into a stale GoPro/Screen/USB source.
     */
    private static boolean sProcessFreshStart = true;

    public MainActivity() {
        super(R.layout.main_activity);
    }
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        //WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,WindowManager.LayoutParams.FLAG_FULLSCREEN | WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION | WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
        super.onCreate(savedInstanceState);
        Log.d(LOGTAG, "onCreate");

        Uri deepLinkData = getIntent().getData();


        if (!hasPermissions(this, PERMISSIONS) || (deepLinkData != null && "import".equals(deepLinkData.getHost()))) {
            Intent intent = new Intent(this, OnBoardingActivity.class);
            if(deepLinkData != null){
                intent.setData(deepLinkData);
            }
            startActivity(intent);
            this.finish();
            return;
        }

        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getWindow().setNavigationBarDividerColor(Color.TRANSPARENT);
        }

        // On a true cold start (fresh process), default the video source back to the phone camera.
        // This runs once per process launch, so reopening the app after fully closing it never
        // lands on a stale GoPro/Screen/USB source the user has to manually switch away from.
        if (sProcessFreshStart) {
            sProcessFreshStart = false;
            PreferenceManager.getDefaultSharedPreferences(this).edit()
                    .putString(Preferences.VIDEO_SOURCE, Preferences.VIDEO_SOURCE_DEFAULT)
                    .apply();
            Log.d(LOGTAG, "Cold start: reset video source to " + Preferences.VIDEO_SOURCE_DEFAULT);
        }

        //getWindow().setFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, WindowManager.LayoutParams.FLAG_LAYOUT_IN_OVERSCAN);

        // Land on the source-selection page first; it navigates to Camera2Fragment once the user
        // picks a broadcast source. Only add it on a fresh create (not on a config-change restore,
        // which would stack a second copy over the streaming screen).
        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .setReorderingAllowed(true)
                    .add(R.id.fragment_container_view, SourceSelectionFragment.class, null)
                    .commit();
        }

        handleTakImportIntent(getIntent());
        handlePendingActionIntent(getIntent());
    }

    /**
     * If we were brought to the foreground by a tap on the floating bubble (or some other
     * push from {@link Camera2Service}), there will be a {@code PENDING_ACTION} extra naming
     * the broadcast the camera fragment should react to once it's settled. We delay the
     * broadcast slightly so the fragment is fully resumed and has registered its receiver.
     */
    private void handlePendingActionIntent(Intent intent) {
        if (intent == null) return;
        final String action = intent.getStringExtra("io.opentakserver.opentakicu.PENDING_ACTION");
        if (action == null) return;
        intent.removeExtra("io.opentakserver.opentakicu.PENDING_ACTION");
        mainHandler.postDelayed(() -> {
            try { sendBroadcast(new Intent(action).setPackage(getPackageName())); }
            catch (Exception e) { Log.e(LOGTAG, "pending action broadcast failed", e); }
        }, 350);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);

        Uri data = intent.getData();
        if (data != null && "import".equals(data.getHost())) {
            Intent onboarding = new Intent(this, OnBoardingActivity.class);
            onboarding.setData(data);
            startActivity(onboarding);
            this.finish();
            return;
        }
        handleTakImportIntent(intent);
        handlePendingActionIntent(intent);
    }

    /**
     * If the launch intent looks like a TAK Data Package zip handed to us via SHARE / VIEW,
     * import it (off thread), then show a dialog with the result and a "Test connection" option.
     */
    private void handleTakImportIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (action == null) return;

        final Uri zipUri;
        if (Intent.ACTION_SEND.equals(action)) {
            zipUri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        } else if (Intent.ACTION_VIEW.equals(action)) {
            Uri d = intent.getData();
            // Avoid eating our own opentakicu:// deep links — those go through the import flow above.
            if (d != null && "opentakicu".equals(d.getScheme())) return;
            zipUri = d;
        } else {
            return;
        }
        if (zipUri == null) return;

        // Don't re-run if we already processed this intent on a config change.
        if (intent.getBooleanExtra("io.opentakserver.opentakicu.IMPORT_HANDLED", false)) return;
        intent.putExtra("io.opentakserver.opentakicu.IMPORT_HANDLED", true);

        Log.d(LOGTAG, "Importing TAK data package from " + zipUri);
        Toast.makeText(this, R.string.import_tak_zip_title, Toast.LENGTH_SHORT).show();

        importExecutor.submit(() -> {
            TakDataPackageImporter.Result result = TakDataPackageImporter.importFromUri(getApplicationContext(), zipUri);
            mainHandler.post(() -> showImportResult(result));
        });
    }

    private void showImportResult(TakDataPackageImporter.Result result) {
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(result.success
                        ? R.string.import_tak_zip_success_title
                        : R.string.import_tak_zip_failed_title)
                .setMessage(result.message)
                .setPositiveButton(R.string.ok, (d, w) -> d.dismiss());
        if (result.success && result.host != null && result.port > 0) {
            b.setNeutralButton(R.string.test_connection_button, (d, w) -> runConnectionTest(result));
        }
        b.create().show();
    }

    private void runConnectionTest(TakDataPackageImporter.Result result) {
        Toast.makeText(this, R.string.testing_connection, Toast.LENGTH_SHORT).show();
        importExecutor.submit(() -> {
            TakDataPackageImporter.ConnectionTestResult ct =
                    TakDataPackageImporter.testConnection(result, 5000);
            mainHandler.post(() -> {
                String body = (ct.tcpOk ? "TCP: OK\n" : "TCP: FAIL\n")
                        + (result.ssl ? (ct.tlsOk ? "TLS: OK\n" : "TLS: FAIL\n") : "")
                        + (ct.details == null ? "" : "\n" + ct.details);
                new AlertDialog.Builder(this)
                        .setTitle((ct.tcpOk && (!result.ssl || ct.tlsOk))
                                ? R.string.connection_test_ok
                                : R.string.connection_test_failed)
                        .setMessage(body)
                        .setPositiveButton(R.string.ok, (d, w) -> d.dismiss())
                        .create().show();
            });
        });
    }

    /**
     * Emergency stop via volume keys — meant for the touch-lock scenario where you can't tap
     * the record button. Two quick presses of VOLUME_DOWN (within {@value #DOUBLE_PRESS_WINDOW_MS} ms)
     * broadcast a stop intent to the {@link Camera2Service}. A single press passes through to
     * the system as normal volume adjustment.
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent ev) {
        if (ev.getKeyCode() == KeyEvent.KEYCODE_VOLUME_DOWN && ev.getAction() == KeyEvent.ACTION_DOWN) {
            long now = SystemClock.elapsedRealtime();
            if (now - lastVolumeDownAtMs < DOUBLE_PRESS_WINDOW_MS) {
                lastVolumeDownAtMs = 0;
                try {
                    sendBroadcast(new Intent(Camera2Service.STOP_STREAM).setPackage(getPackageName()));
                    Toast.makeText(this, "Stream stopped (emergency)", Toast.LENGTH_SHORT).show();
                } catch (Exception e) { Log.e(LOGTAG, "emergency stop broadcast failed", e); }
                return true;   // consume so the volume doesn't change
            }
            lastVolumeDownAtMs = now;
        }
        return super.dispatchKeyEvent(ev);
    }

    private boolean hasPermissions(Context context, ArrayList<String> permissions) {
        permissions();

        if (context != null && permissions != null) {
            for (String permission : permissions) {
                if (ActivityCompat.checkSelfPermission(context, permission)
                        != PackageManager.PERMISSION_GRANTED) {
                    return false;
                }
            }
        }
        return true;
    }

    private void permissions() {
        PERMISSIONS.add(android.Manifest.permission.RECORD_AUDIO);
        PERMISSIONS.add(android.Manifest.permission.CAMERA);
        PERMISSIONS.add(android.Manifest.permission.ACCESS_FINE_LOCATION);

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q)
            PERMISSIONS.add(android.Manifest.permission.WRITE_EXTERNAL_STORAGE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            PERMISSIONS.add(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            PERMISSIONS.add(Manifest.permission.POST_NOTIFICATIONS);
        }
    }
}
