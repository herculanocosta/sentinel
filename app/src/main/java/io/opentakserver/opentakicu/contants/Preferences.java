package io.opentakserver.opentakicu.contants;

import com.pedro.common.AudioCodec;
import com.pedro.common.VideoCodec;

import java.util.UUID;

public class Preferences {
    public static final String UID = "uid";
    public static final String UID_DEFAULT = "OpenTAK-ICU-" + UUID.randomUUID().toString();

    /* Streaming Preferences */
    public static final String STREAM_VIDEO = "stream_video";
    public static final boolean STREAM_VIDEO_DEFAULT = true;
    public static final String STREAM_PROTOCOL = "protocol";
    public static final String STREAM_PROTOCOL_DEFAULT = "rtsp";
    public static final String STREAM_ADDRESS = "address";
    public static final String STREAM_ADDRESS_DEFAULT = "example.com";
    public static final String STREAM_PORT = "port";
    public static final String STREAM_PORT_DEFAULT = "8554";
    public static final String STREAM_PATH = "path";
    public static final String STREAM_PATH_DEFAULT = "my_path";
    /**
     * The viewer/playback URL advertised to ATAK in the CoT {@code <__video>} — what other EUDs
     * use to WATCH the feed. Often differs from the publish URL: SRT viewers use
     * {@code streamid=read:NAME} (publishers use {@code publish:NAME}); RTSP viewers usually want a
     * trailing {@code ?tcp}. Blank → auto-derive {@code protocol://address:port/path}.
     */
    public static final String STREAM_OBSERVER_URL = "observer_url";
    public static final String STREAM_OBSERVER_URL_DEFAULT = "";
    public static final String STREAM_USERNAME = "username";
    public static final String STREAM_USERNAME_DEFAULT = "";
    public static final String STREAM_PASSWORD = "password";
    public static final String STREAM_PASSWORD_DEFAULT = "";
    public static final String STREAM_USE_TCP = "tcp";
    public static final boolean STREAM_USE_TCP_DEFAULT = false;
    public static final String STREAM_SELF_SIGNED_CERT = "self_signed_cert";
    public static final boolean STREAM_SELF_SIGNED_CERT_DEFAULT = false;
    public static final String STREAM_CERTIFICATE = "certificate";
    public static final String STREAM_CERTIFICATE_DEFAULT = null;
    public static final String STREAM_CERTIFICATE_PASSWORD = "certificate_password";
    public static final String STREAM_CERTIFICATE_PASSWORD_DEFAULT = "atakatak";
    /**
     * Saved server endpoints, serialised as a JSON array string. Each entry holds a friendly
     * name plus the connection fields (protocol/address/port/path/user/pass/tcp). Lets the
     * operator keep several destinations (home MediaMTX, field relay, etc.) and switch in one tap.
     * Managed by {@link io.opentakserver.opentakicu.presets.ServerPresets}.
     */
    public static final String SERVER_PRESETS = "server_presets";

