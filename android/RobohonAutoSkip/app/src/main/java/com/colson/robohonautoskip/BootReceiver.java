package com.colson.robohonautoskip;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {

            new Handler().postDelayed(() -> {
                try {
                    Runtime.getRuntime().exec("input tap 540 1650");
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }, 5000);
        }
    }
}
