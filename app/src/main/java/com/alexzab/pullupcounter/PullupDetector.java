package com.alexzab.pullupcounter;

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;
import java.util.List;

public final class PullupDetector {
    public enum State { WAITING, DOWN, GOING_UP, UP, GOING_DOWN }

    public static final class Result {
        public final int count;
        public final State state;
        public final double leftAngle;
        public final double rightAngle;
        public final double barY;
        public final double mouthY;
        public final boolean countedNow;
        public final boolean poseReliable;
        public final boolean calibrated;

        Result(int count, State state, double leftAngle, double rightAngle,
               double barY, double mouthY, boolean countedNow,
               boolean poseReliable, boolean calibrated) {
            this.count = count;
            this.state = state;
            this.leftAngle = leftAngle;
            this.rightAngle = rightAngle;
            this.barY = barY;
            this.mouthY = mouthY;
            this.countedNow = countedNow;
            this.poseReliable = poseReliable;
            this.calibrated = calibrated;
        }
    }

    private static final int MOUTH_LEFT = 9;
    private static final int MOUTH_RIGHT = 10;
    private static final int LEFT_SHOULDER = 11;
    private static final int RIGHT_SHOULDER = 12;
    private static final int LEFT_ELBOW = 13;
    private static final int RIGHT_ELBOW = 14;
    private static final int LEFT_WRIST = 15;
    private static final int RIGHT_WRIST = 16;
    private static final int LEFT_HIP = 23;
    private static final int RIGHT_HIP = 24;
    private static final int LEFT_KNEE = 25;
    private static final int RIGHT_KNEE = 26;

    private double downAngle = AppSettings.DEFAULT_DOWN_ANGLE;
    private double leaveDownAngle = AppSettings.DEFAULT_DOWN_ANGLE - 8.0;
    private double upAngle = AppSettings.DEFAULT_UP_ANGLE;
    private double leaveUpAngle = AppSettings.DEFAULT_UP_ANGLE + 20.0;
    private long topHoldMs = AppSettings.DEFAULT_TOP_HOLD_MS;
    private static final double MOUTH_BAR_MARGIN = 0.015;
    private static final double MIN_VISIBILITY = 0.45;
    private static final double FACE_VISIBILITY = 0.25;
    private static final double WIDE_GRIP_RATIO = 1.55;
    private static final double MAX_WIDE_UP_RELAX_DEG = 18.0;
    private static final double WIDE_DOWN_RELAX_DEG = 5.0;
    private static final double MIN_SHOULDER_RISE = 0.060;
    private static final double MIN_SHOULDER_RISE_WIDE = 0.045;
    private static final double MAX_TOP_SHOULDER_BAR_GAP = 0.28;
    private static final double LOWER_BODY_VISIBILITY = 0.35;
    private static final double MIN_SHOULDER_TO_HIP_DROP = 0.08;
    private static final double MIN_HIP_TO_KNEE_DROP = 0.04;
    private static final int STABLE_FRAMES = 3;
    private static final double EMA_ALPHA = 0.35;

    private int count = 0;
    private State state = State.WAITING;
    private int downStable = 0;
    private int upStable = 0;
    private long topCandidateSinceMs = 0L;
    private boolean hasEma = false;
    private double emaLeftAngle;
    private double emaRightAngle;
    private double downShoulderY = Double.NaN;
    private Double calibratedBarY = null;

    public void applySettings(AppSettings settings) {
        downAngle = settings.getDownAngle();
        leaveDownAngle = downAngle - 8.0;
        upAngle = settings.getUpAngle();
        leaveUpAngle = upAngle + 20.0;
        topHoldMs = settings.getTopHoldMs();
        resetMotionState();
    }

    public void setCalibratedBarY(double normalizedY) {
        calibratedBarY = Math.max(0.0, Math.min(1.0, normalizedY));
        resetMotionState();
    }

    public void clearCalibration() {
        calibratedBarY = null;
        resetMotionState();
    }

    public boolean isCalibrated() {
        return calibratedBarY != null;
    }

    public double getCalibratedBarY() {
        return calibratedBarY == null ? Double.NaN : calibratedBarY;
    }

    public void reset() {
        count = 0;
        resetMotionState();
    }

    public void setCount(int correctedCount) {
        count = Math.max(0, correctedCount);
    }

