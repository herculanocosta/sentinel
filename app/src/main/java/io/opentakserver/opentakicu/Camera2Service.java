package io.opentakserver.opentakicu;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.lifecycle.MutableLiveData;
import androidx.preference.PreferenceManager;
import io.opentakserver.opentakicu.contants.Preferences;
import io.opentakserver.opentakicu.gopro.GoProAudioSource;
import io.opentakserver.opentakicu.gopro.GoProClient;
import io.opentakserver.opentakicu.gopro.GoProRelay;
import io.opentakserver.opentakicu.gopro.GoProSource;
import io.opentakserver.opentakicu.overlay.FloatingBubbleManager;
import io.opentakserver.opentakicu.cot.ConnectionEntry;
import io.opentakserver.opentakicu.cot.Contact;
import io.opentakserver.opentakicu.cot.Detail;
import io.opentakserver.opentakicu.cot.Device;
import io.opentakserver.opentakicu.cot.Status;
import io.opentakserver.opentakicu.cot.Track;
import io.opentakserver.opentakicu.cot.feed;
import io.opentakserver.opentakicu.cot.videoConnections;
import io.opentakserver.opentakicu.cot.event;
import io.opentakserver.opentakicu.cot.Point;
import io.opentakserver.opentakicu.cot.Sensor;
import io.opentakserver.opentakicu.cot.__Video;
import io.opentakserver.opentakicu.utils.PathUtils;
import kotlin.NotImplementedError;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;
import android.util.Size;
import android.util.SizeF;
import android.view.Display;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.WindowManager;
import android.view.View;
import android.widget.Toast;

import com.ctc.wstx.stax.WstxInputFactory;
import com.ctc.wstx.stax.WstxOutputFactory;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.dataformat.xml.XmlFactory;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.pedro.common.AudioCodec;
import com.pedro.common.ConnectChecker;
import com.pedro.common.VideoCodec;
import com.pedro.encoder.input.sources.audio.InternalAudioSource;
import com.pedro.encoder.input.sources.audio.MicrophoneSource;
import com.pedro.encoder.input.sources.audio.NoAudioSource;
import com.pedro.encoder.input.gl.render.filters.object.TextObjectFilterRender;
import com.pedro.encoder.utils.gl.TranslateTo;
import com.pedro.encoder.input.sources.video.Camera2Source;
import com.pedro.encoder.input.sources.video.ScreenSource;
import com.pedro.encoder.input.sources.video.VideoSource;
import com.pedro.encoder.input.video.CameraHelper;
import com.pedro.encoder.utils.CodecUtil;
import com.pedro.extrasources.CameraUvcSource;
import com.pedro.library.base.StreamBase;
import com.pedro.library.rtmp.RtmpStream;
import com.pedro.library.rtsp.RtspStream;
import com.pedro.library.srt.SrtStream;
import com.pedro.library.udp.UdpStream;
import com.pedro.library.util.AndroidMuxerRecordController;
import com.pedro.library.util.BitrateAdapter;
import com.pedro.library.util.streamclient.RtmpStreamClient;
import com.pedro.library.util.streamclient.RtspStreamClient;
import com.pedro.library.view.OpenGlView;
import com.pedro.rtsp.rtsp.Protocol;
import com.topjohnwu.superuser.Shell;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

