package io.opentakserver.opentakicu;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;
import android.util.Range;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.slider.Slider;
import com.pedro.common.ConnectChecker;
import com.pedro.encoder.input.sources.video.Camera2Source;
import com.pedro.encoder.input.video.CameraHelper;

import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;
import io.opentakserver.opentakicu.contants.Preferences;

import com.pedro.library.view.OpenGlView;

public class Camera2Fragment extends Fragment
        implements Button.OnClickListener, SurfaceHolder.Callback, View.OnTouchListener,
        SharedPreferences.OnSharedPreferenceChangeListener, ConnectChecker, PopupMenu.OnMenuItemClickListener {

    private final String LOGTAG = "Camera2Fragment";
    SharedPreferences pref;

    private Activity activity;
    private OpenGlView openGlView;
    private FloatingActionButton bStartStop;
    private PopupMenu popupMenu;
    PopupMenuHandler popupMenuHandler;
    private final Handler handler = new Handler();

    private TextView tvBitrate;
    private TextView tvLocationFix;
    private TextView tvStreamPath;
    private TextView tvRecording;
    private TextView tvTakServer;
    private TextView tvFps;
    private TextView tvFpsDrop;
    private TextView tvStateBanner;
    private TextView tvStateTimer;
    private View rightControls;
    private View statsChip;
    private View stateDot;
    private View takDot;
    private View gpsDot;
    private View recDot;
    private View touchLockOverlay;
    private View touchLockBadge;
    private long streamStartedAtMs = 0;
    private final Runnable elapsedTimer = new Runnable() {
        @Override
        public void run() {
            if (tvStateTimer != null && streamStartedAtMs > 0) {
                long sec = (System.currentTimeMillis() - streamStartedAtMs) / 1000;
                tvStateTimer.setText(String.format(java.util.Locale.US, "%02d:%02d", sec / 60, sec % 60));
                handler.postDelayed(this, 500);
            }
        }
    };
    private FloatingActionButton pictureButton;
    private FloatingActionButton flashlight;
    private View whiteOverlay;
    private View screenCaptureOverlay;
    private FloatingActionButton videoSourceButton;
    private FloatingActionButton switchCameraButton;
    private Slider zoomSlider;

    private boolean service_bound = false;
    /** One-shot: set when arriving from the source page with GoPro picked; triggers auto-connect. */
    private boolean pendingGoProAutoConnect = false;
    /** Throttle for battery/thermal warning toasts (every 30s max). */
    private long lastWarnToastMs = 0;
    private boolean awaitingScreenCapturePermission = false;
    private Camera2Service camera_service;
    private long last_fix_time = 0;

    /** Stream lifecycle state for the top-of-screen banner. */
    private enum StreamState { IDLE, CONNECTING, STREAMING, ERROR }
    private StreamState currentState = StreamState.IDLE;
    /** Drop counter: incremented every time the bitrate adapter reports congestion. */
    private int droppedFrames = 0;
    /** Last time we received a bitrate update — for "stale stream" detection. */
    private long lastBitrateUpdateMs = 0;
    /** Auto-hide controls timer. */
    private static final long CONTROLS_HIDE_DELAY_MS = 3000;

    private ActivityResultLauncher<Intent> screenCaptureLauncher;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceBundle) {
        Log.d(LOGTAG, "onCreateView");
        return inflater.inflate(R.layout.camera2_fragment, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        Log.d(LOGTAG, "onViewCreated");

        getViews();

        // The source-selection page passes this when GoPro was picked → auto-start the BLE→Wi-Fi
        // connect flow once the service is bound (see maybeAutoConnectGoPro()).
        Bundle navArgs = getArguments();
        if (navArgs != null) {
            pendingGoProAutoConnect = navArgs.getBoolean(SourceSelectionFragment.ARG_AUTO_CONNECT_GOPRO, false);
        }

        // Back button: idle → return to the source-selection page; live → confirm stop first.
        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(),
                new androidx.activity.OnBackPressedCallback(true) {
                    @Override public void handleOnBackPressed() {
                        boolean active = false;
                        try {
                            active = service_bound && camera_service != null
                                    && (camera_service.getStream().isStreaming()
                                        || camera_service.getStream().isRecording());
                        } catch (Throwable ignored) {}
                        if (active) {
                            new androidx.appcompat.app.AlertDialog.Builder(activity)
                                    .setTitle(R.string.confirm_stop_title)
                                    .setMessage(R.string.confirm_stop_message)
                                    .setPositiveButton(android.R.string.ok, (d, w) -> { doStopStream(); popToSourcePage(); })
                                    .setNegativeButton(android.R.string.cancel, (d, w) -> d.dismiss())
                                    .show();
                        } else {
                            popToSourcePage();
                        }
                    }
                });

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Camera2Service.EXIT_APP);
        intentFilter.addAction(Camera2Service.START_STREAM);
        intentFilter.addAction(Camera2Service.STOP_STREAM);
        intentFilter.addAction(Camera2Service.AUTH_ERROR);
        intentFilter.addAction(Camera2Service.CONNECTION_FAILED);
        intentFilter.addAction(Camera2Service.TOOK_PICTURE);
        intentFilter.addAction(Camera2Service.NEW_BITRATE);
        intentFilter.addAction(Camera2Service.LOCATION_CHANGE);
        intentFilter.addAction(TcpClient.TAK_SERVER_CONNECTED);
        intentFilter.addAction(TcpClient.TAK_SERVER_DISCONNECTED);
        intentFilter.addAction(Camera2Service.LOCK_SCREEN);
        intentFilter.addAction(Camera2Service.REQUEST_SCREEN_CAPTURE);
        intentFilter.addAction(Camera2Service.SHOW_SOURCE_PICKER);
        intentFilter.addAction(Camera2Service.STREAM_CONNECTED);
        intentFilter.addAction(Camera2Service.STREAM_STATS);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.registerReceiver(receiver, intentFilter, Context.RECEIVER_EXPORTED);
        } else {
            activity.registerReceiver(receiver, intentFilter);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            activity.startForegroundService(new Intent(activity, Camera2Service.class));
        } else {
            activity.startService(new Intent(activity, Camera2Service.class));
        }

        Camera2Service.observer.observe(getViewLifecycleOwner(), cameraService -> {
            Log.d(LOGTAG, "observer");
            camera_service = cameraService;
            if (cameraService != null) {
                popupMenuHandler = new PopupMenuHandler(cameraService, getActivity());
                setZoomRange();
                maybeAutoConnectGoPro();
            } else {
                Log.e(LOGTAG, "observer service null");
            }

            // Preview is always started; when Screen is selected but MediaProjection not granted, service uses camera fallback.
            if (openGlView.getHolder().getSurface().isValid() && camera_service != null) {
                camera_service.startPreview(openGlView);
                Log.d(LOGTAG, "Observer started preview");
            }
        });
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        Log.d(LOGTAG, "onAttach");

        if (context instanceof Activity) {
            activity = (Activity) context;
        }
    }

    final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            try { onReceiveSafe(context, intent); }
            catch (Throwable t) {
                // BroadcastReceiver crashes take the whole process with them — swallow so the
                // app stays alive even if a view reference is stale or an action arrives
                // before the fragment is fully ready.
                Log.e(LOGTAG, "fragment receiver crashed for " + intent, t);
            }
        }
        private void onReceiveSafe(Context context, Intent intent) {
            String action = intent.getAction();
            if (action != null && action.equals(Camera2Service.EXIT_APP)) {
                Log.d(LOGTAG, "Exiting app");
                activity.finishAndRemoveTask();
            } else if (action != null && action.equals(Camera2Service.START_STREAM)) {
                if (!service_bound) {
                    activity.bindService(new Intent(activity, Camera2Service.class), mConnection, Context.BIND_IMPORTANT);
                    bStartStop.setImageResource(R.drawable.stop);
                    lockScreenOrientation();
                }
                if (pref.getBoolean(Preferences.RECORD_VIDEO, Preferences.RECORD_VIDEO_DEFAULT)) {
                    tvRecording.setText("on");
                    tintDot(recDot, COLOR_OK);
                }
                applyStreamState(StreamState.CONNECTING);
                droppedFrames = 0;
            } else if (action != null && (action.equals(Camera2Service.STOP_STREAM) || action.equals(Camera2Service.AUTH_ERROR) || action.equals(Camera2Service.CONNECTION_FAILED))) {
                bStartStop.setImageResource(R.drawable.ic_record);
                if (service_bound)
                    activity.unbindService(mConnection);

                service_bound = false;
                unlockScreenOrientation();
                if (pref.getBoolean(Preferences.RECORD_VIDEO, Preferences.RECORD_VIDEO_DEFAULT)) {
                    tvRecording.setText("off");
                    tintDot(recDot, COLOR_ERR);
                }

                if (action.equals(Camera2Service.AUTH_ERROR) || action.equals(Camera2Service.CONNECTION_FAILED)) {
                    applyStreamState(StreamState.ERROR);
                } else {
                    applyStreamState(StreamState.IDLE);
                }

                setStatusState();
                // If we were in screen mode and the stream stopped externally (e.g. network),
                // try to restore the local camera preview.
                String videoSource = pref.getString(Preferences.VIDEO_SOURCE, Preferences.VIDEO_SOURCE_DEFAULT);
                if (videoSource != null && videoSource.equals(Preferences.VIDEO_SOURCE_SCREEN)
                        && camera_service != null && openGlView != null && openGlView.getHolder().getSurface().isValid()) {
                    if (screenCaptureOverlay != null) {
                        screenCaptureOverlay.setVisibility(View.GONE);
                    }
                    // If a screen capture token already exists, keep preview hidden while idle in
                    // screen mode (avoids rotated camera fallback confusion after stop).
                    if (camera_service.hasScreenCapture()) {
                        openGlView.setVisibility(View.INVISIBLE);
                        camera_service.stopPreview();
                    } else {
                        openGlView.setVisibility(View.VISIBLE);
                        camera_service.startPreview(openGlView);
                    }
                }
            } else if (action != null && action.equals(Camera2Service.TOOK_PICTURE)) {
                activity.runOnUiThread(() -> whiteOverlay.setVisibility(View.INVISIBLE));
            } else if (action != null && action.equals(Camera2Service.NEW_BITRATE)) {
                long bitrate = intent.getLongExtra(Camera2Service.NEW_BITRATE, 0) / 1000;
                tvBitrate.setText(bitrate + "kb/s");
                // First bitrate update after CONNECTING means we are actually streaming.
                if (currentState == StreamState.CONNECTING) {
                    applyStreamState(StreamState.STREAMING);
                }
                onBitrateTick(bitrate);
            } else if (action != null && action.equals(Camera2Service.LOCATION_CHANGE)) {
                last_fix_time = System.currentTimeMillis();
                tvLocationFix.setText("ok");
                tintDot(gpsDot, COLOR_OK);
            } else if (action != null && action.equals(TcpClient.TAK_SERVER_CONNECTED)) {
                tvTakServer.setText("ok");
                tintDot(takDot, COLOR_OK);
            } else if (action != null && action.equals(TcpClient.TAK_SERVER_DISCONNECTED)) {
                tvTakServer.setText("lost");
                tintDot(takDot, COLOR_ERR);
            } else if (action != null && action.equals(Camera2Service.STREAM_CONNECTED)) {
                applyStreamState(StreamState.STREAMING);
            } else if (action != null && action.equals(Camera2Service.STREAM_STATS)) {
                long bitrate = intent.getLongExtra(Camera2Service.KEY_BITRATE_KBPS, 0);
                long fps = intent.getLongExtra(Camera2Service.KEY_FPS, 0);
                long upload = intent.getLongExtra(Camera2Service.KEY_UPLOAD_KBPS, 0);
                boolean congested = intent.getBooleanExtra(Camera2Service.KEY_CONGESTED, false);
                if (tvBitrate != null) tvBitrate.setText(bitrate + "kb/s");
                if (tvFps != null) {
                    tvFps.setText(fps > 0 ? String.valueOf(fps) : "—");
                    tvFps.setTextColor(congested ? COLOR_WARN : (fps > 0 ? COLOR_OK : COLOR_DIM));
                }
                String warning = intent.getStringExtra(Camera2Service.KEY_WARNING);
                boolean hasWarning = warning != null && !warning.isEmpty();
                if (congested || hasWarning) {
                    // Tint the state dot amber to flag poor connection / device warning without
                    // flipping LIVE → ERROR.
                    tintDot(stateDot, COLOR_WARN);
                } else if (currentState == StreamState.STREAMING) {
                    tintDot(stateDot, COLOR_ERR);   // back to LIVE red
                }
                // Surface battery/thermal warnings as a throttled toast (logcat has the rest).
                if (hasWarning) {
                    long now = System.currentTimeMillis();
                    if (now - lastWarnToastMs > 30000) {
                        lastWarnToastMs = now;
                        Toast.makeText(activity, warning, Toast.LENGTH_LONG).show();
                    }
                }
            } else if (action != null && action.equals(Camera2Service.LOCK_SCREEN)) {
                lockTouches();
            } else if (action != null && action.equals(Camera2Service.REQUEST_SCREEN_CAPTURE)) {
                requestScreenCapture();
            } else if (action != null && action.equals(Camera2Service.SHOW_SOURCE_PICKER)) {
                if (popupMenu != null) {
                    popupMenu.show();
                }
            }
        }
    };

    private ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(@NonNull Network network) {
            super.onAvailable(network);
        }

        @Override
        public void onLost(@NonNull Network network) {
            super.onLost(network);
            camera_service.stopStream(getString(R.string.network_lost), null);
            Toast.makeText(activity, "Network lost, stream stopping", Toast.LENGTH_LONG).show();
        }

        @Override
        public void onCapabilitiesChanged(@NonNull Network network, @NonNull NetworkCapabilities networkCapabilities) {
            super.onCapabilitiesChanged(network, networkCapabilities);
            final boolean unmetered = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
        }
    };


    //Suppress this warning for Android versions less than 13
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    @Override
    public void onCreate(Bundle savedInstanceState) {
        Log.d(LOGTAG, "onCreate");
        super.onCreate(savedInstanceState);
        pref = PreferenceManager.getDefaultSharedPreferences(activity);
        pref.registerOnSharedPreferenceChangeListener(this);

        screenCaptureLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    awaitingScreenCapturePermission = false;
                    if (activity == null) return;
                    if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null && camera_service != null) {
                        boolean prepared = camera_service.setScreenCaptureResult(result.getResultCode(), result.getData());
                        if (!prepared) {
                            Toast.makeText(activity, R.string.error_preparing_stream, Toast.LENGTH_LONG).show();
                            bStartStop.setImageResource(R.drawable.ic_record);
                            unlockScreenOrientation();
                            return;
                        }
                        setPreviewSurfaceSecure(true);

                        if (!service_bound) {
                            activity.bindService(new Intent(activity, Camera2Service.class), mConnection, Context.BIND_IMPORTANT);
                        } else {
                            // Already bound (e.g. camera fallback preview); re-prepare with ScreenSource and start streaming.
                            camera_service.startStream();
                            if (openGlView != null) {
                                openGlView.setVisibility(View.INVISIBLE);
                            }
                        }
                        bStartStop.setImageResource(R.drawable.stop);
                        lockScreenOrientation();
                        if (pref.getBoolean(Preferences.RECORD_VIDEO, Preferences.RECORD_VIDEO_DEFAULT)) {
                            tvRecording.setText("on");
                            tintDot(recDot, COLOR_OK);
                        }
                        if (screenCaptureOverlay != null) {
                            screenCaptureOverlay.setVisibility(View.VISIBLE);
                            screenCaptureOverlay.bringToFront();
                        }
                    } else {
                        Toast.makeText(activity, "Screen capture permission denied", Toast.LENGTH_LONG).show();
                        bStartStop.setImageResource(R.drawable.ic_record);
                        unlockScreenOrientation();
                    }
                }
        );

        activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // Note: Firebase Analytics + Crashlytics were intentionally removed for the SENTINEL
        // production build so no telemetry leaks to upstream. If you want crash reporting
        // you can wire up your own provider (e.g. self-hosted Sentry) here.

        String uid = pref.getString(Preferences.UID, null);
        if (uid == null)
            pref.edit().putString(Preferences.UID, Preferences.UID_DEFAULT).apply();

        NetworkRequest networkRequest = new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .build();

        ConnectivityManager connectivityManager = activity.getSystemService(ConnectivityManager.class);
        connectivityManager.requestNetwork(networkRequest, networkCallback);
    }

    private void setZoomRange() {
        if (camera_service != null && camera_service.getStream().getVideoSource() instanceof Camera2Source) {
            Camera2Source camera2Source = (Camera2Source) camera_service.getStream().getVideoSource();
            Range<Float> zoomRange = camera2Source.getZoomRange();
            zoomSlider.setValueFrom(zoomRange.getLower());
            zoomSlider.setValueTo(zoomRange.getUpper());
            zoomSlider.setValue(zoomRange.getLower());
            Log.d(LOGTAG, "Set zoomSlider range to " + zoomRange.getLower() + " - " + zoomRange.getUpper());
        } else {
            zoomSlider.setValueFrom(0);
            zoomSlider.setValueTo(1);
            Log.d(LOGTAG, "set zoom range to 0 - 1");
        }
    }

    private void setStatusState() {
        if (tvLocationFix == null) return;

        boolean atakEnabled = pref.getBoolean(Preferences.ATAK_SEND_COT, Preferences.ATAK_SEND_COT_DEFAULT);
        boolean recordEnabled = pref.getBoolean(Preferences.RECORD_VIDEO, Preferences.RECORD_VIDEO_DEFAULT);

        // TAK chip: gray dot + "off" / dim text when disabled; yellow + "wait" when enabled and idle.
        tvTakServer.setText(atakEnabled ? "wait" : "off");
        tintDot(takDot, atakEnabled ? COLOR_WARN : COLOR_DIM);

        tvLocationFix.setText(atakEnabled ? "wait" : "off");
        tintDot(gpsDot, atakEnabled ? COLOR_WARN : COLOR_DIM);

        tvStreamPath.setText(pref.getString(Preferences.STREAM_PATH, Preferences.STREAM_PATH_DEFAULT));
        tvBitrate.setText(pref.getString(Preferences.VIDEO_BITRATE, Preferences.VIDEO_BITRATE_DEFAULT) + "kb/s");

        tvRecording.setText(recordEnabled ? "wait" : "off");
        tintDot(recDot, recordEnabled ? COLOR_WARN : COLOR_DIM);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(LOGTAG, "onDestroy");
        if (camera_service != null) {
            camera_service.stopPreview();
            camera_service.getStream().release();
        }
        activity.stopService(new Intent(activity, Camera2Service.class));
    }

    @Override
    public void onPause() {
        super.onPause();
        Log.d(LOGTAG, "onPause");
        if (screenCaptureOverlay != null) {
            screenCaptureOverlay.setVisibility(View.GONE);
        }
        if (openGlView != null) {
            openGlView.setVisibility(View.VISIBLE);
        }
        // Launching the Android screen-capture chooser briefly pauses this fragment; avoid
        // tearing down camera/GL preview during that handoff to reduce dead-thread callback noise.
        if (awaitingScreenCapturePermission) {
            Log.d(LOGTAG, "onPause while awaiting screen-capture permission; keeping preview alive");
            return;
        }
        // Hand off background-state to the service so it can decide whether to pop a floating bubble.
        if (camera_service != null) camera_service.setAppForeground(false);
        // Don't tear down the preview/encoder pipeline if a stream or recording is in progress —
        // the foreground service is supposed to keep producing frames in the background.
        if (camera_service != null) {
            boolean active = camera_service.getStream().isStreaming() || camera_service.getStream().isRecording();
            if (active) {
                Log.d(LOGTAG, "onPause while streaming/recording; keeping preview alive");
            } else {
                camera_service.stopPreview();
            }
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        Log.d(LOGTAG, "onResume");
        applyOrientationPreference();
        showControls();
        if (camera_service != null) camera_service.setAppForeground(true);
        String videoSource = pref.getString(Preferences.VIDEO_SOURCE, Preferences.VIDEO_SOURCE_DEFAULT);
        boolean isScreenStreaming = Preferences.VIDEO_SOURCE_SCREEN.equals(videoSource)
                && camera_service != null && camera_service.hasScreenCapture()
                && camera_service.getStream().isStreaming();

        if (isScreenStreaming) {
            setPreviewSurfaceSecure(true);
            // Force preview surface behind our overlay so recursive capture is not visible.
            if (openGlView instanceof SurfaceView) {
                SurfaceView sv = (SurfaceView) openGlView;
                sv.setZOrderOnTop(false);
                sv.setZOrderMediaOverlay(false);
            }
            if (openGlView != null) {
                openGlView.setVisibility(View.INVISIBLE);
            }
            if (screenCaptureOverlay != null) {
                screenCaptureOverlay.setVisibility(View.VISIBLE);
                screenCaptureOverlay.bringToFront();
            }
            if (camera_service != null && openGlView != null && openGlView.getHolder().getSurface().isValid()) {
                camera_service.startPreview(openGlView);
            }
        } else {
            setPreviewSurfaceSecure(false);
            boolean screenIdleWithCapture = Preferences.VIDEO_SOURCE_SCREEN.equals(videoSource)
                    && camera_service != null && camera_service.hasScreenCapture();
            if (openGlView != null) {
                openGlView.setVisibility(screenIdleWithCapture ? View.INVISIBLE : View.VISIBLE);
            }
            if (screenCaptureOverlay != null) {
                screenCaptureOverlay.setVisibility(View.GONE);
            }
            if (!screenIdleWithCapture
                    && camera_service != null && openGlView != null && openGlView.getHolder().getSurface().isValid()) {
                camera_service.startPreview(openGlView);
                Log.d(LOGTAG, "onResume started preview");
            } else {
                Log.d(LOGTAG, "onResume didn't start preview " + (camera_service == null) + " " + (openGlView == null));
            }
        }
    }

    private void getViews() {
        pictureButton = requireActivity().findViewById(R.id.pictureButton);
        pictureButton.setOnClickListener(this);

        openGlView = activity.findViewById(R.id.openGlView);
        openGlView.getHolder().addCallback(this);
        openGlView.setOnTouchListener(this);

        tvBitrate = activity.findViewById(R.id.bitrate_value);
        tvLocationFix = activity.findViewById(R.id.location_fix_status);
        tvStreamPath = activity.findViewById(R.id.stream_path_name);
        tvRecording = activity.findViewById(R.id.recording_status);
        tvTakServer = activity.findViewById(R.id.atak_connection_status);
        tvFps = activity.findViewById(R.id.fps_value);
        tvFpsDrop = activity.findViewById(R.id.fps_drop_value);
        tvStateBanner = activity.findViewById(R.id.state_banner);
        tvStateTimer = activity.findViewById(R.id.state_timer);
        rightControls = activity.findViewById(R.id.right_controls);
        statsChip = activity.findViewById(R.id.stats_chip);
        stateDot = activity.findViewById(R.id.state_dot);
        takDot = activity.findViewById(R.id.tak_dot);
        gpsDot = activity.findViewById(R.id.gps_dot);
        recDot = activity.findViewById(R.id.rec_dot);
        touchLockOverlay = activity.findViewById(R.id.touch_lock_overlay);
        touchLockBadge = activity.findViewById(R.id.touch_lock_badge);
        if (touchLockBadge != null) {
            touchLockBadge.setOnLongClickListener(v -> { unlockTouches(); return true; });
        }
        if (bStartStop != null) {
            bStartStop.setOnLongClickListener(v -> { showRecordOptions(); return true; });
        }
        applyStreamState(StreamState.IDLE);

        videoSourceButton = activity.findViewById(R.id.videoSource);
        videoSourceButton.setOnClickListener(this);

        zoomSlider = activity.findViewById(R.id.zoom_slider);
        zoomSlider.setOnTouchListener(this);
        handler.postDelayed(setZoomSliderVisibility, 3000);
        setZoomRange();

        setStatusState();

        bStartStop = activity.findViewById(R.id.b_start_stop);
        bStartStop.setOnClickListener(this);
        switchCameraButton = activity.findViewById(R.id.switch_camera);
        switchCameraButton.setOnClickListener(this);
        whiteOverlay = activity.findViewById(R.id.white_color_overlay);
        screenCaptureOverlay = activity.findViewById(R.id.screen_capture_overlay);

        flashlight = activity.findViewById(R.id.flashlight);
        flashlight.setOnClickListener(this);

        FloatingActionButton rotateButton = activity.findViewById(R.id.orientation_rotate);
        if (rotateButton != null) rotateButton.setOnClickListener(this);

        applyWindowInsets();

        FloatingActionButton settingsButton = activity.findViewById(R.id.settingsButton);
        settingsButton.setOnClickListener(this);

        popupMenu = new PopupMenu(activity, videoSourceButton);
        popupMenu.getMenuInflater().inflate(R.menu.popup_menu, popupMenu.getMenu());
        popupMenu.setOnMenuItemClickListener(this);

        requireActivity().getWindow().setStatusBarColor(Color.TRANSPARENT);
        requireActivity().getWindow().setNavigationBarColor(Color.TRANSPARENT);
    }

    private void setPreviewSurfaceSecure(boolean secure) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && openGlView instanceof SurfaceView) {
            ((SurfaceView) openGlView).setSecure(secure);
            Log.d(LOGTAG, "setPreviewSurfaceSecure: " + secure);
        }
    }

    private final ServiceConnection mConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName className, IBinder service) {
            Log.d(LOGTAG, "onServiceConnected");
            String videoSource = pref.getString(Preferences.VIDEO_SOURCE, Preferences.VIDEO_SOURCE_DEFAULT);
            // For screen mode, don't start preview before startStream; that can leave camera fallback active.
            if (!Preferences.VIDEO_SOURCE_SCREEN.equals(videoSource)) {
                camera_service.startPreview(openGlView);
            }
            service_bound = true;
            bStartStop.setImageResource(R.drawable.stop);
            tvLocationFix.setText("no");
            tintDot(gpsDot, COLOR_ERR);
            tvTakServer.setText("no");
            tintDot(takDot, COLOR_ERR);
            camera_service.startStream();
            if (Preferences.VIDEO_SOURCE_SCREEN.equals(videoSource) && camera_service.hasScreenCapture()) {
                setPreviewSurfaceSecure(true);
                if (openGlView != null) {
                    openGlView.setVisibility(View.INVISIBLE);
                }
                if (screenCaptureOverlay != null) {
                    screenCaptureOverlay.setVisibility(View.VISIBLE);
                    screenCaptureOverlay.bringToFront();
                }
            }
            popupMenuHandler.stopClock();
            popupMenuHandler.toggleText();
        }

        public void onServiceDisconnected(ComponentName className) {
            service_bound = false;
        }
    };

    private void lockScreenOrientation() {
        // Honor the orientation the user picked in the pre-stream dialog. We pin the activity
        // to that orientation (SENSOR_* so it can still flip 180°) for the duration of the
        // stream, so the OUTPUT stays in the chosen orientation regardless of how the operator
        // tilts the phone. The encoder rotation is derived from FORCE_LANDSCAPE in the service
        // (landscape → rotation 0; portrait → CameraHelper.getCameraOrientation of the locked
        // portrait activity), which the dialog keeps in sync.
        String chosen = pref.getString(Preferences.STREAM_ORIENTATION, Preferences.STREAM_ORIENTATION_DEFAULT);
        int orientation = Preferences.STREAM_ORIENTATION_PORTRAIT.equals(chosen)
                ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                : ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE;
        Log.d(LOGTAG, "lockScreenOrientation " + chosen + " -> " + orientation);
        activity.setRequestedOrientation(orientation);
    }

    private void unlockScreenOrientation() {
        // Returning to idle ⇒ keep the orientation the operator picked with the rotate button.
        Log.d(LOGTAG, "unlockScreenOrientation -> chosen");
        if (activity != null) activity.setRequestedOrientation(streamOrientationToActivity());
    }

    /** Pop back to the source-selection landing page (or finish if it's not on the back stack). */
    private void popToSourcePage() {
        try {
            if (!getParentFragmentManager().popBackStackImmediate()) {
                activity.finish();
            }
        } catch (Exception e) {
            activity.finish();
        }
    }

    /**
     * If we arrived from the source page with GoPro selected, kick off the existing BLE→Wi-Fi
     * connect flow once the service is ready. One-shot: clears the flag so it doesn't re-fire on
     * later service-observer ticks. On failure the user simply backs out to the source page and
     * picks GoPro again (which re-navigates with the flag set).
     */
    private void maybeAutoConnectGoPro() {
        if (!pendingGoProAutoConnect || camera_service == null) return;
        pendingGoProAutoConnect = false;
        if (camera_service.getGoProNetwork() != null) return;   // already connected this session
        // CRITICAL ORDER: connect FIRST, then flip VIDEO_SOURCE to gopro in onSuccess. The GoPro
        // network/keep-alive client only exists after a successful connect; if we switched the
        // source first (as the source page used to), the streaming screen would build a GoProSource
        // with a null client that never tells the camera to push — the "GoPro fails" bug. Until
        // then the screen just shows the normal camera preview.
        io.opentakserver.opentakicu.gopro.GoProDialogs.startAutoConnect(activity, camera_service,
                () -> {
                    pref.edit().putString(Preferences.VIDEO_SOURCE, Preferences.VIDEO_SOURCE_GOPRO).apply();
                    flashlight.setImageResource(R.drawable.flashlight_off);
                });
    }

    /**
     * The camera/streaming SCREEN sits in landscape at idle — the camera is a 16:9 sensor and a
     * portrait viewport stretches it. ("Start in portrait" is satisfied by the source-selection
     * page, which is the portrait screen.) The OUTPUT orientation is still a per-stream choice made
     * in the pre-stream dialog (see {@link #lockScreenOrientation()}); don't touch orientation while
     * a stream/recording is live, or resuming the app would yank the locked stream orientation.
     */
    private void applyOrientationPreference() {
        if (activity == null) return;
        try {
            if (camera_service != null
                    && (camera_service.getStream().isStreaming() || camera_service.getStream().isRecording())) {
                return;
            }
        } catch (Throwable ignored) { /* stream not ready yet — safe to set idle orientation */ }
        int desired = streamOrientationToActivity();
        if (activity.getRequestedOrientation() != desired) {
            activity.setRequestedOrientation(desired);
        }
    }

    private static final int COLOR_OK = 0xFF00C853;
    private static final int COLOR_WARN = 0xFFFFC107;
    private static final int COLOR_ERR = 0xFFE53935;
    private static final int COLOR_DIM = 0xFF888888;

    /**
     * Updates the top-of-screen state chip with a dot color + label appropriate to
     * the current stream lifecycle. Also starts/stops the elapsed-time counter.
     */
    private void applyStreamState(StreamState state) {
        currentState = state;
        if (tvStateBanner == null) return;
        int textRes;
        int dotColor;
        boolean showTimer = false;
        switch (state) {
            case CONNECTING:
                textRes = R.string.state_connecting;
                dotColor = COLOR_WARN;
                break;
            case STREAMING:
                textRes = pref.getBoolean(Preferences.RECORD_VIDEO, Preferences.RECORD_VIDEO_DEFAULT)
                        ? R.string.state_recording : R.string.state_streaming;
                dotColor = COLOR_ERR; // red LIVE dot
                showTimer = true;
                break;
            case ERROR:
                textRes = R.string.state_error;
                dotColor = COLOR_ERR;
                break;
            case IDLE:
            default:
                textRes = R.string.state_idle;
                dotColor = COLOR_DIM;
                break;
        }
        tvStateBanner.setText(textRes);
        tintDot(stateDot, dotColor);

        // Start / stop the elapsed-time counter.
        if (showTimer) {
            if (streamStartedAtMs == 0) streamStartedAtMs = System.currentTimeMillis();
            if (tvStateTimer != null) tvStateTimer.setVisibility(View.VISIBLE);
            handler.removeCallbacks(elapsedTimer);
            handler.post(elapsedTimer);
        } else {
            streamStartedAtMs = 0;
            if (tvStateTimer != null) {
                tvStateTimer.setVisibility(View.GONE);
                tvStateTimer.setText("");
            }
            handler.removeCallbacks(elapsedTimer);
        }
    }

    /**
     * Push the floating chips and the icon strip away from system bars / cutouts so they
     * don't sit under the Android nav buttons in landscape. Works regardless of which
     * side the nav bar lands on (left or right of the screen depending on rotation).
     */
    private void applyWindowInsets() {
        View root = activity.findViewById(R.id.activity_custom);
        if (root == null) return;
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(
                    androidx.core.view.WindowInsetsCompat.Type.systemBars()
                            | androidx.core.view.WindowInsetsCompat.Type.displayCutout());
            int dp10 = (int) (10 * v.getResources().getDisplayMetrics().density);
            int dp12 = (int) (12 * v.getResources().getDisplayMetrics().density);
            // Icon strip is on the LEFT — push it inward by the left system inset.
            setMarginsSafe(activity.findViewById(R.id.right_controls), bars.left + dp10, -1, -1, -1);
            // Settings gear top-LEFT corner — top + left insets.
            setMarginsSafe(activity.findViewById(R.id.settingsButton), bars.left + dp12, bars.top + dp10, -1, -1);
            // Status badges are at bottom-RIGHT.
            setMarginsSafe(activity.findViewById(R.id.status_badges), -1, -1, bars.right + dp12, bars.bottom + dp12);
            // State chip is now top-CENTER (constrained start+end) — only the top inset applies.
            setMarginsSafe(activity.findViewById(R.id.state_chip), -1, bars.top + dp10, -1, -1);
            // Stats chip top-RIGHT — top + right insets.
            setMarginsSafe(activity.findViewById(R.id.stats_chip), -1, bars.top + dp10, bars.right + dp12, -1);
            // Bottom control cluster — keep it inside the bottom inset.
            setMarginsSafe(activity.findViewById(R.id.bottom_controls), -1, -1, -1, bars.bottom + dp12);
            return insets;
        });
        androidx.core.view.ViewCompat.requestApplyInsets(root);
    }

    private void setMarginsSafe(View v, int left, int top, int right, int bottom) {
        if (v == null) return;
        ViewGroup.LayoutParams raw = v.getLayoutParams();
        if (!(raw instanceof ViewGroup.MarginLayoutParams)) return;
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) raw;
        if (left >= 0) lp.leftMargin = left;
        if (top >= 0) lp.topMargin = top;
        if (right >= 0) lp.rightMargin = right;
        if (bottom >= 0) lp.bottomMargin = bottom;
        v.setLayoutParams(lp);
    }

    /** Tint a small circular shape drawable (the chip dots). */
    private void tintDot(View dot, int color) {
        if (dot == null || dot.getBackground() == null) return;
        dot.getBackground().mutate().setColorFilter(
                new android.graphics.PorterDuffColorFilter(color, android.graphics.PorterDuff.Mode.SRC_IN));
    }

    /**
     * Called once per onNewBitrate broadcast (~1 Hz). Updates the FPS readout (configured FPS
     * during a stable stream, "—" otherwise) and increments the drop counter when the stream
     * stalls (no new bitrate for > 1.8s).
     */
    private void onBitrateTick(long kbps) {
        long now = System.currentTimeMillis();
        long sinceLast = now - lastBitrateUpdateMs;
        lastBitrateUpdateMs = now;

        if (tvFps != null) {
            if (currentState == StreamState.STREAMING || currentState == StreamState.CONNECTING) {
                String fps = pref.getString(Preferences.VIDEO_FPS, Preferences.VIDEO_FPS_DEFAULT);
                tvFps.setText(fps);
                tvFps.setTextColor(kbps > 0 ? Color.GREEN : Color.YELLOW);
            } else {
                tvFps.setText("—");
                tvFps.setTextColor(Color.YELLOW);
            }
        }
        // If the bitrate update was abnormally late, count it as a "drop" — pedroSG94 normally
        // ticks ~1 Hz, so > 1800 ms means we lost at least one tick.
        if (sinceLast > 1800 && sinceLast < 30000) {
            droppedFrames++;
        }
        if (tvFpsDrop != null) {
            tvFpsDrop.setText(String.valueOf(droppedFrames));
            tvFpsDrop.setTextColor(droppedFrames > 0 ? Color.RED : Color.GREEN);
        }
    }

    /** Hide right-side controls + zoom slider + stats chip after a few seconds of no touch. */
    private final Runnable hideControlsRunnable = new Runnable() {
        @Override
        public void run() {
            if (rightControls != null) rightControls.animate().alpha(0f).setDuration(250).start();
            if (zoomSlider != null) zoomSlider.animate().alpha(0f).setDuration(250).start();
            if (statsChip != null) statsChip.animate().alpha(0.4f).setDuration(250).start();
        }
    };

    private void showControls() {
        if (rightControls != null) rightControls.animate().alpha(1f).setDuration(150).start();
        if (zoomSlider != null) zoomSlider.animate().alpha(1f).setDuration(150).start();
        if (statsChip != null) statsChip.animate().alpha(1f).setDuration(150).start();
        handler.removeCallbacks(hideControlsRunnable);
        handler.postDelayed(hideControlsRunnable, CONTROLS_HIDE_DELAY_MS);
    }

    Runnable setZoomSliderVisibility = new Runnable() {
        @Override
        public void run() {
            zoomSlider.animate().alpha(0f);
        }
    };

    /**
     * Launches the system MediaProjection consent dialog. Used both when the user picks
     * "Screen" from the popup menu (so the token is pre-acquired while we're still in the
     * foreground) and when {@link Camera2Service#onBubbleTap()} needs us to grant it before
     * starting a screen stream. No-op if the token is already held.
     */
    public void requestScreenCapture() {
        if (camera_service == null) return;
        if (camera_service.hasScreenCapture()) return;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            Toast.makeText(activity, "Screen streaming requires Android 5.0 or higher", Toast.LENGTH_LONG).show();
            return;
        }
        Intent captureIntent = camera_service.createScreenCaptureIntent();
        if (captureIntent == null) {
            Toast.makeText(activity, "Unable to start screen capture", Toast.LENGTH_LONG).show();
            return;
        }
        camera_service.prepareForScreenCapture();
        awaitingScreenCapturePermission = true;
        screenCaptureLauncher.launch(captureIntent);
    }

    /**
     * Enter touch-lock mode. While engaged, the {@code touch_lock_overlay} (a transparent
     * full-screen FrameLayout) swallows all touches except a long-press on the centred badge.
     * Lets the operator carry the phone around / re-grip it during a stream without risking
     * an accidental "Stop stream" tap.
     */
    private void lockTouches() {
        if (touchLockOverlay == null) return;
        touchLockOverlay.setVisibility(View.VISIBLE);
        touchLockOverlay.bringToFront();
        Toast.makeText(activity, R.string.touch_lock_just_locked, Toast.LENGTH_SHORT).show();
    }

    private void unlockTouches() {
        if (touchLockOverlay == null) return;
        touchLockOverlay.setVisibility(View.GONE);
    }

    /**
     * Long-press handler on the record button — pops a sheet so the operator can flip
     * between stream-only / record-only / stream+record and tweak resolution & bitrate
     * without going through Settings. Applied values are written back to SharedPreferences
     * so the existing pref-change listener in {@link Camera2Service} re-runs {@code getSettings()}
     * and re-prepares the encoders.
     */
    private void showRecordOptions() {
        if (camera_service != null
                && (camera_service.getStream().isStreaming() || camera_service.getStream().isRecording())) {
            Toast.makeText(activity, "Stop the current stream first", Toast.LENGTH_SHORT).show();
            return;
        }

        View v = LayoutInflater.from(activity).inflate(R.layout.dialog_record_options, null);
        android.widget.RadioGroup modeGroup = v.findViewById(R.id.opt_mode_group);
        android.widget.RadioGroup resGroup = v.findViewById(R.id.opt_res_group);
        android.widget.RadioGroup brGroup = v.findViewById(R.id.opt_br_group);

        // Pre-select from current prefs.
        boolean stream = pref.getBoolean(Preferences.STREAM_VIDEO, Preferences.STREAM_VIDEO_DEFAULT);
        boolean record = pref.getBoolean(Preferences.RECORD_VIDEO, Preferences.RECORD_VIDEO_DEFAULT);
        int modeId;
        if (stream && record) modeId = R.id.opt_mode_both;
        else if (record) modeId = R.id.opt_mode_record;
        else modeId = R.id.opt_mode_stream;
        modeGroup.check(modeId);

        // Resolution: we store an index into Camera2Source.getResolutionsBack(), but the user
        // sees friendly labels. We default to 1080p; if their saved index is unknown we keep current.
        String savedRes = pref.getString(Preferences.VIDEO_RESOLUTION, Preferences.VIDEO_RESOLUTION_DEFAULT);
        // Mapping is heuristic — we just remember the user's choice via a separate pref string.
        String quickRes = pref.getString("quick_resolution", "1080");
        if ("720".equals(quickRes)) resGroup.check(R.id.opt_res_720);
        else if ("480".equals(quickRes)) resGroup.check(R.id.opt_res_480);
        else resGroup.check(R.id.opt_res_1080);

        int kbps;
        try { kbps = Integer.parseInt(pref.getString(Preferences.VIDEO_BITRATE, Preferences.VIDEO_BITRATE_DEFAULT)); }
        catch (NumberFormatException e) { kbps = 1000; }
        int brId;
        if (kbps >= 8000) brId = R.id.opt_br_8;
        else if (kbps >= 4000) brId = R.id.opt_br_4;
        else if (kbps >= 2000) brId = R.id.opt_br_2;
        else brId = R.id.opt_br_1;
        brGroup.check(brId);

        new androidx.appcompat.app.AlertDialog.Builder(activity)
                .setTitle(R.string.record_options_title)
                .setView(v)
                .setPositiveButton(R.string.record_apply, (d, w) -> {
                    SharedPreferences.Editor e = pref.edit();
                    int mode = modeGroup.getCheckedRadioButtonId();
                    e.putBoolean(Preferences.STREAM_VIDEO, mode != R.id.opt_mode_record);
                    e.putBoolean(Preferences.RECORD_VIDEO, mode != R.id.opt_mode_stream);

                    int res = resGroup.getCheckedRadioButtonId();
                    // Map the chosen resolution back to a sensible camera bucket. We keep the
                    // VIDEO_RESOLUTION pref untouched so that the camera-resolution selector
                    // in Settings still works; the picker writes to the USB pref (used as our
                    // direct width/height inputs for non-Camera2 sources) and a tracking key.
                    int width = 1920, height = 1080;
                    String tag = "1080";
                    if (res == R.id.opt_res_720) { width = 1280; height = 720; tag = "720"; }
                    else if (res == R.id.opt_res_480) { width = 854; height = 480; tag = "480"; }
                    e.putString("quick_resolution", tag);
                    e.putString(Preferences.USB_WIDTH, String.valueOf(width));
                    e.putString(Preferences.USB_HEIGHT, String.valueOf(height));

                    int br = brGroup.getCheckedRadioButtonId();
                    int targetKbps = 1000;
                    if (br == R.id.opt_br_2) targetKbps = 2000;
                    else if (br == R.id.opt_br_4) targetKbps = 4000;
                    else if (br == R.id.opt_br_8) targetKbps = 8000;
                    e.putString(Preferences.VIDEO_BITRATE, String.valueOf(targetKbps));

                    e.apply();
                    Toast.makeText(activity, "Options applied", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(android.R.string.cancel, (d, w) -> d.dismiss())
                .show();
    }

    /**
     * Show a list of all available cameras with human-readable labels (facing + focal length).
     * The user's selection is preserved via {@link Camera2Service#selectCameraByIndex(int)};
     * the bug where startStream() would silently revert to camera 0 is fixed in the service
     * via the restore-selected-camera path inside prepareEncoders().
     */
    private void showCameraPicker() {
        if (camera_service == null) {
            Toast.makeText(activity, "Service not ready", Toast.LENGTH_SHORT).show();
            return;
        }
        java.util.List<String> ids = camera_service.getAvailableCameraIds();
        if (ids.isEmpty()) {
            Toast.makeText(activity, "No cameras detected on this device", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] labels = new String[ids.size()];
        for (int i = 0; i < ids.size(); i++) labels[i] = camera_service.describeCamera(ids.get(i));
        int currentIdx = Math.max(0, camera_service.getCurrentCameraIndex());

        new androidx.appcompat.app.AlertDialog.Builder(activity)
                .setTitle("Choose camera")
                .setSingleChoiceItems(labels, currentIdx, (dialog, which) -> {
                    camera_service.selectCameraByIndex(which);
                    setZoomRange();
                    if (flashlight != null) flashlight.setImageResource(R.drawable.flashlight_off);
                    dialog.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, (d, w) -> d.dismiss())
                .show();
    }

    /** Stops the current stream/recording and resets UI. Used by both the record button and the confirm dialog. */
    private void doStopStream() {
        bStartStop.setImageResource(R.drawable.ic_record);
        if (camera_service != null) {
            camera_service.stopStream(null, null);
        }
        if (service_bound) {
            try { activity.unbindService(mConnection); } catch (IllegalArgumentException ignored) {}
            service_bound = false;
        }
        unlockScreenOrientation();
        setStatusState();
        setPreviewSurfaceSecure(false);
        if (screenCaptureOverlay != null) {
            screenCaptureOverlay.setVisibility(View.GONE);
        }
        applyStreamState(StreamState.IDLE);
        // After stopping a screen stream, return to local camera preview.
        String videoSource = pref.getString(Preferences.VIDEO_SOURCE, Preferences.VIDEO_SOURCE_DEFAULT);
        if (videoSource != null && videoSource.equals(Preferences.VIDEO_SOURCE_SCREEN)
                && camera_service != null && openGlView != null && openGlView.getHolder().getSurface().isValid()) {
            if (camera_service.hasScreenCapture()) {
                openGlView.setVisibility(View.INVISIBLE);
                camera_service.stopPreview();
            } else {
                openGlView.setVisibility(View.VISIBLE);
                camera_service.startPreview(openGlView);
            }
        }
    }

    @Override
    public void onClick(View v) {
        int id = v.getId();
        // Any button tap also resets the auto-hide timer.
        showControls();
        if (id == R.id.b_start_stop) {
            // Confirm before stopping an active stream — avoids accidental taps in the field.
            if (service_bound && camera_service != null
                    && (camera_service.getStream().isStreaming() || camera_service.getStream().isRecording())) {
                new androidx.appcompat.app.AlertDialog.Builder(activity)
                        .setTitle(R.string.confirm_stop_title)
                        .setMessage(R.string.confirm_stop_message)
                        .setPositiveButton(android.R.string.ok, (d, w) -> doStopStream())
                        .setNegativeButton(android.R.string.cancel, (d, w) -> d.dismiss())
                        .show();
                return;
            }
            if (!service_bound) {
                // Orientation is chosen up-front with the rotate button (the preview already shows
                // exactly what the broadcast will look like), so just start.
                proceedStartStream();
            } else {
                doStopStream();
            }
        } else if (id == R.id.orientation_rotate) {
            rotateStreamOrientation();
        } else if (id == R.id.switch_camera) {
            showCameraPicker();
        } else if (id == R.id.settingsButton) {
            Intent intent = new Intent(activity, SettingsActivity.class);
            startActivity(intent);
        } else if (id == R.id.flashlight) {
            boolean lanternEnabled = camera_service.toggleLantern();

            if (lanternEnabled) flashlight.setImageResource(R.drawable.flashlight_on);
            else flashlight.setImageResource(R.drawable.flashlight_off);

        } else if (id == R.id.pictureButton) {
            activity.runOnUiThread(() -> whiteOverlay.setVisibility(View.VISIBLE));
            camera_service.take_photo();
        } else if (id == R.id.videoSource) {
            popupMenu.show();
        }
    }

    /**
     * Pre-stream prompt: landscape or portrait OUTPUT. Tapping a choice records it and proceeds
     * straight into the start sequence. The chosen orientation pins the activity (so preview +
     * output match) and drives the encoder rotation via {@link Preferences#FORCE_LANDSCAPE}.
     */
    /** Map the chosen stream orientation to an Activity orientation constant. */
    private int streamOrientationToActivity() {
        String o = pref.getString(Preferences.STREAM_ORIENTATION, Preferences.STREAM_ORIENTATION_DEFAULT);
        return Preferences.STREAM_ORIENTATION_PORTRAIT.equals(o)
                ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                : ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE;
    }

    /**
     * Rotate button: toggle the preview/stream orientation (landscape ↔ portrait). Rotating the
     * activity triggers {@link #onConfigurationChanged} which re-prepares the encoders + restarts
     * the preview, so the source adapts and the preview shows EXACTLY what the broadcast will look
     * like. Blocked while live (can't re-orient mid-broadcast).
     */
    private void rotateStreamOrientation() {
        boolean active = false;
        try {
            active = service_bound && camera_service != null
                    && (camera_service.getStream().isStreaming() || camera_service.getStream().isRecording());
        } catch (Throwable ignored) {}
        if (active) {
            Toast.makeText(activity, "Stop the broadcast to change orientation", Toast.LENGTH_SHORT).show();
            return;
        }
        String cur = pref.getString(Preferences.STREAM_ORIENTATION, Preferences.STREAM_ORIENTATION_DEFAULT);
        boolean toPortrait = !Preferences.STREAM_ORIENTATION_PORTRAIT.equals(cur);   // toggle
        setStreamOrientation(toPortrait
                ? Preferences.STREAM_ORIENTATION_PORTRAIT
                : Preferences.STREAM_ORIENTATION_LANDSCAPE);
        if (activity != null) activity.setRequestedOrientation(streamOrientationToActivity());
        Toast.makeText(activity, toPortrait ? R.string.orientation_portrait : R.string.orientation_landscape,
                Toast.LENGTH_SHORT).show();
    }

    /** Persist the chosen output orientation and keep FORCE_LANDSCAPE in sync for the encoder. */
    private void setStreamOrientation(String orientation) {
        boolean landscape = Preferences.STREAM_ORIENTATION_LANDSCAPE.equals(orientation);
        pref.edit()
                .putString(Preferences.STREAM_ORIENTATION, orientation)
                .putBoolean(Preferences.FORCE_LANDSCAPE, landscape)
                .apply();
    }

    /** The actual start sequence (formerly inline in the record button handler). */
    private void proceedStartStream() {
        if (service_bound) return;
        String videoSource = pref.getString(Preferences.VIDEO_SOURCE, Preferences.VIDEO_SOURCE_DEFAULT);
        if (videoSource != null && videoSource.equals(Preferences.VIDEO_SOURCE_SCREEN)) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
                Toast.makeText(activity, "Screen streaming requires Android 5.0 or higher", Toast.LENGTH_LONG).show();
                return;
            }

            if (camera_service == null) {
                Toast.makeText(activity, "Service not ready, please try again", Toast.LENGTH_LONG).show();
                return;
            }

            if (!camera_service.hasScreenCapture()) {
                Intent captureIntent = camera_service.createScreenCaptureIntent();
                if (captureIntent != null) {
                    camera_service.prepareForScreenCapture();
                    awaitingScreenCapturePermission = true;
                    bStartStop.setImageResource(R.drawable.stop);
                    lockScreenOrientation();
                    screenCaptureLauncher.launch(captureIntent);
                } else {
                    Toast.makeText(activity, "Unable to start screen capture", Toast.LENGTH_LONG).show();
                    bStartStop.setImageResource(R.drawable.ic_record);
                    unlockScreenOrientation();
                }
                return;
            } else {
                // We already have capture permission; set local UI state immediately.
                setPreviewSurfaceSecure(true);
                if (openGlView != null) {
                    openGlView.setVisibility(View.INVISIBLE);
                }
                if (screenCaptureOverlay != null) {
                    screenCaptureOverlay.setVisibility(View.VISIBLE);
                    screenCaptureOverlay.bringToFront();
                }
            }
        }

        activity.bindService(new Intent(activity, Camera2Service.class), mConnection, Context.BIND_IMPORTANT);
        bStartStop.setImageResource(R.drawable.stop);
        lockScreenOrientation();
        if (pref.getBoolean(Preferences.RECORD_VIDEO, Preferences.RECORD_VIDEO_DEFAULT)) {
            tvRecording.setText("on");
            tintDot(recDot, COLOR_OK);
        }
        // Switch the state chip immediately — the user tapped record. The
        // STREAM_CONNECTED broadcast will then flip it to LIVE when the server accepts
        // the connection, or onConnectionFailed will flip it to ERROR.
        applyStreamState(StreamState.CONNECTING);
        droppedFrames = 0;
    }

    @Override
    public boolean onMenuItemClick(MenuItem menuItem) {
        return popupMenuHandler.onMenuItemClick(menuItem, flashlight);
    }

    @Override
    public boolean onTouch(View view, MotionEvent motionEvent) {
        int action = motionEvent.getAction();

        // Any touch on the preview reveals the right-side controls + zoom slider and resets the
        // auto-hide timer. After 3 s of inactivity they fade out again so the preview is clean.
        if (action == MotionEvent.ACTION_DOWN) {
            showControls();
            handler.removeCallbacks(setZoomSliderVisibility);
        }
        if (action == MotionEvent.ACTION_UP) {
            handler.postDelayed(setZoomSliderVisibility, 3000);
        }

        if (view == zoomSlider && camera_service != null) {
            if (camera_service.getStream().getVideoSource() instanceof Camera2Source) {
                Camera2Source camera2Source = (Camera2Source) camera_service.getStream().getVideoSource();
                camera2Source.setZoom(zoomSlider.getValue());
                return false;
            }
        }
        if (motionEvent.getPointerCount() > 1) {
            if (action == MotionEvent.ACTION_MOVE && camera_service != null) {
                camera_service.setZoom(motionEvent);
                float zoom = camera_service.getZoom();
                if (zoom >= zoomSlider.getValueFrom() && zoom <= zoomSlider.getValueTo())
                    zoomSlider.setValue(camera_service.getZoom());
                else
                    zoomSlider.setValue(zoomSlider.getValueFrom());
            }
        } else if (action == MotionEvent.ACTION_DOWN && camera_service != null) {
            camera_service.tapToFocus(motionEvent);
        }
        return true;
    }

    @Override
    public void surfaceCreated(@NonNull SurfaceHolder surfaceHolder) {
        Log.d(LOGTAG, "surfaceCreated");
        // Defensive: surfaceCreated is sometimes the only callback we get if the surface size
        // doesn't actually change after creation. Without this the preview only starts once
        // surfaceChanged fires, which can be racy on cold launch — leading to "have to start the
        // app a couple times until it works".
        if (camera_service != null && surfaceHolder.getSurface().isValid()) {
            Log.i(LOGTAG, "surfaceCreated starting preview");
            camera_service.startPreview(openGlView);
        }
    }

    @Override
    public void surfaceChanged(@NonNull SurfaceHolder surfaceHolder, int format, int width, int height) {
        Log.d(LOGTAG, "surfaceChanged");
        if (camera_service != null && openGlView.getHolder().getSurface().isValid()) {
            Log.i(LOGTAG, "surfacechanged starting preview");
            camera_service.startPreview(openGlView);
        }
    }

    @Override
    public void surfaceDestroyed(@NonNull SurfaceHolder surfaceHolder) {
        Log.d(LOGTAG, "surfaceDestroyed");
        if (camera_service != null) {
            boolean active = camera_service.getStream().isStreaming() || camera_service.getStream().isRecording();
            if (active) {
                Log.d(LOGTAG, "surfaceDestroyed while streaming; not stopping preview");
            } else {
                camera_service.stopPreview();
            }
        }
    }

    //Handle screen rotation. Note: with screenOrientation="sensorLandscape" + force_landscape
    //pref on, this normally only fires on the 180° landscape flip, not portrait↔landscape.
    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        Log.d(LOGTAG, "onConfig " + newConfig.orientation);

        LayoutInflater inflater = (LayoutInflater) requireContext().getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        View newView = inflater.inflate(R.layout.camera2_fragment, null);
        ViewGroup rootView = (ViewGroup) requireView();
        rootView.removeAllViews();
        rootView.addView(newView);

        getViews();

        // Camera2Service may not be bound yet during cold launch; guard so we don't crash with NPE.
        if (camera_service != null) {
            camera_service.stopPreview();
            camera_service.prepareEncoders();
            camera_service.startPreview(openGlView);
        }
        if (popupMenuHandler != null) {
            popupMenuHandler.stopClock();
            popupMenuHandler.toggleText();
        }
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, @Nullable String s) {
        setStatusState();

        Log.d(LOGTAG, "Got pref " + s);
        if (s != null && s.equals(Preferences.VIDEO_SOURCE)) {
            setZoomRange();
            // If video source is no longer Screen, hide the screen-capture overlay.
            if (screenCaptureOverlay != null) {
                String videoSource = sharedPreferences.getString(Preferences.VIDEO_SOURCE, Preferences.VIDEO_SOURCE_DEFAULT);
                if (videoSource == null || !videoSource.equals(Preferences.VIDEO_SOURCE_SCREEN)) {
                    screenCaptureOverlay.setVisibility(View.GONE);
                    setPreviewSurfaceSecure(false);
                    if (openGlView != null) {
                        openGlView.setVisibility(View.VISIBLE);
                    }
                }
            }
        }
    }

    @Override
    public void onAuthError() {

    }

    @Override
    public void onConnectionStarted(@NonNull String s) {

    }

    @Override
    public void onConnectionSuccess() {

    }

    @Override
    public void onConnectionFailed(@NonNull String s) {

    }

    @Override
    public void onDisconnect() {

    }

    @Override
    public void onAuthSuccess() {

    }
}