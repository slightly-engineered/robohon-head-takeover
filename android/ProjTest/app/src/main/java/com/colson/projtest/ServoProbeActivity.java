package com.colson.projtest;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.util.Log;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

public class ServoProbeActivity extends Activity {
    private static final String TAG = "Servo";

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        new Thread(new Runnable() { public void run() { probe(); } }).start();
    }

    private void probe() {
        try {
            Class<?> c = Class.forName("jp.co.sharp.android.robot.RobotServoManager");

            Log.i(TAG, "=== RobotServoManager methods ===");
            for (Method m : c.getDeclaredMethods()) {
                StringBuilder sb = new StringBuilder(m.getName() + "(");
                for (Class<?> p : m.getParameterTypes()) sb.append(p.getSimpleName()).append(",");
                sb.append(") -> ").append(m.getReturnType().getSimpleName());
                Log.i(TAG, "  " + sb);
            }
            Log.i(TAG, "=== int fields ===");
            for (Field f : c.getDeclaredFields()) {
                if (f.getType() == int.class) {
                    f.setAccessible(true);
                    try { Log.i(TAG, "  " + f.getName() + " = " + f.getInt(null)); }
                    catch (Throwable ig) { Log.i(TAG, "  " + f.getName() + " (instance)"); }
                }
            }

            // dump ServoPosition / ServoStatus structure
            for (String cn : new String[]{"ServoPosition", "ServoStatus"}) {
                Class<?> sp = Class.forName("jp.co.sharp.android.robot." + cn);
                Log.i(TAG, "=== " + cn + " constructors ===");
                for (Constructor<?> k : sp.getDeclaredConstructors()) {
                    StringBuilder sb = new StringBuilder(cn + "(");
                    for (Class<?> p : k.getParameterTypes()) sb.append(p.getSimpleName()).append(",");
                    sb.append(")"); Log.i(TAG, "  " + sb);
                }
                Log.i(TAG, "=== " + cn + " fields ===");
                for (Field f : sp.getDeclaredFields())
                    Log.i(TAG, "  " + f.getType().getSimpleName() + " " + f.getName());
            }

            // get an instance
            Object rs = null;
            try { rs = c.getMethod("createInstance", Context.class).invoke(null, getApplicationContext()); }
            catch (NoSuchMethodException e) {
                try { rs = c.getMethod("getInstance").invoke(null); } catch (Throwable ignore) {}
            }
            Log.i(TAG, "instance = " + rs);
            if (rs == null) return;

            safeCall(rs, "startServoControl");
            safeCall(rs, "getServoStatus");
        } catch (Throwable t) { Log.e(TAG, "probe threw", t); }
    }

    // only calls no-arg methods; logs result or why it couldn't
    private void safeCall(Object o, String name) {
        try {
            Method m = o.getClass().getMethod(name);
            Object r = m.invoke(o);
            if (r != null && r.getClass().isArray()) {
                int n = java.lang.reflect.Array.getLength(r);
                StringBuilder sb = new StringBuilder(name + " -> array[" + n + "]: ");
                for (int i = 0; i < n; i++) sb.append(java.lang.reflect.Array.get(r, i)).append(" | ");
                Log.i(TAG, sb.toString());
            } else {
                Log.i(TAG, name + " -> " + r);
            }
        } catch (NoSuchMethodException e) {
            Log.i(TAG, name + " needs arguments (see method dump)");
        } catch (Throwable t) {
            Log.w(TAG, name + " threw: " + t);
        }
    }
}