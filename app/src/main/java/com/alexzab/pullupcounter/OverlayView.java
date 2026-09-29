package com.alexzab.pullupcounter;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
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
    private final Paint barLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint barLabelBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint guidePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint guideTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private PoseLandmarkerResult result;
    private int inputWidth = 1;
    private int inputHeight = 1;
    private Float barYNormalized = null;
    private boolean selectingBar = false;
    private boolean autoSearching = true;
    private boolean barAutomatic = false;
    private BarSelectionListener barSelectionListener;

    public OverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setClickable(true);

        linePaint.setColor(Color.rgb(55, 230, 180));
        linePaint.setStrokeWidth(5f);
        linePaint.setStyle(Paint.Style.STROKE);

        pointPaint.setColor(Color.WHITE);
        pointPaint.setStrokeWidth(8f);
        pointPaint.setStyle(Paint.Style.FILL);

        barPaint.setStrokeWidth(7f);
        barPaint.setStyle(Paint.Style.STROKE);

        barLabelPaint.setTextSize(30f);
        barLabelPaint.setFakeBoldText(true);
        barLabelPaint.setTextAlign(Paint.Align.CENTER);

        barLabelBgPaint.setColor(Color.argb(205, 12, 18, 24));
        barLabelBgPaint.setStyle(Paint.Style.FILL);

        guidePaint.setColor(Color.argb(176, 10, 15, 20));
        guidePaint.setStyle(Paint.Style.FILL);

        guideTextPaint.setColor(Color.WHITE);
        guideTextPaint.setTextSize(36f);
        guideTextPaint.setTextAlign(Paint.Align.CENTER);
        guideTextPaint.setFakeBoldText(true);
    }

    public void setBarSelectionListener(BarSelectionListener listener) {
        this.barSelectionListener = listener;
    }

    public void beginBarSelection() {
        selectingBar = true;
        autoSearching = false;
        barAutomatic = false;
        postInvalidateOnAnimation();
    }

    public void beginAutoBarSearch() {
        selectingBar = false;
        autoSearching = true;
        barAutomatic = true;
        barYNormalized = null;
        postInvalidateOnAnimation();
    }

    public void setBarYNormalized(Float normalizedY, boolean automatic) {
        this.barYNormalized = normalizedY;
        this.barAutomatic = automatic;
        if (normalizedY != null) {
            selectingBar = false;
            autoSearching = false;
        }
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
        if (!selectingBar) return false;

        if (event.getAction() == MotionEvent.ACTION_DOWN
                || event.getAction() == MotionEvent.ACTION_MOVE
                || event.getAction() == MotionEvent.ACTION_UP) {
            float normalizedY = viewYToImageNormalizedY(event.getY());
            if (normalizedY >= 0f && normalizedY <= 1f) {
                barYNormalized = normalizedY;
                barAutomatic = false;
                invalidate();

                if (event.getAction() == MotionEvent.ACTION_UP) {
                    selectingBar = false;
                    if (barSelectionListener != null) barSelectionListener.onBarSelected(normalizedY);
                    performClick();
                }
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
            int color = barAutomatic ? Color.rgb(55, 230, 180) : Color.rgb(255, 200, 87);
            barPaint.setColor(color);
            barLabelPaint.setColor(color);
            canvas.drawLine(0f, y, getWidth(), y, barPaint);

            String label = barAutomatic ? "AUTO" : "MANUAL";
            float labelX = getWidth() - 58f;
            float labelY = Math.max(36f, y - 16f);
            RectF bg = new RectF(labelX - 48f, labelY - 30f, labelX + 48f, labelY + 10f);
            canvas.drawRoundRect(bg, 18f, 18f, barLabelBgPaint);
            canvas.drawText(label, labelX, labelY, barLabelPaint);
        }

        if (selectingBar) {
            float boxTop = getHeight() * 0.43f;
            float boxBottom = getHeight() * 0.57f;
            canvas.drawRect(0f, boxTop, getWidth(), boxBottom, guidePaint);
            float baseline = (boxTop + boxBottom) * 0.5f
                    - (guideTextPaint.ascent() + guideTextPaint.descent()) * 0.5f;
            canvas.drawText("ПРОВЕДИТЕ ЛИНИЮ ПО ПЕРЕКЛАДИНЕ", getWidth() * 0.5f,
                    baseline, guideTextPaint);
        } else if (autoSearching) {
            float boxTop = getHeight() * 0.78f;
            float boxBottom = getHeight() * 0.86f;
            canvas.drawRoundRect(new RectF(28f, boxTop, getWidth() - 28f, boxBottom),
                    28f, 28f, guidePaint);
            float baseline = (boxTop + boxBottom) * 0.5f
                    - (guideTextPaint.ascent() + guideTextPaint.descent()) * 0.5f;
            guideTextPaint.setTextSize(30f);
            canvas.drawText("ВОЗЬМИТЕСЬ ЗА ПЕРЕКЛАДИНУ И ПОВИСНИТЕ",
                    getWidth() * 0.5f, baseline, guideTextPaint);
            guideTextPaint.setTextSize(36f);
        }
    }
}
