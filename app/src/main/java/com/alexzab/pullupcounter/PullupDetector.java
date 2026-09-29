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

    private static final double DOWN_ANGLE = 155.0;
    private static final double LEAVE_DOWN_ANGLE = 142.0;
    private static final double UP_ANGLE = 85.0;
    private static final double LEAVE_UP_ANGLE = 105.0;
    private static final double MOUTH_BAR_MARGIN = 0.015;
    private static final double MIN_VISIBILITY = 0.45;
    private static final int STABLE_FRAMES = 3;
    private static final double EMA_ALPHA = 0.35;

    private int count = 0;
    private State state = State.WAITING;
    private int downStable = 0;
    private int upStable = 0;
    private boolean hasEma = false;
    private double emaLeftAngle;
    private double emaRightAngle;
    private Double calibratedBarY = null;

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

    private void resetMotionState() {
        state = State.WAITING;
        downStable = 0;
        upStable = 0;
        hasEma = false;
    }

    public Result process(List<NormalizedLandmark> p) {
        final boolean calibrated = calibratedBarY != null;
        final double barY = calibrated ? calibratedBarY : Double.NaN;

        if (p == null || p.size() < 17 || !isReliable(p)) {
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

        double mouthY = (p.get(MOUTH_LEFT).y() + p.get(MOUTH_RIGHT).y()) * 0.5;

        if (!calibrated) {
            return new Result(count, State.WAITING, emaLeftAngle, emaRightAngle,
                    Double.NaN, mouthY, false, true, false);
        }

        double avgAngle = (emaLeftAngle + emaRightAngle) * 0.5;
        double shoulderY = (p.get(LEFT_SHOULDER).y() + p.get(RIGHT_SHOULDER).y()) * 0.5;
        boolean armsExtended = avgAngle > DOWN_ANGLE && shoulderY > barY + 0.12;
        boolean aboveBar = mouthY < barY + MOUTH_BAR_MARGIN;
        boolean topReached = avgAngle < UP_ANGLE && aboveBar;
        boolean countedNow = false;

        switch (state) {
            case WAITING:
                if (armsExtended) {
                    if (++downStable >= STABLE_FRAMES) {
                        state = State.DOWN;
                        downStable = 0;
                    }
                } else {
                    downStable = 0;
                }
                break;
            case DOWN:
                if (avgAngle < LEAVE_DOWN_ANGLE) {
                    state = State.GOING_UP;
                    upStable = 0;
                }
                break;
            case GOING_UP:
                if (topReached) {
                    if (++upStable >= STABLE_FRAMES) {
                        state = State.UP;
                        count++;
                        countedNow = true;
                        upStable = 0;
                    }
                } else {
                    upStable = 0;
                    if (armsExtended) state = State.DOWN;
                }
                break;
            case UP:
                if (avgAngle > LEAVE_UP_ANGLE || !aboveBar) {
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

    private static boolean isReliable(List<NormalizedLandmark> p) {
        int[] ids = {MOUTH_LEFT, MOUTH_RIGHT, LEFT_SHOULDER, RIGHT_SHOULDER,
                LEFT_ELBOW, RIGHT_ELBOW, LEFT_WRIST, RIGHT_WRIST};
        for (int id : ids) {
            float v = p.get(id).visibility().orElse(1.0f);
            if (v < MIN_VISIBILITY) return false;
        }
        return true;
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
