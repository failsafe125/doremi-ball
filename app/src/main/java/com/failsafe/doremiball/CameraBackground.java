package com.failsafe.doremiball;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;

import java.util.Collections;

/** Front-camera preview on a TextureView, centre-cropped to fill the screen. */
public final class CameraBackground implements TextureView.SurfaceTextureListener {

    private final Activity activity;
    private final TextureView view;

    private HandlerThread thread;
    private Handler handler;
    private CameraDevice device;
    private CameraCaptureSession session;
    private Size previewSize;
    private int sensorOrientation = 270;
    private volatile boolean wanted;
    private boolean opening;

    public CameraBackground(Activity activity, TextureView view) {
        this.activity = activity;
        this.view = view;
        view.setSurfaceTextureListener(this);
    }

    public void start() {
        wanted = true;
        if (thread == null) {
            thread = new HandlerThread("camera");
            thread.start();
            handler = new Handler(thread.getLooper());
        }
        if (view.isAvailable()) open();
    }

    public void stop() {
        wanted = false;
        opening = false;
        try { if (session != null) session.close(); } catch (Throwable ignored) { }
        session = null;
        try { if (device != null) device.close(); } catch (Throwable ignored) { }
        device = null;
        if (thread != null) {
            thread.quitSafely();
            thread = null;
            handler = null;
        }
        view.setAlpha(0f);
    }

    private void open() {
        if (!wanted || opening || device != null || handler == null) return;
        if (activity.checkSelfPermission(Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) return;
        try {
            CameraManager mgr = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
            if (mgr == null) return;
            String chosen = null;
            for (String id : mgr.getCameraIdList()) {
                Integer facing = mgr.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
                if (chosen == null) chosen = id;
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    chosen = id;
                    break;
                }
            }
            if (chosen == null) return;

            CameraCharacteristics cc = mgr.getCameraCharacteristics(chosen);
            Integer so = cc.get(CameraCharacteristics.SENSOR_ORIENTATION);
            if (so != null) sensorOrientation = so;
            StreamConfigurationMap map = cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) return;
            Size[] sizes = map.getOutputSizes(SurfaceTexture.class);
            if (sizes == null || sizes.length == 0) return;
            previewSize = pick(sizes);

            opening = true;
            mgr.openCamera(chosen, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice cam) {
                    opening = false;
                    if (!wanted) { cam.close(); return; }
                    device = cam;
                    activity.runOnUiThread(() -> startPreview(cam));
                }
                @Override public void onDisconnected(CameraDevice cam) {
                    opening = false;
                    cam.close();
                    if (device == cam) device = null;
                }
                @Override public void onError(CameraDevice cam, int error) {
                    opening = false;
                    cam.close();
                    if (device == cam) device = null;
                }
            }, handler);
        } catch (Throwable t) {
            opening = false;
        }
    }

    private Size pick(Size[] sizes) {
        float vw = Math.max(1, view.getWidth()), vh = Math.max(1, view.getHeight());
        float wantAspect = Math.max(vw, vh) / Math.min(vw, vh);
        Size best = sizes[0];
        float bestScore = Float.MAX_VALUE;
        for (Size s : sizes) {
            float lng = Math.max(s.getWidth(), s.getHeight());
            float sht = Math.min(s.getWidth(), s.getHeight());
            if (lng > 1920 || sht > 1080 || sht < 480) continue;
            float score = Math.abs(lng / sht - wantAspect) * 2f
                    + Math.abs(lng * sht - 1280f * 720f) / (1280f * 720f);
            if (score < bestScore) {
                bestScore = score;
                best = s;
            }
        }
        return best;
    }

    private void startPreview(final CameraDevice cam) {
        if (!wanted || device != cam || handler == null || !view.isAvailable()) return;
        try {
            SurfaceTexture st = view.getSurfaceTexture();
            if (st == null) return;
            st.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            final Surface surface = new Surface(st);
            final CaptureRequest.Builder req = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            req.addTarget(surface);
            final Handler h = handler;
            cam.createCaptureSession(Collections.singletonList(surface),
                    new CameraCaptureSession.StateCallback() {
                        @Override public void onConfigured(CameraCaptureSession s) {
                            if (device != cam) return;
                            session = s;
                            try {
                                s.setRepeatingRequest(req.build(), null, h);
                            } catch (Throwable ignored) { }
                            activity.runOnUiThread(() -> {
                                applyTransform();
                                view.setAlpha(1f);
                            });
                        }
                        @Override public void onConfigureFailed(CameraCaptureSession s) { }
                    }, h);
        } catch (Throwable ignored) { }
    }

    /** TextureView stretches the buffer to the view; scale it back to a centre crop. */
    private void applyTransform() {
        if (previewSize == null) return;
        float vw = view.getWidth(), vh = view.getHeight();
        if (vw <= 0 || vh <= 0) return;
        boolean swapped = sensorOrientation % 180 == 90;
        float cw = swapped ? previewSize.getHeight() : previewSize.getWidth();
        float ch = swapped ? previewSize.getWidth() : previewSize.getHeight();
        float scale = Math.max(vw / cw, vh / ch);
        Matrix m = new Matrix();
        m.setScale(cw * scale / vw, ch * scale / vh, vw / 2f, vh / 2f);
        view.setTransform(m);
    }

    @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
        if (wanted) open();
    }

    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
        applyTransform();
    }

    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
        try { if (session != null) session.close(); } catch (Throwable ignored) { }
        session = null;
        try { if (device != null) device.close(); } catch (Throwable ignored) { }
        device = null;
        return true;
    }

    @Override public void onSurfaceTextureUpdated(SurfaceTexture st) { }
}
