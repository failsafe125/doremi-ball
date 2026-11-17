package com.failsafe.doremiball;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.os.Bundle;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity implements GameView.Host {

    private static final int REQ_PERMS = 41;

    private final PitchDetector detector = new PitchDetector();
    private final Synth synth = new Synth();
    private CameraBackground camera;
    private GameView game;
    private boolean askedOnce;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);

        FrameLayout root = new FrameLayout(this);
        TextureView texture = new TextureView(this);
        texture.setAlpha(0f); // stays in the draw pass so its surface gets created
        game = new GameView(this, detector, synth, this);
        ViewGroup.LayoutParams fill = new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        root.addView(texture, fill);
        root.addView(game, new ViewGroup.LayoutParams(fill));
        setContentView(root);

        camera = new CameraBackground(this, texture);
    }

    private boolean has(String perm) {
        return checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
    }

    private void askPermissions() {
        List<String> need = new ArrayList<>();
        if (!has(Manifest.permission.RECORD_AUDIO)) need.add(Manifest.permission.RECORD_AUDIO);
        if (!has(Manifest.permission.CAMERA)) need.add(Manifest.permission.CAMERA);
        if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), REQ_PERMS);
    }

    private void startInputs() {
        boolean mic = has(Manifest.permission.RECORD_AUDIO);
        game.setMicGranted(mic);
        if (mic) detector.start();
        if (has(Manifest.permission.CAMERA)) camera.start();
    }

    @Override
    public void requestMic() {
        askPermissions();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMS) startInputs();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideBars();
        if (!askedOnce) {
            askedOnce = true;
            askPermissions();
        }
        startInputs();
        game.setRunning(true);
    }

    @Override
    protected void onPause() {
        game.setRunning(false);
        detector.stop();
        camera.stop();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        synth.shutdown();
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideBars();
    }

    @SuppressWarnings("deprecation")
    private void hideBars() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (game != null && game.onBack()) return;
        super.onBackPressed();
    }
}
