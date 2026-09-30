package com.alexzab.pullupcounter;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult;

import java.util.List;
import java.util.Locale;

public final class MainActivity extends Activity implements PoseEngine.Listener, Camera2Controller.Listener {
    private static final int CAMERA_PERMISSION_REQUEST = 10;

    private ImageView cameraView;
    private OverlayView overlayView;
    private TextView countText;
    private TextView todayTotalText;
    private TextView stateText;
    private TextView debugText;
    private PoseEngine poseEngine;
    private Camera2Controller cameraController;
    private StatsStore statsStore;
    private final PullupDetector detector = new PullupDetector();
    private final AutoBarDetector autoBarDetector = new AutoBarDetector();

    private volatile Bitmap latestFrame;
    private volatile boolean autoCalibrating = true;
    private volatile boolean manualSelectionMode = false;

    private long activeAttemptId = -1L;
    private long activeDayStart;
    private int currentAttemptReps = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statsStore = new StatsStore(this);
        activeDayStart = StatsStore.startOfDay(System.currentTimeMillis());

        cameraView = findViewById(R.id.camera_view);
        overlayView = findViewById(R.id.overlay_view);
        countText = findViewById(R.id.count_text);
        todayTotalText = findViewById(R.id.today_total_text);
        stateText = findViewById(R.id.state_text);
        debugText = findViewById(R.id.debug_text);
        TextView reset = findViewById(R.id.reset_button);
        TextView manual = findViewById(R.id.calibrate_button);
        TextView auto = findViewById(R.id.auto_button);

        findViewById(R.id.today_total_container).setOnClickListener(v ->
                startActivity(new Intent(this, HistoryActivity.class)));

        overlayView.setBarSelectionListener(normalizedY -> {
            manualSelectionMode = false;
            autoCalibrating = false;
            autoBarDetector.reset();
            detector.setCalibratedBarY(normalizedY);
            overlayView.setBarYNormalized(normalizedY, false);
            stateText.setText("ПЕРЕКЛАДИНА ЗАДАНА ✓");
            debugText.setText(String.format(Locale.US,
                    "Ручная линия: y = %.3f • можно начинать", normalizedY));
        });

        reset.setOnClickListener(v -> {
            detector.reset();
            activeAttemptId = -1L;
            currentAttemptReps = 0;
            activeDayStart = StatsStore.startOfDay(System.currentTimeMillis());
            countText.setText("0");
            refreshTodayTotal();
            if (detector.isCalibrated()) {
                stateText.setText("ГОТОВ К ПОДТЯГИВАНИЯМ");
                debugText.setText("Новая попытка • положение перекладины сохранено");
            } else if (manualSelectionMode) {
                stateText.setText("РУЧНАЯ КОРРЕКТИРОВКА");
            } else {
                stateText.setText("АВТОПОИСК ПЕРЕКЛАДИНЫ");
            }
        });

        manual.setOnClickListener(v -> startManualCalibration());
        auto.setOnClickListener(v -> startAutoCalibration());

        refreshTodayTotal();
        startAutoCalibration();

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

    private void startAutoCalibration() {
        autoCalibrating = true;
        manualSelectionMode = false;
        autoBarDetector.reset();
        detector.clearCalibration();
        if (overlayView != null) overlayView.beginAutoBarSearch();
        if (stateText != null) stateText.setText("АВТОПОИСК ПЕРЕКЛАДИНЫ");
        if (debugText != null) {
            debugText.setText("Возьмитесь за перекладину и спокойно повисните на прямых руках");
        }
    }

    private void startManualCalibration() {
        autoCalibrating = false;
        manualSelectionMode = true;
        autoBarDetector.reset();
        detector.clearCalibration();
        overlayView.setBarYNormalized(null, false);
        overlayView.beginBarSelection();
        stateText.setText("РУЧНАЯ КОРРЕКТИРОВКА");
        debugText.setText("Коснитесь перекладины на изображении; линию можно подвинуть пальцем");
    }

    private void startCamera() {
        if (cameraController != null) return;
        cameraController = new Camera2Controller(this, this);
        cameraController.start();
    }

    @Override
    public void onFrame(Bitmap bitmap) {
        latestFrame = bitmap;
        runOnUiThread(() -> cameraView.setImageBitmap(bitmap));
        if (poseEngine != null) poseEngine.detect(bitmap);
    }