    public void resetMotionTracking() {
        resetMotionState();
    }

    private void resetMotionState() {
        state = State.WAITING;
        downStable = 0;
        upStable = 0;
        topCandidateSinceMs = 0L;
        downShoulderY = Double.NaN;
        hasEma = false;
    }

    public Result process(List<NormalizedLandmark> p) {
        final boolean calibrated = calibratedBarY != null;
        final double barY = calibrated ? calibratedBarY : Double.NaN;

        if (!isPoseReliable(p)) {
            return new Result(count, State.WAITING, Double.NaN, Double.NaN,
                    barY, Double.NaN, false, false, calibrated);
        }

        double left = angle(p.get(LEFT_SHOULDER), p.get(LEFT_ELBOW), p.get(LEFT_WRIST));
        double right = angle(p.get(RIGHT_SHOULDER), p.get(RIGHT_ELBOW), p.get(RIGHT_WRIST));

        if (!hasEma) {
            emaLeftAngle = left;
            emaRightAngle = right;
            hasEma = true;
        } else {
            emaLeftAngle = EMA_ALPHA * left + (1.0 - EMA_ALPHA) * emaLeftAngle;
            emaRightAngle = EMA_ALPHA * right + (1.0 - EMA_ALPHA) * emaRightAngle;
        }

        float mouthLeftVisibility = p.get(MOUTH_LEFT).visibility().orElse(0.0f);
        float mouthRightVisibility = p.get(MOUTH_RIGHT).visibility().orElse(0.0f);
        boolean faceReliable = mouthLeftVisibility >= FACE_VISIBILITY
                && mouthRightVisibility >= FACE_VISIBILITY;
        double mouthY = faceReliable
                ? (p.get(MOUTH_LEFT).y() + p.get(MOUTH_RIGHT).y()) * 0.5
                : Double.NaN;

        if (!calibrated) {
            return new Result(count, State.WAITING, emaLeftAngle, emaRightAngle,
                    Double.NaN, mouthY, false, true, false);
        }

        double avgAngle = (emaLeftAngle + emaRightAngle) * 0.5;
        double shoulderY = (p.get(LEFT_SHOULDER).y() + p.get(RIGHT_SHOULDER).y()) * 0.5;

        double shoulderWidth = Math.abs(p.get(LEFT_SHOULDER).x() - p.get(RIGHT_SHOULDER).x());
        double wristWidth = Math.abs(p.get(LEFT_WRIST).x() - p.get(RIGHT_WRIST).x());
        double gripRatio = wristWidth / Math.max(0.04, shoulderWidth);
        boolean wideGrip = gripRatio >= WIDE_GRIP_RATIO;

        double wideRelax = Math.max(0.0,
                Math.min(MAX_WIDE_UP_RELAX_DEG, (gripRatio - 1.45) * 30.0));
        double effectiveUpAngle = upAngle + wideRelax;
        double effectiveLeaveUpAngle = leaveUpAngle + wideRelax;
        double effectiveDownAngle = downAngle - (wideGrip ? WIDE_DOWN_RELAX_DEG : 0.0);

        boolean armsExtended = avgAngle > effectiveDownAngle
                && Math.max(emaLeftAngle, emaRightAngle) > effectiveDownAngle + 2.0
                && shoulderY > barY + 0.10;

        boolean faceAboveBar = faceReliable && mouthY < barY + MOUTH_BAR_MARGIN;
        double shoulderRise = Double.isNaN(downShoulderY) ? 0.0 : downShoulderY - shoulderY;
        double requiredShoulderRise = wideGrip ? MIN_SHOULDER_RISE_WIDE : MIN_SHOULDER_RISE;
        boolean shoulderTop = !Double.isNaN(downShoulderY)
                && shoulderRise >= requiredShoulderRise
                && shoulderY < barY + MAX_TOP_SHOULDER_BAR_GAP;

        // Front view normally uses the face crossing the bar. If the face is not
        // reliable (typical rear view), vertical shoulder travel becomes the
        // top-position signal. Wide grip gets only an angle relaxation that scales
        // with actual wrist/shoulder width.
        boolean topPositionSignal = faceAboveBar || shoulderTop;
        boolean topReached = avgAngle < effectiveUpAngle && topPositionSignal;
        boolean countedNow = false;

        switch (state) {
            case WAITING:
                if (armsExtended) {
                    if (++downStable >= STABLE_FRAMES) {
                        state = State.DOWN;
                        downShoulderY = shoulderY;
                        downStable = 0;
                    }
                } else {
                    downStable = 0;
                }
                break;
            case DOWN:
                if (Double.isNaN(downShoulderY) || shoulderY > downShoulderY) {
                    downShoulderY = shoulderY;
                }
                if (avgAngle < leaveDownAngle - (wideGrip ? WIDE_DOWN_RELAX_DEG : 0.0)) {
                    state = State.GOING_UP;
                    upStable = 0;
                }
                break;
            case GOING_UP:
                if (topReached) {
                    long now = android.os.SystemClock.uptimeMillis();
                    if (topCandidateSinceMs == 0L) topCandidateSinceMs = now;
                    if (now - topCandidateSinceMs >= topHoldMs) {
                        state = State.UP;
                        count++;
                        countedNow = true;
                        upStable = 0;
                        topCandidateSinceMs = 0L;
                    }
                } else {
                    upStable = 0;
                    topCandidateSinceMs = 0L;
                    if (armsExtended) state = State.DOWN;
                }
                break;
            case UP:
                if (avgAngle > effectiveLeaveUpAngle || !topPositionSignal) {
                    state = State.GOING_DOWN;
                }
                break;
            case GOING_DOWN:
                if (armsExtended) {
                    if (++downStable >= STABLE_FRAMES) {
                        state = State.DOWN;
                        downStable = 0;
                    }
                } else {
                    downStable = 0;
                    if (topReached) state = State.UP;
                }
                break;
        }

        return new Result(count, state, emaLeftAngle, emaRightAngle,
                barY, mouthY, countedNow, true, true);
    }

