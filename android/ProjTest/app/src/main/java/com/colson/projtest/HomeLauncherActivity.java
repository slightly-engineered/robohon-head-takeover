package com.colson.projtest;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

public class HomeLauncherActivity extends Activity {
    private static final String TAG = "Home";

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        startService(new Intent(this, DevServerService.class));
        Log.i(TAG, "onCreate  started=" + MainActivity.sStarted);
        handleHome();
    }
    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        Log.i(TAG, "onNewIntent  started=" + MainActivity.sStarted);
        handleHome();
    }
    private void handleHome() {
        boolean toggle = MainActivity.sStarted;   // don't toggle on first boot; do on every press after
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (toggle) i.putExtra("TOGGLE", true);
        startActivity(i);
    }
}