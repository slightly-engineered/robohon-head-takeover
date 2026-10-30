package com.colson.projtest;

import android.app.Activity;
import android.hardware.Camera;
import android.os.Bundle;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.WindowManager;

public class CameraActivity extends Activity implements SurfaceHolder.Callback {

    private static final String TAG = "CamTest";
    private Camera camera;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        SurfaceView sv = new SurfaceView(this);
        sv.getHolder().addCallback(this);
        setContentView(sv);
    }

    public void surfaceCreated(SurfaceHolder holder) {
        try {
            camera = Camera.open(0);                 // back camera (HAL1)
            camera.setDisplayOrientation(0);        // if sideways, try 0 / 180 / 270
            Camera.Parameters p = camera.getParameters();
            Camera.Size best = null;
            for (Camera.Size s : p.getSupportedPreviewSizes()) {
                if (best == null ||
                        Math.abs(s.width * s.height - 1280 * 720) < Math.abs(best.width * best.height - 1280 * 720))
                    best = s;
            }
            if (best != null) { p.setPreviewSize(best.width, best.height);
                Log.i(TAG, "preview size " + best.width + "x" + best.height); }
            camera.setParameters(p);
            camera.setPreviewDisplay(holder);
            camera.startPreview();
            Log.i(TAG, "preview started");
        } catch (Exception e) { Log.e(TAG, "camera open failed", e); }
    }

    public void surfaceChanged(SurfaceHolder h, int fmt, int w, int ht) {
        if (camera == null) return;
        try { camera.stopPreview(); } catch (Exception ignore) {}
        try { camera.setPreviewDisplay(h); camera.startPreview(); }
        catch (Exception e) { Log.e(TAG, "restart failed", e); }
    }

    public void surfaceDestroyed(SurfaceHolder h) { release(); }

    @Override protected void onPause() { super.onPause(); release(); }

    private void release() {
        if (camera != null) {
            try { camera.stopPreview(); } catch (Exception ignore) {}
            camera.release(); camera = null;
        }
    }
}