    /* Video Preferences */
    public static final String VIDEO_RESOLUTION = "resolution";
    public static final String VIDEO_RESOLUTION_DEFAULT = "0";
    public static final String VIDEO_BITRATE = "bitrate";
    public static final String VIDEO_BITRATE_DEFAULT = "1000";
    public static final String VIDEO_ADAPTIVE_BITRATE = "adaptive_bitrate";
    public static final boolean VIDEO_ADAPTIVE_BITRATE_DEFAULT = true;
    public static final String VIDEO_FPS = "fps";
    public static final String VIDEO_FPS_DEFAULT = "30";
    public static final String VIDEO_CODEC = "codec";
    public static final String VIDEO_CODEC_DEFAULT = VideoCodec.H264.name();
    public static final String RECORD_VIDEO = "record";
    public static final boolean RECORD_VIDEO_DEFAULT = false;
    public static final String USB_WIDTH = "usb_width";
    public static final String USB_WIDTH_DEFAULT = "1920";
    public static final String USB_HEIGHT = "usb_height";
    public static final String USB_HEIGHT_DEFAULT = "1080";
    public static final String VIDEO_SOURCE = "video_source";
    public static final String VIDEO_SOURCE_DEFAULT = "camera2";
    public static final String VIDEO_SOURCE_USB = "usb";
    public static final String VIDEO_SOURCE_SCREEN = "screen";
    public static final String VIDEO_SOURCE_GOPRO = "gopro";
    public static final String FORCE_LANDSCAPE = "force_landscape";
    public static final boolean FORCE_LANDSCAPE_DEFAULT = true;
    /**
     * Reliability mode — clamps the phone camera to a conservative native resolution + fps that
     * weak/buggy vendor HALs (notably MediaTek) can sustain without dropping frames, corrupting
     * buffers (visible as a purple bar across the frame), or stalling outright. ON by default; turn
     * OFF only if you trust your device at 720p+/30fps and want full quality.
     */
    public static final String RELIABILITY_MODE = "reliability_mode";
    public static final boolean RELIABILITY_MODE_DEFAULT = true;
    public static final int RELIABILITY_MODE_WIDTH = 854;
    public static final int RELIABILITY_MODE_HEIGHT = 480;
    public static final int RELIABILITY_MODE_FPS = 24;
    /**
     * Chosen OUTPUT orientation for the active stream/recording. Set by the pre-stream
     * orientation dialog. "landscape" or "portrait". Kept in sync with {@link #FORCE_LANDSCAPE}
     * (landscape → true, portrait → false) so the existing encoder rotation logic still applies.
     */
    public static final String STREAM_ORIENTATION = "stream_orientation";
    public static final String STREAM_ORIENTATION_LANDSCAPE = "landscape";
    public static final String STREAM_ORIENTATION_PORTRAIT = "portrait";
    public static final String STREAM_ORIENTATION_DEFAULT = STREAM_ORIENTATION_LANDSCAPE;
    public static final String FLOATING_BUBBLE = "floating_bubble";
    public static final boolean FLOATING_BUBBLE_DEFAULT = false;
    /** Auto-reconnect a dropped broadcast (network blip in the field) instead of just stopping. */
    public static final String AUTO_RECONNECT = "auto_reconnect";
    public static final boolean AUTO_RECONNECT_DEFAULT = true;
    public static final String TEXT_OVERLAY = "text_overlay";
    public static final boolean TEXT_OVERLAY_DEFAULT = false;
    public static final String TEXT_OVERLAY_TIMEZONE = "text_overlay_timezone";
    public static final boolean TEXT_OVERLAY_TIMEZONE_DEFAULT = true;
    public static final String CHROMA_KEY_BACKGROUND = "chroma_bg";
    public static final String CHROMA_KEY_BACKGROUND_DEFAULT = null;

    /* Audio Preferences */
    public static final String ENABLE_AUDIO = "enable_audio";
    public static final boolean ENABLE_AUDIO_DEFAULT = true;
    public static final String AUDIO_BITRATE = "audio_bitrate";
    public static final String AUDIO_BITRATE_DEFAULT = "128";
    public static final String AUDIO_SAMPLE_RATE = "samplerate";
    public static final String AUDIO_SAMPLE_RATE_DEFAULT = "44100";
    public static final String AUDIO_CODEC = "audio_codec";
    public static final String AUDIO_CODEC_DEFAULT = AudioCodec.AAC.name();
    public static final String STEREO_AUDIO = "stereo";
    public static final boolean STEREO_AUDIO_DEFAULT = true;
    public static final String AUDIO_ECHO_CANCEL = "echo_cancel";
    public static final boolean AUDIO_ECHO_CANCEL_DEFAULT = true;
    public static final String AUDIO_NOISE_REDUCTION = "noise_reduction";
    public static final boolean AUDIO_NOISE_REDUCTION_DEFAULT = true;

