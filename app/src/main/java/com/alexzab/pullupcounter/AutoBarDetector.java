package com.alexzab.pullupcounter;

import android.graphics.Bitmap;

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Estimates the pull-up bar height from the user's grip.
 *
 * MediaPipe wrists provide a coarse region of interest. Inside a narrow horizontal
 * band around the wrists we look for a persistent horizontal luminance edge, then
 * require several consecutive estimates to agree before accepting calibration.
 */
public final class AutoBarDetector {
    public static final class Observation {
        public final boolean candidateFound;
        public final boolean ready;
        public final float normalizedY;
        public final float confidence;
        public final int stableSamples;
        public final boolean edgeRefined;

        private Observation(boolean candidateFound, boolean ready, float normalizedY,
                            float confidence, int stableSamples, boolean edgeRefined) {
            this.candidateFound = candidateFound;
            this.ready = ready;
            this.normalizedY = normalizedY;
            this.confidence = confidence;
            this.stableSamples = stableSamples;
            this.edgeRefined = edgeRefined;
        }

        static Observation none(int samples) {
            return new Observation(false, false, Float.NaN, 0f, samples, false);
        }
    }

    private static final int LEFT_SHOULDER = 11;
    private static final int RIGHT_SHOULDER = 12;
    private static final int LEFT_ELBOW = 13;
    private static final int RIGHT_ELBOW = 14;
    private static final int LEFT_WRIST = 15;
    private static final int RIGHT_WRIST = 16;

    private static final float MIN_VISIBILITY = 0.50f;
    private static final int REQUIRED_SAMPLES = 7;
    private static final int MAX_SAMPLES = 10;
    private static final float MAX_MEDIAN_DEVIATION = 0.014f;

    private final ArrayDeque<Float> samples = new ArrayDeque<>();

    public void reset() {
        samples.clear();
    }

    public int getRequiredSamples() {
        return REQUIRED_SAMPLES;
    }

    public Observation observe(Bitmap bitmap, List<NormalizedLandmark> p) {
        if (bitmap == null || p == null || p.size() <= RIGHT_WRIST) {
            return Observation.none(samples.size());
        }
        if (!visible(p, LEFT_WRIST) || !visible(p, RIGHT_WRIST)
                || !visible(p, LEFT_ELBOW) || !visible(p, RIGHT_ELBOW)
                || !visible(p, LEFT_SHOULDER) || !visible(p, RIGHT_SHOULDER)) {
            return Observation.none(samples.size());
        }

        float wristY = (p.get(LEFT_WRIST).y() + p.get(RIGHT_WRIST).y()) * 0.5f;
        float elbowY = (p.get(LEFT_ELBOW).y() + p.get(RIGHT_ELBOW).y()) * 0.5f;
        float shoulderY = (p.get(LEFT_SHOULDER).y() + p.get(RIGHT_SHOULDER).y()) * 0.5f;

        // Calibration is intentionally conservative: the user should be hanging from the bar,
        // not merely raising their hands somewhere in the frame.
        if (wristY < 0.015f || wristY > 0.88f
                || elbowY < wristY + 0.025f
                || shoulderY < wristY + 0.070f) {
            return Observation.none(samples.size());
        }

        float leftX = Math.min(p.get(LEFT_WRIST).x(), p.get(RIGHT_WRIST).x());
        float rightX = Math.max(p.get(LEFT_WRIST).x(), p.get(RIGHT_WRIST).x());
        float handSpan = rightX - leftX;
        if (handSpan < 0.08f) {
            float center = (leftX + rightX) * 0.5f;
            leftX = center - 0.16f;
            rightX = center + 0.16f;
            handSpan = 0.32f;
        }

        float pad = Math.max(0.035f, handSpan * 0.28f);
        leftX = clamp01(leftX - pad);
        rightX = clamp01(rightX + pad);

        EdgeResult edge = refineWithHorizontalEdge(bitmap, wristY, leftX, rightX);
        boolean edgeRefined = edge.confidence >= 0.28f;

        // The wrist landmark is usually slightly below the bar center. If image contrast is poor,
        // use it as a fallback with a small upward correction.
        float candidateY = edgeRefined ? edge.y : clamp01(wristY - 0.012f);
        if (Math.abs(candidateY - wristY) > 0.075f) {
            candidateY = clamp01(wristY - 0.012f);
            edgeRefined = false;
        }

        samples.addLast(candidateY);
        while (samples.size() > MAX_SAMPLES) samples.removeFirst();

        float median = median(samples);
        float mad = medianAbsoluteDeviation(samples, median);
        boolean ready = samples.size() >= REQUIRED_SAMPLES && mad <= MAX_MEDIAN_DEVIATION;

        float stability = 1f - Math.min(1f, mad / 0.035f);
        float confidence = clamp01(0.45f * edge.confidence + 0.55f * stability);
        return new Observation(true, ready, median, confidence, samples.size(), edgeRefined);
    }

