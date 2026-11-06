package cn.MoeKoe.Music.playback;

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Base64;
import android.util.Log;

import java.io.ByteArrayOutputStream;

/**
 * Short microphone capture in the exact shape the recognition endpoint expects: 8 kHz, mono,
 * 16-bit PCM. Bounded at 20 seconds so a stuck page cannot record forever.
 */
public final class PcmRecorder {

    private static final String TAG = "MoePcmRecorder";
    public static final int SAMPLE_RATE = 8000;
    public static final int MAX_SECONDS = 20;
    private static final int MAX_BYTES = SAMPLE_RATE * 2 * MAX_SECONDS;
    private static final int MIN_BYTES = SAMPLE_RATE * 2 / 4;

    public static final class Result {
        public final String pcmBase64;
        public final int sampleRate;
        public final long durationMs;
        public final boolean cancelled;

        Result(String pcmBase64, int sampleRate, long durationMs, boolean cancelled) {
            this.pcmBase64 = pcmBase64;
            this.sampleRate = sampleRate;
            this.durationMs = durationMs;
            this.cancelled = cancelled;
        }
    }

    private AudioRecord record;
    private Thread thread;
    private volatile boolean recording;
    private ByteArrayOutputStream buffer;
    private long startedAt;

    public boolean isRecording() {
        return recording;
    }

    /** @throws IllegalStateException when the device has no usable microphone for this format */
    @SuppressLint("MissingPermission")
    public synchronized void start() {
        if (recording) throw new IllegalStateException("已在录音");
        int minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuffer <= 0) throw new IllegalStateException("设备不支持录音");
        record = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                Math.max(minBuffer, 4096));
        if (record.getState() != AudioRecord.STATE_INITIALIZED) {
            record.release();
            record = null;
            throw new IllegalStateException("麦克风初始化失败");
        }
        buffer = new ByteArrayOutputStream(MAX_BYTES / 2);
        startedAt = System.currentTimeMillis();
        recording = true;
        record.startRecording();
        thread = new Thread(this::pump, "moe-recorder");
        thread.setDaemon(true);
        thread.start();
    }

    private void pump() {
        byte[] chunk = new byte[1600];
        AudioRecord active = record;
        while (recording && active != null) {
            int read = active.read(chunk, 0, chunk.length);
            if (read <= 0) continue;
            synchronized (this) {
                if (buffer == null) break;
                int room = MAX_BYTES - buffer.size();
                if (room <= 0) break;
                buffer.write(chunk, 0, Math.min(read, room));
            }
        }
    }

    public synchronized Result stop(boolean cancel) {
        if (!recording && buffer == null) {
            return new Result("", SAMPLE_RATE, 0L, cancel);
        }
        recording = false;
        AudioRecord active = record;
        record = null;
        if (active != null) {
            try {
                active.stop();
            } catch (Exception error) {
                Log.w(TAG, "stop failed", error);
            }
            active.release();
        }
        Thread worker = thread;
        thread = null;
        if (worker != null) {
            try {
                worker.join(500L);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        }
        byte[] pcm = buffer == null ? new byte[0] : buffer.toByteArray();
        buffer = null;
        long durationMs = System.currentTimeMillis() - startedAt;
        if (cancel || pcm.length < MIN_BYTES) {
            return new Result("", SAMPLE_RATE, durationMs, true);
        }
        return new Result(Base64.encodeToString(pcm, Base64.NO_WRAP), SAMPLE_RATE, durationMs, false);
    }

    public synchronized void cancel() {
        stop(true);
    }
}
