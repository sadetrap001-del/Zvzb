package com.oyun.studio;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Telefon ekranını TV için touchpad yapar.
 * Tek parmak kaydır = imleç, dokun = tıkla, basılı tut = uzun bas,
 * iki parmak kaydır = kaydırma, Sürükle modu = basılı tutup sürükleme.
 */
public class TouchpadActivity extends Activity {

    static final int RED = 0xFFE5202E;

    final Handler ui = new Handler(Looper.getMainLooper());
    TextView status;
    Button dragBtn;
    LinearLayout permBox;
    boolean dragMode;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(0xFF000000);
        getWindow().setNavigationBarColor(0xFF000000);
        setContentView(buildUi());
    }

    @Override
    protected void onResume() {
        super.onResume();
        attachCursor();
    }

    @Override
    protected void onDestroy() {
        TouchService s = TouchService.inst;
        if (s != null) s.removeCursor();
        super.onDestroy();
    }

    int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    Display tvDisplay() {
        DisplayManager dm = (DisplayManager) getSystemService(DISPLAY_SERVICE);
        Display[] ds = dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION);
        return ds.length > 0 ? ds[0] : null;
    }

    void attachCursor() {
        if (!TouchService.supported()) {
            status.setText("Touchpad için Android 11 veya üstü gerekiyor.");
            permBox.setVisibility(View.GONE);
            return;
        }
        TouchService s = TouchService.inst;
        if (s == null) {
            status.setText("Touchpad izni kapalı.");
            permBox.setVisibility(View.VISIBLE);
            return;
        }
        permBox.setVisibility(View.GONE);
        Display d = tvDisplay();
        if (d == null) {
            status.setText("TV bağlı değil. HDMI kabloyu tak.");
            return;
        }
        if (s.attach(d)) status.setText("Kaydır = imleç · Dokun = tıkla · Basılı tut = uzun bas · İki parmak = kaydır");
        else status.setText("İmleç TV'ye eklenemedi.");
    }

    Button button(String t, int bg, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(t);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(15);
        GradientDrawable g = new GradientDrawable();
        g.setColor(bg);
        g.setCornerRadius(dp(12));
        b.setBackground(g);
        b.setOnClickListener(l);
        return b;
    }

    View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF000000);
        root.setPadding(dp(12), dp(14), dp(12), dp(12));

        TextView title = new TextView(this);
        title.setText("● TOUCHPAD");
        title.setTextColor(RED);
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setLetterSpacing(0.1f);
        root.addView(title);

        status = new TextView(this);
        status.setTextColor(0xFFBBBBBB);
        status.setTextSize(13);
        status.setPadding(0, dp(4), 0, dp(10));
        root.addView(status);

        // İzin kutusu
        permBox = new LinearLayout(this);
        permBox.setOrientation(LinearLayout.VERTICAL);
        permBox.setVisibility(View.GONE);
        TextView pt = new TextView(this);
        pt.setTextColor(Color.WHITE);
        pt.setTextSize(14);
        pt.setText("TV'de imleç için bir kerelik izin gerekiyor:\n"
                + "1) Aşağıdaki butona bas\n"
                + "2) \"Yüklü uygulamalar\" içinde \"Oyun Studio Touchpad\"ı aç\n\n"
                + "Açılmıyor / gri görünüyorsa: Ayarlar > Uygulamalar > Oyun Studio > sağ üstte ⋮ > \"Kısıtlanmış ayarlara izin ver\", sonra tekrar dene.");
        permBox.addView(pt);
        Button open = button("Erişilebilirlik ayarlarını aç", RED, v -> {
            try { startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); } catch (Exception ignored) { }
        });
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(-1, dp(52));
        olp.topMargin = dp(10);
        olp.bottomMargin = dp(10);
        permBox.addView(open, olp);
        root.addView(permBox);

        // Touchpad alanı
        Pad pad = new Pad(this);
        GradientDrawable pg = new GradientDrawable();
        pg.setColor(0xFF15151B);
        pg.setCornerRadius(dp(18));
        pg.setStroke(dp(1), 0x33FFFFFF);
        pad.setBackground(pg);
        root.addView(pad, new LinearLayout.LayoutParams(-1, 0, 1f));

        // Alt butonlar
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(10), 0, 0);

        Button click = button("TIKLA", RED, v -> { TouchService s = TouchService.inst; if (s != null) s.tap(60); });
        Button lng = button("UZUN BAS", 0xFF23232B, v -> { TouchService s = TouchService.inst; if (s != null) s.tap(800); });
        dragBtn = button("SÜRÜKLE: KAPALI", 0xFF23232B, v -> {
            dragMode = !dragMode;
            dragBtn.setText(dragMode ? "SÜRÜKLE: AÇIK" : "SÜRÜKLE: KAPALI");
            ((GradientDrawable) dragBtn.getBackground()).setColor(dragMode ? 0xFF8A1018 : 0xFF23232B);
        });
        Button studio = button("STUDIO", 0xFF23232B, v -> {
            startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            finish();
        });
        View[] bs = {click, lng, dragBtn, studio};
        float[] w = {1.1f, 1f, 1.3f, 0.9f};
        for (int i = 0; i < bs.length; i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(56), w[i]);
            lp.setMargins(dp(4), 0, dp(4), 0);
            row.addView(bs[i], lp);
        }
        root.addView(row);
        return root;
    }

    /** Dokunmatik alan */
    class Pad extends View {
        float lastX, lastY, downX, downY, lastAvgY, scrollAcc;
        float dragStartX, dragStartY;
        long downTime;
        boolean moved, twoFinger, longFired;
        final Paint hint = new Paint(Paint.ANTI_ALIAS_FLAG);

        final Runnable longPress = () -> {
            TouchService s = TouchService.inst;
            if (s != null && !moved && !twoFinger && !dragMode) {
                longFired = true;
                s.tap(800);
                performHapticFeedback(0);
            }
        };

        Pad(Context c) {
            super(c);
            hint.setColor(0x33FFFFFF);
            hint.setTextAlign(Paint.Align.CENTER);
            hint.setTextSize(dp(15));
        }

        @Override
        protected void onDraw(Canvas c) {
            super.onDraw(c);
            c.drawText(dragMode ? "SÜRÜKLE MODU" : "TOUCHPAD", getWidth() / 2f, getHeight() / 2f, hint);
        }

        float speed(float dist) {
            TouchService s = TouchService.inst;
            float tvH = s != null ? s.dh : 1080;
            float base = tvH / Math.max(1f, getHeight()) * 1.4f;
            float accel = 1f + Math.min(2.5f, dist / dp(12));
            return base * accel;
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            TouchService s = TouchService.inst;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    lastX = downX = e.getX();
                    lastY = downY = e.getY();
                    downTime = SystemClock.uptimeMillis();
                    moved = false;
                    twoFinger = false;
                    longFired = false;
                    scrollAcc = 0;
                    if (s != null) { dragStartX = s.cx; dragStartY = s.cy; }
                    ui.postDelayed(longPress, 550);
                    invalidate();
                    return true;

                case MotionEvent.ACTION_POINTER_DOWN:
                    twoFinger = true;
                    ui.removeCallbacks(longPress);
                    lastAvgY = avgY(e);
                    scrollAcc = 0;
                    return true;

                case MotionEvent.ACTION_MOVE:
                    if (twoFinger && e.getPointerCount() >= 2) {
                        float ay = avgY(e);
                        scrollAcc += ay - lastAvgY;
                        lastAvgY = ay;
                        if (Math.abs(scrollAcc) > dp(18) && s != null) {
                            s.scroll(0, scrollAcc * speed(0) * 1.5f);
                            scrollAcc = 0;
                        }
                        return true;
                    }
                    float dx = e.getX() - lastX, dy = e.getY() - lastY;
                    lastX = e.getX();
                    lastY = e.getY();
                    if (Math.hypot(e.getX() - downX, e.getY() - downY) > dp(8)) {
                        moved = true;
                        ui.removeCallbacks(longPress);
                    }
                    if (s != null) {
                        float sp = speed((float) Math.hypot(dx, dy));
                        s.move(dx * sp, dy * sp);
                    }
                    return true;

                case MotionEvent.ACTION_UP:
                    ui.removeCallbacks(longPress);
                    long dt = SystemClock.uptimeMillis() - downTime;
                    if (s != null) {
                        if (dragMode && moved && !twoFinger) {
                            s.drag(dragStartX, dragStartY, s.cx, s.cy, dt);
                        } else if (!moved && !twoFinger && !longFired && dt < 300) {
                            s.tap(60);
                        }
                    }
                    twoFinger = false;
                    return true;

                case MotionEvent.ACTION_CANCEL:
                    ui.removeCallbacks(longPress);
                    twoFinger = false;
                    return true;
            }
            return super.onTouchEvent(e);
        }

        float avgY(MotionEvent e) {
            float sum = 0;
            for (int i = 0; i < e.getPointerCount(); i++) sum += e.getY(i);
            return sum / e.getPointerCount();
        }
    }
}
