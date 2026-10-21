package com.alexzab.pullupcounter;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult;

import java.util.List;
import java.util.Locale;

public final class MainActivity extends Activity implements PoseEngine.Listener, Camera2Controller.Listener {
    private static final int CAMERA_PERMISSION_REQUEST = 10;
    private static final long ADVICE_MIN_INTERVAL_MS = 2500L;

    private ImageView cameraView;
    private OverlayView overlayView;
    private TextView scoreText;
    private TextView repAnimationText;
    private TextView stateText;
    private TextView debugText;
    private ImageButton countTtsButton;
    private ImageButton adviceTtsButton;

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

    private TextToSpeech tts;
    private boolean ttsReady = false;
    private boolean countSpeechEnabled = false;
    private boolean adviceSpeechEnabled = false;

    private PullupDetector.State lastDetectorState = PullupDetector.State.WAITING;
    private long detectorStateEnteredAtMs = 0L;
    private long lastAdviceAtMs = 0L;
    private boolean readyAdviceSpoken = false;
    private boolean waitingAdviceSpoken = false;
    private boolean goingDownAdviceSpoken = false;
    private boolean goingUpAdviceSpoken = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statsStore = new StatsStore(this);
        activeDayStart = StatsStore.startOfDay(System.currentTimeMillis());

        cameraView = findViewById(R.id.camera_view);
        overlayView = findViewById(R.id.overlay_view);
        scoreText = findViewById(R.id.score_text);
        repAnimationText = findViewById(R.id.rep_animation_text);
        stateText = findViewById(R.id.state_text);
        debugText = findViewById(R.id.debug_text);
        countTtsButton = findViewById(R.id.count_tts_button);
        adviceTtsButton = findViewById(R.id.advice_tts_button);

        TextView reset = findViewById(R.id.reset_button);
        TextView manual = findViewById(R.id.calibrate_button);
        TextView auto = findViewById(R.id.auto_button);

        findViewById(R.id.score_container).setOnClickListener(v ->
                startActivity(new Intent(this, HistoryActivity.class)));

        countTtsButton.setOnClickListener(v -> toggleCountSpeech());
        adviceTtsButton.setOnClickListener(v -> toggleAdviceSpeech());
        updateTtsButtons();
        resetAdviceSession();

        overlayView.setBarSelectionListener(normalizedY -> {
            manualSelectionMode = false;
            autoCalibrating = false;
            autoBarDetector.reset();
            detector.setCalibratedBarY(normalizedY);
            overlayView.setBarYNormalized(normalizedY, false);
            resetAdviceSession();
            stateText.setText("ПЕРЕКЛАДИНА ЗАДАНА ✓");
            debugText.setText(String.format(Locale.US,
                    "Ручная линия: y = %.3f • можно начинать", normalizedY));
            speakAdvice("Перекладина задана");
        });

