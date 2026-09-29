package com.alexzab.pullupcounter;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker;
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult;

public final class OverlayView extends View {
    public interface BarSelectionListener {
        void onBarSelected(float normalizedY);
    }

    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pointPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint guidePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint guideTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private PoseLandmarkerResult result;
    private int inputWidth = 1;
    private int inputHeight = 1;
    private Float barYNormalized = null;
    private boolean selectingBar = true;
    private BarSelectionListener barSelectionListener;

    public OverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setClickable(true);

        linePaint.setColor(Color.rgb(0, 255, 170));
        linePaint.setStrokeWidth(6f);
        linePaint.setStyle(Paint.Style.STROKE);

        pointPaint.setColor(Color.YELLOW);
        pointPaint.setStrokeWidth(10f);
        pointPaint.setStyle(Paint.Style.FILL);

        barPaint.setColor(Color.rgb(255, 80, 80));
        barPaint.setStrokeWidth(7f);
        barPaint.setStyle(Paint.Style.STROKE);

        guidePaint.setColor(Color.argb(150, 0, 0, 0));
        guidePaint.setStyle(Paint.Style.FILL);

        guideTextPaint.setColor(Color.WHITE);
        guideTextPaint.setTextSize(42f);
        guideTextPaint.setTextAlign(Paint.Align.CENTER);
        guideTextPaint.setFakeBoldText(true);
    }

    public void setBarSelectionListener(BarSelectionListener listener) {
        this.barSelectionListener = listener;
    }

    public void beginBarSelection() {
        selectingBar = true;
        postInvalidateOnAnimation();
    }

    public void setBarYNormalized(Float normalizedY) {
        this.barYNormalized = normalizedY;
        if (normalizedY != null) selectingBar = false;
        postInvalidateOnAnimation();
    }

    public void setResult(PoseLandmarkerResult result, int inputWidth, int inputHeight) {
        this.result = result;
        this.inputWidth = Math.max(1, inputWidth);
        this.inputHeight = Math.max(1, inputHeight);
        postInvalidateOnAnimation();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!selectingBar) return super.onTouchEvent(event);
        if (event.getAction() == MotionEvent.ACTION_UP) {
            float normalizedY = viewYToImageNormalizedY(event.getY());
            if (normalizedY >= 0f && normalizedY <= 1f) {
                barYNormalized = normalizedY;
                selectingBar = false;
                if (barSelectionListener != null) barSelectionListener.onBarSelected(normalizedY);
                performClick();
                invalidate();
            }
            return true;
        }
        return true;
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    private float viewYToImageNormalizedY(float viewY) {
        float scale = Math.max(getWidth() / (float) inputWidth, getHeight() / (float) inputHeight);
        float shownH = inputHeight * scale;
        float offsetY = (getHeight() - shownH) * 0.5f;
        return (viewY - offsetY) / shownH;
    }

    private float imageYToViewY(float normalizedY) {
        float scale = Math.max(getWidth() / (float) inputWidth, getHeight() / (float) inputHeight);
        float shownH = inputHeight * scale;
        float offsetY = (getHeight() - shownH) * 0.5f;
        return offsetY + normalizedY * shownH;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (result != null && !result.landmarks().isEmpty()) {
            float scale = Math.max(getWidth() / (float) inputWidth, getHeight() / (float) inputHeight);
            float shownW = inputWidth * scale;
            float shownH = inputHeight * scale;
            float offsetX = (getWidth() - shownW) * 0.5f;
            float offsetY = (getHeight() - shownH) * 0.5f;

            var points = result.landmarks().get(0);
            for (var connection : PoseLandmarker.POSE_LANDMARKS) {
                var a = points.get(connection.start());
                var b = points.get(connection.end());
                canvas.drawLine(offsetX + a.x() * shownW, offsetY + a.y() * shownH,
                        offsetX + b.x() * shownW, offsetY + b.y() * shownH, linePaint);
            }

            for (var p : points) {
                canvas.drawPoint(offsetX + p.x() * shownW, offsetY + p.y() * shownH, pointPaint);
            }
        }

        if (barYNormalized != null) {
            float y = imageYToViewY(barYNormalized);
            canvas.drawLine(0f, y, getWidth(), y, barPaint);
        }

        if (selectingBar) {
            float boxTop = getHeight() * 0.42f;
            float boxBottom = getHeight() * 0.58f;
            canvas.drawRect(0f, boxTop, getWidth(), boxBottom, guidePaint);
            float baseline = (boxTop + boxBottom) * 0.5f
                    - (guideTextPaint.ascent() + guideTextPaint.descent()) * 0.5f;
            canvas.drawText("КОСНИТЕСЬ ПЕРЕКЛАДИНЫ", getWidth() * 0.5f, baseline, guideTextPaint);
        }
    }
}
