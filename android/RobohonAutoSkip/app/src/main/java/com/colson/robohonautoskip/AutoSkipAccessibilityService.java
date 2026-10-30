package com.colson.robohonautoskip;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.util.Log;

public class AutoSkipAccessibilityService extends AccessibilityService {

    private static final String TAG = "AutoSkipService";
    private static final String TARGET_ACTIVITY =
            "jp.co.sharp.android.rb.settings.initialsettings.InitialSettingsActivity";
    private static final String SKIP_TEXT = "Skip";

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;

        CharSequence className = event.getClassName();
        if (className == null) return;

        // Only act when the Robohon InitialSettingsActivity is shown
        if (!TARGET_ACTIVITY.contentEquals(className)) {
            return;
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;

        clickSkipButton(root);
    }

    private void clickSkipButton(AccessibilityNodeInfo node) {
        if (node == null) return;

        // Look for a node with text "Skip"
        if (node.getText() != null && SKIP_TEXT.contentEquals(node.getText())) {
            if (node.isClickable()) {
                boolean result = node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                Log.d(TAG, "Skip button clicked: " + result);
            }
            return;
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            clickSkipButton(node.getChild(i));
        }
    }

    @Override
    public void onInterrupt() {
        // No-op
    }
}
