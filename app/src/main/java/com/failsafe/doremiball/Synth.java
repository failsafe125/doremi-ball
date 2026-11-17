package com.failsafe.doremiball;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Tiny tone generator for guide notes and sound effects. No audio assets. */
public final class Synth {

    private static final int SR = 22050;

    private final ExecutorService pool = Executors.newFixedThreadPool(3);
    public volatile boolean enabled = true;

    // What the speaker is doing right now, so the game can ignore its own sound in the mic.
    private volatile long busyUntilMs;
    private volatile float playingHz;   // 0 = mixed/unknown pitch

    public boolean isBusy() { return System.currentTimeMillis() < busyUntilMs; }

    /** Pitch currently coming out of the speaker, or 0 if it is a mix. */
    public float playingHz() { return playingHz; }

    /** One soft sine note. */
    public void tone(float hz, int ms, float vol) {
        notes(vol, hz, ms);
    }

    /** Sequence of notes: pairs of (hz, ms). hz <= 0 is a rest. */
    public void notes(float vol, float... hzMs) {
        if (!enabled || hzMs.length < 2) return;
        int total = 0;
        for (int i = 1; i < hzMs.length; i += 2) total += (int) (hzMs[i] * SR / 1000f);
        short[] pcm = new short[total];
        int pos = 0;
        for (int i = 0; i + 1 < hzMs.length; i += 2) {
            float hz = hzMs[i];
            int n = (int) (hzMs[i + 1] * SR / 1000f);
            if (hz > 0f) {
                int attack = Math.min(n / 4, SR / 120);
                for (int k = 0; k < n; k++) {
                    float tt = k / (float) SR;
                    float env = k < attack ? k / (float) attack : (float) Math.exp(-3.0 * (k - attack) / n);
                    if (n - k < 64) env *= (n - k) / 64f;
                    double ph = 2 * Math.PI * hz * tt;
                    float s = (float) (Math.sin(ph) + 0.25 * Math.sin(2 * ph) + 0.08 * Math.sin(3 * ph));
                    pcm[pos + k] = (short) (s * env * vol * 22000f);
                }
            }
            pos += n;
        }
        int ms = (int) (total * 1000L / SR);
        playingHz = hzMs.length == 2 ? hzMs[0] : 0f;
        busyUntilMs = System.currentTimeMillis() + ms + 90;
        play(pcm, ms);
    }

    /** Falling thud for crashing into a board. */
    public void thud() {
        if (!enabled) return;
        int n = SR * 240 / 1000;
        short[] pcm = new short[n];
        double ph = 0;
        java.util.Random r = new java.util.Random(7);
        for (int k = 0; k < n; k++) {
            float p = k / (float) n;
            float hz = 170f - 110f * p;
            ph += 2 * Math.PI * hz / SR;
            float env = (1f - p) * (1f - p);
            float s = (float) Math.sin(ph) * 0.8f + (r.nextFloat() - 0.5f) * 0.5f * (1f - p);
            pcm[k] = (short) (s * env * 20000f);
        }
        playingHz = 0f;
        busyUntilMs = System.currentTimeMillis() + 240 + 90;
        play(pcm, 240);
    }

    private void play(final short[] pcm, final int ms) {
        try {
            pool.execute(() -> {
                AudioTrack track = null;
                try {
                    track = new AudioTrack.Builder()
                            .setAudioAttributes(new AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_GAME)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                    .build())
                            .setAudioFormat(new AudioFormat.Builder()
                                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                    .setSampleRate(SR)
                                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                    .build())
                            .setBufferSizeInBytes(pcm.length * 2)
                            .setTransferMode(AudioTrack.MODE_STATIC)
                            .build();
                    track.write(pcm, 0, pcm.length);
                    track.play();
                    Thread.sleep(ms + 60);
                } catch (Throwable ignored) {
                } finally {
                    if (track != null) {
                        try { track.release(); } catch (Throwable ignored) { }
                    }
                }
            });
        } catch (Throwable ignored) { }
    }

    public void shutdown() {
        pool.shutdownNow();
    }
}
