package com.failsafe.doremiball;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.Random;

/**
 * The whole game: boards with a hole scroll in from the right, the ball's height
 * follows the pitch you sing, and you have to be on the right note to fit through.
 */
public final class GameView extends View {

    public interface Host {
        void requestMic();
    }

    // ---- music ----
    private static final int[] SCALE = {0, 2, 4, 5, 7, 9, 11, 12};
    private static final String[] NAMES = {"Do", "Re", "Mi", "Fa", "Sol", "La", "Ti", "Do"};
    private static final int[] COLORS = {
            0xFFFF5252, 0xFFFF9F43, 0xFFFFD93D, 0xFF6BCB77,
            0xFF4D96FF, 0xFF7C6CFF, 0xFFE06CFF, 0xFFFF5252};
    private static final String[] PITCH_NAMES =
            {"C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"};
    private static final float S_MIN = -2.5f, S_MAX = 14.5f;   // semitone range shown on screen

    // Depth. z = 1 is the plane the ball lives on; boards spawn at Z_FAR and fly past the camera.
    private static final float Z_FAR = 5f, Z_GONE = 0.55f, Z_MARGIN = 0.02f;

    private enum State { MENU, TUNE, INTRO, PLAY, OVER }

    // ---- level definition ----
    private static final class Level {
        String name = "", flair = "";
        int[] pool = {0, 1, 2};
        int[] melody;
        int count = 8;
        float speed = 0.20f;   // screen widths per second
        float gap = 3.4f;      // seconds between boards
        float tol = 1.5f;      // hole half-height in semitones
        boolean stars, slide, tunnel, door, pairs, shrink, disco;
    }

    private static final class Wall {
        float z, dz;            // front face depth and thickness
        int deg;
        float s0, s, tol0, tol;
        boolean slide, door, shrink;
        float phase, open = 1f;
        boolean passed, entered, bumped;
        float errSum;
        int errN;
    }

    private static final class Star {
        float z, s, spin;
        boolean taken, missed;
    }

    private static final class Particle {
        float x, y, vx, vy, life, max, size;
        int color;
    }

    private static final class Pop {
        float x, y, t;
        String text;
        int color;
    }

    private static final class Button {
        final RectF r = new RectF();
        String label;
        int id;
        int color;
    }

    private static final int B_START = 1, B_TUNE = 2, B_SOUND = 3, B_MIC = 4, B_AGAIN = 5,
            B_MENU = 6, B_CANCEL = 7;

    // ---- plumbing ----
    private final PitchDetector detector;
    private final Synth synth;
    private final Host host;
    private final SharedPreferences prefs;
    private final Random rnd = new Random();
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Path path2 = new Path();
    private final Paint sp = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float[] hsv = new float[3];

    private boolean running;
    private boolean micGranted;
    private long lastNanos;
    private float time;

    // ---- layout ----
    private float w, h, playTop, playBottom, semPx, ballX, ballR;
    private float vx, vy, wallL, wallR, scroll;   // vanishing point, board edges on the ball plane

    // ---- state ----
    private State state = State.MENU;
    private float tonicHz;
    private int best;
    private int score, lives, combo, levelNo;
    private Level lv = new Level();
    private int spawned, done, lastDeg = -1;
    private float spawnTimer, endTimer, introT, shake, flash;
    private boolean startAfterTune;
    private float stuckT;
    private Wall stuckWall;

    private float bx, ballY, ballVy, silentT = 9f;   // bx = where the ball is now, ballX = its home spot
    private boolean voiced;
    private final float[] trail = new float[16];
    private int trailHead;
    private float trailAcc;

    private float tuneHold, tuneRef, tuneLogSum, tuneHz;
    private int tuneN;

    private final ArrayList<Wall> walls = new ArrayList<>();
    private final ArrayList<Star> stars = new ArrayList<>();
    private final ArrayList<Particle> parts = new ArrayList<>();
    private final ArrayList<Pop> pops = new ArrayList<>();
    private final ArrayList<Button> buttons = new ArrayList<>();

    public GameView(Context c, PitchDetector detector, Synth synth, Host host) {
        super(c);
        this.detector = detector;
        this.synth = synth;
        this.host = host;
        prefs = c.getSharedPreferences("doremi", Context.MODE_PRIVATE);
        tonicHz = prefs.getFloat("tonic", 0f);
        best = prefs.getInt("best", 0);
        synth.enabled = prefs.getBoolean("sound", true);
        tp.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        tp.setTextAlign(Paint.Align.CENTER);
        setHapticFeedbackEnabled(true);
    }

    public void setMicGranted(boolean granted) {
        micGranted = granted;
    }

    public void setRunning(boolean r) {
        running = r;
        lastNanos = 0;
        if (r) invalidate();
    }

    /** Back button: leave a game or the tuner for the menu. Returns false to let the app close. */
    public boolean onBack() {
        if (state == State.MENU) return false;
        saveBest();
        state = State.MENU;
        return true;
    }

    @Override
    protected void onSizeChanged(int nw, int nh, int ow, int oh) {
        w = nw;
        h = nh;
        playTop = h * 0.17f;
        playBottom = h * 0.94f;
        semPx = (playBottom - playTop) / (S_MAX - S_MIN);
        ballX = w * 0.60f;
        vx = w * 0.40f;
        vy = h * 0.38f;
        wallL = w * 0.29f;
        wallR = w * 0.91f;
        ballR = w * 0.045f;
        bx = ballX;
        ballY = playBottom - ballR;
        for (int i = 0; i < trail.length; i++) trail[i] = ballY;
    }

    /** Perspective: a point on the ball plane shrinks toward the vanishing point with depth. */
    private float px(float x, float z) { return vx + (x - vx) / z; }
    private float py(float y, float z) { return vy + (y - vy) / z; }

    private float zSpeed() { return (Z_FAR - 1f) / (0.8f / lv.speed); }

    private static float clamp01(float v) { return v < 0f ? 0f : (v > 1f ? 1f : v); }

    private float yFor(float s) {
        return playBottom - (s - S_MIN) * semPx;
    }

    private float noteHz(int deg) {
        return tonicHz * (float) Math.pow(2.0, SCALE[deg] / 12.0);
    }

    private static String pitchName(float hz) {
        if (hz <= 0) return "--";
        int midi = Math.round(69f + 12f * (float) (Math.log(hz / 440.0) / Math.log(2.0)));
        return PITCH_NAMES[((midi % 12) + 12) % 12] + (midi / 12 - 1);
    }

    // =====================================================================
    // levels
    // =====================================================================

    private static int[] range(int n) {
        int[] a = new int[n];
        for (int i = 0; i < n; i++) a[i] = i;
        return a;
    }

