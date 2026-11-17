package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.SweepGradient;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;

/**
 * A rainbow light that circles the focused tile. It sits just above the {@link GlassView} and copies its position,
 * size and fade on every frame, so the glass keeps its cached texture and only this one ring is redrawn. The
 * angle comes from the clock, not from an Animator, so the speed does not change with the system animation scale.
 */
final class SpinRimView extends View {

    private static final long PERIOD_MS = 3600;
    private static final int[] COLORS = {
        0xFFFF3B30, 0xFFFF9F0A, 0xFFFFD60A, 0xFF30D158, 0xFF64D2FF, 0xFF5E5CE6, 0xFFBF5AF2, 0xFFFF3B30,
    };

    private final GlassView glass;
    private final float density;
    private final Paint soft = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint core = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix matrix = new Matrix();
    private final RectF rect = new RectF();
    private SweepGradient sweep;

    /** Adds the ring right above the glass in the same parent. */
    static void attach(final GlassView glass) {
        final ViewGroup parent = (ViewGroup) glass.getParent();
        final SpinRimView ring = new SpinRimView(glass.getContext(), glass);
        int dp10 = Math.round(10f * glass.getResources().getDisplayMetrics().density);
        parent.addView(ring, parent.indexOfChild(glass) + 1, new FrameLayout.LayoutParams(dp10, dp10));
        parent.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                ring.follow();
                return true;
            }
        });
    }

    private SpinRimView(Context context, GlassView glass) {
        super(context);
        this.glass = glass;
        density = getResources().getDisplayMetrics().density;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setFocusable(false);
        setClickable(false);
        setVisibility(INVISIBLE);
        for (Paint p : new Paint[] {soft, mid, core}) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
        }
        soft.setStrokeWidth(6f * density);
        soft.setAlpha(55);
        mid.setStrokeWidth(3f * density);
        mid.setAlpha(130);
        core.setStrokeWidth(1.8f * density);
        core.setAlpha(255);
    }

    /** Runs before each frame: match the glass, and keep the loop going while it is showing. */
    private void follow() {
        boolean show = glass.getAlpha() > 0.02f && glass.getVisibility() == VISIBLE
                && glass.getWidth() > 0 && glass.getHeight() > 0;
        if (!show) {
            if (getVisibility() != INVISIBLE) setVisibility(INVISIBLE);
            return;
        }
        ViewGroup.LayoutParams lp = getLayoutParams();
        if (lp.width != glass.getWidth() || lp.height != glass.getHeight()) {
            lp.width = glass.getWidth();
            lp.height = glass.getHeight();
            setLayoutParams(lp);
        }
        setTranslationX(glass.getX() - getLeft());
        setTranslationY(glass.getY() - getTop());
        setAlpha(glass.getAlpha());
        if (getVisibility() != VISIBLE) setVisibility(VISIBLE);
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        sweep = new SweepGradient(w / 2f, h / 2f, COLORS, null);
        soft.setShader(sweep);
        mid.setShader(sweep);
        core.setShader(sweep);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (sweep == null) return;
        float angle = (SystemClock.uptimeMillis() % PERIOD_MS) * 360f / PERIOD_MS;
        matrix.setRotate(angle, getWidth() / 2f, getHeight() / 2f);
        sweep.setLocalMatrix(matrix);
        float inset = 6f * density;   // the same inset GlassView leaves for its glow
        rect.set(inset, inset, getWidth() - inset, getHeight() - inset);
        float r = glass.getRadius();
        canvas.drawRoundRect(rect, r, r, soft);
        canvas.drawRoundRect(rect, r, r, mid);
        canvas.drawRoundRect(rect, r, r, core);
    }
}
