package io.opentakserver.opentakicu.gopro;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Network;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.List;

import io.opentakserver.opentakicu.Camera2Service;
import io.opentakserver.opentakicu.R;

/**
 * Tiny helper that drives the user-visible GoPro connect dialog flow:
 * permission gate → progress dialog → success / fail. On success, the
 * {@link Camera2Service} is wired with the new {@link Network} and the
 * caller's {@link Runnable} runs to flip the video-source preference.
 */
public final class GoProDialogs {

    public static void startAutoConnect(@NonNull Context ctx,
                                        @NonNull Camera2Service service,
                                        @NonNull Runnable onSuccess) {
        // Pre-flight: permissions → Bluetooth on → Wi-Fi on → proceed.
        // Each check, if it fails, offers a "Fix it" button that actually does the right thing
        // (request the perm, request to enable BT, open the system Wi-Fi panel). Then the user
        // can re-tap "GoPro" once and the connect flow runs through.

        if (!hasBluetoothPermissions(ctx)) {
            promptBluetoothPermission(ctx);
            return;
        }

        BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = bm == null ? null : bm.getAdapter();
        if (adapter == null) {
            new AlertDialog.Builder(ctx)
                    .setTitle(R.string.gopro_connect_failed_title)
                    .setMessage("This device doesn't support Bluetooth — required to wake the GoPro Wi-Fi.")
                    .setPositiveButton(R.string.ok, (d, w) -> d.dismiss())
                    .show();
            return;
        }
        if (!adapter.isEnabled()) {
            promptEnableBluetooth(ctx, adapter);
            return;
        }

        WifiManager wm = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm == null || !wm.isWifiEnabled()) {
            promptEnableWifi(ctx);
            return;
        }