        reset.setOnClickListener(v -> {
            detector.reset();
            activeAttemptId = -1L;
            currentAttemptReps = 0;
            activeDayStart = StatsStore.startOfDay(System.currentTimeMillis());
            resetAdviceSession();
            refreshScore();

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

        refreshScore();
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
        resetAdviceSession();

        if (overlayView != null) overlayView.beginAutoBarSearch();
        if (stateText != null) stateText.setText("АВТОПОИСК ПЕРЕКЛАДИНЫ");
        if (debugText != null) {
            debugText.setText("Возьмитесь за перекладину и спокойно повисните на прямых руках");
        }
        speakAdvice("Возьмитесь за перекладину и спокойно повисните");
    }

    private void startManualCalibration() {
        autoCalibrating = false;
        manualSelectionMode = true;
        autoBarDetector.reset();
        detector.clearCalibration();
        resetAdviceSession();

        overlayView.setBarYNormalized(null, false);
        overlayView.beginBarSelection();
        stateText.setText("РУЧНАЯ КОРРЕКТИРОВКА");
        debugText.setText("Коснитесь перекладины на изображении и подвиньте линию пальцем");
        speakAdvice("Коснитесь перекладины на изображении");
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
                resetAdviceSession();
                stateText.setText("ПЕРЕКЛАДИНА НАЙДЕНА ✓");
                debugText.setText(String.format(Locale.US,
                        "Авто y = %.3f • уверенность %.0f%% • если неточно — нажмите «Линия»",
                        autoObservation.normalizedY, autoObservation.confidence * 100f));
                speakAdvice("Перекладина найдена");
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
            stateText.setText(stateName(r));

            if (r.countedNow) {
                currentAttemptReps = r.count;
                activeAttemptId = statsStore.saveAttemptProgress(activeAttemptId, currentAttemptReps);
                int todayTotal = statsStore.getTodayTotal();
                speakCount(currentAttemptReps);
                animateCountChange(currentAttemptReps, todayTotal);
            }

            handleAdvice(r);

            if (!r.poseReliable) {
                debugText.setText("Плечи, локти или кисти видны недостаточно хорошо");
            } else {
                debugText.setText(String.format(Locale.US,
                        "Локти %.0f° / %.0f°   •   линия %.3f   •   %d ms",
                        r.leftAngle, r.rightAngle, r.barY, inferenceMs));
            }
        });
    }

    private void animateCountChange(int current, int todayTotal) {
        if (repAnimationText == null || scoreText == null) {
            setScore(current, todayTotal);
            return;
        }

        repAnimationText.animate().cancel();
        repAnimationText.setText(Integer.toString(current));
        repAnimationText.setVisibility(View.VISIBLE);
        repAnimationText.setAlpha(1f);
        repAnimationText.setScaleX(0.98f);
        repAnimationText.setScaleY(0.98f);
        repAnimationText.setTranslationX(0f);
        repAnimationText.setTranslationY(0f);

        repAnimationText.post(() -> {
            int[] from = new int[2];
            int[] target = new int[2];
            repAnimationText.getLocationOnScreen(from);
            scoreText.getLocationOnScreen(target);

            float fromCenterX = from[0] + repAnimationText.getWidth() * 0.5f;
            float fromCenterY = from[1] + repAnimationText.getHeight() * 0.5f;

            // Current attempt is the left-hand value in "attempt / today".
            float targetX = target[0] + scoreText.getWidth() * 0.28f;
            float targetY = target[1] + scoreText.getHeight() * 0.5f;
            float dx = targetX - fromCenterX;
            float dy = targetY - fromCenterY;

            repAnimationText.animate()
                    .alpha(1f)
                    .scaleX(1.12f)
                    .scaleY(1.12f)
                    .setDuration(160L)
                    .withEndAction(() -> repAnimationText.animate()
                            .translationX(dx)
                            .translationY(dy)
                            .scaleX(0.22f)
                            .scaleY(0.22f)
                            .alpha(0.06f)
                            .setInterpolator(new AccelerateDecelerateInterpolator())
                            .setDuration(650L)
                            .withEndAction(() -> {
                                setScore(current, todayTotal);
                                repAnimationText.setVisibility(View.INVISIBLE);
                                repAnimationText.setTranslationX(0f);
                                repAnimationText.setTranslationY(0f);
                                repAnimationText.setScaleX(1f);
                                repAnimationText.setScaleY(1f);

                                scoreText.setScaleX(0.92f);
                                scoreText.setScaleY(0.92f);
                                scoreText.animate()
                                        .scaleX(1f)
                                        .scaleY(1f)
                                        .setDuration(160L)
                                        .start();
                            })
                            .start())
                    .start();
        });
    }

    private void setScore(int current, int total) {
        String left = Integer.toString(current);
        String right = Integer.toString(total);
        String value = left + " / " + right;
        SpannableString styled = new SpannableString(value);

        styled.setSpan(new ForegroundColorSpan(Color.rgb(55, 230, 180)),
                0, left.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        int slashStart = left.length();
        int rightStart = slashStart + 3;
        styled.setSpan(new ForegroundColorSpan(Color.argb(145, 255, 255, 255)),
                slashStart, rightStart, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        styled.setSpan(new ForegroundColorSpan(Color.WHITE),
                rightStart, value.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        scoreText.setText(styled);
    }

    private void ensureDayBoundary() {
        long todayStart = StatsStore.startOfDay(System.currentTimeMillis());
        if (todayStart == activeDayStart) return;

        activeDayStart = todayStart;
        activeAttemptId = -1L;
        currentAttemptReps = 0;
        detector.reset();
        resetAdviceSession();
        refreshScore();
    }

    private void refreshScore() {
        if (scoreText != null && statsStore != null) {
            setScore(currentAttemptReps, statsStore.getTodayTotal());
        }
    }

    private void toggleCountSpeech() {
        countSpeechEnabled = !countSpeechEnabled;
        if (countSpeechEnabled) ensureTts();
        updateTtsButtons();
    }

    private void toggleAdviceSpeech() {
        adviceSpeechEnabled = !adviceSpeechEnabled;
        if (adviceSpeechEnabled) {
            ensureTts();
            resetAdviceSession();
        }
        updateTtsButtons();
    }

    private void ensureTts() {
        if (tts != null) return;

        tts = new TextToSpeech(getApplicationContext(), status -> runOnUiThread(() -> {
            if (status != TextToSpeech.SUCCESS) {
                ttsReady = false;
                countSpeechEnabled = false;
                adviceSpeechEnabled = false;
                updateTtsButtons();
                Toast.makeText(this, "TTS недоступен на этом устройстве", Toast.LENGTH_LONG).show();
                return;
            }

            ttsReady = true;
            int languageResult = tts.setLanguage(new Locale("ru", "RU"));
            if (languageResult == TextToSpeech.LANG_MISSING_DATA
                    || languageResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts.setLanguage(Locale.getDefault());
            }
            tts.setSpeechRate(1.0f);
        }));
    }

    private void updateTtsButtons() {
        updateTtsButton(countTtsButton, countSpeechEnabled,
                "Озвучка счёта включена", "Озвучка счёта выключена");
        updateTtsButton(adviceTtsButton, adviceSpeechEnabled,
                "Голосовые рекомендации включены", "Голосовые рекомендации выключены");
    }

    private void updateTtsButton(ImageButton button, boolean enabled,
                                 String enabledDescription, String disabledDescription) {
        if (button == null) return;
        button.setAlpha(enabled ? 1.0f : 0.42f);
        button.setColorFilter(enabled ? Color.rgb(55, 230, 180) : Color.WHITE);
        button.setContentDescription(enabled ? enabledDescription : disabledDescription);
    }

    private void speakCount(int count) {
        if (!countSpeechEnabled || !ttsReady || tts == null) return;
        long now = SystemClock.uptimeMillis();
        tts.speak(Integer.toString(count), TextToSpeech.QUEUE_FLUSH,
                null, "count_" + now);
    }

    private boolean speakAdvice(String text) {
        if (!adviceSpeechEnabled || !ttsReady || tts == null) return false;

        long now = SystemClock.uptimeMillis();
        if (now - lastAdviceAtMs < ADVICE_MIN_INTERVAL_MS) return false;

        lastAdviceAtMs = now;
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "advice_" + now);
        return true;
    }

    private void resetAdviceSession() {
        lastDetectorState = PullupDetector.State.WAITING;
        detectorStateEnteredAtMs = SystemClock.uptimeMillis();
        lastAdviceAtMs = 0L;
        readyAdviceSpoken = false;
        waitingAdviceSpoken = false;
        goingDownAdviceSpoken = false;
        goingUpAdviceSpoken = false;
    }

    private void handleAdvice(PullupDetector.Result r) {
        long now = SystemClock.uptimeMillis();

        if (r.state != lastDetectorState) {
            lastDetectorState = r.state;
            detectorStateEnteredAtMs = now;
            waitingAdviceSpoken = false;
            goingDownAdviceSpoken = false;
            goingUpAdviceSpoken = false;
        }

        if (!adviceSpeechEnabled || !r.poseReliable) return;

        double avgAngle = (r.leftAngle + r.rightAngle) * 0.5;

        if (r.state == PullupDetector.State.DOWN && !readyAdviceSpoken
                && now - detectorStateEnteredAtMs > 250L) {
            if (speakAdvice("Можно начинать")) readyAdviceSpoken = true;
            return;
        }

        if (r.state == PullupDetector.State.WAITING
                && !waitingAdviceSpoken
                && avgAngle < 150.0
                && now - detectorStateEnteredAtMs > 1800L) {
            if (speakAdvice("Опуститесь ниже и распрямите руки")) {
                waitingAdviceSpoken = true;
            }
            return;
        }

        if (r.state == PullupDetector.State.GOING_DOWN
                && !goingDownAdviceSpoken
                && now - detectorStateEnteredAtMs > 1800L) {
            if (speakAdvice("Опуститесь ниже")) {
                goingDownAdviceSpoken = true;
            }
            return;
        }

        if (r.state == PullupDetector.State.GOING_UP
                && !goingUpAdviceSpoken
                && avgAngle < 120.0
                && now - detectorStateEnteredAtMs > 2500L) {
            if (speakAdvice("Поднимитесь выше")) {
                goingUpAdviceSpoken = true;
            }
        }
    }

    private static String stateName(PullupDetector.Result r) {
        if (!r.calibrated) return "КАЛИБРОВКА";
        if (!r.poseReliable) return "ПОЗА НЕУВЕРЕННАЯ";
        switch (r.state) {
            case DOWN: return "МОЖНО НАЧИНАТЬ";
            case GOING_UP: return "ВВЕРХ ↑";
            case UP: return r.countedNow ? "ЗАСЧИТАНО ✓" : "ВВЕРХУ";
            case GOING_DOWN: return "ОПУСТИТЕСЬ НИЖЕ ↓";
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
        refreshScore();
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
        if (tts != null) {
            tts.stop();
            tts.shutdown();
            tts = null;
        }
        super.onDestroy();
    }
}
