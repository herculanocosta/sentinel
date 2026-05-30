package io.opentakserver.opentakicu.gopro;

import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.util.Log;

import com.pedro.encoder.Frame;
import com.pedro.encoder.input.audio.GetMicrophoneData;
import com.pedro.encoder.input.sources.audio.AudioSource;

import java.nio.ByteBuffer;

/**
 * Pulls AAC ADTS audio frames out of the GoPro's MPEG-TS preview stream (via
 * {@link GoProSource#setAudioSink(GoProSource.AudioFrameSink)}) and feeds decoded PCM to the
 * pedroSG94 audio pipeline. The pipeline then re-encodes the PCM in whatever audio codec
 * the user picked (AAC / Opus / G.711) and pushes it out on the same RTSP/RTMP session as
 * the video.
 *
 * GoPro preview audio is — across all current firmwares — AAC LC, 48 kHz, stereo. We hard-code
 * those parameters when configuring the decoder; if a future firmware changes them we'll see
 * MediaCodec errors and can switch to parsing the ADTS header per-frame for sample-rate /
 * channel-config and rebuilding the {@code csd-0} ASC bytes on the fly.
 */
public class GoProAudioSource extends AudioSource implements GoProSource.AudioFrameSink {

    private static final String TAG = "GoProAudioSource";

    /** AudioSpecificConfig bytes for AAC LC / 48000 Hz / stereo. profile=2, sr_idx=3, ch_cfg=2. */
    private static final byte[] AAC_LC_48K_STEREO_ASC = new byte[]{ 0x11, (byte) 0x90 };

    private MediaCodec decoder;
    private GetMicrophoneData callback;
    private volatile boolean running = false;

    @Override
    protected boolean create(int sampleRate, boolean stereo, boolean echoCanceler, boolean noiseSuppressor) {
        // GoPro audio is fixed format — ignore the caller's preferences and announce reality.
        setSampleRate(48000);
        setStereo(true);
        return true;
    }

    @Override
    public void start(GetMicrophoneData cb) {
        this.callback = cb;
        try {
            decoder = MediaCodec.createDecoderByType("audio/mp4a-latm");
            MediaFormat fmt = MediaFormat.createAudioFormat("audio/mp4a-latm", 48000, 2);
            fmt.setByteBuffer("csd-0", ByteBuffer.wrap(AAC_LC_48K_STEREO_ASC));
            decoder.configure(fmt, null, null, 0);
            decoder.start();
            running = true;
        } catch (Exception e) {
            Log.e(TAG, "AAC decoder configure failed", e);
            running = false;
            return;
        }
        // Attach to the live MPEG-TS demuxer in GoProSource.
        GoProSource.setAudioSink(this);
        Log.i(TAG, "GoPro AAC decoder started (48 kHz / stereo / LC)");
    }

    @Override
    public void onAdtsFrame(byte[] data, int off, int len) {
        if (!running || decoder == null || callback == null) return;
        // MediaCodec("audio/mp4a-latm") accepts raw AAC frames (no ADTS header). Strip the header:
        //   bit 1 of byte 1 = "protection_absent" — when 0, an extra 2-byte CRC follows.
        int headerLen = ((data[off + 1] & 0x1) == 0) ? 9 : 7;
        if (len <= headerLen) return;
        try {
            int idx = decoder.dequeueInputBuffer(20_000);
            if (idx < 0) return;
            ByteBuffer ib = decoder.getInputBuffer(idx);
            if (ib == null) return;
            ib.clear();
            ib.put(data, off + headerLen, len - headerLen);
            long ptsUs = System.nanoTime() / 1000;
            decoder.queueInputBuffer(idx, 0, len - headerLen, ptsUs, 0);

            // Drain any decoded PCM frames and push them into the pedroSG94 audio pipeline.
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int outIdx;
            while ((outIdx = decoder.dequeueOutputBuffer(info, 0)) >= 0) {
                ByteBuffer ob = decoder.getOutputBuffer(outIdx);
                if (ob != null && info.size > 0 && callback != null) {
                    byte[] pcm = new byte[info.size];
                    ob.position(info.offset);
                    ob.get(pcm, 0, info.size);
                    Frame f = new Frame(pcm, info.size, AudioFormat.ENCODING_PCM_16BIT, ptsUs);
                    try { callback.inputPCMData(f); }
                    catch (Exception cbEx) { Log.w(TAG, "callback rejected PCM", cbEx); }
                }
                decoder.releaseOutputBuffer(outIdx, false);
            }
        } catch (IllegalStateException e) {
            // Codec moved to error state — most likely the stream rebooted. Try a clean restart.
            Log.w(TAG, "decoder ISE, restarting", e);
            try { decoder.flush(); }
            catch (Exception ignored) {
                try { decoder.stop(); decoder.release(); } catch (Exception ignored2) {}
                decoder = null;
                running = false;
            }
        } catch (Throwable t) {
            Log.e(TAG, "onAdtsFrame failed", t);
        }
    }

    @Override
    public void stop() {
        running = false;
        if (GoProSource.getAudioSink() == this) GoProSource.setAudioSink(null);
        if (decoder != null) {
            try { decoder.stop(); } catch (Exception ignored) {}
            try { decoder.release(); } catch (Exception ignored) {}
            decoder = null;
        }
    }

    @Override
    public void release() {
        stop();
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
