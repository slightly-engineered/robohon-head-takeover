package com.colson.projtest;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.util.Log;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

public class ServoMoveActivity extends Activity {
    private static final String TAG = "ServoMove";
    private static final int NECK = 8;
    private static final int DELTA = 1500;
    private static final int MOVE_TIME = 500;

    private Class<?> c, sp, arrClass;
    private Object rsm;
    private Field fId, fPos;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        new Thread(new Runnable(){ public void run(){ try { go(); } catch (Throwable t){ Log.e(TAG,"threw",t);} }}).start();
    }

    private void call(String name) { try { Log.i(TAG, name + "=" + c.getMethod(name).invoke(rsm)); } catch (Throwable t) { Log.w(TAG, name + " " + t); } }

    private void go() throws Exception {
        c = Class.forName("jp.co.sharp.android.robot.RobotServoManager");
        rsm = c.getMethod("createInstance", Context.class).invoke(null, getApplicationContext());
        if (rsm == null) { Log.e(TAG, "no instance"); return; }

        try {
            Method mTok = c.getDeclaredMethod("setToken", boolean.class);
            mTok.setAccessible(true);
            Log.i(TAG, "setToken=" + mTok.invoke(rsm, true));
        } catch (Throwable t) { Log.w(TAG, "setToken " + t); }

        call("getLockStatus");
        call("unlockFixedServoPosition");
        try { Log.i(TAG, "unlockServo(8)=" + c.getMethod("unlockServo", int.class).invoke(rsm, NECK)); }
        catch (Throwable t) { Log.w(TAG, "unlockServo " + t); }
        call("getLockStatus");
        call("startServoControl");

        sp = Class.forName("jp.co.sharp.android.robot.ServoPosition");
        fId = sp.getDeclaredField("servo_id"); fId.setAccessible(true);
        fPos = sp.getDeclaredField("pos"); fPos.setAccessible(true);
        arrClass = Array.newInstance(sp, 0).getClass();

        int home = read("getServoPosition");
        int min  = read("getMinServoPosition");
        int max  = read("getMaxServoPosition");
        Log.i(TAG, "neck(8) home=" + home + " min=" + min + " max=" + max);

        int base = (home == Integer.MIN_VALUE) ? 0 : home;
        int target = clamp(base + DELTA, min, max);
        Log.i(TAG, "move neck -> " + target);
        Log.i(TAG, "setServoPosition=" + move(NECK, target));
        Thread.sleep(1500);
        Log.i(TAG, "return home -> " + base);
        Log.i(TAG, "setServoPosition=" + move(NECK, base));
    }

    private int read(String method) throws Exception {
        Object arr = Array.newInstance(sp, 1);
        Object e = sp.getConstructor().newInstance();
        fId.setInt(e, NECK);
        Array.set(arr, 0, e);
        Object ret = c.getMethod(method, arrClass).invoke(rsm, arr);
        Log.i(TAG, method + " ret=" + ret);
        try { return fPos.getInt(Array.get(arr, 0)); } catch (Throwable t) { return Integer.MIN_VALUE; }
    }

    private Object move(int id, int pos) throws Exception {
        Object arr = Array.newInstance(sp, 1);
        Object e = sp.getConstructor().newInstance();
        fId.setInt(e, id); fPos.setInt(e, pos);
        Array.set(arr, 0, e);
        return c.getMethod("setServoPosition", arrClass, int.class).invoke(rsm, arr, MOVE_TIME);
    }

    private int clamp(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }
}