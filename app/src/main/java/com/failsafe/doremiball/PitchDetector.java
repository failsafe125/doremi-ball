package com.failsafe.doremiball;

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

/**
 * Mic capture + YIN pitch tracker.
 * Reads 44.1 kHz mono, decimates by 2, runs YIN over a 1024-sample window
 * every 512 raw samples (~86 estimates per second).
 */
public final class PitchDetector {

    private static final int SR = 44100;
    private static final int RAW_HOP = 512;
    private static final int DS = SR / 2;          // decimated rate
    private static final int WINDOW = 1024;        // decimated samples
    private static final int HOP = RAW_HOP / 2;    // decimated samples per hop
    private static final float MIN_HZ = 70f;
    private static final float MAX_HZ = 1100f;
    private static final float YIN_THRESHOLD = 0.15f;
    private static final float RMS_GATE = 0.008f;

    private volatile boolean running;
    private volatile boolean failed;
    private volatile float pitchHz;
    private volatile float level;
    private volatile Thread thread;

    /** Latest pitch in Hz, 0 when nothing singable is heard. */
    public float getPitch() { return pitchHz; }

    /** Latest RMS level, 0..1. */
    public float getLevel() { return level; }

    public boolean hasFailed() { return failed; }

    public synchronized void start() {
        if (running) return;
        running = true;
        failed = false;
        thread = new Thread(this::loop, "pitch-detector");
        thread.setPriority(Thread.MAX_PRIORITY);
        thread.start();
    }

    public synchronized void stop() {
        running = false;
        Thread t = thread;
        thread = null;
        if (t != null) {
            try { t.join(500); } catch (InterruptedException ignored) { }
        }
        pitchHz = 0f;
        level = 0f;
    }

    @SuppressLint("MissingPermission")
    private void loop() {
        AudioRecord rec = null;
        try {
            int minBuf = AudioRecord.getMinBufferSize(SR,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            int bufBytes = Math.max(minBuf, RAW_HOP * 8);
            rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SR,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufBytes);
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
                rec.release();
                rec = new AudioRecord(MediaRecorder.AudioSource.MIC, SR,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufBytes);
            }
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
                failed = true;
                return;
            }
            rec.startRecording();

            final int tauMin = (int) (DS / MAX_HZ);
            final int tauMax = (int) (DS / MIN_HZ);
            final int integ = WINDOW - tauMax;
            short[] raw = new short[RAW_HOP];
            float[] win = new float[WINDOW];
            float[] d = new float[tauMax + 2];
            float[] recent = new float[3];

            while (running) {
                int got = 0;
                while (got < RAW_HOP && running) {
                    int n = rec.read(raw, got, RAW_HOP - got);
                    if (n <= 0) { failed = n < 0; break; }
                    got += n;
                }
                if (got < RAW_HOP) {
                    if (failed) break;
                    continue;
                }

                System.arraycopy(win, HOP, win, 0, WINDOW - HOP);
                double sum = 0;
                int base = WINDOW - HOP;
                for (int i = 0; i < HOP; i++) {
                    float v = (raw[2 * i] + raw[2 * i + 1]) / 65536f;
                    win[base + i] = v;
                    sum += v * v;
                }
                float rms = (float) Math.sqrt(sum / HOP);
                level = rms;

                float f = rms < RMS_GATE ? 0f : yin(win, d, tauMin, tauMax, integ);

                recent[0] = recent[1];
                recent[1] = recent[2];
                recent[2] = f;
                if (f <= 0f) {
                    pitchHz = 0f;
                } else if (recent[0] > 0f && recent[1] > 0f) {
                    pitchHz = median(recent[0], recent[1], recent[2]);
                } else {
                    pitchHz = f;
                }
            }
        } catch (Throwable t) {
            failed = true;
        } finally {
            if (Thread.currentThread() == thread) running = false;   // lets start() retry after a failure
            pitchHz = 0f;
            level = 0f;
            if (rec != null) {
                try { rec.stop(); } catch (Throwable ignored) { }
                rec.release();
            }
        }
    }

    private static float median(float a, float b, float c) {
        return Math.max(Math.min(a, b), Math.min(Math.max(a, b), c));
    }

    private static float yin(float[] x, float[] d, int tauMin, int tauMax, int integ) {
        // difference function
        for (int tau = 1; tau <= tauMax; tau++) {
            float s = 0f;
            for (int j = 0; j < integ; j++) {
                float diff = x[j] - x[j + tau];
                s += diff * diff;
            }
            d[tau] = s;
        }
        // cumulative mean normalised difference
        float running = 0f;
        d[0] = 1f;
        for (int tau = 1; tau <= tauMax; tau++) {
            running += d[tau];
            d[tau] = running > 0f ? d[tau] * tau / running : 1f;
        }
        // first dip under the threshold, walked down to its local minimum
        int best = -1;
        for (int tau = tauMin; tau < tauMax; tau++) {
            if (d[tau] < YIN_THRESHOLD) {
                while (tau + 1 < tauMax && d[tau + 1] < d[tau]) tau++;
                best = tau;
                break;
            }
        }
        if (best < 0) return 0f;

        // parabolic interpolation around the minimum
        float t = best;
        if (best > 1 && best < tauMax) {
            float a = d[best - 1], b = d[best], c = d[best + 1];
            float den = a - 2f * b + c;
            if (Math.abs(den) > 1e-9f) t = best + 0.5f * (a - c) / den;
        }
        float hz = DS / t;
        return (hz < MIN_HZ || hz > MAX_HZ) ? 0f : hz;
    }
}
