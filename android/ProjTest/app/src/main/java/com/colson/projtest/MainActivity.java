package com.colson.projtest;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.VideoView;

import java.io.File;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import android.content.Intent;

public class MainActivity extends Activity {

    private static final String TAG = "ProjTest";
    private static final String IMG_PATH   = Environment.getExternalStorageDirectory() + "/projector.png";
    private static final String VIDEO_PATH = Environment.getExternalStorageDirectory() + "/projector.mp4";

    private Object rp;
    private Class<?> rpClass;
    private int PW_ON = 1, PW_OFF = 0, OV_ON = 1;
    private boolean projectorOn = false, videoEnabled = false, apiReady = false, transitioning = false;
    private final Handler h = new Handler();
    private TextView ui;
    private ImageView image;
    private VideoView video;
    private Bitmap bmp;
    public static boolean sStarted = false;

    // --- Projector API boot-race retry ---
    // At boot the app launches early (it's the HOME launcher), often BEFORE Sharp's
    // robot projector system service is registered. createInstance() then returns
    // null. Instead of latching "not available" forever, we keep retrying until the
    // service shows up (it does within seconds of boot).
    private int apiAttempts = 0;
    private static final int MAX_API_ATTEMPTS = 40;   // ~60s of retries after boot
    private static final long API_RETRY_MS = 1500;

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent != null && intent.getBooleanExtra("TOGGLE", false)) toggle();
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        sStarted = true;

        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
                        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                        WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD |
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        try {
            KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
            km.newKeyguardLock("ProjTest").disableKeyguard();
        } catch (Throwable ignore) {}

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        FrameLayout.LayoutParams fill = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);

        video = new VideoView(this);
        FrameLayout.LayoutParams vlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        vlp.gravity = Gravity.CENTER;
        video.setLayoutParams(vlp);
        video.setVisibility(View.GONE);
        root.addView(video);

        image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setVisibility(View.GONE);
        root.addView(image, fill);

        ui = new TextView(this);
        ui.setTextSize(30);
        ui.setGravity(Gravity.CENTER);
        root.addView(ui, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Transparent top overlay so a tap always toggles, even over video.
        View touch = new View(this);
        touch.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { toggle(); }
        });
        root.addView(touch, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(root);

        goImmersive();
        initApi();      // prepare API (will self-retry if the service isn't up yet)
        showOff();
    }

    private void goImmersive() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN |
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) goImmersive();
    }

    private void clearMedia() {
        try { video.stopPlayback(); } catch (Throwable ignore) {}
        video.setVisibility(View.GONE);
        image.setVisibility(View.GONE);
        image.setImageDrawable(null);
        if (bmp != null) { bmp.recycle(); bmp = null; }
    }

    private void showOff() {
        projectorOn = false; transitioning = false;
        clearMedia();
        ui.setVisibility(View.VISIBLE);
        ui.setBackgroundColor(Color.parseColor("#202020"));
        ui.setTextColor(Color.WHITE);
        ui.setText("PROJECTOR OFF\n\nTAP TO TURN ON");
    }

    private void showOn() {
        projectorOn = true; transitioning = false;

        if (new File(VIDEO_PATH).exists()) {
            ui.setVisibility(View.GONE);
            image.setVisibility(View.GONE);
            video.setVisibility(View.VISIBLE);
            video.setVideoPath(VIDEO_PATH);
            video.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                public void onPrepared(MediaPlayer mp) { mp.setLooping(true); video.start(); }
            });
            video.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                public boolean onError(MediaPlayer mp, int what, int extra) {
                    Log.e(TAG, "video error what=" + what + " extra=" + extra);
                    video.setVisibility(View.GONE);
                    showImageOrText();
                    return true;
                }
            });
            return;
        }
        showImageOrText();
    }

    private void showImageOrText() {
        bmp = loadImage();
        if (bmp != null) {
            image.setImageBitmap(bmp);
            image.setVisibility(View.VISIBLE);
            ui.setVisibility(View.GONE);
        } else {
            image.setVisibility(View.GONE);
            ui.setVisibility(View.VISIBLE);
            ui.setBackgroundColor(Color.BLACK);
            ui.setTextColor(Color.parseColor("#39FF14"));
            ui.setText("PROJECTOR ON\n(no video or image)\n\nTAP TO TURN OFF");
        }
    }

    private void failOn() {
        showOff();
        ui.setText("POWER-ON FAILED\n\nTAP TO RETRY");
    }

    private Bitmap loadImage() {
        try {
            if (!new File(IMG_PATH).exists()) return null;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(IMG_PATH, o);
            int sample = 1;
            while (o.outWidth / sample > 1600 || o.outHeight / sample > 1600) sample *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            return BitmapFactory.decodeFile(IMG_PATH, o2);
        } catch (Throwable t) { Log.e(TAG, "loadImage failed", t); return null; }
    }

    private void toggle() {
        if (!apiReady) {
            // Service may just not be up yet (early boot). Show a warming message and
            // kick a fresh retry burst instead of dead-ending.
            ui.setVisibility(View.VISIBLE);
            ui.setBackgroundColor(Color.parseColor("#202020"));
            ui.setTextColor(Color.WHITE);
            ui.setText("PROJECTOR WARMING UP...\n\nTAP AGAIN IN A MOMENT");
            apiAttempts = 0;        // fresh retry budget on an explicit user request
            initApi();
            return;
        }
        if (transitioning) return;
        if (projectorOn) powerOff();
        else powerOn();
    }

    private int constOf(String n, int d) {
        try { return rpClass.getField(n).getInt(null); } catch (Throwable t) { return d; }
    }
    private Object call(String n, Class<?>[] t, Object... a) {
        try { return rpClass.getMethod(n, t).invoke(rp, a); }
        catch (Throwable e) { Log.w(TAG, n + "() failed: " + e); return null; }
    }

    private void initApi() {
        if (apiReady) return;
        try {
            if (rpClass == null) rpClass = Class.forName("jp.co.sharp.android.robot.RobotProjector");
            rp = rpClass.getMethod("createInstance", Context.class).invoke(null, getApplicationContext());
            if (rp == null) {
                Log.w(TAG, "createInstance null (attempt " + apiAttempts + ") - projector service not up yet");
                scheduleApiRetry();
                return;
            }
            PW_ON = constOf("PRJ_PW_ON", 1);
            PW_OFF = constOf("PRJ_PW_OFF", 0);
            OV_ON = constOf("PRJ_OV_ON", 1);
            try { rpClass.getMethod("initialize").invoke(rp); } catch (Throwable ignore) {}
            registerListener();
            apiReady = true;
            Log.i(TAG, "API ready (projector left OFF) after " + apiAttempts + " attempt(s)");
            // Clear any "warming up" / "not available" text once the service appears.
            if (!projectorOn && !transitioning) showOff();
        } catch (Throwable t) {
            Log.w(TAG, "initApi attempt " + apiAttempts + " threw: " + t);
            scheduleApiRetry();
        }
    }

    private void scheduleApiRetry() {
        if (apiReady) return;
        if (apiAttempts++ >= MAX_API_ATTEMPTS) {
            Log.e(TAG, "giving up on projector API after " + apiAttempts + " attempts");
            return;
        }
        h.postDelayed(new Runnable() { public void run() { initApi(); } }, API_RETRY_MS);
    }

    private void registerListener() {
        try {
            Class<?> l = Class.forName("jp.co.sharp.android.robot.RobotProjectorListener");
            if (!l.isInterface()) return;
            Object listener = Proxy.newProxyInstance(l.getClassLoader(), new Class<?>[]{l},
                    new InvocationHandler() {
                        public Object invoke(Object p, Method m, Object[] a) {
                            String n = m.getName();
                            if (n.equals("onCompletePowerOn"))
                                h.post(new Runnable() { public void run() { enableVideo(); showOn(); } });
                            if (n.equals("onAbend"))
                                h.post(new Runnable() { public void run() { videoEnabled = false; showOff(); } });
                            if (m.getReturnType() == int.class) return 0;
                            if (m.getReturnType() == boolean.class) return false;
                            if (m.getReturnType() == String.class) return "";
                            return null;
                        }
                    });
            call("registerProjectorListener", new Class<?>[]{l}, listener);
        } catch (Throwable t) { Log.w(TAG, "listener setup failed: " + t); }
    }

    private void powerOn() {
        transitioning = true;
        clearMedia();
        ui.setVisibility(View.VISIBLE);
        ui.setText("TURNING ON...");
        Log.i(TAG, "setPowerState(ON) = " + call("setPowerState", new Class<?>[]{int.class}, PW_ON));
        poll(0);
    }

    private void powerOff() {
        videoEnabled = false;
        Log.i(TAG, "setPowerState(OFF) = " + call("setPowerState", new Class<?>[]{int.class}, PW_OFF));
        showOff();
    }

    private void enableVideo() {   // projector engine video output (not the file)
        if (videoEnabled) return;
        videoEnabled = true;
        Log.i(TAG, "setOutputVideoState(ON) = " + call("setOutputVideoState", new Class<?>[]{int.class}, OV_ON));
    }

    private void poll(final int n) {
        if (n > 10) { failOn(); Log.w(TAG, "power-on timed out"); return; }
        h.postDelayed(new Runnable() {
            public void run() {
                Object ps = call("getPowerState", new Class<?>[]{});
                Log.i(TAG, "poll#" + n + " powerState=" + ps);
                if (ps instanceof Integer && (Integer) ps == PW_ON) { enableVideo(); showOn(); return; }
                poll(n + 1);
            }
        }, 1000);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try { if (apiReady) call("setPowerState", new Class<?>[]{int.class}, PW_OFF); } catch (Throwable ignore) {}
        clearMedia();
    }
}
