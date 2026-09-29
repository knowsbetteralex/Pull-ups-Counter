package com.alexzab.pullupcounter;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.util.Log;

import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker;
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult;

public final class PoseEngine implements AutoCloseable {
    public interface Listener {
        void onPose(PoseLandmarkerResult result, int inputWidth, int inputHeight, long inferenceMs);
        void onError(String message);
    }

    private static final String TAG = "PoseEngine";
    private final PoseLandmarker landmarker;
    private final Listener listener;

    public PoseEngine(Context context, Listener listener) {
        this.listener = listener;

        BaseOptions baseOptions = BaseOptions.builder()
                .setModelAssetPath("pose_landmarker_lite.task")
                .build();

        PoseLandmarker.PoseLandmarkerOptions options = PoseLandmarker.PoseLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setNumPoses(1)
                .setMinPoseDetectionConfidence(0.50f)
                .setMinPosePresenceConfidence(0.50f)
                .setMinTrackingConfidence(0.50f)
                .setResultListener((result, input) -> {
                    long inferenceMs = SystemClock.uptimeMillis() - result.timestampMs();
                    listener.onPose(result, input.getWidth(), input.getHeight(), inferenceMs);
                })
                .setErrorListener(error -> {
                    Log.e(TAG, "MediaPipe error", error);
                    listener.onError(error.getMessage() == null ? "MediaPipe error" : error.getMessage());
                })
                .build();

        landmarker = PoseLandmarker.createFromOptions(context, options);
    }

    public void detect(Bitmap bitmap) {
        if (bitmap == null) return;
        MPImage image = new BitmapImageBuilder(bitmap).build();
        landmarker.detectAsync(image, SystemClock.uptimeMillis());
    }

    @Override
    public void close() {
        landmarker.close();
    }
}
