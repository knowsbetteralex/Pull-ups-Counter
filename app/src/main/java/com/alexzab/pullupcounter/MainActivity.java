package com.alexzab.pullupcounter;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult;

import java.util.Locale;

public final class MainActivity extends Activity implements PoseEngine.Listener, Camera2Controller.Listener {
    private static final int CAMERA_PERMISSION_REQUEST = 10;

    private ImageView cameraView;
    private OverlayView overlayView;
    private TextView countText;
    private TextView stateText;
    private TextView debugText;
    private PoseEngine poseEngine;
    private Camera2Controller cameraController;
    private final PullupDetector detector = new PullupDetector();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        cameraView = findViewById(R.id.camera_view);
        overlayView = findViewById(R.id.overlay_view);
        countText = findViewById(R.id.count_text);
        stateText = findViewById(R.id.state_text);
        debugText = findViewById(R.id.debug_text);
        Button reset = findViewById(R.id.reset_button);
        Button calibrate = findViewById(R.id.calibrate_button);

        overlayView.setBarSelectionListener(normalizedY -> {
            detector.setCalibratedBarY(normalizedY);
            stateText.setText("РАСПРЯМИ РУКИ");
            debugText.setText(String.format(Locale.US,
                    "Перекладина: y = %.3f", normalizedY));
        });

        reset.setOnClickListener(v -> {
            detector.reset();
            countText.setText("0");
            stateText.setText(detector.isCalibrated() ? "ПОИСК НИЖНЕЙ ТОЧКИ" : "УКАЖИТЕ ПЕРЕКЛАДИНУ");
        });

        calibrate.setOnClickListener(v -> {
            detector.clearCalibration();
            overlayView.setBarYNormalized(null);
            overlayView.beginBarSelection();
            stateText.setText("УКАЖИТЕ ПЕРЕКЛАДИНУ");
            debugText.setText("Коснитесь пальцем линии перекладины на изображении");
        });

        overlayView.beginBarSelection();
        stateText.setText("УКАЖИТЕ ПЕРЕКЛАДИНУ");
        debugText.setText("Коснитесь пальцем линии перекладины на изображении");

        try {
            poseEngine = new PoseEngine(this, this);
        } catch (RuntimeException e) {
            showError("MediaPipe не запустился: " + e.getMessage());
            return;
        }

        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST);
        }
    }

    private void startCamera() {
        if (cameraController != null) return;
        if (!detector.isCalibrated()) stateText.setText("УКАЖИТЕ ПЕРЕКЛАДИНУ");
        cameraController = new Camera2Controller(this, this);
        cameraController.start();
    }

    @Override
    public void onFrame(Bitmap bitmap) {
        runOnUiThread(() -> cameraView.setImageBitmap(bitmap));
        if (poseEngine != null) poseEngine.detect(bitmap);
    }

    @Override
    public void onPose(PoseLandmarkerResult result, int inputWidth, int inputHeight, long inferenceMs) {
        runOnUiThread(() -> {
            overlayView.setResult(result, inputWidth, inputHeight);

            if (!detector.isCalibrated()) {
                stateText.setText("УКАЖИТЕ ПЕРЕКЛАДИНУ");
                debugText.setText("Коснитесь пальцем линии перекладины на изображении");
                return;
            }

            if (result.landmarks().isEmpty()) {
                stateText.setText("ЧЕЛОВЕК НЕ НАЙДЕН");
                debugText.setText("Встань полностью в кадр");
                return;
            }

            PullupDetector.Result r = detector.process(result.landmarks().get(0));
            countText.setText(Integer.toString(r.count));
            stateText.setText(stateName(r));

            if (!r.poseReliable) {
                debugText.setText("Плечи / локти / кисти видны недостаточно хорошо");
            } else {
                debugText.setText(String.format(Locale.US,
                        "L %.0f°   R %.0f°   mouthY %.3f   barY %.3f   %d ms",
                        r.leftAngle, r.rightAngle, r.mouthY, r.barY, inferenceMs));
            }
        });
    }

    private static String stateName(PullupDetector.Result r) {
        if (!r.calibrated) return "УКАЖИТЕ ПЕРЕКЛАДИНУ";
        if (!r.poseReliable) return "ПОЗА НЕУВЕРЕННАЯ";
        switch (r.state) {
            case DOWN: return "ВНИЗУ — ГОТОВ";
            case GOING_UP: return "ВВЕРХ ↑";
            case UP: return r.countedNow ? "ЗАСЧИТАНО ✓" : "ВВЕРХУ";
            case GOING_DOWN: return "ВНИЗ ↓";
            default: return "РАСПРЯМИ РУКИ";
        }
    }

    @Override
    public void onError(String message) {
        runOnUiThread(() -> showError(message));
    }

    private void showError(String message) {
        stateText.setText("ОШИБКА");
        debugText.setText(message == null ? "Неизвестная ошибка" : message);
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_REQUEST && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            showError("Нужно разрешение на камеру");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (poseEngine != null && cameraController == null
                && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        }
    }

    @Override
    protected void onPause() {
        if (cameraController != null) {
            cameraController.close();
            cameraController = null;
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (poseEngine != null) poseEngine.close();
        super.onDestroy();
    }
}
