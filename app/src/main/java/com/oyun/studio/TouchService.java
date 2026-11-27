package com.oyun.studio;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.os.Build;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

/**
 * TV'de imleç gösterir ve imlecin olduğu yere dokunma hareketi gönderir.
 * Telefon ekranındaki touchpad (TouchpadActivity) bu servisi kullanır.
 * Android 11 (API 30) ve üstü gerekir.
 */
public class TouchService extends AccessibilityService {

    static volatile TouchService inst;

    WindowManager wm;
    CursorView cursor;
    WindowManager.LayoutParams lp;
    Display display;
    int dispId = -1;
    float cx, cy;
    int dw = 1920, dh = 1080;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        inst = this;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) { }

    @Override
    public void onInterrupt() { }

    @Override
    public void onDestroy() {
        removeCursor();
        if (inst == this) inst = null;
        super.onDestroy();
    }

    static boolean supported() {
        return Build.VERSION.SDK_INT >= 30;
    }

    void refreshSize() {
        if (display == null) return;
        Point p = new Point();
        display.getRealSize(p);
        if (p.x > 0 && p.y > 0) { dw = p.x; dh = p.y; }
    }

    /** İmleci verilen ekrana (TV) yerleştir. */
    boolean attach(Display d) {
        if (!supported() || d == null) return false;
        if (cursor != null && dispId == d.getDisplayId()) return true;
        removeCursor();
        try {
            display = d;
            refreshSize();
            Context dc = createDisplayContext(d);
            Context wc = dc.createWindowContext(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null);
            wm = wc.getSystemService(WindowManager.class);
            int size = Math.max(28, dh / 26);
            cursor = new CursorView(wc);
            lp = new WindowManager.LayoutParams(size, size,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.LEFT;
            cx = dw / 2f;
            cy = dh / 2f;
            lp.x = (int) cx;
            lp.y = (int) cy;
            wm.addView(cursor, lp);
            dispId = d.getDisplayId();
            return true;
        } catch (Exception e) {
            cursor = null;
            dispId = -1;
            return false;
        }
    }

    void removeCursor() {
        if (cursor != null && wm != null) {
            try { wm.removeView(cursor); } catch (Exception ignored) { }
        }
        cursor = null;
        dispId = -1;
    }

    boolean ready() {
        return cursor != null && dispId != -1;
    }

    void move(float dx, float dy) {
        if (!ready()) return;
        refreshSize();
        cx = Math.max(0, Math.min(dw - 1, cx + dx));
        cy = Math.max(0, Math.min(dh - 1, cy + dy));
        lp.x = (int) cx;
        lp.y = (int) cy;
        try { wm.updateViewLayout(cursor, lp); } catch (Exception ignored) { }
    }

    /** İmlecin olduğu yere dokun. Uzun basma için süreyi uzat. */
    void tap(long durationMs) {
        if (!ready() || !supported()) return;
        Path p = new Path();
        p.moveTo(cx, cy);
        p.lineTo(cx + 1, cy + 1);
        dispatch(p, durationMs);
    }

    /** İmleçten başlayarak kaydır (liste/sayfa kaydırma). */
    void scroll(float dx, float dy) {
        if (!ready() || !supported()) return;
        float ex = Math.max(0, Math.min(dw - 1, cx + dx));
        float ey = Math.max(0, Math.min(dh - 1, cy + dy));
        Path p = new Path();
        p.moveTo(cx, cy);
        p.lineTo(ex, ey);
        dispatch(p, 220);
    }

    /** Bir noktadan diğerine sürükle. */
    void drag(float sx, float sy, float ex, float ey, long durationMs) {
        if (!ready() || !supported()) return;
        Path p = new Path();
        p.moveTo(sx, sy);
        p.lineTo(ex, ey);
        dispatch(p, Math.max(80, Math.min(3000, durationMs)));
    }

    void dispatch(Path p, long dur) {
        if (Build.VERSION.SDK_INT < 30) return;
        try {
            GestureDescription.Builder b = new GestureDescription.Builder();
            b.addStroke(new GestureDescription.StrokeDescription(p, 0, Math.max(1, dur)));
            b.setDisplayId(dispId);
            dispatchGesture(b.build(), null, null);
            if (cursor != null) cursor.flash();
        } catch (Exception ignored) { }
    }

    /** Ok şeklinde imleç; ucu sol üst köşede (tıklama noktası). */
    static class CursorView extends View {
        final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        boolean pressed;

        CursorView(Context c) {
            super(c);
            fill.setStyle(Paint.Style.FILL);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setColor(0xFF000000);
        }

        void flash() {
            pressed = true;
            invalidate();
            postDelayed(() -> { pressed = false; invalidate(); }, 150);
        }

        @Override
        protected void onDraw(Canvas c) {
            float s = getWidth();
            stroke.setStrokeWidth(Math.max(2f, s / 14f));
            fill.setColor(pressed ? 0xFFE5202E : 0xFFFFFFFF);
            Path a = new Path();
            a.moveTo(s * 0.06f, s * 0.04f);
            a.lineTo(s * 0.06f, s * 0.80f);
            a.lineTo(s * 0.26f, s * 0.62f);
            a.lineTo(s * 0.40f, s * 0.94f);
            a.lineTo(s * 0.54f, s * 0.88f);
            a.lineTo(s * 0.40f, s * 0.57f);
            a.lineTo(s * 0.66f, s * 0.57f);
            a.close();
            c.drawPath(a, fill);
            c.drawPath(a, stroke);
        }
    }
}