    private Level makeLevel(int n) {
        Level l = new Level();
        switch (n) {
            case 1:
                l.name = "Do  Re  Mi";
                l.flair = "Sing the note on the board to lift the ball through the hole. The board waits until you hit it";
                break;
            case 2:
                l.name = "Up to Sol";
                l.flair = "Two new notes, and stars to grab on the way";
                l.pool = range(5); l.count = 10; l.speed = 0.22f; l.gap = 3.1f; l.tol = 1.4f;
                l.stars = true;
                break;
            case 3:
                l.name = "The Whole Ladder";
                l.flair = "All eight notes, low Do to high Do";
                l.pool = range(8); l.count = 10; l.speed = 0.23f; l.gap = 3.0f; l.tol = 1.3f;
                l.stars = true;
                break;
            case 4:
                l.name = "Sliders";
                l.flair = "The holes drift. Bend your note to follow them";
                l.pool = range(8); l.count = 10; l.speed = 0.23f; l.gap = 3.0f; l.tol = 1.3f;
                l.slide = true;
                break;
            case 5:
                l.name = "Twinkle";
                l.flair = "The boards are a tune now. Sing it!";
                l.melody = new int[]{0, 0, 4, 4, 5, 5, 4, 3, 3, 2, 2, 1, 1, 0};
                l.count = l.melody.length; l.speed = 0.26f; l.gap = 1.9f; l.tol = 1.35f;
                break;
            case 6:
                l.name = "Tunnels";
                l.flair = "Long boards. Hold the note all the way through";
                l.pool = range(6); l.count = 8; l.speed = 0.24f; l.gap = 3.6f; l.tol = 1.25f;
                l.tunnel = true;
                break;
            case 7:
                l.name = "Trapdoors";
                l.flair = "Holes open at the last second. Trust the name on the board";
                l.pool = range(8); l.count = 10; l.speed = 0.24f; l.gap = 2.8f; l.tol = 1.3f;
                l.door = true; l.stars = true;
                break;
            case 8:
                l.name = "Leaps";
                l.flair = "Boards come in pairs, far apart. Jump!";
                l.pool = range(8); l.count = 12; l.speed = 0.25f; l.gap = 3.4f; l.tol = 1.3f;
                l.pairs = true;
                break;
            case 9:
                l.name = "Squeeze";
                l.flair = "The holes shrink as they come at you";
                l.pool = range(8); l.count = 10; l.speed = 0.25f; l.gap = 2.8f; l.tol = 1.5f;
                l.shrink = true; l.stars = true;
                break;
            case 10:
                l.name = "Ode to Joy";
                l.flair = "Big finish. Lights on!";
                l.melody = new int[]{2, 2, 3, 4, 4, 3, 2, 1, 0, 0, 1, 2, 2, 1, 1};
                l.count = l.melody.length; l.speed = 0.28f; l.gap = 1.7f; l.tol = 1.25f;
                l.disco = true;
                break;
            default: {
                int k = n - 10;
                l.name = "Chaos " + k;
                l.pool = range(8); l.count = 12;
                l.speed = Math.min(0.40f, 0.26f + 0.015f * k);
                l.gap = Math.max(1.8f, 2.8f - 0.1f * k);
                l.tol = Math.max(0.9f, 1.25f - 0.03f * k);
                l.stars = true; l.disco = true;
                StringBuilder sb = new StringBuilder();
                int a = rnd.nextInt(5), b = (a + 1 + rnd.nextInt(4)) % 5;
                for (int m : new int[]{a, b}) {
                    if (sb.length() > 0) sb.append(" + ");
                    switch (m) {
                        case 0: l.slide = true; sb.append("sliders"); break;
                        case 1: l.door = true; sb.append("trapdoors"); break;
                        case 2: l.shrink = true; sb.append("squeeze"); break;
                        case 3: l.tunnel = true; l.gap += 0.8f; sb.append("tunnels"); break;
                        default: l.pairs = true; l.gap += 0.6f; sb.append("leaps"); break;
                    }
                }
                l.flair = "Everything at once: " + sb + ", and faster";
            }
        }
        return l;
    }

    private void newGame() {
        score = 0;
        lives = 3;
        combo = 0;
        levelNo = 0;
        walls.clear();
        stars.clear();
        pops.clear();
        bx = ballX;
        stuckWall = null;
        beginLevel(1);
    }

    private void beginLevel(int n) {
        levelNo = n;
        lv = makeLevel(n);
        spawned = 0;
        done = 0;
        lastDeg = -1;
        endTimer = 0;
        introT = n == 1 ? 3.6f : 3.0f;
        state = State.INTRO;
    }

    private void spawnWall() {
        int deg;
        if (lv.melody != null) {
            deg = lv.melody[spawned];
        } else if (lv.pairs && spawned % 2 == 1 && lastDeg >= 0) {
            // second board of a pair: at least a third away
            do { deg = lv.pool[rnd.nextInt(lv.pool.length)]; } while (Math.abs(deg - lastDeg) < 3);
        } else {
            do { deg = lv.pool[rnd.nextInt(lv.pool.length)]; } while (deg == lastDeg);
        }
        lastDeg = deg;

        Wall wl = new Wall();
        float vz = zSpeed();
        wl.z = Z_FAR;
        wl.dz = lv.tunnel ? vz * 1.4f : vz * 0.15f;
        wl.deg = deg;
        wl.s0 = wl.s = SCALE[deg];
        wl.tol0 = wl.tol = lv.tol;
        wl.slide = lv.slide;
        wl.door = lv.door;
        wl.shrink = lv.shrink;
        wl.phase = rnd.nextFloat() * 6.28f;
        if (wl.door) wl.open = 0f;
        walls.add(wl);
        spawned++;

        float gap = lv.gap;
        if (lv.pairs) gap = spawned % 2 == 1 ? (lv.tunnel ? 2.6f : 1.35f) : lv.gap;
        if (lv.melody != null && levelNo == 5 && spawned == 7) gap *= 1.7f;   // breath between phrases
        spawnTimer = gap;

        if (lv.stars && spawned < lv.count && rnd.nextFloat() < 0.6f && !(lv.pairs && spawned % 2 == 1)) {
            Star st = new Star();
            st.z = Z_FAR + gap * 0.5f * vz;
            st.s = SCALE[lv.pool[rnd.nextInt(lv.pool.length)]];
            stars.add(st);
        }

        if (tonicHz > 0) synth.tone(noteHz(deg), 260, 0.22f);
    }

    // =====================================================================
    // update
    // =====================================================================

