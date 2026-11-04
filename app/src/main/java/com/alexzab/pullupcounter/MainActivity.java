package com.alexzab.pullupcounter;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult;

import java.util.List;
import java.util.Locale;

public final class MainActivity extends Activity implements PoseEngine.Listener, Camera2Controller.Listener {
    private static final int CAMERA_PERMISSION_REQUEST = 10;
    private static final long ADVICE_MIN_INTERVAL_MS = 2500L;
    private static final String PREFS_NAME = "pullup_ui";
    private static final String PREF_SCORE_MODE = "score_mode";

    private enum ScoreMode {
        ATTEMPT_TODAY,
        TODAY,
        ALL_TIME
    }

    private ImageView cameraView;
    private OverlayView overlayView;
    private TextView scoreText;
    private TextView scoreModeText;
    private TextView repAnimationText;
    private TextView stateText;
    private TextView debugText;
    private ImageButton countTtsButton;
    private ImageButton adviceTtsButton;
    private ImageButton editAttemptButton;
    private ImageButton historyButton;
    private ImageButton pauseButton;
    private LinearLayout editControls;
    private LinearLayout mainControls;
    private LinearLayout instructionContainer;
    private View scoreContainer;

    private PoseEngine poseEngine;
    private Camera2Controller cameraController;
    private StatsStore statsStore;
    private SharedPreferences preferences;
    private final PullupDetector detector = new PullupDetector();
    private final AutoBarDetector autoBarDetector = new AutoBarDetector();

    private volatile Bitmap latestFrame;
    private volatile boolean autoCalibrating = true;
    private volatile boolean manualSelectionMode = false;

    private long activeAttemptId = -1L;
    private long activeDayStart;
    private int currentAttemptReps = 0;

    private ScoreMode scoreMode = ScoreMode.ATTEMPT_TODAY;
    private boolean editingAttempt = false;
    private boolean paused = false;
    private int editOriginalReps = 0;
    private int editDraftReps = 0;

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
        preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        scoreMode = loadScoreMode();
        activeDayStart = StatsStore.startOfDay(System.currentTimeMillis());

        cameraView = findViewById(R.id.camera_view);
        overlayView = findViewById(R.id.overlay_view);
        scoreContainer = findViewById(R.id.score_container);
        scoreText = findViewById(R.id.score_text);
        scoreModeText = findViewById(R.id.score_mode_text);
        repAnimationText = findViewById(R.id.rep_animation_text);
        stateText = findViewById(R.id.state_text);
        debugText = findViewById(R.id.debug_text);
        countTtsButton = findViewById(R.id.count_tts_button);
        adviceTtsButton = findViewById(R.id.advice_tts_button);
        editAttemptButton = findViewById(R.id.edit_attempt_button);
        historyButton = findViewById(R.id.history_button);
        pauseButton = findViewById(R.id.pause_button);
        editControls = findViewById(R.id.edit_controls);
        mainControls = findViewById(R.id.main_controls);
        instructionContainer = findViewById(R.id.instruction_container);
        installInstructionInsets();

        TextView reset = findViewById(R.id.reset_button);
        TextView manual = findViewById(R.id.calibrate_button);
        TextView auto = findViewById(R.id.auto_button);
        TextView editMinus = findViewById(R.id.edit_minus_button);
        TextView editPlus = findViewById(R.id.edit_plus_button);
        TextView editOk = findViewById(R.id.edit_ok_button);
        TextView editCancel = findViewById(R.id.edit_cancel_button);

        scoreContainer.setOnClickListener(v -> {
            if (!editingAttempt) cycleScoreMode();
        });
        historyButton.setOnClickListener(v -> {
            if (!editingAttempt) {
                startActivity(new Intent(this, HistoryActivity.class));
            }
        });
        editAttemptButton.setOnClickListener(v -> beginAttemptEdit());
        editMinus.setOnClickListener(v -> adjustAttemptEdit(-1));
        editPlus.setOnClickListener(v -> adjustAttemptEdit(1));
        editOk.setOnClickListener(v -> finishAttemptEdit(true));
        editCancel.setOnClickListener(v -> finishAttemptEdit(false));