    /* ATAK Preferences */
    public static final String ATAK_SEND_COT = "send_cot";
    public static final boolean ATAK_SEND_COT_DEFAULT = false;
    public static final String ATAK_SEND_STREAM_DETAILS = "send_stream_details";
    public static final boolean ATAK_SEND_STREAM_DETAILS_DEFAULT = false;
    public static final String ATAK_SERVER_ADDRESS = "atak_address";
    public static final String ATAK_SERVER_ADDRESS_DEFAULT = "example.com";
    public static final String ATAK_SERVER_PORT = "atak_port";
    public static final String ATAK_SERVER_PORT_DEFAULT = "8088";
    public static final String ATAK_SERVER_AUTHENTICATION = "atak_auth";
    public static final boolean ATAK_SERVER_AUTHENTICATION_DEFAULT = false;
    public static final String ATAK_SERVER_USERNAME = "atak_username";
    public static final String ATAK_SERVER_USERNAME_DEFAULT = null;
    public static final String ATAK_SERVER_PASSWORD = "atak_password";
    public static final String ATAK_SERVER_PASSWORD_DEFAULT = null;
    public static final String ATAK_SERVER_SSL = "atak_ssl";
    public static final boolean ATAK_SERVER_SSL_DEFAULT = false;
    public static final String ATAK_SERVER_SSL_TRUST_STORE = "trust_store_certificate";
    public static final String ATAK_SERVER_SSL_TRUST_STORE_DEFAULT = null;
    public static final String ATAK_SERVER_SSL_TRUST_STORE_PASSWORD = "trust_store_cert_password";
    public static final String ATAK_SERVER_SSL_TRUST_STORE_PASSWORD_DEFAULT = "atakatak";
    public static final String ATAK_SERVER_SSL_CLIENT_CERTIFICATE = "client_certificate";
    public static final String ATAK_SERVER_SSL_CLIENT_CERTIFICATE_DEFAULT = null;
    public static final String ATAK_SERVER_SSL_CLIENT_CERTIFICATE_PASSWORD = "client_cert_password";
    public static final String ATAK_SERVER_SSL_CLIENT_CERTIFICATE_PASSWORD_DEFAULT = "atakatak";

    /* ---- Self-marker video advertisement -------------------------------------------------
     * Attach the live feed to the operator's ATAK self marker (so peers see the video under the
     * operator's callsign) instead of spawning a separate sensor icon. Because SENTINEL runs on
     * the SAME device as ATAK, it publishes a CoT carrying the ATAK self marker's UID + a
     * self-marker type with a <__video> detail; ATAK ignores the loopback to its own UID but the
     * server reflects it to peers. ATAK keeps broadcasting its self marker without the video, so
     * SENTINEL re-asserts the __video on a short heartbeat while streaming. */

    /** Callsign used on the CoT contact + as the __video sender/alias. */
    public static final String ATAK_CALLSIGN = "atak_callsign";
    public static final String ATAK_CALLSIGN_DEFAULT = "SENTINEL";
    /**
     * The ATAK self marker UID to attach the feed to (ATAK → Settings → Device Preferences →
     * "Your UID"). When blank, SENTINEL advertises the feed on its OWN marker (its device UID),
     * which is the right choice when SENTINEL runs on a separate streaming device.
     */
    public static final String ATAK_SELF_UID = "atak_self_uid";
    public static final String ATAK_SELF_UID_DEFAULT = "";
    /**
     * CoT type for the video marker. {@code b-i-v} is the type ATAK itself uses for video feeds
     * (renders the camera/video icon) — confirmed from ATAK's VideoXMLHandler. The earlier
     * {@code a-f-G-U-C} rendered a friendly-combat-unit icon, which is wrong for a camera feed.
     */
    public static final String ATAK_MARKER_TYPE = "atak_marker_type";
    public static final String ATAK_MARKER_TYPE_DEFAULT = "b-i-v";
    /** Optional display label shown in ATAK's video pane. Blank → use the callsign. */
    public static final String ATAK_VIDEO_ALIAS = "atak_video_alias";
    public static final String ATAK_VIDEO_ALIAS_DEFAULT = "";
}