    private void update(float dt) {
        time += dt;
        if (shake > 0) shake = Math.max(0, shake - dt);
        if (flash > 0) flash = Math.max(0, flash - dt * 2.5f);

        updateBall(dt);

        switch (state) {
            case TUNE:
                updateTune(dt);
                break;
            case INTRO:
                introT -= dt;
                // boards left over from the last level keep flying past
                scroll += zSpeed() * dt;
                for (Iterator<Wall> it = walls.iterator(); it.hasNext(); ) {
                    Wall wl = it.next();
                    wl.z -= zSpeed() * dt;
                    if (wl.z + wl.dz < Z_GONE) it.remove();
                }
                stars.clear();
                if (introT <= 0) {
                    state = State.PLAY;
                    spawnTimer = 0.2f;
                }
                break;
            case PLAY:
                updatePlay(dt);
                break;
            default:
                break;
        }

        for (Iterator<Particle> it = parts.iterator(); it.hasNext(); ) {
            Particle q = it.next();
            q.life -= dt;
            if (q.life <= 0) { it.remove(); continue; }
            q.vy += h * 0.9f * dt;
            q.x += q.vx * dt;
            q.y += q.vy * dt;
        }
        for (Iterator<Pop> it = pops.iterator(); it.hasNext(); ) {
            Pop o = it.next();
            o.t += dt;
            o.y -= h * 0.06f * dt;
            if (o.t > 1.1f) it.remove();
        }
    }

    private void updateBall(float dt) {
        float f = micGranted ? detector.getPitch() : 0f;
        boolean hold = false;

        // Don't let the game's own speaker sounds steer the ball.
        if (f > 0 && synth.isBusy()) {
            float sp = synth.playingHz();
            if (sp <= 0) {
                hold = true;
            } else {
                float diff = 12f * (float) (Math.log(f / sp) / Math.log(2.0));
                diff -= 12f * Math.round(diff / 12f);
                if (Math.abs(diff) < 0.8f && !voiced) hold = true;
            }
        }

        float floor = playBottom - ballR;
        if (hold) {
            ballVy = 0;
        } else if (f > 0 && tonicHz > 0) {
            float s = 12f * (float) (Math.log(f / tonicHz) / Math.log(2.0));
            if (s < S_MIN - 1.5f) s += 12f;          // sang an octave under: fold up
            else if (s > S_MAX + 1.5f) s -= 12f;     // an octave over: fold down
            s = Math.max(S_MIN + 0.4f, Math.min(S_MAX - 0.4f, s));
            float target = yFor(s);
            ballY += (target - ballY) * (1f - (float) Math.exp(-dt * 16f));
            ballVy = 0;
            silentT = 0;
            voiced = true;
        } else {
            silentT += dt;
            if (silentT > 0.12f) {
                voiced = false;
                ballVy += (playBottom - playTop) * 2.4f * dt;
                ballY += ballVy * dt;
                if (ballY > floor) {
                    ballY = floor;
                    ballVy = ballVy > h * 0.25f ? -ballVy * 0.32f : 0;
                }
            }
        }
        if (ballY > floor) ballY = floor;
        if (ballY < playTop + ballR) ballY = playTop + ballR;

        // roll back to the home spot after a board has shoved the ball left
        if (bx < ballX) {
            bx += Math.min((ballX - bx) * dt * 6f + w * 0.03f * dt, w * 0.9f * dt);
            if (bx > ballX) bx = ballX;
        }

        trailAcc += dt;
        if (trailAcc > 0.016f) {
            trailAcc = 0;
            trailHead = (trailHead + 1) % trail.length;
            trail[trailHead] = ballY;
        }
    }

    private int nearestDeg(float s) {
        int bestI = -1;
        float bestD = 0.75f;
        for (int i = 0; i < SCALE.length; i++) {
            float d = Math.abs(s - SCALE[i]);
            if (d < bestD) { bestD = d; bestI = i; }
        }
        return bestI;
    }

    private float ballS() {
        return S_MIN + (playBottom - ballY) / semPx;
    }

    private void updateTune(float dt) {
        float f = micGranted ? detector.getPitch() : 0f;
        if (synth.isBusy()) f = 0;
        tuneHz = f;
        if (f <= 0) {
            tuneHold = Math.max(0, tuneHold - dt * 2f);
            if (tuneHold == 0) { tuneN = 0; tuneLogSum = 0; }
            return;
        }
        float st = 12f * (float) (Math.log(f / 100.0) / Math.log(2.0));
        if (tuneN == 0 || Math.abs(st - tuneRef) > 0.7f) {
            tuneRef = st;
            tuneHold = 0;
            tuneN = 0;
            tuneLogSum = 0;
        }
        tuneRef += (st - tuneRef) * 0.1f;
        tuneHold += dt;
        tuneLogSum += (float) Math.log(f);
        tuneN++;
        if (tuneHold >= 1.3f) {
            float hz = (float) Math.exp(tuneLogSum / tuneN);
            hz = Math.max(75f, Math.min(420f, hz));
            tonicHz = hz;
            prefs.edit().putFloat("tonic", hz).apply();
            tuneHold = 0;
            tuneN = 0;
            tuneLogSum = 0;
            // play their scale back so they hear the ladder
            float[] seq = new float[16];
            for (int i = 0; i < 8; i++) { seq[2 * i] = noteHz(i); seq[2 * i + 1] = 150; }
            synth.notes(0.22f, seq);
            if (startAfterTune) newGame(); else state = State.MENU;
        }
    }