        // Phase 1: scan and show the list of cameras to pick from.
        runScan(ctx, service, onSuccess);
    }

    /** Phase 1: BLE-scan (~5s), then present the list of GoPro cameras found. */
    @SuppressWarnings("deprecation")
    private static void runScan(@NonNull Context ctx, @NonNull Camera2Service service, @NonNull Runnable onSuccess) {
        final ProgressDialog dlg = new ProgressDialog(ctx);
        dlg.setTitle(R.string.gopro_connecting_title);
        dlg.setMessage("Searching for GoPro cameras…");
        dlg.setIndeterminate(true);
        dlg.setCancelable(true);
        final boolean[] cancelled = { false };
        final GoProAutoConnector conn = new GoProAutoConnector(ctx);
        dlg.setOnCancelListener(d -> { cancelled[0] = true; conn.cancel(); });
        dlg.show();

        conn.startScan(new GoProAutoConnector.ScanListCallback() {
            @Override
            public void onScanComplete(@NonNull List<GoProAutoConnector.Found> devices) {
                if (cancelled[0]) return;
                if (dlg.isShowing()) dlg.dismiss();
                if (devices.isEmpty()) {
                    showRetry(ctx, "No GoPro cameras found nearby.\nMake sure the camera is ON and within a few metres.",
                            () -> runScan(ctx, service, onSuccess), conn);
                } else {
                    showDeviceList(ctx, conn, devices, service, onSuccess);
                }
            }
            @Override
            public void onScanFailed(@NonNull String reason) {
                if (cancelled[0]) return;
                if (dlg.isShowing()) dlg.dismiss();
                showRetry(ctx, reason, () -> runScan(ctx, service, onSuccess), conn);
            }
        });
    }

    /** Phase 1b: pick one camera from the discovered list. */
    private static void showDeviceList(@NonNull Context ctx, @NonNull GoProAutoConnector conn,
                                       @NonNull List<GoProAutoConnector.Found> devices,
                                       @NonNull Camera2Service service, @NonNull Runnable onSuccess) {
        CharSequence[] items = new CharSequence[devices.size()];
        for (int i = 0; i < devices.size(); i++) {
            GoProAutoConnector.Found f = devices.get(i);
            items[i] = f.name + "    " + signalLabel(f.rssi);
        }
        new AlertDialog.Builder(ctx)
                .setTitle("Select your GoPro")
                .setItems(items, (d, which) -> connectChosen(ctx, conn, devices.get(which), service, onSuccess))
                .setNegativeButton(R.string.cancel, (d, w) -> conn.cancel())
                .setOnCancelListener(d -> conn.cancel())
                .show();
    }

    /** Phase 2: connect to the chosen camera (BLE → Wi-Fi). */
    private static void connectChosen(@NonNull Context ctx, @NonNull GoProAutoConnector conn,
                                      @NonNull GoProAutoConnector.Found found,
                                      @NonNull Camera2Service service, @NonNull Runnable onSuccess) {
        // A plain AlertDialog (not a spinner) with a persistent RETRY button. The message updates
        // with the current step; each step times out in ~4s and flips the message to the error, but
        // the Retry button is there the whole time so the operator can re-kick it instantly if a
        // step ever hangs. Retry uses a FRESH connector against the SAME camera.
        final AlertDialog dlg = new AlertDialog.Builder(ctx)
                .setTitle("Connecting to " + found.name)
                .setMessage("Starting…")
                .setCancelable(false)
                .setPositiveButton("Retry", null)
                .setNegativeButton(R.string.cancel, (d, w) -> conn.cancel())
                .create();
        dlg.setOnShowListener(dd -> {
            android.widget.Button retry = dlg.getButton(AlertDialog.BUTTON_POSITIVE);
            if (retry != null) retry.setOnClickListener(v -> {
                conn.cancel();
                dlg.dismiss();
                connectChosen(ctx, new GoProAutoConnector(ctx), found, service, onSuccess);
            });
        });
        dlg.show();

        conn.connectToDevice(found.device, new GoProAutoConnector.Callback() {
            @Override
            public void onState(@NonNull GoProAutoConnector.State state, @NonNull String detail) {
                if (dlg.isShowing()) dlg.setMessage(detail);
            }
            @Override
            public void onConnected(@NonNull Network goproNetwork, @NonNull String goproHost) {
                if (dlg.isShowing()) dlg.dismiss();
                service.setGoProNetwork(goproNetwork, goproHost);
                onSuccess.run();
                if (conn.usedSystemNetwork()) {
                    // Connected, but via a SAVED Wi-Fi network — Android may make it the default and
                    // kill mobile data (and the broadcast). Offer to forget it.
                    new AlertDialog.Builder(ctx)
                            .setTitle("Connected — one tip")
                            .setMessage("This GoPro Wi-Fi is saved on your phone, so Android may treat it as your "
                                    + "internet connection and switch off mobile data — which would stop the "
                                    + "broadcast. Forget it in Wi-Fi settings so SENTINEL uses its own app-bound "
                                    + "connection (it'll still connect automatically).")
                            .setPositiveButton("Open Wi-Fi settings", (d, w) -> {
                                try {
                                    Intent i = new Intent(Settings.ACTION_WIFI_SETTINGS);
                                    i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                    ctx.startActivity(i);
                                } catch (Exception ignored) {}
                            })
                            .setNegativeButton("Ignore", (d, w) -> d.dismiss())
                            .show();
                } else {
                    new AlertDialog.Builder(ctx)
                            .setTitle("GoPro ready")
                            .setMessage("Connected to " + found.name + ". Tap record to start streaming.")
                            .setPositiveButton(R.string.ok, (d, w) -> d.dismiss())
                            .show();
                }
            }
            @Override
            public void onFailed(@NonNull String reason) {
                // Keep the dialog up with the error; the Retry button is already there.
                if (dlg.isShowing()) dlg.setMessage(reason + "\n\nTap Retry to try again.");
            }
        });
    }

    /** Failure dialog with a Retry button. */
    private static void showRetry(@NonNull Context ctx, @NonNull String message,
                                  @NonNull Runnable onRetry, GoProAutoConnector conn) {
        new AlertDialog.Builder(ctx)
                .setTitle(R.string.gopro_connect_failed_title)
                .setMessage(message)
                .setPositiveButton("Retry", (d, w) -> onRetry.run())
                .setNegativeButton(R.string.cancel, (d, w) -> { if (conn != null) conn.cancel(); })
                .setOnCancelListener(d -> { if (conn != null) conn.cancel(); })
                .show();
    }

    /** Rough signal-strength dots from RSSI (≈ -30 strong … -100 weak). */
    private static String signalLabel(int rssi) {
        if (rssi == 0) return "";
        if (rssi >= -55) return "●●●";
        if (rssi >= -70) return "●●";
        return "●";
    }

    /** Show a friendly dialog asking the user to grant the BT permissions we need. */
    private static void promptBluetoothPermission(Context ctx) {
        if (!(ctx instanceof Activity)) {
            new AlertDialog.Builder(ctx)
                    .setTitle(R.string.gopro_connect_failed_title)
                    .setMessage(R.string.gopro_need_bluetooth)
                    .setPositiveButton(R.string.ok, (d, w) -> d.dismiss())
                    .show();
            return;
        }
        Activity act = (Activity) ctx;
        new AlertDialog.Builder(act)
                .setTitle(R.string.gopro_connect_failed_title)
                .setMessage(R.string.gopro_need_bluetooth)
                .setPositiveButton(R.string.gopro_grant, (d, w) -> {
                    String[] perms;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        perms = new String[] {
                                Manifest.permission.BLUETOOTH_SCAN,
                                Manifest.permission.BLUETOOTH_CONNECT
                        };
                    } else {
                        perms = new String[] { Manifest.permission.ACCESS_FINE_LOCATION };
                    }
                    ActivityCompat.requestPermissions(act, perms, 4242);
                })
                .setNegativeButton(R.string.cancel, (d, w) -> d.dismiss())
                .show();
    }

    /**
     * Ask the user to turn on Bluetooth. On Android 12+ we can route the request through the
     * system "Allow this app to turn on Bluetooth?" dialog (intent
     * {@link BluetoothAdapter#ACTION_REQUEST_ENABLE}); on older versions we just fall back to
     * the system Bluetooth settings page so they can flip the switch themselves.
     */
    @SuppressLint("MissingPermission")
    private static void promptEnableBluetooth(Context ctx, BluetoothAdapter adapter) {
        new AlertDialog.Builder(ctx)
                .setTitle("Bluetooth is off")
                .setMessage("SENTINEL needs Bluetooth to wake the GoPro's Wi-Fi. Turn it on now?")
                .setPositiveButton("Turn on", (d, w) -> {
                    try {
                        Intent enableBt = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                        enableBt.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        ctx.startActivity(enableBt);
                    } catch (Exception e) {
                        try {
                            Intent settings = new Intent(Settings.ACTION_BLUETOOTH_SETTINGS);
                            settings.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            ctx.startActivity(settings);
                        } catch (Exception ignored) {
                            Toast.makeText(ctx, "Could not open Bluetooth settings", Toast.LENGTH_LONG).show();
                        }
                    }
                })
                .setNegativeButton(R.string.cancel, (d, w) -> d.dismiss())
                .show();
    }

    /**
     * Android 10+ does not let apps programmatically enable Wi-Fi, so we open the system
     * "Internet" panel (a small bottom sheet on Pixel-style ROMs) where the user can flip
     * Wi-Fi on with one tap. Falls back to the full Wi-Fi settings page if that intent is
     * unsupported on this device.
     */
    private static void promptEnableWifi(Context ctx) {
        new AlertDialog.Builder(ctx)
                .setTitle("Wi-Fi is off")
                .setMessage("SENTINEL needs Wi-Fi turned on so we can connect to the GoPro's access point. Open Wi-Fi settings now?")
                .setPositiveButton("Open Wi-Fi", (d, w) -> {
                    Intent intent = null;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        try {
                            intent = new Intent(Settings.Panel.ACTION_WIFI);
                        } catch (Exception ignored) {}
                    }
                    if (intent == null) {
                        intent = new Intent(Settings.ACTION_WIFI_SETTINGS);
                    }
                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try { ctx.startActivity(intent); }
                    catch (Exception ignored) {
                        Toast.makeText(ctx, "Could not open Wi-Fi settings", Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton(R.string.cancel, (d, w) -> d.dismiss())
                .show();
    }

    private static boolean hasBluetoothPermissions(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                    && ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        }
        return ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }
}
