package com.colson.projtest;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.hardware.Camera;
import android.os.Bundle;
import android.util.Log;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

public class TrackActivity extends Activity implements SurfaceHolder.Callback, Camera.PreviewCallback {

    private static final String TAG = "Track";
    private Camera camera;
    private int pw = 320, ph = 240;
    private SurfaceView sv;
    private Overlay overlay;

    // tracked color (YUV chroma) and tuning
    private volatile int tU = 128, tV = 128;
    private volatile boolean haveTarget = false;
    private final int colorThresh = 34;   // chroma match window
    private final int minSat = 16;        // ignore near-gray pixels
    private final int minCount = 4;       // min matching samples to trust
    private final int step = 1;           // sample every Nth pixel
    private final int searchWin = 70;     // half-size of the local search window (px)
    private final float smooth = 0.4f;    // 0..1, lower = smoother/laggier

    // latest centroid shown (-1 = none) and last good lock
    private volatile float cx = -1, cy = -1;
    private float lastCx = -1, lastCy = -1;

    // pending tap (view coords)
    private volatile float tapX = -1, tapY = -1, tapVW = 0, tapVH = 0;

    // ---- UDP centroid broadcast to the ESP32 neck follower ----
    private static final String DEST_IP = "255.255.255.255"; // limited broadcast: reaches NeckTrack on any LAN
    private static final int DEST_PORT = 8888;
    private static final int SEND_INTERVAL_MS = 40;         // ~25 Hz
    private DatagramSocket sock;
    private volatile boolean sending = false;
    private Thread sender;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        FrameLayout root = new FrameLayout(this);
        sv = new SurfaceView(this);
        sv.getHolder().addCallback(this);
        root.addView(sv);
        overlay = new Overlay(this);
        root.addView(overlay);
        root.setOnTouchListener(new View.OnTouchListener() {
            public boolean onTouch(View v, MotionEvent e) {
                if (e.getAction() == MotionEvent.ACTION_DOWN) {
                    tapX = e.getX(); tapY = e.getY();
                    tapVW = v.getWidth(); tapVH = v.getHeight();
                }
                return true;
            }
        });
        setContentView(root);
        startSender();
    }

    public void surfaceCreated(SurfaceHolder h) {
        try {
            camera = Camera.open(0);
            camera.setDisplayOrientation(0);
            Camera.Parameters p = camera.getParameters();
            Camera.Size best = null;
            for (Camera.Size s : p.getSupportedPreviewSizes())
                if (best == null || Math.abs(s.width*s.height - 320*240) < Math.abs(best.width*best.height - 320*240))
                    best = s;
            if (best != null) { pw = best.width; ph = best.height; p.setPreviewSize(pw, ph); }
            camera.setParameters(p);
            camera.setPreviewDisplay(h);
            camera.setPreviewCallback(this);
            camera.startPreview();
            Log.i(TAG, "preview " + pw + "x" + ph);
        } catch (Exception e) { Log.e(TAG, "open failed", e); }
    }

    public void surfaceChanged(SurfaceHolder h, int f, int w, int ht) {}
    public void surfaceDestroyed(SurfaceHolder h) { release(); }
    @Override protected void onPause() { super.onPause(); release(); stopSender(); }

    private void release() {
        if (camera != null) {
            try { camera.setPreviewCallback(null); camera.stopPreview(); } catch (Exception ignore) {}
            camera.release(); camera = null;
        }
    }

    // ---- UDP sender: broadcast the smoothed centroid as "x,y" (-1000..1000) ----
    private void startSender() {
        if (sending) return;
        sending = true;
        sender = new Thread(new Runnable() {
            public void run() {
                try {
                    sock = new DatagramSocket();
                    sock.setBroadcast(true);
                    InetAddress dst = InetAddress.getByName(DEST_IP);
                    while (sending) {
                        float lx = cx, ly = cy;
                        if (lx >= 0 && pw > 0 && ph > 0) {
                            int xn = (int) ((lx / pw * 2f - 1f) * 1000f);
                            int yn = (int) ((ly / ph * 2f - 1f) * 1000f);
                            if (xn < -1000) xn = -1000; if (xn > 1000) xn = 1000;
                            if (yn < -1000) yn = -1000; if (yn > 1000) yn = 1000;
                            byte[] msg = (xn + "," + yn + "\n").getBytes();
                            try {
                                sock.send(new DatagramPacket(msg, msg.length, dst, DEST_PORT));
                            } catch (Exception sendErr) { /* transient; keep going */ }
                        }
                        Thread.sleep(SEND_INTERVAL_MS);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "sender failed", e);
                } finally {
                    if (sock != null) { try { sock.close(); } catch (Exception ignore) {} sock = null; }
                }
            }
        }, "centroid-udp");
        sender.start();
    }

    private void stopSender() {
        sending = false;
        if (sender != null) { sender.interrupt(); sender = null; }
    }

    private int clamp(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }

    // scan a rectangular region; returns {sumX, sumY, count}
    private long[] scanRegion(byte[] data, int w, int h, int x0, int y0, int x1, int y1) {
        int ySize = w * h;
        long sx = 0, sy = 0; int cnt = 0;
        for (int y = y0; y < y1; y += step) {
            int uvRow = ySize + (y / 2) * w;
            for (int x = x0; x < x1; x += step) {
                int ci = uvRow + (x / 2) * 2;
                int V = data[ci] & 0xff, U = data[ci + 1] & 0xff;
                int sat = Math.abs(U - 128) + Math.abs(V - 128);
                boolean hit = haveTarget
                        ? (Math.abs(U - tU) < colorThresh && Math.abs(V - tV) < colorThresh && sat > minSat)
                        : (sat > minSat + 30);
                if (hit) { sx += x; sy += y; cnt++; }
            }
        }
        return new long[]{sx, sy, cnt};
    }

    // NV21: Y plane (w*h), then interleaved V,U at half resolution
    public void onPreviewFrame(byte[] data, Camera cam) {
        if (data == null) return;
        int w = pw, h = ph, ySize = w * h;

        if (tapX >= 0 && tapVW > 0) {           // lock onto tapped color
            int fx = (int)(tapX / tapVW * w), fy = (int)(tapY / tapVH * h);
            fx = clamp(fx, 0, w - 1); fy = clamp(fy, 0, h - 1);
            int ci = ySize + (fy / 2) * w + (fx / 2) * 2;
            if (ci + 1 < data.length) { tV = data[ci] & 0xff; tU = data[ci + 1] & 0xff; haveTarget = true; }
            lastCx = fx; lastCy = fy;           // start the search where you tapped
            tapX = -1;
        }

        long[] r = null;
        if (lastCx >= 0) {                      // look locally around last lock first
            int x0 = clamp((int) lastCx - searchWin, 0, w), x1 = clamp((int) lastCx + searchWin, 0, w);
            int y0 = clamp((int) lastCy - searchWin, 0, h), y1 = clamp((int) lastCy + searchWin, 0, h);
            r = scanRegion(data, w, h, x0, y0, x1, y1);
        }
        if (r == null || r[2] < minCount) {     // reacquire: full-frame scan
            r = scanRegion(data, w, h, 0, 0, w, h);
        }

        if (r[2] >= minCount) {
            float ncx = (float) r[0] / r[2], ncy = (float) r[1] / r[2];
            if (lastCx < 0) { lastCx = ncx; lastCy = ncy; }
            else { lastCx += smooth * (ncx - lastCx); lastCy += smooth * (ncy - lastCy); }
            cx = lastCx; cy = lastCy;
        } else {
            cx = -1; cy = -1; lastCx = -1; lastCy = -1;
        }
        overlay.postInvalidate();
    }

    class Overlay extends View {
        Paint paint = new Paint();
        Overlay(Context c) {
            super(c);
            paint.setColor(Color.GREEN);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(4);
            paint.setAntiAlias(true);
        }
        @Override protected void onDraw(Canvas canvas) {
            float lx = cx, ly = cy;
            if (lx < 0) return;
            float vx = lx / pw * getWidth();
            float vy = ly / ph * getHeight();
            canvas.drawCircle(vx, vy, 40, paint);
            canvas.drawLine(vx - 60, vy, vx + 60, vy, paint);
            canvas.drawLine(vx, vy - 60, vx, vy + 60, paint);
        }
    }
}