public class Camera2Service extends Service implements ConnectChecker,
        SharedPreferences.OnSharedPreferenceChangeListener, SensorEventListener {
    static final String START_STREAM = "start_stream";
    static final String STOP_STREAM = "stop_stream";
    static final String EXIT_APP = "exit_app";
    static final String AUTH_ERROR = "auth_error";
    static final String CONNECTION_FAILED = "connection_failed";
    static final String TOOK_PICTURE = "took_picture";
    static final String NEW_BITRATE = "new_bitrate";
    static final String LOCATION_CHANGE = "location_change";
    /** Periodic stats tick (every ~1 s). Extras: KEY_BITRATE_KBPS, KEY_FPS, KEY_UPLOAD_KBPS, KEY_CONGESTED. */
    public static final String STREAM_STATS = "io.opentakserver.opentakicu.STREAM_STATS";
    public static final String KEY_BITRATE_KBPS = "bitrate_kbps";
    public static final String KEY_FPS = "fps";
    public static final String KEY_UPLOAD_KBPS = "upload_kbps";
    public static final String KEY_CONGESTED = "congested";
    /** Optional non-empty human warning (low battery / device hot); shown on the state chip. */
    public static final String KEY_WARNING = "warning";
    /** Fires once when the RTSP/RTMP connection is fully up. */
    public static final String STREAM_CONNECTED = "io.opentakserver.opentakicu.STREAM_CONNECTED";
    /** Broadcast emitted by the popup menu to ask the camera UI to enter touch-lock mode. */
    public static final String LOCK_SCREEN = "io.opentakserver.opentakicu.LOCK_SCREEN";
    /**
     * Broadcast that asks the Camera UI to invoke the system MediaProjection screen-capture
     * permission dialog. The dialog requires a foreground Activity to deliver its result, so
     * we route through the fragment instead of letting the service open it directly.
     */
    public static final String REQUEST_SCREEN_CAPTURE = "io.opentakserver.opentakicu.REQUEST_SCREEN_CAPTURE";
    /** Broadcast that asks the Camera UI to open the video-source picker popup. */
    public static final String SHOW_SOURCE_PICKER = "io.opentakserver.opentakicu.SHOW_SOURCE_PICKER";

    private NotificationManager notificationManager;
    private static final String LOGTAG = "CameraService";
    private final String channelId = "CameraServiceChannel";
    private final int notifyId = 3425;
    private SharedPreferences preferences;

    private RtspStream rtspStream;
    private RtmpStream rtmpStream;
    private SrtStream srtStream;
    private UdpStream udpStream;
    private BitrateAdapter bitrateAdapter;

    private String protocol;
    private String address;
    private int port;
    private String path;
    private boolean tcp;
    private String username;
    private String password;
    private boolean stream_self_signed_cert;
    private String cert_file;
    private String cert_password;
    private int samplerate;
    private boolean stereo;
    private boolean echo_cancel;
    private boolean noise_reduction;
    private int fps;
    private Size resolution;
    private boolean adaptive_bitrate;
    private boolean record;
    private boolean stream;
    private boolean enable_audio;

    /** Auto-reconnect state: true once a stream has fully connected, so an UNEXPECTED later drop
     *  triggers a reconnect (a clean user stop clears this so we don't fight it). */
    private volatile boolean wasConnected = false;
    /** Battery/thermal guard throttle + one-shot thermal bitrate cut. */
    private long lastGuardWarnMs = 0;
    private boolean thermalBitrateReduced = false;
    private int bitrate;
    private int audio_bitrate;
    private String audio_codec;
    private String codec;
    private String uid;
    private double horizonalFov;
    private double verticalFov;
    public String videoSource;

    private boolean send_cot = false;
    private boolean send_stream_details = false;
    private String atak_address;
    private long last_fix_time = 0;
    /** Last GPS fix, cached for the burn-in overlay and the immediate self-marker CoT. */
    private Location lastKnownLocation;
    private boolean locationRequested = false;

    /* Standalone video-marker advertisement (a named marker carrying the live feed). */
    private String atak_callsign;
    private String atak_marker_type;
    private String atak_video_alias;
    /** Heartbeat that re-asserts the __video on the self marker (ATAK keeps overwriting it). */
    private final Handler cotHandler = new Handler(Looper.getMainLooper());
    private static final long COT_HEARTBEAT_MS = 3000;

    /* GPS/timestamp burn-in overlay (TextObjectFilterRender on the GL pipeline). */
    private TextObjectFilterRender overlayFilter;
    private boolean overlayAttached = false;
    private boolean overlayEnabled = false;
    private boolean overlayUtc = true;
    private final Handler overlayHandler = new Handler(Looper.getMainLooper());

    private OpenGlView openGlView;
    private boolean prepareAudio = false;
    private boolean prepareVideo = false;

    private int currentCameraId = 0;
    private boolean hasRedLightCamera = false;
    private boolean redLightEnabled = false;
    private int redLightCameraId = -1;
    private boolean isRooted = false;
    private final ArrayList<String> cameraIds = new ArrayList<>();
    private boolean lanternEnabled = false; //Keeps track of lantern when using a USB camera

    private SensorManager sensorManager;
    private android.hardware.Sensor magnetometer;
    private android.hardware.Sensor accelerometer;
    private float[] gravityData = new float[3];
    private float[] geomagneticData  = new float[3];
    private boolean hasGravityData = false;
    private boolean hasGeomagneticData = false;
    private double rotationInDegrees;

    private boolean exiting = false;
    private final IBinder binder = new LocalBinder();

    // GoPro support: network + HTTP client come from GoProAutoConnector and persist across
    // start/stop so the source can rebind on each preview restart.
    private android.net.Network goproNetwork;
    private String goproHost;
    private GoProClient goproClient;

    // Floating bubble — shown while app is backgrounded (and the user has enabled the pref).
    private FloatingBubbleManager bubble;
    private boolean appInForeground = true;

    /** Active GoPro UDP passthrough relay (only when videoSource=gopro AND protocol=udp). */
    private GoProRelay goproRelay;

    /**
     * Partial wake lock acquired while a stream / recording is active so the CPU keeps the
     * encoder + RTSP push alive even with the screen off. We use bounded timeouts (4 h) and
     * re-acquire on each startStream to satisfy lint and OEM background policies.
     */
    private android.os.PowerManager.WakeLock streamWakeLock;

    // Screen capture (MediaProjection) support
    private MediaProjection mediaProjection;
    private MediaProjectionManager mediaProjectionManager;
    private final MediaProjection.Callback mediaProjectionCallback = new MediaProjection.Callback() {
        @Override
        public void onStop() {
            Log.d(LOGTAG, "MediaProjection callback onStop: invalidating projection token");
            mediaProjection = null;
        }
    };
    // True only while startStream() is preparing encoders for an imminent stream start.
    private boolean preparingStreamStart = false;

    private File folder;
    private String currentDateAndTime;
    public static MutableLiveData<Camera2Service> observer = new MutableLiveData<>();

    private LocationListener _locListener;
    private LocationManager _locManager;
    private OkHttpClient okHttpClient = new OkHttpClient();
    private TcpClient tcpClient;
    private Thread tcpClientThread;
    private MulticastClient multicastClient;
    ExecutorService executor = Executors.newSingleThreadExecutor();

    final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            try {
                String action = intent.getAction();
                if (action == null) return;
                switch (action) {
                    case START_STREAM:
                        startStream();
                        break;
                    case STOP_STREAM:
                        stopStream(null, null);
                        break;
                    case EXIT_APP:
                        exiting = true;
                        stopStream(null, null);
                        stopSelf();
                        break;
                }
            } catch (Throwable t) {
                // BroadcastReceiver.onReceive runs on the main thread; any uncaught throw here
                // crashes the entire process. Log + swallow so the service keeps running.
                Log.e(LOGTAG, "service receiver crashed for " + intent, t);
            }
        }
    };

    //Suppress this warning for Android versions less than 13
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(LOGTAG, "onCreate");

        preferences = PreferenceManager.getDefaultSharedPreferences(this);
        preferences.registerOnSharedPreferenceChangeListener(this);
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        folder = PathUtils.getRecordPath();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            mediaProjectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Channel name appears in system Settings → Apps → SENTINEL → Notifications.
            NotificationChannel channel = new NotificationChannel(
                    channelId, getString(R.string.app_name) + " · Stream", NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("Foreground notification while a SENTINEL stream or recording is active.");
            notificationManager.createNotificationChannel(channel);
        }

        Notification notification = showNotification(getString(R.string.ready_to_stream), true);

        int type = 0;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                    | ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;
        }

        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.Q) {
            startForeground(notifyId, notification, type);
        } else {
            startForeground(notifyId, notification);
        }

        getSettings();
        //startPreview();
        observer.postValue(this);

        // Setup broadcast receiver for action in the notification
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(START_STREAM);
        intentFilter.addAction(STOP_STREAM);
        intentFilter.addAction(EXIT_APP);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, intentFilter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(receiver, intentFilter);
        }

        _locListener = new ICULocationListener();
        _locManager = (LocationManager) getApplicationContext().getSystemService(Context.LOCATION_SERVICE);

        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        magnetometer = sensorManager.getDefaultSensor(android.hardware.Sensor.TYPE_MAGNETIC_FIELD);
        accelerometer = sensorManager.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER);

        getCameraIds();
        bubble = new FloatingBubbleManager(this);
        // Lets GoProSource toast / broadcast diagnostic messages as it negotiates.
        io.opentakserver.opentakicu.gopro.GoProSource.setDiagnosticsContext(getApplicationContext());

        android.os.PowerManager pm = (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            streamWakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "Sentinel:Stream");
            streamWakeLock.setReferenceCounted(false);
        }
    }

    private void acquireStreamWakeLock() {
        if (streamWakeLock == null) return;
        try {
            if (!streamWakeLock.isHeld()) streamWakeLock.acquire(4L * 60 * 60 * 1000); // 4 h
        } catch (Exception e) { Log.w(LOGTAG, "wake lock acquire failed", e); }
    }

    private void releaseStreamWakeLock() {
        if (streamWakeLock == null) return;
        try { if (streamWakeLock.isHeld()) streamWakeLock.release(); }
        catch (Exception ignored) {}
    }

    /**
     * Tell the service whether the app's UI is currently in the foreground. When we go to the
     * background and the user has enabled the floating bubble (and granted overlay permission),
     * we pop a draggable bubble over other apps so they can see status + one-tap toggle the
     * stream. When the UI comes back, hide the bubble.
     */
    public void setAppForeground(boolean inForeground) {
        this.appInForeground = inForeground;
        refreshFloatingBubble();
    }

    private void refreshFloatingBubble() {
        if (bubble == null) return;
        // Floating bubble feature retired (the user wasn't using it). Keep the manager wired so
        // the rest of the code's null-guarded bubble.* calls stay no-ops, but never show it.
        boolean wantBubble = false;
        if (wantBubble && !bubble.isVisible()) {
            bubble.show(new FloatingBubbleManager.BubbleCallback() {
                @Override public void onBubbleTap() { Camera2Service.this.onBubbleTap(); }
                @Override public void onBubbleLongPress() { Camera2Service.this.onBubbleLongPress(); }
            });
            applyBubbleStateFromCurrent();
        } else if (!wantBubble && bubble.isVisible()) {
            bubble.hide();
        }
    }

    /**
     * User tapped the floating bubble — start the stream if idle, stop it if streaming.
     *
     * Special case: if the configured video source is SCREEN but we don't yet have a
     * {@code MediaProjection} token (i.e. the user picked "Screen" from a menu but never tapped
     * record while focused), the system permission dialog must be hosted by an Activity. We
     * bring {@link MainActivity} to the foreground and ask the fragment to launch the dialog.
     * That way a bubble tap from inside (say) the GoPro app does the right thing — opens
     * SENTINEL once for consent, then starts capturing.
     */
    private void onBubbleTap() {
        try {
            if (getStream().isStreaming() || getStream().isRecording()) {
                stopStream(null, null);
                applyBubbleStateFromCurrent();
                return;
            }
            String src = preferences.getString(Preferences.VIDEO_SOURCE, Preferences.VIDEO_SOURCE_DEFAULT);
            if (Preferences.VIDEO_SOURCE_SCREEN.equals(src) && !hasScreenCapture()) {
                bringActivityForward(REQUEST_SCREEN_CAPTURE);
                return;
            }
            startStream();
            applyBubbleStateFromCurrent();
        } catch (Throwable t) {
            Log.e(LOGTAG, "onBubbleTap crashed", t);
        }
    }

    /**
     * User long-pressed the bubble — bring SENTINEL forward and pop the source picker so the
     * user can switch source without needing to dig through the in-app menus.
     */
    public void onBubbleLongPress() {
        try { bringActivityForward(SHOW_SOURCE_PICKER); }
        catch (Throwable t) { Log.e(LOGTAG, "onBubbleLongPress crashed", t); }
    }

    /** Launches MainActivity then broadcasts the given action so the fragment handles it. */
    private void bringActivityForward(String thenBroadcastAction) {
        Intent open = new Intent(getApplicationContext(), MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        open.putExtra("io.opentakserver.opentakicu.PENDING_ACTION", thenBroadcastAction);
        startActivity(open);
    }

    private void applyBubbleStateFromCurrent() {
        if (bubble == null || !bubble.isVisible()) return;
        int dot;
        if (getStream().isStreaming()) {
            dot = 0xFF00C853;  // green
        } else if (getStream().isRecording()) {
            dot = 0xFFE53935;  // red
        } else {
            dot = 0xFF888888;  // dim
        }
        bubble.updateState(dot, 0);
    }

    /**
     * Populate {@link #cameraIds} with every physical camera the device exposes. Asked from
     * the Camera Manager directly so it works regardless of whether the current pedroSG94
     * video source is Camera2 (fresh start), USB, Screen, or GoPro — the user can always pop
     * the camera picker and see the device's cameras.
     */
    private void getCameraIds() {
        cameraIds.clear();
        try {
            CameraManager cm = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            if (cm != null) cameraIds.addAll(Arrays.asList(cm.getCameraIdList()));
        } catch (Exception e) {
            Log.w(LOGTAG, "Could not enumerate cameras from CameraManager", e);
        }

        // Some OEM-specific quirks the original code handled. Keep them.
        if (Objects.equals(Build.MODEL, "Armor 26 Ultra") && !cameraIds.contains("2")) {
            cameraIds.add("2");
        } else {
            Log.d(LOGTAG, "Device model is " + Build.MODEL);
        }

        redLightCameraId = FeatureSwitcher.getRedLightCamId();
        if (redLightCameraId != -1) {
            hasRedLightCamera = true;
            String redId = String.valueOf(redLightCameraId);
            if (!cameraIds.contains(redId)) cameraIds.add(redId);
            Log.d(LOGTAG, "This device has a red light camera (" + redLightCameraId + "), checking for root...");
            try { isRooted = Shell.getShell().isRoot(); }
            catch (Throwable t) { isRooted = false; }
            Log.d(LOGTAG, "Device rooted: " + isRooted);
        }

        int wideAngleCamId = FeatureSwitcher.getWideAngleCamId();
        if (wideAngleCamId != -1) {
            String wideId = String.valueOf(wideAngleCamId);
            if (!cameraIds.contains(wideId)) cameraIds.add(wideId);
            Log.d(LOGTAG, "This device has a wide angle camera with ID " + wideAngleCamId);
        }

        Log.d(LOGTAG, "Got cameraIds " + cameraIds);
    }

    public float getZoom() {
        Log.d(LOGTAG, "GetZoom " + (getStream().getVideoSource() instanceof Camera2Source));
        if (getStream().getVideoSource() instanceof Camera2Source) {
            Camera2Source camera2Source = (Camera2Source) getStream().getVideoSource();
            Log.d(LOGTAG, "Zoom is " + camera2Source.getZoom());
            if (camera2Source.getZoom() < camera2Source.getZoomRange().getLower() || camera2Source.getZoom() > camera2Source.getZoomRange().getUpper())
                return camera2Source.getZoomRange().getLower();

            return camera2Source.getZoom();
        }
        Log.d(LOGTAG, "Zoom is 0");
        return 0f;
    }

    public VideoSource getVideoSource() {
        if (videoSource.equals(Preferences.VIDEO_SOURCE_USB)) {
            Log.d(LOGTAG, "returning new usb cam");
            return new CameraUvcSource();
        } else if (videoSource.equals(Preferences.VIDEO_SOURCE_SCREEN)) {
            Log.d(LOGTAG, "returning new screen source");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && mediaProjection != null) {
                return new ScreenSource(getApplicationContext(), mediaProjection);
            } else {
                Log.w(LOGTAG, "Screen source requested but MediaProjection is not available, falling back to Camera2");
                return new Camera2Source(getApplicationContext());
            }
        } else if (videoSource.equals(Preferences.VIDEO_SOURCE_GOPRO)) {
            Log.d(LOGTAG, "returning new GoPro source");
            return new GoProSource(this, GoProSource.DEFAULT_UDP_PORT, goproClient);
        } else {
            Log.d(LOGTAG, "returning new cam2");
            return new Camera2Source(getApplicationContext());
        }
    }

    /**
     * Called by {@link io.opentakserver.opentakicu.gopro.GoProAutoConnector} after BLE wake
     * + Wi-Fi join succeed. Stores the bound {@link android.net.Network} so subsequent
     * {@link GoProSource} instances can use it (and so we can build a {@link GoProClient}
     * for HTTP control + keep-alive).
     */
    public void setGoProNetwork(android.net.Network network, String host) {
        this.goproNetwork = network;
        this.goproHost = host;
        if (goproClient != null) {
            try { goproClient.shutdown(); } catch (Exception ignored) {}
        }
        this.goproClient = new GoProClient(this, host, 8080, network);
    }

    public android.net.Network getGoProNetwork() { return goproNetwork; }
    public String getGoProHost() { return goproHost; }

    private NotificationCompat.Action startStreamAction() {
        Intent start_streaming = new Intent();
        start_streaming.setAction(START_STREAM);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(getApplicationContext(), 1, start_streaming, PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Action(R.drawable.ic_record, getString(R.string.start_stream), pendingIntent);
    }

    private NotificationCompat.Action stopStreamAction() {
        Intent stop = new Intent();
        stop.setAction(STOP_STREAM);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(getApplicationContext(), 1, stop, PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Action(R.drawable.stop, getString(R.string.stop_stream), pendingIntent);
    }

    public Notification showNotification(String content, boolean silent) {
        if (exiting)
            return null;

        // Always show the exit app button in the notification
        Intent exitIntent = new Intent();
        exitIntent.setAction(EXIT_APP);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(getApplicationContext(), 69, exitIntent, PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Action exit = new NotificationCompat.Action(R.drawable.icon_microphone_off, getString(R.string.exit), pendingIntent);

        // Bring MainActivity to the screen when the notification is pressed
        Intent intent = new Intent(getApplicationContext(), MainActivity.class);
        intent.setAction(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent launchActivity = PendingIntent.getActivity(getApplicationContext(), 1, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);

        NotificationCompat.Builder notificationBuilder = new NotificationCompat.Builder(this, channelId)
                .setOngoing(true)
                .setSilent(silent)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(content))
                .setContentTitle(getString(R.string.app_name))
                // Monochrome status-bar glyph (not the launcher icon — that renders as a white blob)
                // + the SENTINEL accent colour so the notification matches the app theme.
                .setSmallIcon(R.drawable.ic_notification)
                .setColor(getColor(R.color.appColor))
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(launchActivity)
                .setContentText(content);

        // Show start/stop stream button in notification
        if (getStream().isStreaming()) {
            notificationBuilder.addAction(stopStreamAction());
        } else {
            notificationBuilder.addAction(startStreamAction());
        }

        notificationBuilder.addAction(exit);
        Notification notification = notificationBuilder.build();
        notificationManager.notify(notifyId, notification);

        return notification;
    }

    public StreamBase getStream() {
        if (rtspStream != null)
            return rtspStream;
        if  (rtmpStream != null)
            return rtmpStream;
        if (srtStream != null)
            return srtStream;
        if (udpStream != null)
            return udpStream;

        return  new RtspStream(getApplicationContext(), this);
    }

    public void startPreview(OpenGlView openGlView) {
        this.openGlView = openGlView;
        // For screen source, never show screen capture in the local preview while streaming,
        // to avoid a recursive feedback loop. Preview is only used as a camera fallback.
        if (videoSource.equals(Preferences.VIDEO_SOURCE_SCREEN) && getStream().isStreaming()) {
            Log.d(LOGTAG, "Skipping local preview: streaming screen");
            return;
        }
        // If screen mode is selected but we're not actively streaming, make sure preview uses
        // camera fallback instead of a potentially stale ScreenSource.
        if (videoSource.equals(Preferences.VIDEO_SOURCE_SCREEN) && !getStream().isStreaming()) {
            Log.d(LOGTAG, "Screen mode idle: reconfiguring preview to camera fallback");
            prepareEncoders();
            return;
        }
        if (!getStream().isOnPreview()) {
            Log.d(LOGTAG, "Starting Preview");
            try {
                getStream().startPreview(openGlView, true);
                // Fresh GL pipeline → (re)attach the burn-in overlay if it's enabled.
                overlayAttached = false;
                applyTextOverlay();
            } catch (SecurityException e) {
                Log.e(LOGTAG, "Failed to start preview, MediaProjection is no longer valid", e);
                if (videoSource.equals(Preferences.VIDEO_SOURCE_SCREEN)) {
                    mediaProjection = null;
                }
            }
        } else {
            Log.e(LOGTAG, "not starting preview");
            applyTextOverlay();
        }
    }

    public void stopPreview() {
        if (getStream().isOnPreview()) {
            Log.d(LOGTAG, "Stopping Preview");
            onGlTornDown();
            getStream().stopPreview();
        }
    }

    /**
     * Returns true if a MediaProjection for screen capture has already been obtained.
     */
    public boolean hasScreenCapture() {
        return mediaProjection != null;
    }

    /**
     * Create an intent to request screen capture permission from the user.
     * Only valid on API 21+.
     */
    public Intent createScreenCaptureIntent() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && mediaProjectionManager != null) {
            return mediaProjectionManager.createScreenCaptureIntent();
        }
        return null;
    }

    /**
     * Updates foreground notification; tries to include {@link ServiceInfo#FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION}.
     * On Android 15+ some builds throw {@link SecurityException} when upgrading an already-running FGS to add
     * projection — fall back to mic|camera so the capture dialog can still be shown.
     */
    private void startForegroundWithProjectionBestEffort(@NonNull Notification notification) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return;
        }
        int withProjection = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                | ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                | ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
        int basicTypes = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                | ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;
        try {
            startForeground(notifyId, notification, withProjection);
        } catch (SecurityException e) {
            Log.e(LOGTAG, "startForeground with MEDIA_PROJECTION rejected; retrying basic FGS types", e);
            try {
                startForeground(notifyId, notification, basicTypes);
            } catch (SecurityException e2) {
                Log.e(LOGTAG, "startForeground with basic types also failed", e2);
            }
        }
    }

    /**
     * Switch to a foreground service with MEDIA_PROJECTION type before launching the screen
     * capture permission dialog. Required on API 34+ so that getMediaProjection() can succeed
     * when the user grants permission.
     */
    public void prepareForScreenCapture() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Notification notification = showNotification(getString(R.string.ready_to_stream), true);
            startForegroundWithProjectionBestEffort(notification);
        }
    }

    /**
     * Store the MediaProjection returned from the screen capture permission dialog.
     */
    public boolean setScreenCaptureResult(int resultCode, Intent data) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP || mediaProjectionManager == null) {
            Log.e(LOGTAG, "MediaProjection not supported on this device");
            return false;
        }
        try {
            if (mediaProjection != null) {
                try {
                    mediaProjection.unregisterCallback(mediaProjectionCallback);
                } catch (Exception ignored) {
                }
                mediaProjection.stop();
            }
            mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data);
            if (mediaProjection == null) {
                Log.e(LOGTAG, "Failed to get MediaProjection");
                return false;
            }
            mediaProjection.registerCallback(mediaProjectionCallback, new Handler(Looper.getMainLooper()));
            Log.d(LOGTAG, "MediaProjection acquired");
            // Add mediaProjection to foreground service type so we're allowed to use it (API 34+).
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Notification notification = showNotification(getString(R.string.ready_to_stream), true);
                startForegroundWithProjectionBestEffort(notification);
            }
            return true;
        } catch (Exception e) {
            Log.e(LOGTAG, "Error setting MediaProjection result", e);
            mediaProjection = null;
            return false;
        }
    }

    public void setView(OpenGlView openGlView) {
        Log.d(LOGTAG, "setView openGlView");
        //getCamera().replaceView(openGlView);
    }

    public void setView(Context context) {
        Log.d(LOGTAG, "setView context");
        //getCamera().replaceView(context);
    }

    public boolean toggleLantern() {
        if (videoSource.equals(Preferences.VIDEO_SOURCE_DEFAULT)) {
            Camera2Source camera2Source = (Camera2Source) getStream().getVideoSource();
            if (Objects.equals(camera2Source.getCurrentCameraId(), redLightCameraId + "")) {
                return toggleRedLights();
            }
            else if (camera2Source.isLanternEnabled()) {
                camera2Source.disableLantern();
            } else {
                try {
                    camera2Source.enableLantern();
                } catch (Exception e) {
                    Log.d(LOGTAG, "Failed to enable lantern: " + e.getLocalizedMessage());
                    e.printStackTrace();
                }
            }
            return camera2Source.isLanternEnabled();
        }

        else {
            CameraManager camManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            try {
                camManager.setTorchMode(camManager.getCameraIdList()[0], !lanternEnabled);   //Turn ON
                lanternEnabled = !lanternEnabled;
            } catch (CameraAccessException e) {
                e.printStackTrace();
            }
            return lanternEnabled;
        }
    }

    private boolean toggleRedLights() {
        Camera2Source camera2Source;
        if (videoSource.equals(Preferences.VIDEO_SOURCE_DEFAULT))
            camera2Source = (Camera2Source) getStream().getVideoSource();
        else
            return false;

        if (hasRedLightCamera && isRooted && Objects.equals(camera2Source.getCurrentCameraId(), FeatureSwitcher.getRedLightCamId() + "")) {
            /*
                The magic file to toggle the IR LEDs will either be /sys/class/flash_irtouch/flash_irtouch_data/irtouch_value or
                /sys/class/flashlight_core/flashlight/flashlight_irtorch depending on the device. Running the ls command followed by &&
                ensures that the echo command will only run if the file actually exists
             */

            if (redLightEnabled) {
                Shell.cmd("ls /sys/class/flash_irtouch/flash_irtouch_data/irtouch_value && echo 0 > /sys/class/flash_irtouch/flash_irtouch_data/irtouch_value").exec();
                Shell.cmd("ls /sys/class/flashlight_core/flashlight/flashlight_irtorch && echo 0 > /sys/class/flashlight_core/flashlight/flashlight_irtorch").exec();
                redLightEnabled = false;
            } else {
                Shell.cmd("ls /sys/class/flash_irtouch/flash_irtouch_data/irtouch_value && echo 1 > /sys/class/flash_irtouch/flash_irtouch_data/irtouch_value").exec();
                Shell.cmd("ls /sys/class/flashlight_core/flashlight/flashlight_irtorch && echo 1 > /sys/class/flashlight_core/flashlight/flashlight_irtorch").exec();
                redLightEnabled = true;
            }
        }

        return redLightEnabled;
    }

    /**
     * Returns the list of camera ids ATAK ICU knows about. Each id can be passed back
     * via {@link #selectCameraById(String)} or to {@link #describeCamera(String)}.
     */
    public java.util.List<String> getAvailableCameraIds() {
        if (cameraIds.isEmpty()) getCameraIds();
        return new java.util.ArrayList<>(cameraIds);
    }

    /** Index into {@link #getAvailableCameraIds()} of the currently active camera. */
    public int getCurrentCameraIndex() {
        return currentCameraId;
    }

    /**
     * Build a human-readable label for a camera id. Combines lens facing ("Back" /
     * "Front" / "External") with the primary focal length so the user can see "Back · 4.4mm".
     */
    public String describeCamera(String cameraId) {
        try {
            CameraManager cm = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            CameraCharacteristics c = cm.getCameraCharacteristics(cameraId);
            Integer facing = c.get(CameraCharacteristics.LENS_FACING);
            String name;
            if (facing == null) name = "Camera";
            else if (facing == CameraCharacteristics.LENS_FACING_FRONT) name = "Front";
            else if (facing == CameraCharacteristics.LENS_FACING_BACK) name = "Back";
            else name = "External";
            // Mark IR / red-light camera explicitly so the user knows what it is.
            if (redLightCameraId != -1 && cameraId.equals(String.valueOf(redLightCameraId))) {
                return "IR / Red-light · " + name + " (id " + cameraId + ")";
            }
            float[] focals = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
            if (focals != null && focals.length > 0) {
                return String.format(Locale.US, "%s · %.1fmm (id %s)", name, focals[0], cameraId);
            }
            return name + " (id " + cameraId + ")";
        } catch (Exception e) {
            return "Camera " + cameraId;
        }
    }

    /**
     * Activate a camera by its index in {@link #getAvailableCameraIds()}. If the current video
     * source isn't Camera2 (user came from GoPro / USB / Screen), we also flip the
     * {@link Preferences#VIDEO_SOURCE} pref back to camera so the next stream uses the
     * selected camera.
     */
    public void selectCameraByIndex(int idx) {
        if (cameraIds.isEmpty()) getCameraIds();
        if (idx < 0 || idx >= cameraIds.size()) return;

        // Coming from a non-camera source — switch back to Camera2 first.
        if (!videoSource.equals(Preferences.VIDEO_SOURCE_DEFAULT)) {
            preferences.edit().putString(Preferences.VIDEO_SOURCE, Preferences.VIDEO_SOURCE_DEFAULT).apply();
            // getSettings() will be triggered via the shared-pref listener; it will re-prepare
            // encoders and set the videoSource field. We still need to apply the camera id to
            // the freshly-created Camera2Source. Save the desired index; getSettings will pick
            // it up via currentCameraId.
            currentCameraId = idx;
            return;
        }

        if (idx == currentCameraId) return;

        Camera2Source camera2Source = (Camera2Source) getStream().getVideoSource();
        // Turn off IR LEDs if we're leaving the IR camera.
        if (cameraIds.get(currentCameraId).equals(redLightCameraId + "") && redLightEnabled) {
            toggleRedLights();
        }
        currentCameraId = idx;
        String wantId = cameraIds.get(currentCameraId);
        Log.d(LOGTAG, "selectCameraByIndex -> " + wantId);
        camera2Source.openCameraId(wantId);
        if (wantId.equals(redLightCameraId + "")) toggleRedLights();
        updateFovForCurrentCamera();
    }

    private void updateFovForCurrentCamera() {
        if (!videoSource.equals(Preferences.VIDEO_SOURCE_DEFAULT)) return;
        try {
            Camera2Source camera2Source = (Camera2Source) getStream().getVideoSource();
            CameraManager cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            CameraCharacteristics cc = cameraManager.getCameraCharacteristics(camera2Source.getCurrentCameraId());
            float[] maxFocus = cc.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
            SizeF size = cc.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
            if (maxFocus != null && size != null) {
                horizonalFov = (2 * Math.atan(size.getWidth() / (maxFocus[0] * 2))) * 180 / Math.PI;
                verticalFov = (2 * Math.atan(size.getHeight() / (maxFocus[0] * 2))) * 180 / Math.PI;
            }
        } catch (Exception e) {
            Log.w(LOGTAG, "updateFovForCurrentCamera: " + e.getMessage());
        }
    }

    public void switchCamera() {
        if (videoSource.equals(Preferences.VIDEO_SOURCE_DEFAULT)) {
            Log.d(LOGTAG, "Camera Changed");
            Camera2Source camera2Source = (Camera2Source) getStream().getVideoSource();

            if (cameraIds.isEmpty())
                getCameraIds();

            // Turn off the IR LEDs if they're on and we're switching away from the IR Camera
            if (cameraIds.get(currentCameraId).equals(redLightCameraId + "") && redLightEnabled) {
                toggleRedLights();
            }

            // Switch the camera
            currentCameraId++;
            if (currentCameraId > cameraIds.size() - 1) {
                currentCameraId = 0;
            }
            Log.d(LOGTAG, "Switching to camera " + cameraIds.get(currentCameraId));
            camera2Source.openCameraId(cameraIds.get(currentCameraId));

            // Turn on the IR LEDs if we're switching to the IR Camera
            if (cameraIds.get(currentCameraId).equals(redLightCameraId + "")) {
                toggleRedLights();
            }

            CameraManager cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            try {
                CameraCharacteristics cameraCharacteristics = cameraManager.getCameraCharacteristics(camera2Source.getCurrentCameraId());
                float[] maxFocus = cameraCharacteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
                SizeF size = cameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
                float w = size.getWidth();
                float h = size.getHeight();
                horizonalFov = (2*Math.atan(w/(maxFocus[0]*2))) * 180/Math.PI;
                verticalFov = (2*Math.atan(h/(maxFocus[0]*2))) * 180/Math.PI;
                Log.d(LOGTAG, "horizontalFov = " + horizonalFov);
                Log.d(LOGTAG, "verticalFov = " + verticalFov);
            } catch (CameraAccessException e) {
                Log.e(LOGTAG, "Failed to get camera characteristics", e);
            }
        }
    }

    public void setZoom(MotionEvent motionEvent) {
        if (videoSource.equals(Preferences.VIDEO_SOURCE_DEFAULT)) {
            Camera2Source camera2Source = (Camera2Source) getStream().getVideoSource();
            camera2Source.setZoom(motionEvent);
        }
    }

    public void tapToFocus(MotionEvent motionEvent) {
        if (videoSource.equals(Preferences.VIDEO_SOURCE_DEFAULT)) {
            Camera2Source camera2Source = (Camera2Source) getStream().getVideoSource();
            if (openGlView != null) {
                camera2Source.tapToFocus((View) openGlView, motionEvent);
            }
        }
    }

    @Override
    public void onDestroy() {
        observer.postValue(null);
        unregisterReceiver(receiver);
        preferences.unregisterOnSharedPreferenceChangeListener(this);
        if (mediaProjection != null) {
            mediaProjection.stop();
            mediaProjection = null;
        }
        if (bubble != null) {
            bubble.hide();
            bubble = null;
        }
        releaseStreamWakeLock();
        prefSettleHandler.removeCallbacksAndMessages(null);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int start_id) {
        return START_STICKY;
    }

    /**
     * Listen for device rotation so the floating bubble can reposition (default to left edge
     * in landscape, top in portrait — see {@link FloatingBubbleManager}).
     */
    @Override
    public void onConfigurationChanged(@NonNull android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (bubble != null && bubble.isVisible()) {
            bubble.repositionForCurrentOrientation();
        }
    }

    @Override
    public void onSensorChanged(SensorEvent sensorEvent) {
        switch (sensorEvent.sensor.getType()){
            case android.hardware.Sensor.TYPE_ACCELEROMETER:
                System.arraycopy(sensorEvent.values, 0, gravityData, 0, 3);
                hasGravityData = true;
                break;
            case android.hardware.Sensor.TYPE_MAGNETIC_FIELD:
                System.arraycopy(sensorEvent.values, 0, geomagneticData, 0, 3);
                hasGeomagneticData = true;
                break;
            default:
                return;
        }

        if (hasGravityData && hasGeomagneticData) {
            float[] identityMatrix = new float[9];
            float[] rotationMatrix = new float[9];
            boolean success = SensorManager.getRotationMatrix(rotationMatrix, identityMatrix,
                    gravityData, geomagneticData);

            if (success) {
                float[] orientationMatrix = new float[3];
                SensorManager.getOrientation(rotationMatrix, orientationMatrix);
                float rotationInRadians = orientationMatrix[0];
                rotationInDegrees = Math.toDegrees(rotationInRadians);

                WindowManager windowService = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
                int rotation =  windowService.getDefaultDisplay().getRotation();
                int screen_orientation;
                switch (rotation) {
                    case Surface.ROTATION_90:
                        screen_orientation = 90;
                        break;
                    case Surface.ROTATION_180:
                        screen_orientation = -180;
                        break;
                    case Surface.ROTATION_270:
                        screen_orientation = -90;
                        break;
                    default:
                        screen_orientation = 0;
                        break;
                }

                rotationInDegrees += screen_orientation;

                if (rotationInDegrees < 0.0f) {
                    rotationInDegrees += 360.0f;
                }
            }
        }
    }

    @Override
    public void onAccuracyChanged(android.hardware.Sensor sensor, int i) {

    }

    public class LocalBinder extends Binder {
        Camera2Service getService() {
            return Camera2Service.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        Log.d(LOGTAG, "onBind");
        return binder;
    }


    @Override
    public void onAuthError() {
        Log.d(LOGTAG, "Auth error");
        stopStream(getString(R.string.auth_error), AUTH_ERROR);
        if (bubble != null) bubble.updateState(0xFFE53935, 0);
    }

    @Override
    public void onAuthSuccess() {
        Log.d(LOGTAG, "Auth success");
    }

    @Override
    public void onConnectionFailed(@NonNull String reason) {
        Log.e(LOGTAG, "Connection failed: ".concat(reason));
        // AUTO-RECONNECT: if we were already live (a mid-stream drop, e.g. a cellular blip in the
        // field) and the user hasn't stopped, let pedroSG94 retry the connection with backoff
        // instead of tearing everything down. reTry() returns false once the retry budget set in
        // onConnectionSuccess() is exhausted, at which point we give up cleanly. Initial-connect
        // failures (wasConnected == false, e.g. a bad server address) fall straight through to the
        // give-up path so we don't spin forever on a misconfiguration.
        boolean autoReconnect = preferences.getBoolean(Preferences.AUTO_RECONNECT, Preferences.AUTO_RECONNECT_DEFAULT);
        if (autoReconnect && wasConnected) {
            boolean retrying = false;
            try { retrying = getStream().getStreamClient().reTry(5000, reason, null); }
            catch (Throwable t) { Log.w(LOGTAG, "reTry failed", t); }
            if (retrying) {
                Log.w(LOGTAG, "Auto-reconnecting in 5s after: " + reason);
                showNotification(getString(R.string.state_connecting) + " — " + reason, true);
                if (bubble != null) bubble.updateState(0xFFFFC107, 0);   // amber
                return;
            }
        }
        stopStream(getString(R.string.connection_failed) + ": " + reason, CONNECTION_FAILED);
        if (bubble != null) bubble.updateState(0xFFE53935, 0);
    }

    @Override
    public void onConnectionStarted(@NonNull String s) {

    }

    @Override
    public void onConnectionSuccess() {
        // Always create the bitrate adapter so we get adaptive throttling on congestion AND
        // so we get periodic onNewBitrate callbacks (these drive the UI stats display).
        if (bitrateAdapter == null) {
            bitrateAdapter = new BitrateAdapter(bitrate -> {
                if (adaptive_bitrate) getStream().setVideoBitrateOnFly(bitrate);
            });
            bitrateAdapter.setMaxBitrate(bitrate * 1024);
        }
        // Arm auto-reconnect: remember we've been live (so a later drop reconnects) and give the
        // stream client a retry budget it can spend across drops.
        wasConnected = true;
        thermalBitrateReduced = false;
        if (preferences.getBoolean(Preferences.AUTO_RECONNECT, Preferences.AUTO_RECONNECT_DEFAULT)) {
            try { getStream().getStreamClient().setReTries(15); } catch (Throwable ignored) {}
        }
        Toast.makeText(getApplicationContext(), "Connection success", Toast.LENGTH_SHORT).show();
        if (bubble != null) bubble.updateState(0xFF00C853, 0);
        startStatsTicker();
        // Tell the camera fragment to flip the state chip from CONNECTING to LIVE.
        try { sendBroadcast(new Intent(STREAM_CONNECTED).setPackage(getPackageName())); }
        catch (Throwable ignored) {}
    }

    @Override
    public void onDisconnect() {

    }

    @Override
    public void onNewBitrate(final long bitrate) {
        // Adaptive bitrate throttling (only takes effect if the user enabled the pref — see
        // BitrateAdapter callback in onConnectionSuccess).
        if (bitrateAdapter != null) {
            try {
                bitrateAdapter.adaptBitrate(bitrate, getStream().getStreamClient().hasCongestion());
            } catch (Throwable t) { Log.w(LOGTAG, "adaptBitrate", t); }
        }
        // Always broadcast — the UI (and the bubble) needs the live bitrate even when
        // adaptive_bitrate is OFF.
        Intent intent = new Intent(NEW_BITRATE);
        intent.putExtra(NEW_BITRATE, bitrate);
        getApplicationContext().sendBroadcast(intent);
    }

    /* ============================================================
       Live stream stats (bitrate / fps / upload kbps / congestion)
       — emitted on STREAM_STATS broadcast every ~1 s while streaming.
       ============================================================ */
    private long lastBytesSent = 0;
    private long lastStatsAtMs = 0;
    private long lastSentVideoFrames = 0;
    private final Handler statsHandler = new Handler(Looper.getMainLooper());
    private final Runnable statsTick = new Runnable() {
        @Override
        public void run() {
            try { emitStreamStats(); } catch (Throwable t) { Log.w(LOGTAG, "stats tick", t); }
            if (getStream().isStreaming()) statsHandler.postDelayed(this, 1000);
        }
    };

    private void startStatsTicker() {
        lastBytesSent = 0;
        lastStatsAtMs = 0;
        lastSentVideoFrames = 0;
        statsHandler.removeCallbacks(statsTick);
        statsHandler.post(statsTick);
    }

    private void stopStatsTicker() {
        statsHandler.removeCallbacks(statsTick);
    }

    private void emitStreamStats() {
        if (!getStream().isStreaming()) return;
        long now = System.currentTimeMillis();
        long bytes = 0, frames = 0;
        boolean congested = false;
        try {
            bytes = getStream().getStreamClient().getBytesSend();
            frames = getStream().getStreamClient().getSentVideoFrames();
            congested = getStream().getStreamClient().hasCongestion(0.5f);
        } catch (Throwable ignored) {}

        long uploadKbps = 0;
        long fps = 0;
        if (lastStatsAtMs > 0) {
            long dtMs = Math.max(1, now - lastStatsAtMs);
            uploadKbps = ((bytes - lastBytesSent) * 8L) / dtMs;   // bytes*8 / ms = kbps
            fps = ((frames - lastSentVideoFrames) * 1000L) / dtMs;
        }
        lastBytesSent = bytes;
        lastStatsAtMs = now;
        lastSentVideoFrames = frames;

        // Headline bitrate for the in-app chip (kbps).
        int configuredKbps = bitrate;
        long effectiveBitrate = uploadKbps > 0 ? uploadKbps : configuredKbps;

        String warning = checkBatteryThermal();

        Intent i = new Intent(STREAM_STATS).setPackage(getPackageName());
        i.putExtra(KEY_BITRATE_KBPS, effectiveBitrate);
        i.putExtra(KEY_FPS, fps);
        i.putExtra(KEY_UPLOAD_KBPS, uploadKbps);
        i.putExtra(KEY_CONGESTED, congested);
        if (warning != null) i.putExtra(KEY_WARNING, warning);
        sendBroadcast(i);

        // Bubble stats overlay — works in the background too.
        if (bubble != null && bubble.isVisible()) {
            bubble.updateStats(effectiveBitrate, fps, uploadKbps, congested);
        }
    }

    /**
     * Battery + thermal guard. Runs on each stats tick while streaming. Returns a short warning
     * string to surface on the state chip (low battery / device hot), or null. On CRITICAL thermal
     * it also halves the encoder bitrate once to shed heat (reset when the device cools), so a long
     * field broadcast doesn't get killed by an overheat shutdown.
     */
    private String checkBatteryThermal() {
        int batteryPct = -1;
        boolean charging = false;
        try {
            BatteryManager bm = (BatteryManager) getSystemService(Context.BATTERY_SERVICE);
            if (bm != null) {
                batteryPct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
                charging = bm.isCharging();
            }
        } catch (Throwable ignored) {}

        int thermal = 0; // THERMAL_STATUS_NONE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                android.os.PowerManager pm = (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
                if (pm != null) thermal = pm.getCurrentThermalStatus();
            } catch (Throwable ignored) {}
        }

        boolean lowBattery = in_range(batteryPct) && batteryPct <= 15 && !charging;
        boolean hot = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                && thermal >= android.os.PowerManager.THERMAL_STATUS_SEVERE;      // 4 = severe
        boolean critical = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                && thermal >= android.os.PowerManager.THERMAL_STATUS_CRITICAL;    // 5 = critical

        // One-shot heat-shed: halve the bitrate on critical thermal; restore-eligible once cooled.
        if (critical && !thermalBitrateReduced && getStream().isStreaming()) {
            thermalBitrateReduced = true;
            try { getStream().setVideoBitrateOnFly((bitrate * 1024) / 2); }
            catch (Throwable ignored) {}
            Log.w(LOGTAG, "Critical thermal — bitrate halved to shed heat");
        } else if (!hot) {
            thermalBitrateReduced = false;
        }

        String warning = null;
        if (critical) warning = "Device very hot — bitrate reduced";
        else if (hot) warning = "Device hot";
        else if (lowBattery) warning = "Low battery " + batteryPct + "%";

        if (warning != null) {
            long now = System.currentTimeMillis();
            if (now - lastGuardWarnMs > 30000) {   // throttle the log/notification, not the chip
                lastGuardWarnMs = now;
                Log.w(LOGTAG, "Guard: " + warning);
            }
        }
        return warning;
    }

    /** Guard against the -1 "unknown" battery reading some devices return. */
    private static boolean in_range(int pct) { return pct >= 0 && pct <= 100; }

    private void addCert() {
        Log.d(LOGTAG, "add cert");
        if (stream_self_signed_cert && cert_file != null && (protocol.equals("rtsps") || protocol.equals("rtmps"))) {
            try {
                Log.d(LOGTAG, "Using cert: " + getFilesDir().getAbsolutePath());
                KeyStore keyStore = KeyStore.getInstance("PKCS12");
                FileInputStream caFile = new FileInputStream(cert_file);
                keyStore.load(caFile, cert_password.toCharArray());
                caFile.close();

                TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                trustManagerFactory.init(keyStore);

                SSLContext sslctx = SSLContext.getInstance("TLS");
                sslctx.init(null, trustManagerFactory.getTrustManagers(), new SecureRandom());

                if (protocol.equals("rtsps")) {
                    RtspStreamClient rtspStreamClient = (RtspStreamClient) getStream().getStreamClient();
                    rtspStreamClient.addCertificates(trustManagerFactory.getTrustManagers()[0]);
                } else if (protocol.equals("rtmps")) {
                    RtmpStreamClient rtmpStreamClient = (RtmpStreamClient) getStream().getStreamClient();
                    rtmpStreamClient.addCertificates(trustManagerFactory.getTrustManagers()[0]);
                }

            } catch (Exception e) {
                Log.e(LOGTAG, e.getMessage());
                Toast.makeText(this, "Failed to open cert: " + e.getMessage(),
                        Toast.LENGTH_SHORT).show();
            }
        } else {
            Log.d(LOGTAG, "Cert null");
        }
    }

    public boolean prepareEncoders() {
        try {
            return prepareEncodersInternal();
        } catch (Throwable t) {
            // Codec failures, illegal-state from a half-released camera, OOM from a too-large
            // surface buffer — none of these should ever crash the foreground service. Log and
            // return false so callers know prep didn't succeed.
            Log.e(LOGTAG, "prepareEncoders crashed", t);
            return false;
        }
    }

    /**
     * Lower the RTP MTU used by pedroSG94's RTSP packetiser so MediaMTX accepts our packets
     * without having to re-fragment them (its default {@code udpMaxPayloadSize} is 1430 but
     * pedroSG94 publishes at 1460 = MTU 1500 − 28 IP/UDP − 12 RTP). Setting MTU = 1470 gives
     * us ~1430-byte RTP payloads. We must do this BEFORE the encoder starts because the
     * packetiser caches the constant per-stream.
     *
     * MTU is a {@code const val} in Kotlin so most compile sites are inlined and won't change,
     * but the dynamic reads inside the packetiser still pick the new value up on most builds.
     * Best-effort: failures are non-fatal — MediaMTX will continue to remux as it does today.
     */
    private static volatile boolean rtpMtuApplied = false;
    private static void applyMediaMtxMtu() {
        if (rtpMtuApplied) return;
        try {
            Class<?> cls = Class.forName("com.pedro.rtsp.utils.RtpConstants");
            java.lang.reflect.Field f = cls.getDeclaredField("MTU");
            f.setAccessible(true);
            // Clear the final modifier so reflection can write the field on Java 17+.
            try {
                java.lang.reflect.Field mods = java.lang.reflect.Field.class.getDeclaredField("modifiers");
                mods.setAccessible(true);
                mods.setInt(f, f.getModifiers() & ~java.lang.reflect.Modifier.FINAL);
            } catch (Throwable ignored) {}
            f.setInt(null, 1470);
            Log.i(LOGTAG, "RtpConstants.MTU = 1470 (RTP payload ≈1430 for MediaMTX compat)");
            rtpMtuApplied = true;
        } catch (Throwable t) {
            Log.w(LOGTAG, "Could not lower RtpConstants.MTU — MediaMTX will continue to remux", t);
        }
    }

    private boolean prepareEncodersInternal() {
        Log.d(LOGTAG, "prepareEncoders");
        applyMediaMtxMtu();
        /*if (prepareAudio && prepareVideo) {
            Log.d(LOGTAG, "already prepared");
            return true;
        }*/
        int width = resolution.getWidth();
        int height = resolution.getHeight();

        if (Objects.equals(codec, VideoCodec.H265.name()))
            getStream().setVideoCodec(VideoCodec.H265);
        else if (Objects.equals(codec, VideoCodec.AV1.name()) && !protocol.equals("udp") && !protocol.equals("srt"))
            getStream().setVideoCodec(VideoCodec.AV1);
        else {
            getStream().setVideoCodec(VideoCodec.H264);
        }

        if (Objects.equals(audio_codec, AudioCodec.G711.name()) && !protocol.equals("srt") && !protocol.equals("udp")) {
            getStream().setAudioCodec(AudioCodec.G711);
            Log.d(LOGTAG, "Set audio codec to G711");
        } else if (audio_codec.equals(AudioCodec.OPUS.name()) && !protocol.startsWith("rtmp")) {
            getStream().setAudioCodec(AudioCodec.OPUS);
            Log.d(LOGTAG, "Set audio codec to OPUS");
        } else {
            // Fall back to AAC since all streaming protocol support it
            getStream().setAudioCodec(AudioCodec.AAC);
            Log.d(LOGTAG, "Set audio codec to AAC");
        }

        Log.d(LOGTAG, "Setting video bitrate to ".concat(String.valueOf(bitrate)));
        Log.d(LOGTAG, "Setting audio bitrate to ".concat(String.valueOf(audio_bitrate)));
        Log.d(LOGTAG, "Setting res to ".concat(String.valueOf(width)).concat(" x ").concat(String.valueOf(height)));

        addCert();

        if (getStream().isOnPreview())
            getStream().stopPreview();

        boolean forceLandscape = preferences.getBoolean(Preferences.FORCE_LANDSCAPE, Preferences.FORCE_LANDSCAPE_DEFAULT);
        int cameraRotation = forceLandscape ? 0 : CameraHelper.getCameraOrientation(getApplicationContext());
        Log.d(LOGTAG, "force_landscape=" + forceLandscape + " cameraRotation=" + cameraRotation);

        if (videoSource.equals(Preferences.VIDEO_SOURCE_USB)) {
            // Swap source FIRST so prepareVideo()'s internal source.init() runs against the
            // UVC source, not the previous source (which may be a Camera2Source whose HAL
            // is in a transient bad state, e.g. after a USB hotplug).
            getStream().changeVideoSource(new CameraUvcSource());
            prepareVideo = getStream().prepareVideo(width, height, bitrate, fps);
        } else if (videoSource.equals(Preferences.VIDEO_SOURCE_GOPRO)) {
            // GoPro preview is delivered as MPEG-TS over UDP. Decoder writes to the GL
            // input surface; the encoder pipeline picks up from there. No camera rotation
            // applies — the GoPro is already landscape.
            //
            // CRITICAL ORDER: swap to GoProSource BEFORE prepareVideo. pedroSG94's
            // StreamBase.prepareVideo() internally calls source.init() on the CURRENT
            // video source. If we leave Camera2Source in place (still the source from
            // the camera preview before the GoPro connect), prepareVideo() runs
            // Camera2Source.create() → checkResolutionSupported() → enumerates phone
            // cameras. On MediaTek SoCs the Wi-Fi reconfig that just happened for the
            // GoPro AP join often trips Camera 0's HAL into "Broken pipe (-32)", and
            // the enumeration throws CameraOpenException("Camera no detected").
            // Swapping to GoProSource first means prepareVideo()'s init runs on
            // GoProSource (which is a pure UDP listener and never touches the camera
            // HAL), and the swap-then-prepare order matches what works for the
            // Camera2-default branch below.
            // Reuse an existing GoProSource if one is already live (e.g. the preview source created
            // when the GoPro connected). Creating a fresh one here would spin up a SECOND ExoPlayer
            // that tries to bind UDP :8554 while the first still holds it → EADDRINUSE, and the
            // stream would start before the new player decoded a frame → "sps or pps is null".
            // Reusing keeps the already-decoding player, so the encoder gets frames immediately.
            VideoSource existingGoPro;
            try { existingGoPro = getStream().getVideoSource(); }
            catch (Throwable t) { existingGoPro = null; }
            if (!(existingGoPro instanceof GoProSource)) {
                getStream().changeVideoSource(
                        new GoProSource(this, GoProSource.DEFAULT_UDP_PORT, goproClient));
                Log.d(LOGTAG, "GoPro: created new source");
            } else {
                Log.d(LOGTAG, "GoPro: reusing existing source (no UDP re-bind)");
            }
            prepareVideo = getStream().prepareVideo(width, height, bitrate, fps);
        } else if (videoSource.equals(Preferences.VIDEO_SOURCE_SCREEN)) {
            // Only use ScreenSource while actually starting/doing a stream. During idle settings
            // changes, keep camera fallback preview to avoid stale MediaProjection issues.
            boolean useScreenSourceNow = getStream().isStreaming() || preparingStreamStart;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP
                    && mediaProjection != null
                    && useScreenSourceNow) {
                // Swap first, then prepare — see GoPro branch comment for why.
                getStream().changeVideoSource(new ScreenSource(getApplicationContext(), mediaProjection));
                prepareVideo = getStream().prepareVideo(width, height, bitrate, fps);
            } else {
                // No MediaProjection yet: use camera for preview. Must use camera resolutions, not full screen size
                // (prepareVideo with display dimensions + Camera2Source fails encoders on Pixel / Android 15+).
                Log.d(LOGTAG, "Screen selected but MediaProjection not available; using camera for preview");
                Size camSize = pickBackCameraResolution();
                prepareVideo = getStream().prepareVideo(camSize.getWidth(), camSize.getHeight(), bitrate, fps, 2, cameraRotation);
                getStream().changeVideoSource(new Camera2Source(getApplicationContext()));
            }
        } else {
            prepareVideo = getStream().prepareVideo(width, height, bitrate, fps, 1, cameraRotation);
            // Source handoff: keep existing Camera2Source if there is one (avoids a wasteful
            // close/open). If we're coming from a different source (USB / screen / GoPro),
            // create a fresh Camera2Source.
            VideoSource existing;
            try { existing = getStream().getVideoSource(); }
            catch (Throwable t) { existing = null; }
            Camera2Source target;
            if (existing instanceof Camera2Source) {
                target = (Camera2Source) existing;
                Log.d(LOGTAG, "Re-using existing Camera2Source (camera " + target.getCurrentCameraId() + ")");
            } else {
                target = new Camera2Source(getApplicationContext());
                getStream().changeVideoSource(target);
                Log.d(LOGTAG, "Swapped to fresh Camera2Source (from " +
                        (existing == null ? "null" : existing.getClass().getSimpleName()) + ")");
            }
            // BUG FIX: always re-apply the user-selected camera id at the *end* of
            // prepareEncoders. {@code prepareVideo()} can renegotiate resolution and the
            // pedroSG94 library will silently switch to a camera that natively supports
            // the requested size — typically id 0 — which is why picking the IR / night-vision
            // camera was getting reverted on stream start.
            if (!cameraIds.isEmpty() && currentCameraId >= 0 && currentCameraId < cameraIds.size()) {
                try {
                    String wantId = cameraIds.get(currentCameraId);
                    String haveId = target.getCurrentCameraId();
                    if (!wantId.equals(haveId)) {
                        Log.d(LOGTAG, "Forcing camera id back to user pick: " + haveId + " -> " + wantId);
                        target.openCameraId(wantId);
                    }
                } catch (Exception e) {
                    Log.w(LOGTAG, "Could not open user-selected camera id", e);
                }
            }
        }

        Log.d(LOGTAG, "Sample rate: " + samplerate + " stereo " + stereo);
        if (!enable_audio) {
            Log.d(LOGTAG, "disabling audio");
            getStream().changeAudioSource(new NoAudioSource());
        } else if (videoSource.equals(Preferences.VIDEO_SOURCE_GOPRO)) {
            // ExoPlayer now owns the GoPro demux end-to-end and we don't tap its decoded audio,
            // so there's no PCM to hand pedroSG94. Stream video-only (NoAudioSource) rather than
            // wiring a GoProAudioSource that would never receive frames. Routing GoPro audio into
            // the outgoing stream is a future enhancement (needs an ExoPlayer audio-processor tap).
            Log.d(LOGTAG, "GoPro: video-only (NoAudioSource); ExoPlayer audio is muted locally");
            getStream().changeAudioSource(new NoAudioSource());
        } else if (videoSource.equals(Preferences.VIDEO_SOURCE_SCREEN)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && mediaProjection != null) {
                Log.d(LOGTAG, "enabling internal audio for screen capture");
                getStream().changeAudioSource(new InternalAudioSource(mediaProjection, null));
            } else {
                Log.d(LOGTAG, "enabling microphone audio for screen capture");
                getStream().changeAudioSource(new MicrophoneSource());
            }
        } else {
            getStream().changeAudioSource(new MicrophoneSource());
            Log.d(LOGTAG, "enabling audio");
        }
        prepareAudio = getStream().prepareAudio(samplerate, stereo, audio_bitrate * 1024, echo_cancel, noise_reduction);
        if (!prepareAudio && enable_audio && videoSource.equals(Preferences.VIDEO_SOURCE_SCREEN)) {
            Log.w(LOGTAG, "Screen audio prepare failed; retrying with microphone");
            getStream().changeAudioSource(new MicrophoneSource());
            prepareAudio = getStream().prepareAudio(samplerate, stereo, audio_bitrate * 1024, echo_cancel, noise_reduction);
        }

        // Only start preview when video was prepared. For screen capture, a stale MediaProjection
        // can cause "Cannot create VirtualDisplay with non-current MediaProjection"; handle that
        // gracefully instead of crashing.
        boolean skipPreviewForScreenStart = videoSource.equals(Preferences.VIDEO_SOURCE_SCREEN) && preparingStreamStart;
        if (openGlView != null && prepareVideo && !skipPreviewForScreenStart) {
            try {
                getStream().startPreview(openGlView, true);
            } catch (SecurityException e) {
                Log.e(LOGTAG, "Failed to start preview, MediaProjection is no longer valid", e);
                // If screen capture is selected, clear the projection so the app knows it must
                // re-request permission before using screen again.
                if (videoSource.equals(Preferences.VIDEO_SOURCE_SCREEN)) {
                    mediaProjection = null;
                    // Force prepare failure so startStream won't proceed with an invalid ScreenSource.
                    prepareVideo = false;
                }
            }
        }

        Log.d(LOGTAG, "PrepareVideo: ".concat(String.valueOf(prepareVideo)).concat(" Audio ").concat(String.valueOf(prepareAudio)));
        return prepareVideo && prepareAudio;
    }

    public void getSettings() {
        try {
            getSettingsInternal();
        } catch (Throwable t) {
            // Same philosophy as prepareEncoders — don't let a malformed pref / missing cert /
            // codec failure tear the service down. Log it; the next user action can retry.
            Log.e(LOGTAG, "getSettings crashed", t);
        }
    }

    private void getSettingsInternal() {
        Log.d(LOGTAG, "Get settings");
        uid = preferences.getString(Preferences.UID, Preferences.UID_DEFAULT);

        String oldProtocol = protocol;
        protocol = preferences.getString(Preferences.STREAM_PROTOCOL, Preferences.STREAM_PROTOCOL_DEFAULT);

        if (!protocol.equals(oldProtocol)) {
            if (protocol.startsWith("rtmp")) {
                rtmpStream = new RtmpStream(getApplicationContext(), this);
                rtspStream = null;
                srtStream = null;
                udpStream = null;
            } else if (protocol.equals("srt")) {
                srtStream = new SrtStream(getApplicationContext(), this);
                rtspStream = null;
                rtmpStream = null;
                udpStream = null;
            } else if (protocol.startsWith("rtsp")) {
                rtspStream = new RtspStream(getApplicationContext(), this);
                rtmpStream = null;
                srtStream = null;
                udpStream = null;
            } else {
                udpStream = new UdpStream(getApplicationContext(), this);
                rtmpStream = null;
                srtStream = null;
                rtspStream = null;
            }
        }

        /* Stream Preferences */
        stream = preferences.getBoolean(Preferences.STREAM_VIDEO, Preferences.STREAM_VIDEO_DEFAULT);
        address = preferences.getString(Preferences.STREAM_ADDRESS, Preferences.STREAM_ADDRESS_DEFAULT);
        port = Integer.parseInt(preferences.getString(Preferences.STREAM_PORT, Preferences.STREAM_PORT_DEFAULT));
        path = preferences.getString(Preferences.STREAM_PATH, Preferences.STREAM_PATH_DEFAULT);
        tcp = preferences.getBoolean(Preferences.STREAM_USE_TCP, Preferences.STREAM_USE_TCP_DEFAULT);
        username = preferences.getString(Preferences.STREAM_USERNAME, Preferences.STREAM_USERNAME_DEFAULT);
        password = preferences.getString(Preferences.STREAM_PASSWORD, Preferences.STREAM_PASSWORD_DEFAULT);
        stream_self_signed_cert = preferences.getBoolean(Preferences.STREAM_SELF_SIGNED_CERT, Preferences.STREAM_SELF_SIGNED_CERT_DEFAULT);
        cert_file = preferences.getString(Preferences.STREAM_CERTIFICATE, Preferences.STREAM_CERTIFICATE_DEFAULT);
        cert_password = preferences.getString(Preferences.STREAM_CERTIFICATE_PASSWORD, Preferences.STREAM_CERTIFICATE_PASSWORD_DEFAULT);
        Log.d(LOGTAG, "Got cert: " + cert_file);

        /* Video Preferences */
        fps = Integer.parseInt(preferences.getString(Preferences.VIDEO_FPS, Preferences.VIDEO_FPS_DEFAULT));
        record = preferences.getBoolean(Preferences.RECORD_VIDEO, Preferences.RECORD_VIDEO_DEFAULT);
        codec = preferences.getString(Preferences.VIDEO_CODEC, Preferences.VIDEO_CODEC_DEFAULT);
        bitrate = Integer.parseInt(preferences.getString(Preferences.VIDEO_BITRATE, Preferences.VIDEO_BITRATE_DEFAULT));
        adaptive_bitrate = preferences.getBoolean(Preferences.VIDEO_ADAPTIVE_BITRATE, Preferences.VIDEO_ADAPTIVE_BITRATE_DEFAULT);

        /* Audio Preferences */
        enable_audio = preferences.getBoolean(Preferences.ENABLE_AUDIO, Preferences.ENABLE_AUDIO_DEFAULT);
        echo_cancel = preferences.getBoolean(Preferences.AUDIO_ECHO_CANCEL, Preferences.AUDIO_ECHO_CANCEL_DEFAULT);
        noise_reduction = preferences.getBoolean(Preferences.AUDIO_NOISE_REDUCTION, Preferences.AUDIO_NOISE_REDUCTION_DEFAULT);
        audio_bitrate = Integer.parseInt(preferences.getString(Preferences.AUDIO_BITRATE, Preferences.AUDIO_BITRATE_DEFAULT));
        audio_codec = preferences.getString(Preferences.AUDIO_CODEC, Preferences.AUDIO_CODEC_DEFAULT);
        if (audio_codec.equals(AudioCodec.G711.name())) {
            Log.d(LOGTAG, "Forcing G711 settings");
            stereo = false;
            samplerate = 8000;
        } else {
            Log.d(LOGTAG, "Audio Codec " + audio_codec);
            stereo = preferences.getBoolean(Preferences.STEREO_AUDIO, Preferences.STEREO_AUDIO_DEFAULT);
            samplerate = Integer.parseInt(preferences.getString(Preferences.AUDIO_SAMPLE_RATE, Preferences.AUDIO_SAMPLE_RATE_DEFAULT));
        }

        /* ATAK Preferences */
        atak_address = preferences.getString(Preferences.ATAK_SERVER_ADDRESS, Preferences.ATAK_SERVER_ADDRESS_DEFAULT);
        send_cot = preferences.getBoolean(Preferences.ATAK_SEND_COT, Preferences.ATAK_SEND_COT_DEFAULT);
        send_stream_details = preferences.getBoolean(Preferences.ATAK_SEND_STREAM_DETAILS, Preferences.ATAK_SEND_STREAM_DETAILS_DEFAULT);
        atak_callsign = preferences.getString(Preferences.ATAK_CALLSIGN, Preferences.ATAK_CALLSIGN_DEFAULT);
        atak_marker_type = preferences.getString(Preferences.ATAK_MARKER_TYPE, Preferences.ATAK_MARKER_TYPE_DEFAULT);
        // Migrate the old friendly-unit default to ATAK's video type (camera icon). The old value
        // may have been persisted before this fix, so override it explicitly.
        if (atak_marker_type == null || atak_marker_type.isEmpty() || "a-f-G-U-C".equals(atak_marker_type)) {
            atak_marker_type = Preferences.ATAK_MARKER_TYPE_DEFAULT;
        }
        atak_video_alias = preferences.getString(Preferences.ATAK_VIDEO_ALIAS, Preferences.ATAK_VIDEO_ALIAS_DEFAULT);

        String oldVideoSource = videoSource;
        videoSource = preferences.getString(Preferences.VIDEO_SOURCE, Preferences.VIDEO_SOURCE_DEFAULT);
        Log.d(LOGTAG, "videoSourcePref = " + videoSource);

        getResolutions();
        prepareEncoders();

        getStream().getStreamClient().setLogs(false);
        if (videoSource.equals(Preferences.VIDEO_SOURCE_DEFAULT)) {
            Camera2Source camera2Source = (Camera2Source) getStream().getVideoSource();
            for (String camera : camera2Source.camerasAvailable()) {
                Log.d(LOGTAG, "camerasAvailable: " + camera);
            }
        }

        if (protocol.startsWith("rtsp") && username != null && password != null) {
            RtspStreamClient rtspStreamClient = (RtspStreamClient) getStream().getStreamClient();
            rtspStreamClient.setAuthorization(username, password);
        }
        else if (protocol.startsWith("rtmp") && username != null && password != null) {
            RtmpStreamClient rtmpStreamClient = (RtmpStreamClient) getStream().getStreamClient();
            rtmpStreamClient.setAuthorization(username, password);
        }
        // Low-latency tweaks for live (vs VoD) viewing: minimise the publisher-side buffer.
        // We pass 1 (not 0) because pedroSG94's internal PriorityBlockingQueue throws
        // IllegalArgumentException when constructed with capacity 0; 1 is effectively the
        // same for live streaming and stops the noisy stack trace at every onCreate.
        try { getStream().getStreamClient().setDelay(1); }
        catch (Throwable t) { Log.w(LOGTAG, "setDelay(1) failed", t); }
    }

    @NonNull
    private Size pickBackCameraResolution() {
        Camera2Source camera2Source = new Camera2Source(getApplicationContext());
        ArrayList<Size> resolutions = new ArrayList<>(camera2Source.getCameraResolutions(CameraHelper.Facing.BACK));
        String resolutionPref = preferences.getString(Preferences.VIDEO_RESOLUTION, null);
        if (resolutionPref == null) {
            return new Size(1920, 1080);
        }
        try {
            int idx = Integer.parseInt(resolutionPref);
            if (idx >= 0 && idx < resolutions.size()) {
                return resolutions.get(idx);
            }
        } catch (NumberFormatException e) {
            Log.w(LOGTAG, "Invalid VIDEO_RESOLUTION pref", e);
        }
        return new Size(1920, 1080);
    }

    /**
     * Full display size often exceeds real-time encoder limits on phone SoCs; scale down evenly.
     */
    @NonNull
    private static Size clampScreenSizeForEncoder(int w, int h) {
        if (w <= 0 || h <= 0) {
            return new Size(1280, 720);
        }
        final int maxLongSide = 1920;
        int longSide = Math.max(w, h);
        float scale = longSide > maxLongSide ? (float) maxLongSide / longSide : 1f;
        int nw = Math.round(w * scale);
        int nh = Math.round(h * scale);
        nw = Math.max(320, (nw / 2) * 2);
        nh = Math.max(240, (nh / 2) * 2);
        return new Size(nw, nh);
    }

    private void getCamera2Resolutions() {
        Log.d(LOGTAG, "Get res");

        if (videoSource.equals(Preferences.VIDEO_SOURCE_DEFAULT)) {
            resolution = pickBackCameraResolution();
            Log.d(LOGTAG, "getResolution ".concat(String.valueOf(resolution.getWidth())).concat(" x ").concat(String.valueOf(resolution.getHeight())));
        }
    }

    private void getUsbResolution() {
        if (videoSource.equals(Preferences.VIDEO_SOURCE_USB)) {
            int width = Integer.parseInt(preferences.getString(Preferences.USB_WIDTH, Preferences.USB_WIDTH_DEFAULT));
            int height = Integer.parseInt(preferences.getString(Preferences.USB_HEIGHT, Preferences.USB_HEIGHT_DEFAULT));
            resolution = new Size(width, height);
            Log.i(LOGTAG, "Got USB Res " + width + " x " + height);
        }
    }

    private void getScreenResolution() {
        if (videoSource.equals(Preferences.VIDEO_SOURCE_SCREEN)) {
            try {
                WindowManager windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
                if (windowManager == null) {
                    Log.e(LOGTAG, "WindowManager is null, unable to determine screen resolution");
                    return;
                }

                int width;
                int height;

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    // Service has no window; currentWindowMetrics can be wrong on Android 15+.
                    android.view.WindowMetrics metrics = windowManager.getMaximumWindowMetrics();
                    Rect b = metrics.getBounds();
                    width = b.width();
                    height = b.height();
                } else {
                    Display display = windowManager.getDefaultDisplay();
                    android.graphics.Point size = new android.graphics.Point();
                    display.getRealSize(size);
                    width = size.x;
                    height = size.y;
                }

                Size raw = new Size(width, height);
                resolution = clampScreenSizeForEncoder(width, height);
                Log.i(LOGTAG, "Screen display " + raw.getWidth() + " x " + raw.getHeight()
                        + " -> encoder " + resolution.getWidth() + " x " + resolution.getHeight());
            } catch (Exception e) {
                Log.e(LOGTAG, "Failed to get screen resolution", e);
            }
        }
    }

    private void getResolutions() {
        getCamera2Resolutions();
        getUsbResolution();
        getScreenResolution();
        getGoProResolution();
        // Final safety net: if nothing populated `resolution` (e.g. a future source we forgot
        // to plumb), default to 1080p so prepareEncoders never NPEs and the user still gets a
        // working app instead of a startup crash.
        if (resolution == null) {
            Log.w(LOGTAG, "No resolution picked for videoSource=" + videoSource + ", defaulting to 1920x1080");
            resolution = new Size(1920, 1080);
        }
    }

    /**
     * GoPro preview is delivered as MPEG-TS to a local UDP port; the camera fixes the
     * resolution and we re-encode that. HERO 11+ / MAX 2 push at 1920x1080 by default;
     * older HEROs may push 720p. We pick the nominal 1080p here so the encoder pipeline
     * has a valid size at prepare-time — the actual SPS dimensions are applied later
     * inside {@link io.opentakserver.opentakicu.gopro.GoProSource} once the first IDR
     * arrives.
     */
    private void getGoProResolution() {
        if (videoSource.equals(Preferences.VIDEO_SOURCE_GOPRO)) {
            resolution = new Size(1920, 1080);
            Log.i(LOGTAG, "GoPro nominal resolution " + resolution.getWidth() + " x " + resolution.getHeight());
        }
    }

    /** Coalesces a burst of pref edits into a single {@link #getSettings()} call. */
    private final Handler prefSettleHandler = new Handler(Looper.getMainLooper());
    private final Runnable applyPrefChangesRunnable = () -> {
        try { getSettings(); }
        catch (Throwable t) { Log.e(LOGTAG, "applyPrefChanges failed", t); }
    };

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, @Nullable String s) {
        Log.d(LOGTAG, "onSharedPreferenceChanged " + s);
        // Skip pref keys that do NOT require re-preparing encoders. Anything else triggers a
        // debounced refresh so a burst (e.g. the quick-options dialog setting 4 prefs at once)
        // only re-prepares the encoders once, off the main-thread critical path.
        if (s == null) return;
        if (s.equals(Preferences.TEXT_OVERLAY) || s.equals(Preferences.TEXT_OVERLAY_TIMEZONE)) {
            applyTextOverlay();                                  // toggle the burn-in overlay live
            return;
        }
        if (s.equals(Preferences.FORCE_LANDSCAPE)) return;       // takes effect on next stream start
        if (s.equals(Preferences.FLOATING_BUBBLE)) {             // only bubble lifecycle changes
            refreshFloatingBubble();
            return;
        }
        // When the video source moves AWAY from GoPro (user picks the camera, or the cold-start
        // reset flips VIDEO_SOURCE back to camera2 while a foreground service that survived a
        // task-swipe is still pinging the camera), tear down the GoPro keep-alive + network so the
        // app stops doing GoPro work / showing GoPro state with the phone camera selected.
        if (s.equals(Preferences.VIDEO_SOURCE)) {
            String newSource = sharedPreferences.getString(Preferences.VIDEO_SOURCE, Preferences.VIDEO_SOURCE_DEFAULT);
            if (!Preferences.VIDEO_SOURCE_GOPRO.equals(newSource) && goproClient != null) {
                Log.d(LOGTAG, "Source no longer GoPro — shutting down GoPro keep-alive/session");
                try { goproClient.shutdown(); } catch (Exception ignored) {}
                goproClient = null;
                goproNetwork = null;
            }
        }
        prefSettleHandler.removeCallbacks(applyPrefChangesRunnable);
        prefSettleHandler.postDelayed(applyPrefChangesRunnable, 350);
    }

    private void startRecording() {
        Log.d(LOGTAG, "Start recording");
        if (record) {
            try {
                if (!folder.exists()) {
                    folder.mkdir();
                }

                if (enable_audio && !audio_codec.equals(AudioCodec.AAC.name())) {
                    Log.d(LOGTAG, "Trying to record but audio codec is " + audio_codec);
                    // Recordings only support AAC audio and will fail if any other codec is used.
                    // This attempts to create a new recording controller using AAC.
                    // It allows the video to record, but not audio, which is better than no video or audio.
                    // TODO: Figure out if multiple audio encoders can be used at the same time
                    AndroidMuxerRecordController androidMuxerRecordController = new AndroidMuxerRecordController();
                    androidMuxerRecordController.setAudioCodec(AudioCodec.AAC);

                    MediaFormat audioFormat = MediaFormat.createAudioFormat(CodecUtil.AAC_MIME, samplerate, (stereo) ? 2 : 1);
                    audioFormat.setInteger(MediaFormat.KEY_BIT_RATE, audio_bitrate * 1000);
                    audioFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 0);
                    audioFormat.setInteger(MediaFormat.KEY_AAC_PROFILE,
                            MediaCodecInfo.CodecProfileLevel.AACObjectLC);

                    MediaCodec mediaCodec = MediaCodec.createEncoderByType("audio/mp4a-latm");
                    mediaCodec.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                    androidMuxerRecordController.setAudioFormat(audioFormat);
                    getStream().setRecordController(androidMuxerRecordController);
                }

                SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault());
                currentDateAndTime = "SENTINEL_" + sdf.format(new Date());
                if (!getStream().isStreaming()) {
                    if (prepareEncoders()) {
                        getStream().startRecord(folder.getAbsolutePath().concat("/").concat(currentDateAndTime).concat(".mp4"), new RecordingListener());
                        Toast.makeText(this, "Recording... ", Toast.LENGTH_SHORT).show();
                    } else {
                        showNotification(getString(R.string.error_preparing_stream), false);
                    }
                } else {
                    getStream().startRecord(folder.getAbsolutePath().concat("/").concat(currentDateAndTime).concat(".mp4"), new RecordingListener());
                    Log.d(LOGTAG, "Recording!");
                    Toast.makeText(this, "Recording... ", Toast.LENGTH_SHORT).show();
                }
            } catch (IOException e) {
                Log.e(LOGTAG, "Failed to start recording", e);;
                getStream().stopRecord();
                PathUtils.updateGallery(this, folder.getAbsolutePath().concat("/").concat(currentDateAndTime).concat(".mp4"));
                Toast.makeText(this, e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        } else {
            Log.d(LOGTAG, "recording disabled");
        }
    }

    private void stopRecording() {
        Log.d(LOGTAG, "Stop recording");
        if (getStream().isRecording()) {
            getStream().stopRecord();
            PathUtils.updateGallery(this, folder.getAbsolutePath().concat("/").concat(currentDateAndTime).concat(".mp4"));
            Toast.makeText(this,
                    "file ".concat(currentDateAndTime).concat(".mp4 saved in ").concat(folder.getAbsolutePath()),
                    Toast.LENGTH_SHORT).show();
        }
    }

    public void startStream() {
        Log.d(LOGTAG, "startStream");
        // GoPro PASSTHROUGH mode: when the user picks Video Source = GoPro AND Stream
        // Protocol = UDP, skip the pedroSG94 encoder pipeline entirely and just forward each
        // MPEG-TS packet from the GoPro to the configured UDP target (typically a MediaMTX
        // server with `source: udp://0.0.0.0:<port>`). This mirrors the working ATAK plugin
        // architecture and avoids the decode/re-encode latency + decoder compatibility issues.
        if (Preferences.VIDEO_SOURCE_GOPRO.equals(videoSource) && "udp".equals(protocol)) {
            startGoProRelay();
            return;
        }
        if (!getStream().isStreaming() && !getStream().isRecording()) {
            if (protocol.equals("rtsp") && tcp) {
                RtspStreamClient rtspStreamClient = (RtspStreamClient) getStream().getStreamClient();
                rtspStreamClient.setProtocol(Protocol.TCP);
            } else if (Objects.equals(protocol, "rtsp")) {
                RtspStreamClient rtspStreamClient = (RtspStreamClient) getStream().getStreamClient();
                rtspStreamClient.setProtocol(Protocol.UDP);
            }

            boolean encodersPrepared;
            preparingStreamStart = true;
            try {
                encodersPrepared = prepareEncoders();
            } finally {
                preparingStreamStart = false;
            }
            if (getStream().isRecording() || encodersPrepared) {

                if (!protocol.equals("srt") && !protocol.startsWith("udp") && !username.isEmpty() && !password.isEmpty()) {
                    try {
                        getStream().getStreamClient().setAuthorization(username, password);
                    } catch (NotImplementedError e) {
                        Log.e(LOGTAG, e.getMessage());
                    }
                }

                String url = protocol.concat("://").concat(address).concat(":").concat(String.valueOf(port));

                // Support for MediaMTX's way of doing RTMP authentication
                if (protocol.startsWith("rtmp") && !username.equals(Preferences.STREAM_USERNAME_DEFAULT) && !password.equals(Preferences.STREAM_PASSWORD_DEFAULT)) {
                    url = url.concat("/").concat(path);
                    if (username != null && !username.isEmpty())
                        url = url.concat("?user=").concat(username).concat("&pass=").concat(password);
                }
                else if (!protocol.equals("udp") && !protocol.equals("srt"))
                    url = url.concat("/").concat(path);
                else if (protocol.equals("srt")) {
                    url += "?streamid=publish:" + path;
                    if (username != null && !username.isEmpty())
                        url += ":" + username + ":" + password;
                }
                // UDP Multicast
                else {
                    try {
                        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
                                ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                            //This will probably never show since the OnBoardingActivity forces users to grant permission before using the app
                            Toast.makeText(getApplicationContext(), R.string.no_location_permissions, Toast.LENGTH_LONG).show();
                            return;
                        }
                        if (Build.VERSION.SDK_INT >= 31)
                            _locManager.requestLocationUpdates(LocationManager.FUSED_PROVIDER, 5000, 0, _locListener);
                        else
                            _locManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 5000, 0, _locListener);
                        Log.d(LOGTAG,  "Requesting Location updates");
                        sensorManager.registerListener(this, magnetometer, SensorManager.SENSOR_DELAY_GAME);
                        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME);

                        multicastClient = new MulticastClient(getApplicationContext());
                        // Advertise the feed ON the self marker (self UID + self type + __video),
                        // not as a standalone sensor icon. The CoT heartbeat re-asserts it.
                        try {
                            sendCot(buildVideoCot(true));
                        } catch (Exception e) {
                            Log.d(LOGTAG, "Failed to generate CoT: " + e.getMessage());
                        }

                    } catch (Exception e) {
                        Log.e(LOGTAG, "Failed to send UDP CoT", e);
                    }
                }
                Log.d(LOGTAG, url);

                if (videoSource.equals(Preferences.VIDEO_SOURCE_DEFAULT)) {
                    Camera2Source camera2Source = (Camera2Source) getStream().getVideoSource();
                    if (!camera2Source.isAutoFocusEnabled())
                        camera2Source.enableAutoFocus();
                }

                if (stream) {
                    acquireStreamWakeLock();
                    try {
                        getStream().startStream(url);
                    } catch (SecurityException e) {
                        Log.e(LOGTAG, "Failed to start stream, MediaProjection is no longer valid", e);
                        if (videoSource.equals(Preferences.VIDEO_SOURCE_SCREEN)) {
                            mediaProjection = null;
                            showNotification(getString(R.string.error_preparing_stream), false);
                        }
                        return;
                    }
                    Log.d(LOGTAG, "Started stream to ".concat(url));
                    // Re-assert the burn-in overlay onto the now-running encoder pipeline.
                    applyTextOverlay();
                    if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
                            ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                        //This will probably never show since the OnBoardingActivity forces users to grant permission before using the app
                        Toast.makeText(getApplicationContext(), R.string.no_location_permissions, Toast.LENGTH_LONG).show();
                        return;
                    }

                    if (send_cot) {
                        _locManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 5000, 0, _locListener);
                        locationRequested = true;
                        postVideoStream();
                        if (!protocol.equals("udp")) {
                            Log.d(LOGTAG, "Starting Tcp Thread");
                            tcpClient = new TcpClient(getApplicationContext(), address, port, message -> Log.d(LOGTAG, message));
                            tcpClientThread = new Thread(tcpClient);
                            tcpClientThread.start();
                        }

                        sensorManager.registerListener(this, magnetometer, SensorManager.SENSOR_DELAY_GAME);
                        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME);

                        // Put the video marker on the map and keep it fresh (position + stale
                        // refresh). The first beat fires after the TCP client has had a moment to
                        // connect to the TAK server.
                        startCotHeartbeat();
                    }

                    showNotification(getString(R.string.stream_in_progress), true);
                }

                // When streaming screen, stop local preview so we don't recursively display the captured screen.
                if (videoSource.equals(Preferences.VIDEO_SOURCE_SCREEN) && getStream().isOnPreview()) {
                    Log.d(LOGTAG, "Stopping local preview while streaming screen");
                    getStream().stopPreview();
                }

                startRecording();
            } else {
                showNotification(getString(R.string.codec_error), false);
            }
        }
    }

    private void startGoProRelay() {
        if (goproRelay != null && goproRelay.isRunning()) {
            Log.d(LOGTAG, "GoPro relay already running");
            return;
        }
        if (address == null || address.isEmpty()) {
            Toast.makeText(this, "Configure UDP target (Settings → Streaming → Address/Port)", Toast.LENGTH_LONG).show();
            sendBroadcast(new Intent(CONNECTION_FAILED));
            return;
        }
        try {
            goproRelay = new GoProRelay(this,
                    GoProSource.DEFAULT_UDP_PORT,
                    address, port,
                    goproNetwork, goproClient);
            goproRelay.start();
            acquireStreamWakeLock();
            // Tell the UI we're "streaming" so the chip + bubble update like a normal stream.
            sendBroadcast(new Intent(STREAM_CONNECTED).setPackage(getPackageName()));
            startStatsTicker();
            showNotification(getString(R.string.stream_in_progress) + " (relay)", true);
        } catch (Exception e) {
            Log.e(LOGTAG, "Could not start GoPro relay", e);
            Toast.makeText(this, "GoPro relay failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
            sendBroadcast(new Intent(CONNECTION_FAILED));
        }
    }

    public void stopStream(String error, String broadcastIntent) {
        Log.d(LOGTAG, "stopStream " + error);
        // Disarm auto-reconnect — whether this is a user stop or a give-up, we must not reconnect.
        wasConnected = false;
        // Stop re-asserting the self-marker feed and best-effort revoke it (drop the __video) so
        // peers clear the video from the marker promptly. Sent while the CoT transport is still up.
        stopCotHeartbeat();
        if (send_cot) {
            try { sendCot(buildVideoCot(false)); } catch (Throwable t) { Log.w(LOGTAG, "revoke cot", t); }
        }
        // Tear down the GoPro relay if it's active.
        if (goproRelay != null) {
            try { goproRelay.stop(); } catch (Throwable t) { Log.w(LOGTAG, "stop relay", t); }
            goproRelay = null;
        }
        // Stop projection first while source callbacks/threads are still alive, to reduce
        // dead-thread callback warnings when MediaProjection dispatches onStop.
        if (mediaProjection != null) {
            try {
                mediaProjection.unregisterCallback(mediaProjectionCallback);
            } catch (Exception ignored) {
            }
            try {
                mediaProjection.stop();
            } catch (Exception ignored) {
            }
            mediaProjection = null;
        }

        if (getStream().isStreaming())
            getStream().stopStream();

        if (tcpClient != null) {
            Log.d(LOGTAG, "Stopping TcpClient");
            tcpClient.setmRun(false);
            tcpClientThread.interrupt();
        }

        if (multicastClient != null) {
            multicastClient = null;
        }

        stopRecording();
        stopStatsTicker();
        releaseStreamWakeLock();
        sensorManager.unregisterListener(this, magnetometer);
        sensorManager.unregisterListener(this, accelerometer);

        _locManager.removeUpdates(_locListener);
        locationRequested = false;

        // Only show the "Ready to Stream" message if there is no error
        if (error != null && broadcastIntent != null) {
            showNotification(error, false);
            getApplicationContext().sendBroadcast(new Intent(broadcastIntent));
        } else {
            showNotification(getString(R.string.ready_to_stream), true);
        }
    }

    public void take_photo() {
        getStream().getGlInterface().takePhoto(bitmap -> {

            HandlerThread handlerThread = new HandlerThread("HandlerThread");
            handlerThread.start();
            Looper looper = handlerThread.getLooper();
            Handler handler = new Handler(looper);

            handler.post(() -> {
                try {
                    String filename = "SENTINEL_".concat(String.valueOf(System.currentTimeMillis()));

                    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
                        MediaStore.Images.Media.insertImage(getContentResolver(), bitmap, filename, "image:".concat(filename));
                        getApplicationContext().sendBroadcast(new Intent(TOOK_PICTURE));
                        showNotification(getString(R.string.saved_photo), true);
                    } else {
                        boolean savedSuccessfully;
                        OutputStream fos;
                        ContentResolver resolver =  getApplicationContext().getContentResolver();
                        ContentValues contentValues = new ContentValues();
                        contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, filename);
                        contentValues.put(MediaStore.MediaColumns.MIME_TYPE, "image/png");
                        contentValues.put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/SENTINEL");
                        Uri imageUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues);
                        fos = resolver.openOutputStream(imageUri);
                        getApplicationContext().sendBroadcast(new Intent(TOOK_PICTURE).setPackage(getPackageName()));
                        savedSuccessfully = bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
                        fos.flush();
                        fos.close();

                        if (savedSuccessfully) {
                            showNotification(getString(R.string.saved_photo), true);
                        } else {
                            Log.e(LOGTAG, "Failed to save photo");
                            showNotification(getString(R.string.saved_photo_failed), false);
                        }
                    }
                } catch (NullPointerException | IOException e) {
                    Log.e(LOGTAG, "Failed to save photo: ".concat(e.getMessage()));
                    showNotification(getString(R.string.saved_photo_failed) + ": " + e.getMessage(), false);
                }
            });
        });
    }

    // ===== GPS / timestamp burn-in overlay =====================================================

    /**
     * Applies (or removes) the burn-in text overlay per {@link Preferences#TEXT_OVERLAY}. The
     * overlay is a {@link TextObjectFilterRender} on the GL pipeline, so it's baked into the
     * encoded AND recorded video (not just the local preview) for every source. Idempotent.
     */
    private void applyTextOverlay() {
        try {
            overlayEnabled = preferences.getBoolean(Preferences.TEXT_OVERLAY, Preferences.TEXT_OVERLAY_DEFAULT);
            overlayUtc = preferences.getBoolean(Preferences.TEXT_OVERLAY_TIMEZONE, Preferences.TEXT_OVERLAY_TIMEZONE_DEFAULT);
            if (!overlayEnabled) {
                removeTextOverlay();
                return;
            }
            if (overlayFilter == null) overlayFilter = new TextObjectFilterRender();
            if (!overlayAttached) {
                getStream().getGlInterface().addFilter(overlayFilter);
                overlayAttached = true;
            }
            ensureLocationUpdates();
            updateOverlayText();
            overlayHandler.removeCallbacks(overlayTick);
            overlayHandler.postDelayed(overlayTick, 1000);
        } catch (Throwable t) {
            Log.w(LOGTAG, "applyTextOverlay failed", t);
        }
    }

    private final Runnable overlayTick = new Runnable() {
        @Override public void run() {
            if (!overlayEnabled) return;
            updateOverlayText();
            overlayHandler.postDelayed(this, 1000);
        }
    };

    private void updateOverlayText() {
        if (overlayFilter == null || !overlayAttached) return;
        try {
            String text = buildOverlayText();
            android.graphics.Point enc = getStream().getGlInterface().getEncoderSize();
            int w = (enc != null && enc.x > 0) ? enc.x : (resolution != null ? resolution.getWidth() : 1920);
            int h = (enc != null && enc.y > 0) ? enc.y : (resolution != null ? resolution.getHeight() : 1080);
            float textSize = Math.max(18f, h / 28f);
            overlayFilter.setText(text, textSize, Color.WHITE);
            overlayFilter.setDefaultScale(w, h);
            overlayFilter.setPosition(TranslateTo.BOTTOM_LEFT);
        } catch (Throwable t) {
            Log.w(LOGTAG, "updateOverlayText failed", t);
        }
    }

    private String buildOverlayText() {
        SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        String stamp;
        if (overlayUtc) {
            df.setTimeZone(TimeZone.getTimeZone("UTC"));
            stamp = df.format(new Date()) + "Z";
        } else {
            stamp = df.format(new Date());
        }
        String cs = (atak_callsign != null && !atak_callsign.isEmpty()) ? atak_callsign + "  " : "";
        Location l = lastKnownLocation;
        String loc = (l != null)
                ? String.format(Locale.US, "%.5f, %.5f  %.0fm", l.getLatitude(), l.getLongitude(), l.getAltitude())
                : "no GPS";
        return cs + stamp + "   " + loc;
    }

    private void removeTextOverlay() {
        overlayEnabled = false;
        overlayHandler.removeCallbacks(overlayTick);
        if (overlayAttached && overlayFilter != null) {
            try { getStream().getGlInterface().removeFilter(overlayFilter); }
            catch (Throwable t) { Log.w(LOGTAG, "removeFilter failed", t); }
        }
        overlayAttached = false;
    }

    /** The GL pipeline restarts on stopPreview, dropping its filters — mark the overlay detached. */
    private void onGlTornDown() {
        overlayHandler.removeCallbacks(overlayTick);
        overlayAttached = false;
    }

    /**
     * Request location updates for the overlay / self-marker CoT even when "send CoT to server" is
     * off. Guarded so we only register once; {@link #stopStream} clears it. Seeds from the last
     * known fix so the overlay shows coordinates immediately instead of "no GPS".
     */
    private void ensureLocationUpdates() {
        if (locationRequested) return;
        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
                ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        try {
            String provider = (Build.VERSION.SDK_INT >= 31) ? LocationManager.FUSED_PROVIDER : LocationManager.GPS_PROVIDER;
            _locManager.requestLocationUpdates(provider, 2000, 0, _locListener);
            Location seed = _locManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (seed == null) {
                try { seed = _locManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER); }
                catch (Throwable ignored) {}
            }
            if (seed != null) lastKnownLocation = seed;
            locationRequested = true;
        } catch (Throwable t) {
            Log.w(LOGTAG, "ensureLocationUpdates failed", t);
        }
    }

    // ===== Video marker advertisement (CoT) ====================================================

    /**
     * UID for SENTINEL's standalone video marker. Kept distinct from the TcpClient identity CoT
     * (which uses {@link Preferences#UID}) so the two don't clobber each other, and stable so
     * position/heartbeat updates replace the marker rather than spawning duplicates.
     */
    private String videoMarkerUid() {
        return uid + "-video";
    }

    private String callsignOrDefault() {
        return (atak_callsign != null && !atak_callsign.isEmpty()) ? atak_callsign : Preferences.ATAK_CALLSIGN_DEFAULT;
    }

    private String videoAlias() {
        if (atak_video_alias != null && !atak_video_alias.trim().isEmpty()) return atak_video_alias.trim();
        return callsignOrDefault();
    }

    /**
     * The URL ATAK clients use to PLAY the feed. Uses the explicit observer/viewer URL when set
     * (SRT {@code read:} vs {@code publish:}, RTSP {@code ?tcp}, etc.); otherwise auto-derives it
     * from the publish fields (works for RTSP/RTMP where publish URL == viewer URL).
     */
    private String buildPlaybackUrl() {
        String observer = preferences.getString(Preferences.STREAM_OBSERVER_URL, Preferences.STREAM_OBSERVER_URL_DEFAULT);
        if (observer != null && !observer.trim().isEmpty()) return observer.trim();
        String url = protocol + "://" + address + ":" + port;
        if (!protocol.equals("udp")) url = url + "/" + path;
        return url;
    }

    /**
     * Build a {@code <__video>} with a fully-structured {@code <ConnectionEntry>} parsed from the
     * playback URL. ATAK needs the structured entry to play RTSP/RTMP/SRT (a bare url only reliably
     * works for plain HTTP/file), which is why a missing ConnectionEntry shows "invalid video link".
     * {@code ?tcp} is encoded as {@code rtspReliable=1} (the structured way ATAK forces TCP) and
     * stripped from the url/path; SRT keeps its {@code streamid=read:...} query.
     */
    private __Video buildVideoElement(String rawUrl, String feedUid) {
        String scheme = protocol, host = address, path0 = "", query = "";
        int p = port;
        try {
            String u = rawUrl.trim();
            int ss = u.indexOf("://");
            if (ss > 0) { scheme = u.substring(0, ss).toLowerCase(Locale.US); u = u.substring(ss + 3); }
            int qq = u.indexOf('?');
            if (qq >= 0) { query = u.substring(qq + 1); u = u.substring(0, qq); }
            int at = u.indexOf('@');
            if (at >= 0) u = u.substring(at + 1);              // drop user:pass@
            int sl = u.indexOf('/');
            String hostport = (sl >= 0) ? u.substring(0, sl) : u;
            path0 = (sl >= 0) ? u.substring(sl) : "";          // keeps leading '/'
            int cc = hostport.lastIndexOf(':');
            if (cc >= 0) {
                host = hostport.substring(0, cc);
                try { p = Integer.parseInt(hostport.substring(cc + 1)); } catch (Exception ignore) {}
            } else {
                host = hostport;
            }
        } catch (Exception e) {
            Log.w(LOGTAG, "observer URL parse failed; using stream fields", e);
        }
        String proto = scheme.equals("rtsps") ? "rtsp" : scheme;   // ATAK has no RTSPS
        boolean tcp = query.toLowerCase(Locale.US).contains("tcp")
                || (proto.equals("rtsp") && preferences.getBoolean(Preferences.STREAM_USE_TCP, Preferences.STREAM_USE_TCP_DEFAULT));
        int reliable = proto.equals("udp") ? 0 : (tcp ? 1 : 0);

        String cePath, cleanUrl;
        if (proto.equals("srt")) {
            // SRT needs the streamid (read:...) preserved; ATAK plays from the url.
            cePath = query.isEmpty() ? path0 : "?" + query;
            cleanUrl = scheme + "://" + host + ":" + p + (query.isEmpty() ? "" : "?" + query);
        } else {
            cePath = path0;                                    // drop ?tcp etc.
            cleanUrl = scheme + "://" + host + ":" + p + path0;
        }

        ConnectionEntry ce = new ConnectionEntry(host, videoAlias(), feedUid, p, cePath, proto);
        ce.setRtspReliable(reliable);
        __Video v = new __Video(cleanUrl, feedUid, ce);
        v.setSender(callsignOrDefault());
        v.setAlias(videoAlias());
        return v;
    }

    private float batteryPercent() {
        try {
            IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            Intent batteryStatus = getApplicationContext().registerReceiver(null, ifilter);
            int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            return scale > 0 ? level * 100 / (float) scale : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    private XmlMapper cotMapper() {
        XmlFactory xmlFactory = XmlFactory.builder()
                .xmlInputFactory(new WstxInputFactory())
                .xmlOutputFactory(new WstxOutputFactory())
                .build();
        XmlMapper xmlMapper = XmlMapper.builder(xmlFactory).build();
        xmlMapper.setSerializationInclusion(JsonInclude.Include.NON_EMPTY);
        return xmlMapper;
    }

    /**
     * Builds the CoT for SENTINEL's standalone video marker — a named marker carrying the live feed
     * as a {@code <__video>} media source (includeVideo=true) or a plain marker with the feed
     * removed (false, used to revoke on stop). The marker name is the configurable callsign.
     */
    private String buildVideoCot(boolean includeVideo) throws Exception {
        event ev = new event();
        ev.setUid(videoMarkerUid());
        ev.setType((atak_marker_type != null && !atak_marker_type.isEmpty())
                ? atak_marker_type : Preferences.ATAK_MARKER_TYPE_DEFAULT);

        Location l = lastKnownLocation;
        Point point = (l != null)
                ? new Point(l.getLatitude(), l.getLongitude(), l.getAltitude())
                : new Point(9999999, 9999999, 9999999);
        ev.setPoint(point);

        String callsign = callsignOrDefault();
        Contact contact = new Contact(callsign);

        __Video video = includeVideo
                ? buildVideoElement(buildPlaybackUrl(), videoMarkerUid() + "-feed")
                : null;

        Device device = new Device(rotationInDegrees, 0);
        Sensor sensor = new Sensor(horizonalFov, rotationInDegrees);
        Track track = (l != null) ? new Track(l.getBearing(), l.getSpeed()) : null;
        Detail detail = new Detail(contact, video, device, sensor, null, null, track, new Status(batteryPercent()));
        ev.setDetail(detail);

        return cotMapper().writeValueAsString(ev);
    }

    /** Send a CoT over whichever transport is live (TCP to the TAK server, or UDP multicast). */
    private void sendCot(String cot) {
        if (cot == null) return;
        try {
            if (!protocol.equals("udp") && tcpClient != null) tcpClient.sendMessage(cot);
            else if (protocol.equals("udp") && multicastClient != null) multicastClient.send_cot(cot);
        } catch (Throwable t) {
            Log.w(LOGTAG, "sendCot failed", t);
        }
    }

    private final Runnable cotHeartbeat = new Runnable() {
        @Override public void run() {
            if (!send_cot || !getStream().isStreaming()) return;
            try { sendCot(buildVideoCot(true)); }
            catch (Throwable t) { Log.w(LOGTAG, "cot heartbeat failed", t); }
            cotHandler.postDelayed(this, COT_HEARTBEAT_MS);
        }
    };

    private void startCotHeartbeat() {
        cotHandler.removeCallbacks(cotHeartbeat);
        cotHandler.post(cotHeartbeat);
    }

    private void stopCotHeartbeat() {
        cotHandler.removeCallbacks(cotHeartbeat);
    }

    class ICULocationListener implements LocationListener {
        @Override
        public void onLocationChanged(@NonNull Location location) {
            Log.d(LOGTAG, "onLocationChanged");
            lastKnownLocation = location;
            try {
                last_fix_time = System.currentTimeMillis();
                getApplicationContext().sendBroadcast(new Intent(LOCATION_CHANGE));
                // Push an updated self-marker CoT immediately so the marker tracks movement between
                // heartbeats. The heartbeat is what keeps the __video attached against ATAK's own
                // self broadcasts; this just freshens position/track.
                if (send_cot) sendCot(buildVideoCot(true));
            } catch (Exception e) {
                Log.e(LOGTAG, "Failed to send location to ATAK: " + e.getLocalizedMessage());
            }
        }
    }

    private void postVideoStream() {
        try {
            feed Feed = new feed(protocol, path, uid, address, port, path);
            videoConnections VideoConnections = new videoConnections(Feed);
            XmlFactory xmlFactory = XmlFactory.builder()
                    .xmlInputFactory(new WstxInputFactory())
                    .xmlOutputFactory(new WstxOutputFactory())
                    .build();

            XmlMapper xmlMapper = XmlMapper.builder(xmlFactory).build();

            RequestBody requestBody = RequestBody.create(xmlMapper.writeValueAsString(VideoConnections).getBytes());
            Request request = new Request.Builder()
                    .url("http://" + atak_address + ":8080/Marti/vcm")
                    .post(requestBody)
                    .build();
            executor.execute(() -> {
                try {
                    Response response = okHttpClient.newCall(request).execute();
                    Log.d(LOGTAG, "Posted video: " + response.message() + " " + response.code());
                    Log.d(LOGTAG, response.toString());
                } catch (IOException e) {
                    Log.e(LOGTAG, "Failed to post video stream: " + e.getMessage());
                }
            });
        } catch (Exception e) {
            Log.e(LOGTAG, "Failed to post video");
            Log.e(LOGTAG, e.toString());
        }
    }
}