    private void updatePlay(float dt) {
        float vz = zSpeed();

        // Boards are solid and patient: if the ball is not lined up with the hole when a
        // board reaches it, everything stops and waits until the right note is sung.
        Wall blocker = null;
        for (Wall wl : walls) {
            if (wl.passed) continue;
            if (wl.z - vz * dt > 1f + Z_MARGIN || wl.z + wl.dz < 1f - Z_MARGIN) continue;
            float holeY = yFor(wl.s);
            float allowed = Math.max(ballR * 0.5f, wl.tol * semPx - ballR * 0.55f);
            float dy = ballY - holeY;
            boolean inBand = Math.abs(dy) <= allowed;
            if (!wl.entered) {
                if (inBand) wl.entered = true; else blocker = wl;
            } else if (!inBand) {
                ballY = holeY + Math.signum(dy) * allowed;   // the wood around the hole is solid too
                ballVy = 0;
                blocker = wl;
            }
            if (wl.entered) {
                wl.errSum += Math.min(Math.abs(dy), allowed);
                wl.errN++;
            }
        }
        if (blocker != null) {
            bump(blocker);
            vz = 0;
            if (stuckWall != blocker) { stuckWall = blocker; stuckT = 0; }
            stuckT += dt;
            // replay the note every couple of seconds as a hint
            if (stuckT > 1.2f && tonicHz > 0 && !synth.isBusy()
                    && ((int) (stuckT / 2.2f)) != ((int) ((stuckT - dt) / 2.2f))) {
                synth.tone(noteHz(blocker.deg), 420, 0.24f);
            }
        } else {
            stuckWall = null;
            stuckT = 0;
            spawnTimer -= dt;
        }
        scroll += vz * dt;
        if (spawned < lv.count && spawnTimer <= 0) spawnWall();

        for (Iterator<Wall> it = walls.iterator(); it.hasNext(); ) {
            Wall wl = it.next();
            wl.z -= vz * dt;
            if (wl.slide) wl.s = wl.s0 + (float) Math.sin(time * 1.7f + wl.phase) * 1.0f;
            if (wl.door) {
                float tgt = wl.z < 2.3f ? 1f : 0f;
                wl.open += (tgt - wl.open) * Math.min(1f, dt * 9f);
            }
            if (wl.shrink) {
                float prog = clamp01((Z_FAR - wl.z) / (Z_FAR - 1f));
                wl.tol = wl.tol0 + (0.85f - wl.tol0) * prog;
            }
            if (!wl.passed && wl.z + wl.dz < 1f - Z_MARGIN) pass(wl);
            if (wl.z + wl.dz < Z_GONE) it.remove();
        }

        for (Iterator<Star> it = stars.iterator(); it.hasNext(); ) {
            Star st = it.next();
            st.z -= vz * dt;
            st.spin += dt * 2.5f;
            if (!st.taken && !st.missed && st.z <= 1f) {
                if (Math.abs(yFor(st.s) - ballY) < ballR * 2.4f) {
                    st.taken = true;
                    score += 5;
                    burst(bx, yFor(st.s), 0xFFFFE066, 10, 0.5f);
                    pop(bx, yFor(st.s) - ballR * 2, "+5", 0xFFFFE066);
                    synth.tone(1568f, 70, 0.12f);
                } else {
                    st.missed = true;
                }
            }
            if (st.taken || st.z < Z_GONE) it.remove();
        }

        if (state == State.PLAY && done >= lv.count) {
            endTimer += dt;
            if (endTimer > 0.9f) {
                score += 25;
                saveBest();
                float d = noteHz(0);
                synth.notes(0.2f, d, 90, d * 1.26f, 90, d * 1.5f, 90, d * 2f, 220);
                for (int i = 0; i < 5; i++) {
                    burst(w * (0.15f + 0.7f * rnd.nextFloat()), h * (0.25f + 0.4f * rnd.nextFloat()),
                            COLORS[rnd.nextInt(7)], 14, 0.9f);
                }
                beginLevel(levelNo + 1);
            }
        }
    }

    /** First touch of the wood: the board stops and the streak is gone. */
    private void bump(Wall wl) {
        if (wl.bumped) return;
        wl.bumped = true;
        combo = 0;
        shake = 0.18f;
        burst(bx + ballR, ballY, 0xFFE3B37A, 10, 0.5f);
        pop(bx, ballY - ballR * 2.5f, "Bonk!", 0xFFFF6B6B);
        synth.thud();
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
    }

    private void pass(Wall wl) {
        wl.passed = true;
        done++;
        float avg = wl.errN > 0 ? wl.errSum / wl.errN : semPx;
        boolean perfect = !wl.bumped && avg < semPx * 0.35f;
        int pts = wl.bumped ? 5 : 10 * (1 + combo / 4) + (perfect ? 10 : 0);
        score += pts;
        if (!wl.bumped) combo++;
        int col = COLORS[wl.deg];
        burst(bx, ballY, col, perfect ? 22 : 12, 0.7f);
        pop(bx + ballR * 2, ballY - ballR * 2.5f,
                (perfect ? "Perfect! +" : wl.bumped ? "Got it +" : "+") + pts, perfect ? 0xFFFFFFFF : col);
        if (combo > 0 && combo % 5 == 0) pop(w * 0.5f, h * 0.3f, combo + " in a row!", 0xFFFFE066);
        if (tonicHz > 0) synth.tone(noteHz(wl.deg) * 2f, 80, 0.12f);
    }

    private void saveBest() {
        if (score > best) {
            best = score;
            prefs.edit().putInt("best", best).apply();
        }
    }

    private void burst(float x, float y, int color, int n, float power) {
        if (parts.size() > 400) return;
        for (int i = 0; i < n; i++) {
            Particle q = new Particle();
            double a = rnd.nextDouble() * Math.PI * 2;
            float sp = w * (0.15f + rnd.nextFloat() * 0.6f) * power;
            q.x = x;
            q.y = y;
            q.vx = (float) Math.cos(a) * sp;
            q.vy = (float) Math.sin(a) * sp - h * 0.12f;
            q.life = q.max = 0.4f + rnd.nextFloat() * 0.5f;
            q.size = w * (0.008f + rnd.nextFloat() * 0.012f);
            q.color = color;
            parts.add(q);
        }
    }

    private void pop(float x, float y, String text, int color) {
        Pop o = new Pop();
        o.x = x;
        o.y = y;
        o.text = text;
        o.color = color;
        pops.add(o);
    }

    // =====================================================================
    // draw
    // =====================================================================

    @Override
    protected void onDraw(Canvas c) {
        long now = System.nanoTime();
        float dt = lastNanos == 0 ? 0.016f : (now - lastNanos) / 1e9f;
        lastNanos = now;
        if (dt > 0.05f) dt = 0.05f;
        if (w <= 0) return;

        update(dt);

        buttons.clear();
        c.save();
        if (shake > 0) {
            float a = shake * w * 0.05f;
            c.translate((rnd.nextFloat() - 0.5f) * a, (rnd.nextFloat() - 0.5f) * a);
        }
        drawBackdrop(c);
        drawTrack(c);
        drawLanes(c);
        boolean scene = state == State.PLAY || state == State.INTRO || state == State.OVER;
        if (scene) {
            for (int i = walls.size() - 1; i >= 0; i--) {          // far to near, behind the ball
                if (walls.get(i).z >= 1f) drawWall(c, walls.get(i));
            }
            drawStars(c);
        }
        drawBall(c);
        if (scene) {
            for (int i = walls.size() - 1; i >= 0; i--) {          // boards already sweeping past the camera
                if (walls.get(i).z < 1f) drawWall(c, walls.get(i));
            }
        }
        drawParticles(c);
        c.restore();

        if (flash > 0) c.drawColor(Color.argb((int) (70 * flash), 255, 60, 60));

        switch (state) {
            case MENU: drawMenu(c); break;
            case TUNE: drawTune(c); break;
            case INTRO: drawHud(c); drawIntro(c); break;
            case PLAY: drawHud(c); break;
            case OVER: drawHud(c); drawOver(c); break;
        }

        if (running) postInvalidateOnAnimation();
    }

    private int hue(float deg, float sat, float val, int alpha) {
        hsv[0] = ((deg % 360f) + 360f) % 360f;
        hsv[1] = sat;
        hsv[2] = val;
        return Color.HSVToColor(alpha, hsv);
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (Math.max(0, Math.min(255, alpha)) << 24);
    }