    public static boolean isPoseReliable(List<NormalizedLandmark> p) {
        if (p == null || p.size() < 27) return false;

        // Face landmarks are deliberately optional: in rear-view pull-ups the
        // mouth/chin landmarks are often invisible, while shoulders/elbows/wrists
        // remain reliable enough to count the movement.
        int[] upperIds = {LEFT_SHOULDER, RIGHT_SHOULDER,
                LEFT_ELBOW, RIGHT_ELBOW, LEFT_WRIST, RIGHT_WRIST};
        for (int id : upperIds) {
            float v = p.get(id).visibility().orElse(1.0f);
            if (v < MIN_VISIBILITY) return false;
        }

        float leftHipVisibility = p.get(LEFT_HIP).visibility().orElse(1.0f);
        float rightHipVisibility = p.get(RIGHT_HIP).visibility().orElse(1.0f);
        if (leftHipVisibility < LOWER_BODY_VISIBILITY
                || rightHipVisibility < LOWER_BODY_VISIBILITY) {
            return false;
        }

        double shoulderY = (p.get(LEFT_SHOULDER).y() + p.get(RIGHT_SHOULDER).y()) * 0.5;
        double hipY = (p.get(LEFT_HIP).y() + p.get(RIGHT_HIP).y()) * 0.5;
        if (hipY < shoulderY + MIN_SHOULDER_TO_HIP_DROP) return false;

        boolean leftKneeValid = p.get(LEFT_KNEE).visibility().orElse(1.0f) >= LOWER_BODY_VISIBILITY
                && p.get(LEFT_KNEE).y() > hipY + MIN_HIP_TO_KNEE_DROP;
        boolean rightKneeValid = p.get(RIGHT_KNEE).visibility().orElse(1.0f) >= LOWER_BODY_VISIBILITY
                && p.get(RIGHT_KNEE).y() > hipY + MIN_HIP_TO_KNEE_DROP;

        return leftKneeValid || rightKneeValid;
    }

    private static double angle(NormalizedLandmark a, NormalizedLandmark b, NormalizedLandmark c) {
        double abx = a.x() - b.x();
        double aby = a.y() - b.y();
        double cbx = c.x() - b.x();
        double cby = c.y() - b.y();
        double dot = abx * cbx + aby * cby;
        double mag = Math.hypot(abx, aby) * Math.hypot(cbx, cby);
        if (mag < 1e-8) return 180.0;
        double cos = Math.max(-1.0, Math.min(1.0, dot / mag));
        return Math.toDegrees(Math.acos(cos));
    }
}
