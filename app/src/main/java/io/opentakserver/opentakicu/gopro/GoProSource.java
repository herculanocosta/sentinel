package io.opentakserver.opentakicu.gopro;

import android.content.Context;
import android.content.Intent;
import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.pedro.encoder.input.sources.OrientationConfig;
import com.pedro.encoder.input.sources.OrientationForced;
import com.pedro.encoder.input.sources.video.VideoSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * pedroSG94 {@link VideoSource} that ingests a GoPro MPEG-TS preview stream over UDP, decodes it
 * with {@link MediaCodec}, and renders the decoded frames into the encoder's GL input surface so
 * the rest of the pipeline (filters, encoder at the user-chosen bitrate, RTSP/SRT push) re-encodes
 * it on-device.
 *
 * <h3>What the GoPro actually sends (confirmed by ffprobe on a captured stream)</h3>
 * <pre>
 *   format = mpegts, ts_packetsize = 188
 *   PMT on PID 0x100
 *     video : HEVC (H.265) Main, 1920x1080, PID 0x1011, stream_type 0x24
 *     audio : AAC-LC 48k stereo,            PID 0x1100, stream_type 0x0F
 *     (also an AC-3 track and a private GoPro metadata stream — ignored)
 * </pre>
 * The earlier hand-rolled demuxer (and Media3's ProgressiveMediaSource) failed because the video
 * is <b>HEVC, not H.264</b>: HEVC NAL units use a 2-byte header with {@code type = (b0 >> 1) & 0x3F}
 * (VPS 32 / SPS 33 / PPS 34 / IDR 19-20 / CRA 21), nothing like H.264's {@code b0 & 0x1F}. This
 * implementation parses PAT→PMT to discover the real video PID + codec (so it also handles older
 * H.264 GoPros, stream_type 0x1B), then frames NAL units with the correct math.
 */
public class GoProSource extends VideoSource {

    private static final String TAG = "GoProSource";
    private static final int TS_PACKET_SIZE = 188;
    private static final byte TS_SYNC = 0x47;
    public static final int DEFAULT_UDP_PORT = 8554;
    /** Watchdog: no MPEG-TS for this long ⇒ ask the GoPro to restart its push. */
    private static final long FRAME_WATCHDOG_MS = 6000;

    /* ----- legacy audio sink hooks (kept so GoProAudioSource still compiles; unused) ----- */
    @Deprecated public interface AudioFrameSink { void onAdtsFrame(byte[] data, int off, int len); }
    @Deprecated private static volatile AudioFrameSink audioSink;
    @Deprecated public static void setAudioSink(AudioFrameSink sink) { audioSink = sink; }
    @Deprecated public static AudioFrameSink getAudioSink() { return audioSink; }

    /* ------------------------------- diagnostics ------------------------------- */

    public static final String GOPRO_DIAG = "io.opentakserver.opentakicu.GOPRO_DIAG";
    public static final String EXTRA_TEXT = "text";

    @Nullable private static Context diagCtx;
    public static void setDiagnosticsContext(@Nullable Context ctx) {
        diagCtx = ctx == null ? null : ctx.getApplicationContext();
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static void diag(String msg) {
        // Diagnostics go to LOGCAT ONLY now. The on-screen "GoPro: …" toasts were invaluable while
        // bringing the pipeline up, but in normal use they're noise (especially the per-second
        // "dec in/out"). To re-enable for debugging, restore the Toast/broadcast here.
        Log.i(TAG, "diag: " + msg);
    }

    /* ------------------------------- config / state ------------------------------- */

    private final Context appCtx;
    private final int udpPort;
    @Nullable private final GoProClient gopro;

    // Networking
    private DatagramSocket socket;
    private Thread receiverThread;
    private volatile boolean running = false;
    private volatile long lastPacketAtMs = 0;
    private ScheduledExecutorService watchdog;

    // Decoder
    private MediaCodec decoder;
    private volatile Surface outputSurface;
    private boolean decoderStarted = false;
    private String videoMime = null;          // MediaFormat.MIMETYPE_VIDEO_HEVC / _AVC

    // TS demux state
    private static final int PID_PAT = 0x0000;
    private int pmtPid = -1;
    private int videoPid = -1;
    private int videoStreamType = -1;         // 0x24 HEVC, 0x1B H.264
    private final ByteArrayOutputStream pes = new ByteArrayOutputStream(64 * 1024);
    private boolean pesActive = false;
    private final ByteArrayOutputStream nalBuf = new ByteArrayOutputStream(256 * 1024);

    // Codec config (parameter sets)
    private byte[] vps, sps, pps;             // HEVC needs all three; H.264 uses sps+pps
    private long packetsSeen = 0;
    private long framesFed = 0, framesRendered = 0, lastDiagMs = 0;
    private boolean firstFrameDiagFired = false;

    public GoProSource(Context ctx, int udpPort, @Nullable GoProClient gopro) {
        this.appCtx = ctx.getApplicationContext();
        this.udpPort = udpPort > 0 ? udpPort : DEFAULT_UDP_PORT;
        this.gopro = gopro;
    }

    /* =========================== VideoSource overrides =========================== */

    @Override
    protected boolean create(int width, int height, int fps, int rotation) {
        setWidth(width);
        setHeight(height);
        setFps(fps);
        setRotation(rotation);
        return true;
    }

    /**
     * Tell pedroSG94 the source is intrinsically LANDSCAPE (the GoPro always streams 16:9
     * 1920x1080). Without this, the default {@code OrientationConfig(forced = NONE)} lets the GL
     * pipeline apply device-orientation mapping to the decoded frame, which squashes the landscape
     * picture into a portrait box and stretches it back out — exactly the "portrait stretched to
     * landscape" artifact. This mirrors pedroSG94's own Media3VideoSource (decoded-video) source.
     */
    @Override
    public OrientationConfig getOrientationConfig() {
        return new OrientationConfig(null, null, OrientationForced.LANDSCAPE);
    }

    @Override
    public void start(SurfaceTexture surfaceTexture) {
        setSurfaceTexture(surfaceTexture);
        // NOTE: deliberately NOT calling surfaceTexture.setDefaultBufferSize() — pedroSG94 owns the
        // encoder-input texture geometry, and the MediaCodec decoder drives the buffer size to the
        // decoded frame size. Forcing it here fought pedroSG94's sizing and contributed to the
        // aspect distortion. The reference Media3VideoSource doesn't set it either.
        Surface newSurface = new Surface(surfaceTexture);

        // IDEMPOTENT RE-START: pedroSG94 calls start() again with a fresh SurfaceTexture when the
        // GL pipeline is reconfigured (e.g. a stream begins on top of the live preview). Don't
        // re-open the UDP socket / decoder — just retarget the running decoder at the new surface,
        // so the single decode session survives preview→stream and the encoder gets frames
        // immediately (no "sps or pps is null", no EADDRINUSE).
        if (running) {
            Surface old = outputSurface;
            outputSurface = newSurface;
            if (decoder != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try { decoder.setOutputSurface(newSurface); }
                catch (Exception e) { Log.w(TAG, "setOutputSurface on reuse failed", e); }
            }
            if (old != null) old.release();
            return;
        }

        outputSurface = newSurface;
        running = true;
        resetDemuxState();

        try {
            socket = new DatagramSocket(null);
            socket.setReuseAddress(true);
            socket.setSoTimeout(1000);
            try { socket.setReceiveBufferSize(4 * 1024 * 1024); } catch (Exception ignored) {}
            socket.bind(new InetSocketAddress(udpPort));
        } catch (IOException e) {
            Log.e(TAG, "UDP bind failed on " + udpPort, e);
            diag("UDP bind failed: " + e.getMessage());
            running = false;
            return;
        }

        receiverThread = new Thread(this::receiveLoop, "gopro-rx");
        receiverThread.setPriority(Thread.MAX_PRIORITY - 1);
        receiverThread.start();
        diag("listening UDP " + udpPort);

        if (gopro != null) kickGoProPush();
        else diag("no GoProClient — HTTP control disabled");

        watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "gopro-watchdog"); t.setDaemon(true); return t;
        });
        watchdog.scheduleAtFixedRate(this::watchdogTick, 3, 3, TimeUnit.SECONDS);
    }

    @Override
    public void stop() {
        running = false;
        if (watchdog != null) { watchdog.shutdownNow(); watchdog = null; }
        try { if (socket != null) socket.close(); } catch (Exception ignored) {}
        try { if (receiverThread != null) receiverThread.join(1200); } catch (InterruptedException ignored) {}
        if (decoder != null) {
            try { decoder.stop(); } catch (Exception ignored) {}
            try { decoder.release(); } catch (Exception ignored) {}
            decoder = null;
        }
        decoderStarted = false;
        Surface s = outputSurface;
        if (s != null) { try { s.release(); } catch (Exception ignored) {} outputSurface = null; }
        if (gopro != null) {
            final GoProClient g = gopro;
            new Thread(() -> { try { g.stopPreview(); } catch (Exception ignored) {} }, "gopro-http-stop").start();
        }
    }

    @Override public void release() { stop(); }
    @Override public boolean isRunning() { return running; }

    private void resetDemuxState() {
        pmtPid = -1; videoPid = -1; videoStreamType = -1; videoMime = null;
        pes.reset(); pesActive = false; nalBuf.reset();
        vps = sps = pps = null;
        packetsSeen = framesFed = framesRendered = 0; lastDiagMs = 0; firstFrameDiagFired = false;
    }

    /* =========================== receive ===========================  */

    private void receiveLoop() {
        byte[] buf = new byte[4096];
        DatagramPacket pkt = new DatagramPacket(buf, buf.length);
        boolean firstDiag = false;
        long n = 0;
        while (running) {
            try {
                socket.receive(pkt);
                if (!firstDiag) { diag("first UDP packet (" + pkt.getLength() + " B)"); firstDiag = true; }
                lastPacketAtMs = System.currentTimeMillis();
                n++;
                processBytes(buf, pkt.getLength());
            } catch (SocketTimeoutException ignored) {
            } catch (IOException e) {
                if (running) Log.w(TAG, "UDP recv: " + e.getMessage());
                break;
            }
        }
        Log.i(TAG, "receiveLoop ended after " + n + " datagrams");
    }

    private void watchdogTick() {
        if (!running || lastPacketAtMs == 0 || gopro == null) return;
        if (System.currentTimeMillis() - lastPacketAtMs > FRAME_WATCHDOG_MS) {
            Log.w(TAG, "no GoPro packets — restarting push");
            try { gopro.stopPreview(); gopro.startPreview(udpPort); } catch (IOException ignored) {}
        }
    }

    /* =========================== MPEG-TS demux =========================== */

    private void processBytes(byte[] data, int len) {
        int i = 0;
        while (i + TS_PACKET_SIZE <= len) {
            if (data[i] != TS_SYNC) {
                int next = resync(data, i, len);
                if (next < 0) return;
                i = next;
            }
            processTsPacket(data, i);
            i += TS_PACKET_SIZE;
        }
    }

    private int resync(byte[] d, int from, int len) {
        for (int j = from; j + 2 * TS_PACKET_SIZE <= len; j++) {
            if (d[j] == TS_SYNC && d[j + TS_PACKET_SIZE] == TS_SYNC) return j;
        }
        return -1;
    }

    private void processTsPacket(byte[] d, int off) {
        packetsSeen++;
        int pid = ((d[off + 1] & 0x1F) << 8) | (d[off + 2] & 0xFF);
        boolean pusi = (d[off + 1] & 0x40) != 0;
        int adaptation = (d[off + 3] >> 4) & 0x3;
        int payloadOff = off + 4;
        if ((adaptation & 0x2) != 0) {                 // adaptation field present
            int adaptLen = d[off + 4] & 0xFF;
            payloadOff = off + 5 + adaptLen;
        }
        if ((adaptation & 0x1) == 0) return;           // no payload
        if (payloadOff >= off + TS_PACKET_SIZE) return;
        int payloadLen = (off + TS_PACKET_SIZE) - payloadOff;

        if (pid == PID_PAT) {
            if (pusi) parsePat(d, payloadOff, payloadLen);
            return;
        }
        if (pid == pmtPid) {
            if (pusi) parsePmt(d, payloadOff, payloadLen);
            return;
        }
        if (pid == videoPid && videoPid >= 0) {
            if (pusi) {
                if (pesActive && pes.size() > 0) flushVideoPes();
                pesActive = true;
                pes.reset();
            }
            if (pesActive) pes.write(d, payloadOff, payloadLen);
        }
    }

    /** PAT: section after a 1-byte pointer_field. Grab the first program's PMT PID. */
    private void parsePat(byte[] d, int off, int len) {
        try {
            int p = off + 1 + (d[off] & 0xFF);          // skip pointer_field
            if (p + 8 > off + len) return;
            int sectionLen = ((d[p + 1] & 0x0F) << 8) | (d[p + 2] & 0xFF);
            int progStart = p + 8;
            int progEnd = Math.min(off + len, p + 3 + sectionLen - 4);  // exclude CRC32
            for (int q = progStart; q + 4 <= progEnd; q += 4) {
                int programNumber = ((d[q] & 0xFF) << 8) | (d[q + 1] & 0xFF);
                int pid = ((d[q + 1 + 1] & 0x1F) << 8) | (d[q + 3] & 0xFF);
                if (programNumber != 0) {                // skip NIT (program 0)
                    if (pmtPid != pid) {
                        pmtPid = pid;
                        Log.i(TAG, "PAT: program " + programNumber + " PMT PID 0x" + Integer.toHexString(pid));
                    }
                    return;
                }
            }
        } catch (Exception e) { Log.w(TAG, "parsePat", e); }
    }

    /** PMT: find the video elementary stream (HEVC 0x24 preferred, H.264 0x1B fallback). */
    private void parsePmt(byte[] d, int off, int len) {
        if (videoPid >= 0) return;                      // already locked
        try {
            int p = off + 1 + (d[off] & 0xFF);          // skip pointer_field
            if (p + 12 > off + len) return;
            int sectionLen = ((d[p + 1] & 0x0F) << 8) | (d[p + 2] & 0xFF);
            int programInfoLen = ((d[p + 10] & 0x0F) << 8) | (d[p + 11] & 0xFF);
            int es = p + 12 + programInfoLen;
            int end = Math.min(off + len, p + 3 + sectionLen - 4);
            int h264Pid = -1, h264Type = -1;
            while (es + 5 <= end) {
                int streamType = d[es] & 0xFF;
                int elementaryPid = ((d[es + 1] & 0x1F) << 8) | (d[es + 2] & 0xFF);
                int esInfoLen = ((d[es + 3] & 0x0F) << 8) | (d[es + 4] & 0xFF);
                if (streamType == 0x24) {                              // HEVC (H.265) — GoPro default
                    lockVideo(elementaryPid, streamType, MediaFormat.MIMETYPE_VIDEO_HEVC);
                    return;
                } else if (streamType == 0x1B && h264Pid < 0) {        // H.264 (older GoPros)
                    h264Pid = elementaryPid; h264Type = streamType;
                }
                es += 5 + esInfoLen;
            }
            if (h264Pid >= 0) lockVideo(h264Pid, h264Type, MediaFormat.MIMETYPE_VIDEO_AVC);
        } catch (Exception e) { Log.w(TAG, "parsePmt", e); }
    }

    private void lockVideo(int pid, int streamType, String mime) {
        videoPid = pid; videoStreamType = streamType; videoMime = mime;
        Log.i(TAG, "Video locked: PID 0x" + Integer.toHexString(pid)
                + " stream_type 0x" + Integer.toHexString(streamType) + " (" + mime + ")");
        diag("video " + (MediaFormat.MIMETYPE_VIDEO_HEVC.equals(mime) ? "HEVC" : "H264")
                + " PID 0x" + Integer.toHexString(pid));
    }

    /** Strip the PES header, append the elementary bytes to the running NAL buffer, extract NALs. */
    private void flushVideoPes() {
        byte[] b = pes.toByteArray();
        pes.reset();
        if (b.length < 9) return;
        if (!(b[0] == 0 && b[1] == 0 && b[2] == 1)) return;     // not a PES start
        int esStart = 9 + (b[8] & 0xFF);
        if (esStart >= b.length) return;
        nalBuf.write(b, esStart, b.length - esStart);
        extractNalUnits();
    }

    /* =========================== NAL extraction =========================== */

    private void extractNalUnits() {
        byte[] b = nalBuf.toByteArray();
        int last = -1;          // start offset of the current start code
        int i = 0;
        while (i + 3 < b.length) {
            int sc = startCodeLen(b, i);
            if (sc > 0) {
                if (last >= 0) {
                    int prev = startCodeLen(b, last);
                    emitNal(b, last + prev, i - (last + prev));
                }
                last = i;
                i += sc;
            } else {
                i++;
            }
        }
        // Keep the tail (from the last start code onward) for the next pass.
        nalBuf.reset();
        if (last >= 0) nalBuf.write(b, last, b.length - last);
        else if (b.length > 3) nalBuf.write(b, b.length - 3, 3);   // keep a possible partial start code
    }

    private int startCodeLen(byte[] b, int o) {
        if (o + 3 < b.length && b[o] == 0 && b[o + 1] == 0 && b[o + 2] == 0 && b[o + 3] == 1) return 4;
        if (o + 2 < b.length && b[o] == 0 && b[o + 1] == 0 && b[o + 2] == 1) return 3;
        return 0;
    }

    private void emitNal(byte[] data, int off, int len) {
        if (len <= 0) return;
        boolean hevc = MediaFormat.MIMETYPE_VIDEO_HEVC.equals(videoMime);
        boolean keyFrame;
        if (hevc) {
            int type = (data[off] >> 1) & 0x3F;
            switch (type) {
                case 32: vps = copy(data, off, len); tryStartDecoder(); if (decoderStarted) feed(data, off, len, false); return;
                case 33: sps = copy(data, off, len); tryStartDecoder(); if (decoderStarted) feed(data, off, len, false); return;
                case 34: pps = copy(data, off, len); tryStartDecoder(); if (decoderStarted) feed(data, off, len, false); return;
                default: break;
            }
            keyFrame = (type >= 16 && type <= 21);     // BLA/IDR/CRA
        } else {
            int type = data[off] & 0x1F;
            switch (type) {
                case 7: sps = copy(data, off, len); tryStartDecoder(); if (decoderStarted) feed(data, off, len, false); return;
                case 8: pps = copy(data, off, len); tryStartDecoder(); if (decoderStarted) feed(data, off, len, false); return;
                default: break;
            }
            keyFrame = (type == 5);                    // IDR
        }
        if (keyFrame && !firstFrameDiagFired) { firstFrameDiagFired = true; diag("first keyframe"); }
        if (decoderStarted) feed(data, off, len, keyFrame);
    }

    private static byte[] copy(byte[] s, int o, int l) { byte[] d = new byte[l]; System.arraycopy(s, o, d, 0, l); return d; }

    /* =========================== MediaCodec =========================== */

    private void tryStartDecoder() {
        if (decoderStarted || videoMime == null || outputSurface == null) return;
        boolean hevc = MediaFormat.MIMETYPE_VIDEO_HEVC.equals(videoMime);
        if (sps == null || pps == null || (hevc && vps == null)) return;   // need full parameter set
        int w = getWidth() > 0 ? getWidth() : 1920;
        int h = getHeight() > 0 ? getHeight() : 1080;
        try {
            MediaFormat fmt = MediaFormat.createVideoFormat(videoMime, w, h);
            fmt.setInteger(MediaFormat.KEY_FRAME_RATE, getFps() > 0 ? getFps() : 30);
            // csd-0 = parameter sets back-to-back, each with a 4-byte start code (how Android's
            // own MediaExtractor presents them). HEVC: VPS+SPS+PPS; H.264: SPS+PPS.
            ByteArrayOutputStream csd = new ByteArrayOutputStream();
            byte[] sc = {0, 0, 0, 1};
            if (hevc) { csd.write(sc); csd.write(vps); }
            csd.write(sc); csd.write(sps);
            csd.write(sc); csd.write(pps);
            fmt.setByteBuffer("csd-0", ByteBuffer.wrap(csd.toByteArray()));

            decoder = MediaCodec.createDecoderByType(videoMime);
            decoder.configure(fmt, outputSurface, null, 0);
            decoder.start();
            decoderStarted = true;
            Log.i(TAG, "MediaCodec " + videoMime + " started " + w + "x" + h);
            diag("decoder " + (hevc ? "HEVC " : "H264 ") + w + "x" + h);
        } catch (Exception e) {
            Log.e(TAG, "decoder configure failed", e);
            diag("decoder failed: " + e.getMessage());
            decoder = null;
        }
    }

    private void feed(byte[] data, int off, int len, boolean keyFrame) {
        try {
            int idx = decoder.dequeueInputBuffer(15_000);
            if (idx >= 0) {
                ByteBuffer ib = decoder.getInputBuffer(idx);
                if (ib != null) {
                    ib.clear();
                    ib.put((byte) 0).put((byte) 0).put((byte) 0).put((byte) 1);
                    ib.put(data, off, len);
                    decoder.queueInputBuffer(idx, 0, len + 4, System.nanoTime() / 1000,
                            keyFrame ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
                    framesFed++;
                }
            }
            // Drain + render to the encoder surface.
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int o;
            while ((o = decoder.dequeueOutputBuffer(info, 0)) >= 0) {
                decoder.releaseOutputBuffer(o, true);
                framesRendered++;
            }
            long now = System.currentTimeMillis();
            if (now - lastDiagMs > 1000) {
                lastDiagMs = now;
                diag("dec in=" + framesFed + " out=" + framesRendered);
            }
        } catch (IllegalStateException e) {
            Log.w(TAG, "decoder error, reinit on next params", e);
            try { decoder.release(); } catch (Exception ignored) {}
            decoder = null; decoderStarted = false;
            vps = sps = pps = null;
        }
    }

    /* =========================== GoPro HTTP control =========================== */

    private void kickGoProPush() {
        if (gopro == null) return;
        new Thread(() -> {
            Exception last = null;
            for (int attempt = 1; attempt <= 3 && running; attempt++) {
                try { gopro.startPreview(udpPort); diag("stream/start OK"); return; }
                catch (Exception e) {
                    last = e;
                    Log.w(TAG, "stream/start attempt " + attempt + " failed: " + e.getMessage());
                    try { Thread.sleep(800L * attempt); }
                    catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
                }
            }
            diag("stream/start gave up: " + (last == null ? "?" : last.getMessage()));
        }, "gopro-http-start").start();
    }
}
