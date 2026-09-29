package com.alexzab.pullupcounter;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;

public final class Camera2Controller implements AutoCloseable {
    public interface Listener {
        void onFrame(Bitmap bitmap);
        void onError(String message);
    }

    private static final String TAG = "Camera2Controller";
    private static final long FRAME_INTERVAL_MS = 120;

    private final Activity activity;
    private final Listener listener;
    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private ImageReader imageReader;
    private int sensorOrientation = 0;
    private boolean frontCamera = true;
    private long lastFrameTime = 0;
    private final AtomicBoolean converting = new AtomicBoolean(false);

    public Camera2Controller(Activity activity, Listener listener) {
        this.activity = activity;
        this.listener = listener;
    }

    public void start() {
        if (cameraThread != null) return;
        cameraThread = new HandlerThread("PullupCamera");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
        openCamera();
    }

    private void openCamera() {
        CameraManager manager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
        try {
            String id = chooseCamera(manager);
            if (id == null) {
                listener.onError("Камера не найдена");
                return;
            }
            CameraCharacteristics cc = manager.getCameraCharacteristics(id);
            Integer orientation = cc.get(CameraCharacteristics.SENSOR_ORIENTATION);
            sensorOrientation = orientation == null ? 0 : orientation;
            Integer facing = cc.get(CameraCharacteristics.LENS_FACING);
            frontCamera = facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT;

            StreamConfigurationMap map = cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) throw new IllegalStateException("Нет StreamConfigurationMap");
            Size size = chooseAnalysisSize(map.getOutputSizes(ImageFormat.YUV_420_888));
            imageReader = ImageReader.newInstance(size.getWidth(), size.getHeight(), ImageFormat.YUV_420_888, 2);
            imageReader.setOnImageAvailableListener(this::onImageAvailable, cameraHandler);

            if (activity.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                listener.onError("Нет разрешения CAMERA");
                return;
            }
            manager.openCamera(id, stateCallback, cameraHandler);
        } catch (Exception e) {
            Log.e(TAG, "openCamera", e);
            listener.onError("Не удалось открыть камеру: " + e.getMessage());
        }
    }

    private String chooseCamera(CameraManager manager) throws CameraAccessException {
        String fallback = null;
        for (String id : manager.getCameraIdList()) {
            CameraCharacteristics cc = manager.getCameraCharacteristics(id);
            Integer facing = cc.get(CameraCharacteristics.LENS_FACING);
            if (fallback == null) fallback = id;
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) return id;
        }
        return fallback;
    }

    private static Size chooseAnalysisSize(Size[] sizes) {
        if (sizes == null || sizes.length == 0) return new Size(640, 480);
        Size best = sizes[0];
        long target = 640L * 480L;
        long bestScore = Long.MAX_VALUE;
        for (Size s : sizes) {
            long area = (long) s.getWidth() * s.getHeight();
            double ratioPenalty = Math.abs(s.getWidth() / (double) s.getHeight() - 4.0 / 3.0) * target * 2.0;
            long score = (long) (Math.abs(area - target) + ratioPenalty);
            if (score < bestScore) {
                best = s;
                bestScore = score;
            }
        }
        return best;
    }

    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override public void onOpened(CameraDevice camera) {
            cameraDevice = camera;
            createSession();
        }

        @Override public void onDisconnected(CameraDevice camera) {
            camera.close();
            cameraDevice = null;
        }

        @Override public void onError(CameraDevice camera, int error) {
            camera.close();
            cameraDevice = null;
            listener.onError("Ошибка камеры: " + error);
        }
    };

    private void createSession() {
        if (cameraDevice == null || imageReader == null) return;
        try {
            Surface analysisSurface = imageReader.getSurface();
            cameraDevice.createCaptureSession(Collections.singletonList(analysisSurface),
                    new CameraCaptureSession.StateCallback() {
                        @Override public void onConfigured(CameraCaptureSession session) {
                            if (cameraDevice == null) return;
                            captureSession = session;
                            try {
                                CaptureRequest.Builder request = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                                request.addTarget(analysisSurface);
                                session.setRepeatingRequest(request.build(), null, cameraHandler);
                            } catch (CameraAccessException e) {
                                listener.onError("Не удалось запустить поток камеры: " + e.getMessage());
                            }
                        }

                        @Override public void onConfigureFailed(CameraCaptureSession session) {
                            listener.onError("Не удалось настроить поток камеры");
                        }
                    }, cameraHandler);
        } catch (CameraAccessException e) {
            listener.onError("Ошибка создания camera session: " + e.getMessage());
        }
    }

    private void onImageAvailable(ImageReader reader) {
        Image image = reader.acquireLatestImage();
        if (image == null) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastFrameTime < FRAME_INTERVAL_MS || !converting.compareAndSet(false, true)) {
            image.close();
            return;
        }
        lastFrameTime = now;
        try {
            Bitmap raw = yuv420ToBitmap(image);
            int rotation = calculateRotationDegrees();
            Bitmap oriented = rotateAndMirror(raw, rotation, frontCamera);
            listener.onFrame(oriented);
        } catch (Throwable t) {
            Log.e(TAG, "frame conversion", t);
            listener.onError("Ошибка обработки кадра: " + t.getMessage());
        } finally {
            image.close();
            converting.set(false);
        }
    }

    private int calculateRotationDegrees() {
        int displayDegrees;
        switch (activity.getWindowManager().getDefaultDisplay().getRotation()) {
            case Surface.ROTATION_90: displayDegrees = 90; break;
            case Surface.ROTATION_180: displayDegrees = 180; break;
            case Surface.ROTATION_270: displayDegrees = 270; break;
            default: displayDegrees = 0;
        }
        if (frontCamera) return (sensorOrientation + displayDegrees) % 360;
        return (sensorOrientation - displayDegrees + 360) % 360;
    }

    private static Bitmap rotateAndMirror(Bitmap src, int degrees, boolean mirror) {
        if (degrees == 0 && !mirror) return src;
        Matrix matrix = new Matrix();
        if (degrees != 0) matrix.postRotate(degrees);
        if (mirror) matrix.postScale(-1f, 1f);
        return Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), matrix, true);
    }

    private static Bitmap yuv420ToBitmap(Image image) {
        int width = image.getWidth();
        int height = image.getHeight();
        Image.Plane[] planes = image.getPlanes();
        ByteBuffer yBuf = planes[0].getBuffer();
        ByteBuffer uBuf = planes[1].getBuffer();
        ByteBuffer vBuf = planes[2].getBuffer();
        int yRowStride = planes[0].getRowStride();
        int yPixelStride = planes[0].getPixelStride();
        int uRowStride = planes[1].getRowStride();
        int uPixelStride = planes[1].getPixelStride();
        int vRowStride = planes[2].getRowStride();
        int vPixelStride = planes[2].getPixelStride();

        int[] argb = new int[width * height];
        for (int y = 0; y < height; y++) {
            int yRow = y * yRowStride;
            int uvY = y >> 1;
            int uRow = uvY * uRowStride;
            int vRow = uvY * vRowStride;
            for (int x = 0; x < width; x++) {
                int yIndex = yRow + x * yPixelStride;
                int uvX = x >> 1;
                int uIndex = uRow + uvX * uPixelStride;
                int vIndex = vRow + uvX * vPixelStride;

                int Y = yBuf.get(yIndex) & 0xff;
                int U = (uBuf.get(uIndex) & 0xff) - 128;
                int V = (vBuf.get(vIndex) & 0xff) - 128;

                int c = Math.max(0, Y - 16);
                int r = (298 * c + 409 * V + 128) >> 8;
                int g = (298 * c - 100 * U - 208 * V + 128) >> 8;
                int b = (298 * c + 516 * U + 128) >> 8;
                r = clamp(r); g = clamp(g); b = clamp(b);
                argb[y * width + x] = 0xff000000 | (r << 16) | (g << 8) | b;
            }
        }
        return Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888);
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : Math.min(v, 255);
    }

    @Override
    public void close() {
        if (captureSession != null) {
            captureSession.close();
            captureSession = null;
        }
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
        if (cameraThread != null) {
            cameraThread.quitSafely();
            try { cameraThread.join(1000); } catch (InterruptedException ignored) { }
            cameraThread = null;
            cameraHandler = null;
        }
    }
}