    private static EdgeResult refineWithHorizontalEdge(Bitmap bitmap, float wristY,
                                                        float leftXNorm, float rightXNorm) {
        int imageW = bitmap.getWidth();
        int imageH = bitmap.getHeight();
        if (imageW < 32 || imageH < 32) return new EdgeResult(wristY, 0f);

        int centerY = Math.round(wristY * imageH);
        int radius = Math.max(10, Math.round(imageH * 0.060f));
        int top = clamp(centerY - radius - 2, 0, imageH - 1);
        int bottom = clamp(centerY + radius + 2, 0, imageH - 1);
        int left = clamp(Math.round(leftXNorm * imageW), 0, imageW - 1);
        int right = clamp(Math.round(rightXNorm * imageW), 0, imageW - 1);

        if (right - left < 24 || bottom - top < 8) return new EdgeResult(wristY, 0f);

        int cropW = right - left + 1;
        int cropH = bottom - top + 1;
        int[] pixels = new int[cropW * cropH];
        bitmap.getPixels(pixels, 0, cropW, left, top, cropW, cropH);

        int xStep = Math.max(2, cropW / 140);
        float bestScore = -Float.MAX_VALUE;
        int bestLocalY = Math.max(2, Math.min(cropH - 3, centerY - top));
        float scoreSum = 0f;
        int scoredRows = 0;

        for (int localY = 2; localY < cropH - 2; localY++) {
            int globalY = top + localY;
            if (Math.abs(globalY - centerY) > radius) continue;

            float diffSum = 0f;
            int strong = 0;
            int count = 0;
            int rowUp = (localY - 2) * cropW;
            int rowDown = (localY + 2) * cropW;

            for (int x = 0; x < cropW; x += xStep) {
                int a = luminance(pixels[rowUp + x]);
                int b = luminance(pixels[rowDown + x]);
                int diff = Math.abs(a - b);
                diffSum += diff;
                if (diff >= 20) strong++;
                count++;
            }

            if (count == 0) continue;
            float averageDiff = diffSum / count;
            float continuity = strong / (float) count;
            float distancePenalty = Math.abs(globalY - centerY) / (float) radius * 5.5f;
            float score = averageDiff + continuity * 12f - distancePenalty;
            scoreSum += score;
            scoredRows++;

            if (score > bestScore) {
                bestScore = score;
                bestLocalY = localY;
            }
        }

        if (scoredRows == 0) return new EdgeResult(wristY, 0f);
        float meanScore = scoreSum / scoredRows;
        float separation = Math.max(0f, bestScore - meanScore);
        float confidence = clamp01((separation - 1.5f) / 16f);
        float y = (top + bestLocalY) / (float) imageH;
        return new EdgeResult(clamp01(y), confidence);
    }

    private static int luminance(int color) {
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        return (77 * r + 150 * g + 29 * b) >> 8;
    }

    private static boolean visible(List<NormalizedLandmark> p, int index) {
        return p.get(index).visibility().orElse(1.0f) >= MIN_VISIBILITY;
    }

    private static float median(ArrayDeque<Float> values) {
        ArrayList<Float> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int n = sorted.size();
        if (n == 0) return Float.NaN;
        if ((n & 1) == 1) return sorted.get(n / 2);
        return (sorted.get(n / 2 - 1) + sorted.get(n / 2)) * 0.5f;
    }

    private static float medianAbsoluteDeviation(ArrayDeque<Float> values, float median) {
        ArrayList<Float> deviations = new ArrayList<>(values.size());
        for (float v : values) deviations.add(Math.abs(v - median));
        Collections.sort(deviations);
        int n = deviations.size();
        if (n == 0) return Float.MAX_VALUE;
        if ((n & 1) == 1) return deviations.get(n / 2);
        return (deviations.get(n / 2 - 1) + deviations.get(n / 2)) * 0.5f;
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static final class EdgeResult {
        final float y;
        final float confidence;

        EdgeResult(float y, float confidence) {
            this.y = y;
            this.confidence = confidence;
        }
    }
}