    @Override
    public void onPose(PoseLandmarkerResult result, int inputWidth, int inputHeight, long inferenceMs) {
        List<NormalizedLandmark> points = result.landmarks().isEmpty()
                ? null : result.landmarks().get(0);

        AutoBarDetector.Observation observation = null;
        boolean justAutoCalibrated = false;

        if (autoCalibrating && !manualSelectionMode && !detector.isCalibrated() && points != null) {
            observation = autoBarDetector.observe(latestFrame, points);
            if (observation.ready) {
                detector.setCalibratedBarY(observation.normalizedY);
                autoCalibrating = false;
                justAutoCalibrated = true;
            }
        }

        final AutoBarDetector.Observation autoObservation = observation;
        final boolean autoReadyNow = justAutoCalibrated;

        runOnUiThread(() -> {
            ensureDayBoundary();
            overlayView.setResult(result, inputWidth, inputHeight);

            if (autoReadyNow && autoObservation != null) {
                overlayView.setBarYNormalized(autoObservation.normalizedY, true);
                stateText.setText("ПЕРЕКЛАДИНА НАЙДЕНА ✓");
                debugText.setText(String.format(Locale.US,
                        "Авто y = %.3f • уверенность %.0f%% • если неточно — нажмите «Линия»",
                        autoObservation.normalizedY, autoObservation.confidence * 100f));
                return;
            }

            if (!detector.isCalibrated()) {
                if (manualSelectionMode) {
                    stateText.setText("РУЧНАЯ КОРРЕКТИРОВКА");
                    debugText.setText("Коснитесь перекладины на изображении");
                    return;
                }

                stateText.setText("АВТОПОИСК ПЕРЕКЛАДИНЫ");
                if (result.landmarks().isEmpty()) {
                    debugText.setText("Встаньте в кадр и возьмитесь за перекладину");
                } else if (autoObservation == null || !autoObservation.candidateFound) {
                    debugText.setText("Повисните на прямых руках — кисти должны быть хорошо видны");
                } else {
                    debugText.setText(String.format(Locale.US,
                            "Стабилизация %d/%d • %s • уверенность %.0f%%",
                            Math.min(autoObservation.stableSamples, autoBarDetector.getRequiredSamples()),
                            autoBarDetector.getRequiredSamples(),
                            autoObservation.edgeRefined ? "контур найден" : "по положению кистей",
                            autoObservation.confidence * 100f));
                }
                return;
            }

            if (result.landmarks().isEmpty()) {
                stateText.setText("ЧЕЛОВЕК НЕ НАЙДЕН");
                debugText.setText("Встаньте полностью в кадр");
                return;
            }

            PullupDetector.Result r = detector.process(result.landmarks().get(0));
            countText.setText(Integer.toString(r.count));
            stateText.setText(stateName(r));

            if (r.countedNow) {
                currentAttemptReps = r.count;
                activeAttemptId = statsStore.saveAttemptProgress(activeAttemptId, currentAttemptReps);
                refreshTodayTotal();
            }

            if (!r.poseReliable) {
                debugText.setText("Плечи, локти или кисти видны недостаточно хорошо");
            } else {
                debugText.setText(String.format(Locale.US,
                        "Локти %.0f° / %.0f°   •   линия %.3f   •   %d ms",
                        r.leftAngle, r.rightAngle, r.barY, inferenceMs));
            }
        });
    }

    private void ensureDayBoundary() {
        long todayStart = StatsStore.startOfDay(System.currentTimeMillis());
        if (todayStart == activeDayStart) return;

        activeDayStart = todayStart;
        activeAttemptId = -1L;
        currentAttemptReps = 0;
        detector.reset();
        countText.setText("0");
        refreshTodayTotal();
    }

    private void refreshTodayTotal() {
        if (todayTotalText != null && statsStore != null) {
            todayTotalText.setText(Integer.toString(statsStore.getTodayTotal()));
        }
    }

    private static String stateName(PullupDetector.Result r) {
        if (!r.calibrated) return "КАЛИБРОВКА";
        if (!r.poseReliable) return "ПОЗА НЕУВЕРЕННАЯ";
        switch (r.state) {
            case DOWN: return "ВНИЗУ — ГОТОВ";
            case GOING_UP: return "ВВЕРХ ↑";
            case UP: return r.countedNow ? "ЗАСЧИТАНО ✓" : "ВВЕРХУ";
            case GOING_DOWN: return "ВНИЗ ↓";
            default: return "РАСПРЯМИТЕ РУКИ";
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
        ensureDayBoundary();
        refreshTodayTotal();
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
        if (statsStore != null) statsStore.close();
        super.onDestroy();
    }
}