    private void drawBackdrop(Canvas c) {
        boolean disco = lv.disco && (state == State.PLAY || state == State.INTRO);
        if (disco) {
            c.drawColor(hue(time * 50f, 0.75f, 0.22f, 0x88));
            p.setStyle(Paint.Style.FILL);
            for (int i = 0; i < 4; i++) {
                float cx = w * (0.5f + 0.45f * (float) Math.sin(time * (0.6f + i * 0.23f) + i * 2f));
                float cy = h * (0.5f + 0.4f * (float) Math.cos(time * (0.5f + i * 0.17f) + i));
                p.setColor(hue(time * 80f + i * 90f, 0.8f, 1f, 0x26));
                c.drawCircle(cx, cy, w * 0.42f, p);
            }
        } else {
            c.drawColor(0x7A070B22);
        }
    }

    /** The runway the boards slide down, with rungs that scroll so you feel the speed. */
    private void drawTrack(Canvas c) {
        float zn = 0.6f, zf = Z_FAR * 4f;
        float yb = playBottom;
        path.reset();
        path.moveTo(px(wallL, zn), py(yb, zn));
        path.lineTo(px(wallR, zn), py(yb, zn));
        path.lineTo(px(wallR, zf), py(yb, zf));
        path.lineTo(px(wallL, zf), py(yb, zf));
        path.close();
        p.setStyle(Paint.Style.FILL);
        p.setColor(0x8C0E1230);
        c.drawPath(path, p);

        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(w * 0.006f);
        p.setColor(0x99FFFFFF);
        c.drawLine(px(wallL, zn), py(yb, zn), px(wallL, zf), py(yb, zf), p);
        c.drawLine(px(wallR, zn), py(yb, zn), px(wallR, zf), py(yb, zf), p);

        int rungs = 12;
        float spacing = 0.5f, range = rungs * spacing;
        for (int i = 0; i < rungs; i++) {
            float t = ((i * spacing - scroll) % range + range) % range;
            float z = 0.62f + t;
            float a = clamp01((Z_FAR + 1.5f - z) / 3f) * clamp01((z - 0.62f) / 0.2f);
            p.setStrokeWidth(Math.max(1f, w * 0.006f / z));
            p.setColor(withAlpha(0xFFFFFFFF, (int) (90 * a)));
            c.drawLine(px(wallL, z), py(yb, z), px(wallR, z), py(yb, z), p);
        }
        p.setStyle(Paint.Style.FILL);
    }

    /** Note ladder on the left, at the ball's depth: the height each note puts the ball at. */
    private void drawLanes(Canvas c) {
        int want = -1;
        if (state == State.PLAY) {
            for (Wall wl : walls) {
                if (!wl.passed) { want = wl.deg; break; }   // list is nearest-first
            }
        }
        int sung = voiced ? nearestDeg(ballS()) : -1;

        float pillW = w * 0.115f, pillH = w * 0.062f;
        tp.setTextAlign(Paint.Align.CENTER);
        tp.setTextSize(w * 0.036f);
        for (int i = 0; i < SCALE.length; i++) {
            float y = yFor(SCALE[i]);
            boolean inPool = false;
            if (lv.melody != null) {
                for (int m : lv.melody) if (m == i) { inPool = true; break; }
            } else {
                for (int m : lv.pool) if (m == i) { inPool = true; break; }
            }
            if (state == State.MENU || state == State.TUNE) inPool = true;
            int col = COLORS[i];
            boolean hot = i == want || i == sung;

            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(hot ? w * 0.006f : w * 0.003f);
            p.setColor(withAlpha(col, hot ? 170 : (inPool ? 70 : 28)));
            c.drawLine(pillW + w * 0.03f, y, hot ? bx - ballR * 1.6f : w * 0.2f, y, p);

            p.setStyle(Paint.Style.FILL);
            float grow = i == want ? 1f + 0.08f * (float) Math.sin(time * 9f) : 1f;
            rect.set(w * 0.015f, y - pillH * grow / 2, w * 0.015f + pillW * grow, y + pillH * grow / 2);
            p.setColor(withAlpha(col, hot ? 255 : (inPool ? 190 : 70)));
            c.drawRoundRect(rect, pillH, pillH, p);
            tp.setColor(withAlpha(0xFF10142E, inPool || hot ? 255 : 130));
            c.drawText(NAMES[i], rect.centerX(), rect.centerY() + tp.getTextSize() * 0.35f, tp);
        }
    }

