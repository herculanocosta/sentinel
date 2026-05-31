package io.opentakserver.opentakicu.gopro;

import android.content.Context;
import android.net.Network;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Minimal Open GoPro v2.0 HTTP client.
 *
 * Endpoints used:
 *   GET /gopro/camera/state
 *   GET /gopro/camera/stream/start          start UDP preview (MPEG-TS to the requester)
 *   GET /gopro/camera/stream/stop
 *   GET /gopro/camera/keep_alive            must arrive ≤ 3.0 s apart or the preview stops
 *
 * <h3>Networking — why raw sockets via the Network's SocketFactory</h3>
 * The GoPro AP (10.5.5.9) is reached over a {@link android.net.wifi.WifiNetworkSpecifier} Wi-Fi
 * network that has NO internet. Two earlier approaches both broke the simultaneous "video in over
 * Wi-Fi, broadcast out over mobile" use case:
 * <ul>
 *   <li>{@link Network#bindSocket(Socket)} / {@link Network#openConnection} → {@code EPERM} after
 *       a few seconds on the specifier network.</li>
 *   <li>{@link android.net.ConnectivityManager#bindProcessToNetwork(Network)} → works for the HTTP
 *       call, but it is <b>process-wide</b>: for the ~50 ms of each keep-alive (every 2.8 s) the
 *       <i>entire app</i> is pinned to the no-internet Wi-Fi, so pedroSG94's RTSP/SRT upload loses
 *       its route and the broadcast stalls. This is the "GoPro stream loses internet" bug.</li>
 * </ul>
 * {@link javax.net.SocketFactory} obtained from {@link Network#getSocketFactory()} creates a socket
 * already bound to the Wi-Fi network <b>without</b> EPERM (verified: the connectivity probe uses it)
 * and <b>without</b> touching the process default network. So the keep-alive/control traffic rides
 * the GoPro Wi-Fi while everything else (the RTSP/SRT broadcast) keeps using cellular — exactly how
 * ATAK's UAS Tool behaves.
 */
public final class GoProClient {

    private static final String TAG = "GoProClient";

    /** Open GoPro spec recommends keep-alive every 3.0 s. 2800 ms gives margin. */
    private static final long KEEP_ALIVE_INTERVAL_MS = 2800;
    private static final int TIMEOUT_MS = 4000;

    private final String host;
    private final int port;
    private final Network network;
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "gopro-keepalive");
                t.setDaemon(true);
                return t;
            });
    private ScheduledFuture<?> keepAliveTask;
    private final AtomicBoolean streaming = new AtomicBoolean(false);

    /** {@code ctx} is accepted for call-site compatibility; no longer used (no process binding). */
    public GoProClient(Context ctx, String host, int port, Network network) {
        this.host = host;
        this.port = port;
        this.network = network;
    }

    /** Open a socket bound to the GoPro Wi-Fi network (or the default network if none). */
    private Socket newSocket() throws IOException {
        return network != null ? network.getSocketFactory().createSocket() : new Socket();
    }

    /** Quick TCP probe to verify the GoPro HTTP server is reachable. */
    public boolean probe(int timeoutMs) {
        try (Socket s = newSocket()) {
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (IOException e) {
            Log.w(TAG, "probe failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Starts the UDP preview stream. The camera pushes MPEG-TS to the IP that issued this request.
     * {@code ?port=N} is honoured on HERO 12/13 / MAX 2 / LIT HERO; older HEROs ignore it (default 8554).
     */
    public void startPreview(int localUdpPort) throws IOException {
        get("/gopro/camera/stream/start?port=" + localUdpPort);
        streaming.set(true);
        if (keepAliveTask == null || keepAliveTask.isCancelled()) {
            keepAliveTask = scheduler.scheduleAtFixedRate(() -> {
                try { get("/gopro/camera/keep_alive"); }
                catch (Exception e) { Log.w(TAG, "keep_alive failed: " + e.getMessage()); }
            }, KEEP_ALIVE_INTERVAL_MS, KEEP_ALIVE_INTERVAL_MS, TimeUnit.MILLISECONDS);
        }
    }

    public void stopPreview() {
        streaming.set(false);
        if (keepAliveTask != null) {
            keepAliveTask.cancel(false);
            keepAliveTask = null;
        }
        try { get("/gopro/camera/stream/stop"); }
        catch (IOException e) { Log.w(TAG, "stream/stop failed: " + e.getMessage()); }
    }

    public boolean isStreaming() { return streaming.get(); }

    // ---- Camera control endpoints --------------------------------------------------------------

    /**
     * Start the shutter on the GoPro. In video mode this begins recording to the SD card; in photo
     * mode this captures a single photo. The recording is independent of the preview stream we're
     * pulling — recording to SD continues even while we keep ingesting the preview for broadcast.
     */
    public void shutterStart() throws IOException { get("/gopro/camera/shutter/start"); }

    /** Stop the shutter (ends SD recording in video mode). No-op for photo capture. */
    public void shutterStop() throws IOException { get("/gopro/camera/shutter/stop"); }

    /** Add a hilight tag at the current timestamp — a bookmark for later media review. */
    public void hilightAdd() throws IOException { get("/gopro/camera/hilight/add"); }

    public void shutdown() {
        stopPreview();
        scheduler.shutdownNow();
    }

    /**
     * Issues a GET over a raw, Wi-Fi-bound socket (minimal HTTP/1.1, {@code Connection: close}).
     * Returns the response body, or throws on non-2xx / IO error. Never changes the process network.
     */
    public String get(String path) throws IOException {
        Socket s = null;
        try {
            s = newSocket();
            s.connect(new InetSocketAddress(host, port), TIMEOUT_MS);
            s.setSoTimeout(TIMEOUT_MS);
            OutputStream os = s.getOutputStream();
            String req = "GET " + path + " HTTP/1.1\r\n"
                    + "Host: " + host + ":" + port + "\r\n"
                    + "User-Agent: SENTINEL\r\n"
                    + "Accept: */*\r\n"
                    + "Connection: close\r\n\r\n";
            os.write(req.getBytes(StandardCharsets.UTF_8));
            os.flush();

            InputStream in = s.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[2048];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            String full = new String(bos.toByteArray(), StandardCharsets.UTF_8);

            int firstLineEnd = full.indexOf("\r\n");
            String statusLine = firstLineEnd > 0 ? full.substring(0, firstLineEnd) : full;
            // Expect "HTTP/1.1 200 OK"
            if (!statusLine.contains(" 200")) {
                throw new IOException("GoPro " + path + " -> " + statusLine);
            }
            int bodyStart = full.indexOf("\r\n\r\n");
            return bodyStart >= 0 ? full.substring(bodyStart + 4) : "";
        } finally {
            if (s != null) { try { s.close(); } catch (Exception ignored) {} }
        }
    }
}
