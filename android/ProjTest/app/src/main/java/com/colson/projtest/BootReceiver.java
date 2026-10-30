package com.colson.projtest;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    public void onReceive(Context c, Intent i) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(i.getAction())) {
            c.startService(new Intent(c, DevServerService.class));
            Intent l = new Intent(c, HomeLauncherActivity.class);
            l.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(l);
        }
    }
}