    /** One board in perspective: wood face, see-through hole with visible depth, note name on the wood. */
    private void drawWall(Canvas c, Wall wl) {
        float zb = wl.z + wl.dz;
        if (zb < Z_GONE) return;
        float zf = Math.max(wl.z, 0.5f);
        float fog = clamp01((Z_FAR - wl.z) / 0.8f);
        float aFront = fog * (wl.z >= 1f ? 1f : clamp01((wl.z - 0.62f) / 0.38f));
        float aInner = fog * (zb >= 1f ? 1f : clamp01((zb - Z_GONE) / (1f - Z_GONE)));
        int col = COLORS[wl.deg];

        float top = playTop - w * 0.02f;
        float holeY = yFor(wl.s);
        float half = wl.tol * semPx * wl.open;
        float halfW = ballR * 2.6f;
        boolean hasHole = half > 3f;

        float l = px(wallL, zf), r = px(wallR, zf), t = py(top, zf), b = py(playBottom, zf);
        float hl = px(ballX - halfW, zf), hr = px(ballX + halfW, zf);
        float ht = py(holeY - half, zf), hb = py(holeY + half, zf);
        float rr = w * 0.02f / zf;

        // inside of the hole: the part of the front opening not covered by the back opening
        if (hasHole && aInner > 0.01f) {
            float bl = px(ballX - halfW, zb), br = px(ballX + halfW, zb);
            float bt = py(holeY - half, zb), bb = py(holeY + half, zb);
            path.reset();
            path.addRect(hl, ht, hr, hb, Path.Direction.CW);
            path2.reset();
            path2.addRect(bl, bt, br, bb, Path.Direction.CW);
            path.op(path2, Path.Op.DIFFERENCE);
            p.setStyle(Paint.Style.FILL);
            p.setColor(withAlpha(0xFF9A6A38, (int) (245 * aInner)));
            c.drawPath(path, p);
            p.setColor(withAlpha(col, (int) (70 * aInner)));
            c.drawPath(path, p);
        }

        if (aFront <= 0.01f) return;

        // wood face with the hole cut out
        path.reset();
        path.setFillType(Path.FillType.EVEN_ODD);
        rect.set(l, t, r, b);
        path.addRoundRect(rect, rr, rr, Path.Direction.CW);
        if (hasHole) {
            rect.set(hl, ht, hr, hb);
            path.addRoundRect(rect, rr, rr, Path.Direction.CW);
        }
        p.setStyle(Paint.Style.FILL);
        p.setColor(withAlpha(0xFFE0AE70, (int) (250 * aFront)));
        c.drawPath(path, p);
        path.setFillType(Path.FillType.WINDING);

        // grain: vertical streaks, skipping the hole
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1f, w * 0.005f / zf));
        p.setColor(withAlpha(0xFFC48F55, (int) (170 * aFront)));
        for (int i = 1; i < 9; i++) {
            float gx = l + (r - l) * (i / 9f + 0.02f * (float) Math.sin(i * 12.9f + wl.phase));
            if (hasHole && gx > hl - 2 && gx < hr + 2) {
                c.drawLine(gx, t + rr, gx, ht, p);
                c.drawLine(gx, hb, gx, b - rr, p);
            } else {
                c.drawLine(gx, t + rr, gx, b - rr, p);
            }
        }
        // edge highlight
        p.setColor(withAlpha(0xFFF6DAAE, (int) (200 * aFront)));
        p.setStrokeWidth(Math.max(1.5f, w * 0.008f / zf));
        rect.set(l, t, r, b);
        c.drawRoundRect(rect, rr, rr, p);

        // coloured rim round the hole
        if (hasHole) {
            p.setColor(withAlpha(col, (int) (255 * aFront)));
            p.setStrokeWidth(Math.max(2f, w * 0.014f / zf));
            rect.set(hl, ht, hr, hb);
            c.drawRoundRect(rect, rr, rr, p);
        }
        p.setStyle(Paint.Style.FILL);

        // note name printed on the wood, on whichever side of the hole has room
        float size = w * 0.19f / zf;
        boolean below = SCALE[wl.deg] >= 7;
        float fullHalf = wl.tol0 * semPx;
        float nameY = below ? holeY + fullHalf + w * 0.2f : holeY - fullHalf - w * 0.07f;
        float tx = px((wallL + wallR) / 2f, zf), ty = py(nameY, zf);
        sp.setTypeface(tp.getTypeface());
        sp.setTextAlign(Paint.Align.CENTER);
        sp.setTextSize(size);
        sp.setStyle(Paint.Style.STROKE);
        sp.setStrokeWidth(size * 0.09f);
        sp.setStrokeJoin(Paint.Join.ROUND);
        sp.setColor(withAlpha(0xFF2A1B0C, (int) (255 * aFront)));
        String label = wl.door && wl.open < 0.5f ? NAMES[wl.deg] + "?" : NAMES[wl.deg];
        c.drawText(label, tx, ty, sp);
        sp.setStyle(Paint.Style.FILL);
        sp.setColor(withAlpha(0xFFFFFFFF, (int) (255 * aFront)));
        c.drawText(label, tx, ty, sp);
    }

    private void drawStars(Canvas c) {
        p.setStyle(Paint.Style.FILL);
        for (Star st : stars) {
            if (st.z > Z_FAR || st.z < 0.7f) continue;
            float a = clamp01((Z_FAR - st.z) / 0.8f);
            float cx = px(ballX, st.z), cy = py(yFor(st.s), st.z);
            float r = w * 0.04f / st.z * (1f + 0.1f * (float) Math.sin(st.spin * 2));
            path.reset();
            for (int i = 0; i < 10; i++) {
                double ang = st.spin * 0.4 + i * Math.PI / 5 - Math.PI / 2;
                float rad = i % 2 == 0 ? r : r * 0.45f;
                float sx = cx + (float) Math.cos(ang) * rad, sy = cy + (float) Math.sin(ang) * rad;
                if (i == 0) path.moveTo(sx, sy); else path.lineTo(sx, sy);
            }
            path.close();
            p.setColor(withAlpha(0xFFFFE066, (int) (85 * a)));
            c.drawCircle(cx, cy, r * 1.5f, p);
            p.setColor(withAlpha(0xFFFFE066, (int) (255 * a)));
            c.drawPath(path, p);
        }
    }

    private void drawBall(Canvas c) {
        int deg = voiced ? nearestDeg(ballS()) : -1;
        int col = deg >= 0 ? COLORS[deg] : 0xFFF2F2F7;
        p.setStyle(Paint.Style.FILL);

        // shadow on the runway
        float lift = clamp01((playBottom - ballY) / (playBottom - playTop));
        p.setColor(withAlpha(0xFF000000, (int) (110 - 60 * lift)));
        rect.set(bx - ballR * (1.1f - 0.4f * lift), playBottom - ballR * 0.22f,
                bx + ballR * (1.1f - 0.4f * lift), playBottom + ballR * 0.22f);
        c.drawOval(rect, p);

        // motion smear
        float step = 0f;
        for (int i = 1; i < trail.length; i++) {
            int idx = ((trailHead - i) % trail.length + trail.length) % trail.length;
            float k = 1f - i / (float) trail.length;
            p.setColor(withAlpha(col, (int) (45 * k)));
            c.drawCircle(bx - i * step, trail[idx], ballR * (0.5f + 0.45f * k), p);
        }

        if (voiced) {
            p.setColor(withAlpha(col, 70));
            c.drawCircle(bx, ballY, ballR * (1.55f + 0.12f * (float) Math.sin(time * 14f)), p);
        }
        p.setColor(0xFF10142E);
        c.drawCircle(bx, ballY, ballR * 1.1f, p);
        p.setColor(col);
        c.drawCircle(bx, ballY, ballR, p);
        p.setColor(0x99FFFFFF);
        c.drawCircle(bx - ballR * 0.32f, ballY - ballR * 0.35f, ballR * 0.28f, p);
    }

    private void drawParticles(Canvas c) {
        p.setStyle(Paint.Style.FILL);
        for (Particle q : parts) {
            float k = q.life / q.max;
            p.setColor(withAlpha(q.color, (int) (255 * k)));
            c.drawCircle(q.x, q.y, q.size * (0.5f + k), p);
        }
        for (Pop o : pops) {
            float k = 1f - o.t / 1.1f;
            tp.setTextSize(w * 0.055f);
            tp.setColor(withAlpha(0xFF10142E, (int) (220 * k)));
            c.drawText(o.text, o.x + 3, o.y + 3, tp);
            tp.setColor(withAlpha(o.color, (int) (255 * k)));
            c.drawText(o.text, o.x, o.y, tp);
        }
    }

    private void heart(Canvas c, float cx, float cy, float s, int color) {
        path.reset();
        path.moveTo(cx, cy + s * 0.9f);
        path.cubicTo(cx - s * 1.7f, cy - s * 0.3f, cx - s * 0.6f, cy - s * 1.3f, cx, cy - s * 0.4f);
        path.cubicTo(cx + s * 0.6f, cy - s * 1.3f, cx + s * 1.7f, cy - s * 0.3f, cx, cy + s * 0.9f);
        path.close();
        p.setStyle(Paint.Style.FILL);
        p.setColor(color);
        c.drawPath(path, p);
    }

    private void drawHud(Canvas c) {
        float y = h * 0.055f;
        tp.setTextAlign(Paint.Align.LEFT);
        tp.setTextSize(w * 0.075f);
        tp.setColor(0xFFFFFFFF);
        c.drawText(String.valueOf(score), w * 0.04f, y, tp);
        if (combo >= 2) {
            tp.setTextSize(w * 0.04f);
            tp.setColor(0xFFFFE066);
            c.drawText("x" + (1 + combo / 4) + "   " + combo + " streak", w * 0.04f, y + w * 0.055f, tp);
        }
        tp.setTextAlign(Paint.Align.CENTER);
        tp.setTextSize(w * 0.036f);
        tp.setColor(0xCCFFFFFF);
        c.drawText("LEVEL " + levelNo, w * 0.5f, y - w * 0.035f, tp);
        tp.setTextSize(w * 0.042f);
        tp.setColor(0xFFFFFFFF);
        c.drawText(lv.name, w * 0.5f, y + w * 0.012f, tp);

        tp.setTextAlign(Paint.Align.RIGHT);
        tp.setTextSize(w * 0.036f);
        tp.setColor(0xCCFFE066);
        c.drawText("BEST " + Math.max(best, score), w * 0.96f, y - w * 0.012f, tp);
        tp.setTextAlign(Paint.Align.CENTER);

        if (stuckWall != null && state == State.PLAY && stuckT > 0.4f) {
            float pulse = 1f + 0.06f * (float) Math.sin(time * 8f);
            tp.setTextSize(w * 0.085f * pulse);
            tp.setColor(0xFF10142E);
            c.drawText("Sing " + NAMES[stuckWall.deg] + "!", w * 0.5f + 4, playTop + w * 0.2f + 4, tp);
            tp.setColor(COLORS[stuckWall.deg]);
            c.drawText("Sing " + NAMES[stuckWall.deg] + "!", w * 0.5f, playTop + w * 0.2f, tp);
        }

        // progress through the level
        float bw = w * 0.3f, bx = w * 0.5f - bw / 2, by = y + w * 0.035f;
        p.setStyle(Paint.Style.FILL);
        p.setColor(0x44FFFFFF);
        rect.set(bx, by, bx + bw, by + w * 0.012f);
        c.drawRoundRect(rect, w * 0.006f, w * 0.006f, p);
        p.setColor(0xFF6BCB77);
        rect.set(bx, by, bx + bw * Math.min(1f, done / (float) Math.max(1, lv.count)), by + w * 0.012f);
        c.drawRoundRect(rect, w * 0.006f, w * 0.006f, p);

        if (state == State.PLAY && silentT > 2.5f && levelNo <= 2 && stuckWall == null) {
            tp.setTextSize(w * 0.045f);
            tp.setColor(withAlpha(0xFFFFFFFF, (int) (170 + 80 * Math.sin(time * 5f))));
            c.drawText("Sing to lift the ball!", w * 0.5f, playBottom + w * 0.07f, tp);
        }
    }

    /** Draws centred text wrapped to maxWidth; returns the y after the last line. */
    private float wrapped(Canvas c, String text, float cx, float y, float maxWidth, float lineH) {
        String[] words = text.split(" ");
        StringBuilder line = new StringBuilder();
        for (String word : words) {
            String trial = line.length() == 0 ? word : line + " " + word;
            if (tp.measureText(trial) > maxWidth && line.length() > 0) {
                c.drawText(line.toString(), cx, y, tp);
                y += lineH;
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(trial);
            }
        }
        if (line.length() > 0) {
            c.drawText(line.toString(), cx, y, tp);
            y += lineH;
        }
        return y;
    }

    private void panel(Canvas c, float top, float bottom) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xD910142E);
        rect.set(w * 0.07f, top, w * 0.93f, bottom);
        c.drawRoundRect(rect, w * 0.06f, w * 0.06f, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(w * 0.006f);
        p.setColor(0x55FFFFFF);
        c.drawRoundRect(rect, w * 0.06f, w * 0.06f, p);
        p.setStyle(Paint.Style.FILL);
    }

    private float button(Canvas c, int id, String label, float y, int color) {
        float bw = w * 0.66f, bh = w * 0.14f;
        Button b = new Button();
        b.id = id;
        b.label = label;
        b.color = color;
        b.r.set(w * 0.5f - bw / 2, y, w * 0.5f + bw / 2, y + bh);
        buttons.add(b);
        p.setStyle(Paint.Style.FILL);
        p.setColor(color);
        c.drawRoundRect(b.r, bh / 2, bh / 2, p);
        tp.setTextAlign(Paint.Align.CENTER);
        tp.setTextSize(w * 0.05f);
        tp.setColor(0xFF10142E);
        c.drawText(label, b.r.centerX(), b.r.centerY() + tp.getTextSize() * 0.35f, tp);
        return y + bh + w * 0.04f;
    }

    private void drawMenu(Canvas c) {
        float top = h * 0.16f, bottom = h * 0.84f;
        panel(c, top, bottom);
        tp.setTextAlign(Paint.Align.CENTER);

        // rainbow title
        String[] parts3 = {"DO", "RE", "MI"};
        tp.setTextSize(w * 0.15f);
        float y = top + w * 0.2f;
        for (int i = 0; i < 3; i++) {
            float bob = (float) Math.sin(time * 3f + i * 0.9f) * w * 0.012f;
            tp.setColor(COLORS[i * 2]);
            c.drawText(parts3[i], w * (0.27f + 0.23f * i), y + bob, tp);
        }
        tp.setTextSize(w * 0.09f);
        tp.setColor(0xFFFFFFFF);
        y += w * 0.11f;
        c.drawText("BALL", w * 0.5f, y, tp);

        tp.setTextSize(w * 0.04f);
        tp.setColor(0xCCFFFFFF);
        y += w * 0.08f;
        y = wrapped(c, "Sing the note on the board. Your voice lifts the ball through the hole.",
                w * 0.5f, y, w * 0.72f, w * 0.055f);
        y += w * 0.04f;

        if (!micGranted) {
            y = button(c, B_MIC, "ALLOW MICROPHONE", y, 0xFFFFD93D);
            tp.setTextSize(w * 0.034f);
            tp.setColor(0xAAFFFFFF);
            wrapped(c, "The mic is how you play. If the prompt does not show, turn it on in Settings, Apps, Do Re Mi Ball.",
                    w * 0.5f, y + w * 0.02f, w * 0.72f, w * 0.048f);
            return;
        }

        y = button(c, B_START, tonicHz > 0 ? "PLAY" : "TUNE & PLAY", y, 0xFF6BCB77);
        y = button(c, B_TUNE, tonicHz > 0 ? "RE-TUNE   (Do = " + pitchName(tonicHz) + ")" : "TUNE MY VOICE", y, 0xFF4D96FF);
        y = button(c, B_SOUND, synth.enabled ? "SOUND: ON" : "SOUND: OFF", y, 0xFFE06CFF);

        tp.setTextSize(w * 0.042f);
        tp.setColor(0xFFFFE066);
        if (best > 0) c.drawText("Best: " + best, w * 0.5f, y + w * 0.03f, tp);
        tp.setTextSize(w * 0.032f);
        tp.setColor(0x99FFFFFF);
        c.drawText("Headphones make the pitch tracking steadier", w * 0.5f, bottom - w * 0.05f, tp);
    }

    private void drawTune(Canvas c) {
        float top = h * 0.2f, bottom = h * 0.8f;
        panel(c, top, bottom);
        tp.setTextAlign(Paint.Align.CENTER);
        tp.setTextSize(w * 0.07f);
        tp.setColor(0xFFFFFFFF);
        float y = top + w * 0.14f;
        c.drawText("Find your Do", w * 0.5f, y, tp);
        tp.setTextSize(w * 0.04f);
        tp.setColor(0xCCFFFFFF);
        y = wrapped(c, "Sing a comfortable LOW note, \"dooo\", and hold it steady. Leave room to climb eight notes above it.",
                w * 0.5f, y + w * 0.08f, w * 0.72f, w * 0.055f);

        float cx = w * 0.5f, cy = y + w * 0.22f, r = w * 0.17f;
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(w * 0.03f);
        p.setColor(0x33FFFFFF);
        c.drawCircle(cx, cy, r, p);
        p.setColor(0xFF6BCB77);
        p.setStrokeCap(Paint.Cap.ROUND);
        rect.set(cx - r, cy - r, cx + r, cy + r);
        c.drawArc(rect, -90, 360f * Math.min(1f, tuneHold / 1.3f), false, p);
        p.setStrokeCap(Paint.Cap.BUTT);
        p.setStyle(Paint.Style.FILL);

        tp.setTextSize(w * 0.09f);
        tp.setColor(0xFFFFFFFF);
        c.drawText(pitchName(tuneHz), cx, cy + w * 0.015f, tp);
        tp.setTextSize(w * 0.035f);
        tp.setColor(0xAAFFFFFF);
        c.drawText(tuneHz > 0 ? Math.round(tuneHz) + " Hz" : "listening...", cx, cy + w * 0.07f, tp);

        // mic level
        float lvl = Math.min(1f, detector.getLevel() * 8f);
        float bw = w * 0.5f, by = cy + r + w * 0.08f;
        p.setColor(0x33FFFFFF);
        rect.set(cx - bw / 2, by, cx + bw / 2, by + w * 0.015f);
        c.drawRoundRect(rect, w * 0.01f, w * 0.01f, p);
        p.setColor(0xFFFFD93D);
        rect.set(cx - bw / 2, by, cx - bw / 2 + bw * lvl, by + w * 0.015f);
        c.drawRoundRect(rect, w * 0.01f, w * 0.01f, p);

        if (detector.hasFailed()) {
            tp.setTextSize(w * 0.036f);
            tp.setColor(0xFFFF6B6B);
            c.drawText("Mic did not start. Close other apps using it.", cx, by + w * 0.07f, tp);
        }
        button(c, B_CANCEL, "BACK", bottom - w * 0.2f, 0xFFB8BCD8);
    }

    private void drawIntro(Canvas c) {
        float mid = h * 0.42f;
        float k = Math.min(1f, (introT > 0.4f ? 1f : introT / 0.4f));
        p.setStyle(Paint.Style.FILL);
        p.setColor(withAlpha(0xFF10142E, (int) (215 * k)));
        rect.set(w * 0.07f, mid - w * 0.26f, w * 0.93f, mid + w * 0.3f);
        c.drawRoundRect(rect, w * 0.06f, w * 0.06f, p);
        tp.setTextAlign(Paint.Align.CENTER);
        tp.setTextSize(w * 0.05f);
        tp.setColor(withAlpha(0xFFFFE066, (int) (255 * k)));
        c.drawText("LEVEL " + levelNo, w * 0.5f, mid - w * 0.15f, tp);
        tp.setTextSize(w * 0.1f);
        tp.setColor(withAlpha(COLORS[(levelNo - 1) % 7], (int) (255 * k)));
        c.drawText(lv.name, w * 0.5f, mid - w * 0.02f, tp);
        tp.setTextSize(w * 0.042f);
        tp.setColor(withAlpha(0xFFFFFFFF, (int) (235 * k)));
        wrapped(c, lv.flair, w * 0.5f, mid + w * 0.08f, w * 0.74f, w * 0.058f);
    }

    private void drawOver(Canvas c) {
        float top = h * 0.24f, bottom = h * 0.76f;
        panel(c, top, bottom);
        tp.setTextAlign(Paint.Align.CENTER);
        tp.setTextSize(w * 0.1f);
        tp.setColor(0xFFFF6B6B);
        float y = top + w * 0.18f;
        c.drawText("GAME OVER", w * 0.5f, y, tp);
        tp.setTextSize(w * 0.16f);
        tp.setColor(0xFFFFFFFF);
        y += w * 0.2f;
        c.drawText(String.valueOf(score), w * 0.5f, y, tp);
        tp.setTextSize(w * 0.042f);
        tp.setColor(0xFFFFE066);
        y += w * 0.08f;
        c.drawText(score >= best && score > 0 ? "New best!  Reached level " + levelNo
                : "Best " + best + "   Reached level " + levelNo, w * 0.5f, y, tp);
        y += w * 0.07f;
        y = button(c, B_AGAIN, "PLAY AGAIN", y, 0xFF6BCB77);
        button(c, B_MENU, "MENU", y, 0xFFB8BCD8);
    }

    // =====================================================================
    // input
    // =====================================================================

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_DOWN) return true;
        if (e.getAction() != MotionEvent.ACTION_UP) return super.onTouchEvent(e);
        for (Button b : new ArrayList<>(buttons)) {
            if (!b.r.contains(e.getX(), e.getY())) continue;
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            switch (b.id) {
                case B_MIC:
                    host.requestMic();
                    break;
                case B_START:
                    if (tonicHz > 0) newGame(); else startTune(true);
                    break;
                case B_TUNE:
                    startTune(false);
                    break;
                case B_SOUND:
                    synth.enabled = !synth.enabled;
                    prefs.edit().putBoolean("sound", synth.enabled).apply();
                    if (synth.enabled) synth.tone(660f, 90, 0.2f);
                    break;
                case B_AGAIN:
                    newGame();
                    break;
                case B_MENU:
                case B_CANCEL:
                    state = State.MENU;
                    break;
                default:
                    break;
            }
            return true;
        }
        return true;
    }

    private void startTune(boolean thenPlay) {
        startAfterTune = thenPlay;
        tuneHold = 0;
        tuneN = 0;
        tuneLogSum = 0;
        state = State.TUNE;
    }
}
