package com.eyebrowscroller;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Point;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

public class EyebrowAccessibilityService extends AccessibilityService {

    public static EyebrowAccessibilityService instance;
    public static int scrollAmount = 500; // pixels to scroll, adjustable via sensitivity

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Not needed for gesture-based scrolling
    }

    @Override
    public void onInterrupt() {
        // Service interrupted
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        instance = null;
    }

    /**
     * Perform a scroll gesture on the screen.
     * @param scrollDown true = scroll down (content moves up), false = scroll up
     */
    public static void performScroll(boolean scrollDown) {
        if (instance == null) return;

        // Get screen dimensions
        WindowManager wm = (WindowManager) instance.getSystemService(WINDOW_SERVICE);
        DisplayMetrics metrics = new DisplayMetrics();
        wm.getDefaultDisplay().getMetrics(metrics);
        int screenWidth = metrics.widthPixels;
        int screenHeight = metrics.heightPixels;

        // Swipe from center of screen
        int centerX = screenWidth / 2;
        int startY, endY;

        if (scrollDown) {
            // Scroll down = swipe upward (finger moves up)
            startY = (int) (screenHeight * 0.65);
            endY = (int) (screenHeight * 0.35);
        } else {
            // Scroll up = swipe downward (finger moves down)
            startY = (int) (screenHeight * 0.35);
            endY = (int) (screenHeight * 0.65);
        }

        // Build the swipe gesture path
        Path swipePath = new Path();
        swipePath.moveTo(centerX, startY);
        swipePath.lineTo(centerX, endY);

        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(swipePath, 0, 300);

        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(stroke)
                .build();

        instance.dispatchGesture(gesture, new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                // Gesture completed successfully
            }

            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                // Gesture was cancelled
            }
        }, null);
    }
}
