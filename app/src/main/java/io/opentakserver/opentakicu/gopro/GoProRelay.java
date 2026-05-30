package io.opentakserver.opentakicu.gopro;

import android.content.Context;
import android.content.Intent;
import android.net.Network;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.Nullable;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Passthrough MPEG-TS UDP relay for the GoPro source.
 *
 * <p>Mirrors the architecture of the original ATAK plugin: instead of decoding the GoPro's
 * MPEG-TS preview and feeding it through an Android-side encoder, we just forward each TS
 * packet as it arrives to a user-configurable {@code host:port} (typically a MediaMTX server
 * with a path whose {@code source: udp://0.0.0.0:8000} listens for it).
 *
 * <p>The relay also issues the GoPro HTTP {@code /gopro/camera/stream/start} once the
 * receiver is bound, and schedules the obligatory keep-alive via {@link GoProClient}. Stop
 * tears everything down cleanly.
 *
 * <p>This bypasses {@link GoProSource} + pedroSG94's encoder entirely, so the GoPro stream
 * arrives at MediaMTX at native frame rate and quality with no transcoding latency.
 */
public class GoProRelay {

    private static final String TAG = "GoProRelay";

    private final Context ctx;
    private final int udpListenPort;
    private final InetSocketAddress target;
    @Nullable private final Network goproNetwork;
    @Nullable private final GoProClient gopro;

    private DatagramSocket recvSocket;
    private DatagramSocket sendSocket;
    private Thread relayThread;
    private volatile boolean running = false;

    public final AtomicLong packetsForwarded = new AtomicLong(0);
    public final AtomicLong bytesForwarded = new AtomicLong(0);

    private final Handler main = new Handler(Looper.getMainLooper());

    public GoProRelay(Context ctx, int udpListenPort, String targetHost, int targetPort,
                      @Nullable Network goproNetwork, @Nullable GoProClient gopro) throws IOException {
        this.ctx = ctx.getApplicationContext();
        this.udpListenPort = udpListenPort;
        this.target = new InetSocketAddress(InetAddress.getByName(targetHost), targetPort);
        this.goproNetwork = goproNetwork;
        this.gopro = gopro;
    }

    /** Start the relay. Opens the receive socket bound to the GoPro Wi-Fi, an outbound socket
     *  for forwarding, spins the relay thread, and tells the camera to start pushing. */
    public void start() {
        if (running) return;
        try {
            recvSocket = new DatagramSocket(null);
            recvSocket.setReuseAddress(true);
            recvSocket.setSoTimeout(1000);
            try { recvSocket.setReceiveBufferSize(2 * 1024 * 1024); } catch (Exception ignored) {}
            recvSocket.bind(new InetSocketAddress(udpListenPort));
            if (goproNetwork != null) {
                try { goproNetwork.bindSocket(recvSocket); }
                catch (Exception e) { Log.w(TAG, "bind recv to GoPro net: " + e.getMessage()); }
            }
            sendSocket = new DatagramSocket();
            try { sendSocket.setSendBufferSize(2 * 1024 * 1024); } catch (Exception ignored) {}
        } catch (Exception e) {
            Log.e(TAG, "Failed to open relay sockets", e);
            toast("Relay setup failed: " + e.getMessage());
            cleanup();
            return;
        }

        running = true;
        relayThread = new Thread(this::relayLoop, "gopro-relay");
        relayThread.setPriority(Thread.MAX_PRIORITY - 1);
        relayThread.start();

        // Ask the camera to start pushing MPEG-TS. Off-thread because it's an HTTP call.
        if (gopro != null) {
            new Thread(() -> {
                for (int attempt = 1; attempt <= 3 && running; attempt++) {
                    try {
                        gopro.startPreview(udpListenPort);
                        toast("Relay → " + target.getAddress().getHostAddress() + ":" + target.getPort());
                        return;
                    } catch (IOException e) {
                        Log.w(TAG, "stream/start attempt " + attempt + ": " + e.getMessage());
                        try { Thread.sleep(800L * attempt); }
                        catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
                    }
                }
                toast("GoPro stream/start failed — is the camera awake?");
            }, "gopro-http-relay-start").start();
        }
    }

    public void stop() {
        running = false;
        try { if (recvSocket != null) recvSocket.close(); } catch (Exception ignored) {}
        try { if (sendSocket != null) sendSocket.close(); } catch (Exception ignored) {}
        try { if (relayThread != null) relayThread.join(1500); } catch (InterruptedException ignored) {}
        if (gopro != null) {
            // Tell the camera to stop and cancel keep-alive — off thread again.
            new Thread(() -> { try { gopro.stopPreview(); } catch (Exception ignored) {} },
                    "gopro-http-relay-stop").start();
        }
        cleanup();
    }

    public boolean isRunning() { return running; }

    private void relayLoop() {
        byte[] buf = new byte[2048];
        DatagramPacket in = new DatagramPacket(buf, buf.length);
        long firstPacketAt = 0;
        while (running) {
            try {
                recvSocket.receive(in);
                if (firstPacketAt == 0) {
                    firstPacketAt = System.currentTimeMillis();
                    toast("Relay receiving (" + in.getLength() + " B from " + in.getAddress() + ")");
                }
                DatagramPacket out = new DatagramPacket(in.getData(), in.getLength(), target);
                try {
                    sendSocket.send(out);
                    packetsForwarded.incrementAndGet();
                    bytesForwarded.addAndGet(in.getLength());
                } catch (IOException e) {
                    // Don't toast every send error — would spam — just log.
                    if (running) Log.w(TAG, "send: " + e.getMessage());
                }
            } catch (SocketTimeoutException ignored) {
            } catch (IOException e) {
                if (running) Log.w(TAG, "recv: " + e.getMessage());
                break;
            }
        }
        Log.i(TAG, "relay loop ended, forwarded " + packetsForwarded.get() + " packets / "
                + bytesForwarded.get() + " bytes to " + target);
    }

    private void cleanup() {
        recvSocket = null;
        sendSocket = null;
        relayThread = null;
    }

    private void toast(String msg) {
        // Logcat only — relay diagnostics are no longer surfaced as on-screen toasts.
        Log.i(TAG, msg);
    }
}
