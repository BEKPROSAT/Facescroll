package com.eyebrowscroller;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;
import com.google.mlkit.vision.face.FaceLandmark;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final int CAMERA_PERMISSION_REQUEST = 100;
    private static final int NOTIFICATION_PERMISSION_REQUEST = 101;

    // Smile = teeth/tongue visible (high smiling probability)
    // ML Kit smiling probability goes 0.0 (neutral) to 1.0 (big smile/teeth)
    // Tongue out causes an even bigger mouth opening, registering as very high smile prob
    private static final float SMILE_BIG_THRESHOLD  = 0.85f;  // big smile / tongue out → scroll DOWN
    private static final float SMILE_MID_THRESHOLD  = 0.55f;  // medium smile / teeth showing → scroll UP
    private static final float SMILE_NEUTRAL_MAX    = 0.30f;  // below this = neutral face (reset state)

    private ExecutorService cameraExecutor;
    private FaceDetector faceDetector;
    private TextView statusText, gestureText, sensitivityValue, accessibilityStatus;
    private SeekBar sensitivitySlider;
    private PreviewView cameraPreview;
    private MaterialButton btnEnableAccessibility;

    // Gesture cooldown
    private long lastGestureTime = 0;
    private static final long GESTURE_COOLDOWN_MS = 1200;

    // State tracking
    private boolean wasBigSmile  = false;
    private boolean wasMidSmile  = false;

    // Smoothing: keep last N smile values to avoid flicker
    private final float[] smileBuffer = new float[4];
    private int smileBufferIdx = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText          = findViewById(R.id.statusText);
        gestureText         = findViewById(R.id.gestureText);
        sensitivityValue    = findViewById(R.id.sensitivityValue);
        accessibilityStatus = findViewById(R.id.accessibilityStatus);
        sensitivitySlider   = findViewById(R.id.sensitivitySlider);
        cameraPreview       = findViewById(R.id.cameraPreview);
        btnEnableAccessibility = findViewById(R.id.btnEnableAccessibility);

        sensitivitySlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                sensitivityValue.setText(progress + "%");
                EyebrowAccessibilityService.scrollAmount = (int)(200 + 600 * (progress / 100f));
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        btnEnableAccessibility.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));

        // ML Kit — enable smile/eye classification
        FaceDetectorOptions options = new FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setMinFaceSize(0.1f)
                .enableTracking()
                .build();

        faceDetector = FaceDetection.getClient(options);
        cameraExecutor = Executors.newSingleThreadExecutor();

        requestPermissionsAndStart();
    }

    private void requestPermissionsAndStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        NOTIFICATION_PERMISSION_REQUEST);
                return;
            }
        }
        requestCameraAndStart();
    }

    private void requestCameraAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startForegroundServiceAndCamera();
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA},
                    CAMERA_PERMISSION_REQUEST);
        }
    }

    private void startForegroundServiceAndCamera() {
        Intent serviceIntent = new Intent(this, CameraForegroundService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
        startCamera();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateAccessibilityStatus();
    }

    private void updateAccessibilityStatus() {
        boolean enabled = isAccessibilityServiceEnabled();
        if (enabled) {
            accessibilityStatus.setText("✓ Accessibility service active — ready to scroll!");
            accessibilityStatus.setTextColor(0xFF00D4FF);
        } else {
            accessibilityStatus.setText("⚠ Tap button above to enable Accessibility Service");
            accessibilityStatus.setTextColor(0xFFFF6B6B);
        }
    }

    private boolean isAccessibilityServiceEnabled() {
        String prefString = Settings.Secure.getString(
                getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (prefString == null) return false;
        TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
        splitter.setString(prefString);
        while (splitter.hasNext()) {
            if (splitter.next().equalsIgnoreCase(
                    getPackageName() + "/" + EyebrowAccessibilityService.class.getName()))
                return true;
        }
        return false;
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future =
                ProcessCameraProvider.getInstance(this);

        future.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = future.get();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(cameraPreview.getSurfaceProvider());

                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

                imageAnalysis.setAnalyzer(cameraExecutor, imageProxy -> {
                    @SuppressWarnings("UnsafeOptInUsageError")
                    android.media.Image mediaImage = imageProxy.getImage();
                    if (mediaImage != null) {
                        InputImage image = InputImage.fromMediaImage(
                                mediaImage, imageProxy.getImageInfo().getRotationDegrees());
                        faceDetector.process(image)
                                .addOnSuccessListener(faces -> {
                                    processFaces(faces);
                                    imageProxy.close();
                                })
                                .addOnFailureListener(e -> imageProxy.close());
                    } else {
                        imageProxy.close();
                    }
                });

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this,
                        CameraSelector.DEFAULT_FRONT_CAMERA, preview, imageAnalysis);

                runOnUiThread(() -> statusText.setText("Camera active — look at screen 👀"));

            } catch (Exception e) {
                runOnUiThread(() -> statusText.setText("Camera error: " + e.getMessage()));
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private float smoothSmile(float raw) {
        smileBuffer[smileBufferIdx % smileBuffer.length] = raw;
        smileBufferIdx++;
        float sum = 0;
        for (float v : smileBuffer) sum += v;
        return sum / smileBuffer.length;
    }

    private void processFaces(List<Face> faces) {
        if (faces.isEmpty()) {
            runOnUiThread(() -> {
                statusText.setText("No face detected — point camera at your face");
                gestureText.setText("Waiting...");
            });
            wasBigSmile = false;
            wasMidSmile = false;
            return;
        }

        Face face = faces.get(0);
        Float rawSmile = face.getSmilingProbability();

        if (rawSmile == null) {
            runOnUiThread(() -> statusText.setText("Face detected — move a bit closer"));
            return;
        }

        // Smooth the value to avoid flicker
        float smile = smoothSmile(rawSmile);

        boolean isBigSmile = smile >= SMILE_BIG_THRESHOLD;   // tongue out / huge grin → scroll DOWN
        boolean isMidSmile = smile >= SMILE_MID_THRESHOLD && smile < SMILE_BIG_THRESHOLD; // teeth showing → scroll UP
        boolean isNeutral  = smile < SMILE_NEUTRAL_MAX;

        long now = System.currentTimeMillis();
        boolean cooldownPassed = (now - lastGestureTime) > GESTURE_COOLDOWN_MS;

        if (isBigSmile && !wasBigSmile && cooldownPassed) {
            lastGestureTime = now;
            wasBigSmile = true;
            wasMidSmile = false;
            EyebrowAccessibilityService.performScroll(true); // scroll DOWN
            runOnUiThread(() -> {
                gestureText.setText("😛 TONGUE / BIG SMILE → Scrolling DOWN ↓");
                statusText.setText("Gesture detected!");
            });

        } else if (isMidSmile && !wasMidSmile && !wasBigSmile && cooldownPassed) {
            lastGestureTime = now;
            wasMidSmile = true;
            wasBigSmile = false;
            EyebrowAccessibilityService.performScroll(false); // scroll UP
            runOnUiThread(() -> {
                gestureText.setText("😁 TEETH SHOWING → Scrolling UP ↑");
                statusText.setText("Gesture detected!");
            });

        } else if (isNeutral) {
            wasBigSmile = false;
            wasMidSmile = false;
            runOnUiThread(() -> {
                gestureText.setText(String.format("😐 Neutral  smile:%.2f", smile));
                statusText.setText("Face detected ✓");
            });
        } else {
            // In between — just update display
            runOnUiThread(() ->
                gestureText.setText(String.format("🙂 smile:%.2f", smile)));
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
            @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == NOTIFICATION_PERMISSION_REQUEST) {
            requestCameraAndStart();
        } else if (requestCode == CAMERA_PERMISSION_REQUEST) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startForegroundServiceAndCamera();
            } else {
                Toast.makeText(this, "Camera permission required!", Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cameraExecutor.shutdown();
        faceDetector.close();
    }
}