        pauseButton.setOnClickListener(v -> togglePause());
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
            debugText.setText("");
            speakAdvice("Перекладина задана");
        });

        reset.setOnClickListener(v -> {
            if (editingAttempt) finishAttemptEdit(false);
            detector.reset();
            activeAttemptId = -1L;
            currentAttemptReps = 0;
            activeDayStart = StatsStore.startOfDay(System.currentTimeMillis());
            resetAdviceSession();
            refreshScore();

            if (detector.isCalibrated()) {
                stateText.setText("ГОТОВ К ПОДТЯГИВАНИЯМ");
            } else if (manualSelectionMode) {
                stateText.setText("КОСНИТЕСЬ ПЕРЕКЛАДИНЫ");
            } else {
                stateText.setText("ПОКАЖИТЕ В КАДРЕ КОРПУС И НОГИ\nВОЗЬМИТЕСЬ ЗА ПЕРЕКЛАДИНУ И ПОВИСНИТЕ");
            }
        });

        manual.setOnClickListener(v -> {
            if (editingAttempt) finishAttemptEdit(false);
            startManualCalibration();
        });
        auto.setOnClickListener(v -> {
            if (editingAttempt) finishAttemptEdit(false);
            startAutoCalibration();
        });

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

    private ScoreMode loadScoreMode() {
        int stored = preferences.getInt(PREF_SCORE_MODE, ScoreMode.ATTEMPT_TODAY.ordinal());
        ScoreMode[] values = ScoreMode.values();
        if (stored < 0 || stored >= values.length) return ScoreMode.ATTEMPT_TODAY;
        return values[stored];
    }

    private void cycleScoreMode() {
        switch (scoreMode) {
            case ATTEMPT_TODAY:
                scoreMode = ScoreMode.TODAY;
                break;
            case TODAY:
                scoreMode = ScoreMode.ALL_TIME;
                break;
            default:
                scoreMode = ScoreMode.ATTEMPT_TODAY;
                break;
        }
        preferences.edit().putInt(PREF_SCORE_MODE, scoreMode.ordinal()).apply();
        refreshScore();
    }

    private void startAutoCalibration() {
        autoCalibrating = true;
        manualSelectionMode = false;
        autoBarDetector.reset();
        detector.clearCalibration();
        resetAdviceSession();

        if (overlayView != null) overlayView.beginAutoBarSearch();
        if (stateText != null) {
            stateText.setText("ПОКАЖИТЕ В КАДРЕ КОРПУС И НОГИ\nВОЗЬМИТЕСЬ ЗА ПЕРЕКЛАДИНУ И ПОВИСНИТЕ");
        }
        if (debugText != null) debugText.setText("");
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
        stateText.setText("КОСНИТЕСЬ ПЕРЕКЛАДИНЫ");
        debugText.setText("");
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
        if (!paused && poseEngine != null) poseEngine.detect(bitmap);
    }

    @Override
    public void onPose(PoseLandmarkerResult result, int inputWidth, int inputHeight, long inferenceMs) {
        List<NormalizedLandmark> points = result.landmarks().isEmpty()
                ? null : result.landmarks().get(0);

        AutoBarDetector.Observation observation = null;
        boolean justAutoCalibrated = false;

        if (!editingAttempt
                && autoCalibrating
                && !manualSelectionMode
                && !detector.isCalibrated()
                && PullupDetector.isPoseReliable(points)) {
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

            if (paused) {
                stateText.setText("ПАУЗА");
                return;
            }

            overlayView.setResult(result, inputWidth, inputHeight);

            if (editingAttempt) {
                stateText.setText("ИСПРАВЬТЕ СЧЁТ ПОПЫТКИ");
                return;
            }

            if (autoReadyNow && autoObservation != null) {
                overlayView.setBarYNormalized(autoObservation.normalizedY, true);
                resetAdviceSession();
                stateText.setText("ПЕРЕКЛАДИНА НАЙДЕНА ✓");
                speakAdvice("Перекладина найдена");
                return;
            }

            if (!detector.isCalibrated()) {
                if (manualSelectionMode) {
                    stateText.setText("КОСНИТЕСЬ ПЕРЕКЛАДИНЫ");
                    return;
                }

                if (autoObservation != null && autoObservation.candidateFound) {
                    stateText.setText(String.format(Locale.US, "НЕ ДВИГАЙТЕСЬ • ПОИСК %d/%d",
                            Math.min(autoObservation.stableSamples, autoBarDetector.getRequiredSamples()),
                            autoBarDetector.getRequiredSamples()));
                } else {
                    stateText.setText("ПОКАЖИТЕ В КАДРЕ КОРПУС И НОГИ\nВОЗЬМИТЕСЬ ЗА ПЕРЕКЛАДИНУ И ПОВИСНИТЕ");
                }
                return;
            }

            if (points == null) {
                stateText.setText("ВСТАНЬТЕ ПОЛНОСТЬЮ В КАДР");
                return;
            }

            PullupDetector.Result r = detector.process(points);
            stateText.setText(stateName(r));

            if (r.countedNow) {
                currentAttemptReps = r.count;
                activeAttemptId = statsStore.saveAttemptProgress(activeAttemptId, currentAttemptReps);

                int todayTotal = statsStore.getTodayTotal();
                int allTimeTotal = statsStore.getAllTimeTotal();
                int displayedCount = countForMode(currentAttemptReps, todayTotal, allTimeTotal);

                speakCount(displayedCount);
                animateCountChange(displayedCount, currentAttemptReps, todayTotal, allTimeTotal);
            }

            handleAdvice(r);
        });
    }

    private int countForMode(int current, int today, int allTime) {
        switch (scoreMode) {
            case TODAY:
                return today;
            case ALL_TIME:
                return allTime;
            default:
                return current;
        }
    }

    private void animateCountChange(int displayedCount, int current, int todayTotal, int allTimeTotal) {
        if (repAnimationText == null || scoreText == null) {
            renderScore(current, todayTotal, allTimeTotal);
            return;
        }

        repAnimationText.animate().cancel();
        repAnimationText.setText(Integer.toString(displayedCount));
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

            float targetFraction = scoreMode == ScoreMode.ATTEMPT_TODAY ? 0.28f : 0.5f;
            float targetX = target[0] + scoreText.getWidth() * targetFraction;
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
                                renderScore(current, todayTotal, allTimeTotal);
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

    private void renderScore(int current, int today, int allTime) {
        if (editingAttempt) {
            renderEditDraft();
            return;
        }

        switch (scoreMode) {
            case TODAY:
                scoreModeText.setText("СЕГОДНЯ  ↻");
                scoreText.setTextSize(49f);
                scoreText.setTextColor(Color.WHITE);
                scoreText.setText(Integer.toString(today));
                editAttemptButton.setVisibility(View.VISIBLE);
                break;

            case ALL_TIME:
                scoreModeText.setText("ЗА ВСЁ ВРЕМЯ  ↻");
                scoreText.setTextSize(49f);
                scoreText.setTextColor(Color.WHITE);
                scoreText.setText(Integer.toString(allTime));
                editAttemptButton.setVisibility(View.VISIBLE);
                break;

            default:
                scoreModeText.setText("ПОПЫТКА / СЕГОДНЯ  ↻");
                scoreText.setTextSize(44f);
                editAttemptButton.setVisibility(View.VISIBLE);
                setAttemptTodayScore(current, today);
                break;
        }
    }

    private void setAttemptTodayScore(int current, int today) {
        String left = Integer.toString(current);
        String right = Integer.toString(today);
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

    private void beginAttemptEdit() {
        if (editingAttempt) return;

        if (scoreMode != ScoreMode.ATTEMPT_TODAY) {
            scoreMode = ScoreMode.ATTEMPT_TODAY;
            preferences.edit().putInt(PREF_SCORE_MODE, scoreMode.ordinal()).apply();
        }

        repAnimationText.animate().cancel();
        repAnimationText.setVisibility(View.INVISIBLE);

        editingAttempt = true;
        editOriginalReps = currentAttemptReps;
        editDraftReps = currentAttemptReps;

        editAttemptButton.setVisibility(View.GONE);
        editControls.setVisibility(View.VISIBLE);
        mainControls.setVisibility(View.GONE);
        scoreModeText.setText("ИСПРАВЛЕНИЕ ПОПЫТКИ");
        stateText.setText("ИСПРАВЬТЕ СЧЁТ ПОПЫТКИ");
        renderEditDraft();
    }

    private void adjustAttemptEdit(int delta) {
        if (!editingAttempt) return;
        editDraftReps = Math.max(0, editDraftReps + delta);
        renderEditDraft();
    }

    private void renderEditDraft() {
        int storedToday = statsStore.getTodayTotal();
        int previewToday = Math.max(0, storedToday + editDraftReps - editOriginalReps);
        scoreText.setTextSize(44f);
        setAttemptTodayScore(editDraftReps, previewToday);
    }

    private void finishAttemptEdit(boolean save) {
        if (!editingAttempt) return;

        if (save) {
            currentAttemptReps = editDraftReps;
            activeAttemptId = statsStore.setAttemptReps(activeAttemptId, currentAttemptReps);
            detector.setCount(currentAttemptReps);
        }

        editingAttempt = false;
        editControls.setVisibility(View.GONE);
        mainControls.setVisibility(View.VISIBLE);
        editOriginalReps = 0;
        editDraftReps = 0;

        refreshScore();
        stateText.setText(save ? "СЧЁТ ИСПРАВЛЕН ✓" : "ИЗМЕНЕНИЯ ОТМЕНЕНЫ");
    }

    private void ensureDayBoundary() {
        long todayStart = StatsStore.startOfDay(System.currentTimeMillis());
        if (todayStart == activeDayStart) return;

        if (editingAttempt) {
            editingAttempt = false;
            editControls.setVisibility(View.GONE);
            mainControls.setVisibility(View.VISIBLE);
        }

        activeDayStart = todayStart;
        activeAttemptId = -1L;
        currentAttemptReps = 0;
        detector.reset();
        resetAdviceSession();
        refreshScore();
    }

    private void refreshScore() {
        if (scoreText == null || statsStore == null) return;
        int today = statsStore.getTodayTotal();
        int allTime = statsStore.getAllTimeTotal();
        renderScore(currentAttemptReps, today, allTime);
    }

    private void togglePause() {
        paused = !paused;

        if (paused) {
            if (tts != null) tts.stop();
            repAnimationText.animate().cancel();
            repAnimationText.setVisibility(View.INVISIBLE);
            if (overlayView != null) overlayView.clearPose();
            pauseButton.setImageResource(R.drawable.ic_play);
            pauseButton.setColorFilter(Color.rgb(55, 230, 180));
            pauseButton.setContentDescription("Продолжить");
            stateText.setText("ПАУЗА");
            return;
        }

        detector.resetMotionTracking();
        if (autoCalibrating) autoBarDetector.reset();
        resetAdviceSession();

        pauseButton.setImageResource(R.drawable.ic_pause);
        pauseButton.setColorFilter(Color.WHITE);
        pauseButton.setContentDescription("Пауза");

        if (detector.isCalibrated()) {
            stateText.setText("ГОТОВ К ПОДТЯГИВАНИЯМ");
        } else if (manualSelectionMode) {
            stateText.setText("КОСНИТЕСЬ ПЕРЕКЛАДИНЫ");
        } else {
            stateText.setText("ПОКАЖИТЕ В КАДРЕ КОРПУС И НОГИ\nВОЗЬМИТЕСЬ ЗА ПЕРЕКЛАДИНУ И ПОВИСНИТЕ");
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

    private void speakCount(int value) {
        if (paused || !countSpeechEnabled || !ttsReady || tts == null) return;
        long now = SystemClock.uptimeMillis();
        tts.speak(Integer.toString(value), TextToSpeech.QUEUE_FLUSH,
                null, "count_" + now);
    }

    private boolean speakAdvice(String text) {
        if (paused || !adviceSpeechEnabled || !ttsReady || tts == null) return false;

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
        if (!r.poseReliable) return "ПОКАЖИТЕ В КАДРЕ КОРПУС И НОГИ";
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

    private void installInstructionInsets() {
        if (instructionContainer == null) return;

        instructionContainer.setOnApplyWindowInsetsListener((view, insets) -> {
            int navBottom;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                navBottom = insets.getInsets(WindowInsets.Type.navigationBars()).bottom;
            } else {
                navBottom = insets.getSystemWindowInsetBottom();
            }

            ViewGroup.MarginLayoutParams lp =
                    (ViewGroup.MarginLayoutParams) view.getLayoutParams();
            int desiredBottom = navBottom + dp(12);
            if (lp.bottomMargin != desiredBottom) {
                lp.bottomMargin = desiredBottom;
                view.setLayoutParams(lp);
            }
            return insets;
        });
        instructionContainer.requestApplyInsets();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
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
