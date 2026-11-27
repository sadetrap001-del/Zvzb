package com.oyun.studio;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.Presentation;
import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Oyun Studio
 * Telefon HDMI / USB-C ile harici ekrana bağlanınca, harici ekranda
 * (mümkünse 4K) tam ekran bir oyun başlatıcısı açar.
 * Telefon ekranı kumanda olarak kullanılabilir; gamepad de desteklenir.
 */
public class MainActivity extends Activity implements DisplayManager.DisplayListener {

    static final int RED = 0xFFE5202E;
    static final int BG = 0xFF0B0B0F;

    DisplayManager dm;
    final Handler ui = new Handler(Looper.getMainLooper());
    SharedPreferences prefs;

    StudioScreen screen;
    TextView status;
    Switch onlyGamesSwitch;

    boolean resumed;
    boolean hiddenForGame;
    boolean appsLoaded;
    boolean showingFallback;

    final List<AppItem> allApps = new ArrayList<>();

    static class AppItem {
        String label;
        String pkg;
        ComponentName cn;
        Drawable icon;
        boolean game;
        long last;
    }

    // ------------------------------------------------------------------ yaşam döngüsü

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("studio", MODE_PRIVATE);
        dm = (DisplayManager) getSystemService(DISPLAY_SERVICE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        setContentView(buildPhoneUi());
        loadApps();
    }

    @Override
    protected void onRestart() {
        super.onRestart();
        if (launchedAt != 0 && SystemClock.elapsedRealtime() - launchedAt < 15000) {
            // Oyun açıldıktan hemen sonra geri dönüldü: oyun kapanmış/çökmüş olabilir
            Toast.makeText(this, "Oyun hemen kapandı. Studio açılmadı; tekrar denemek için \"Studio'yu göster\"e bas.", Toast.LENGTH_LONG).show();
            hiddenForGame = true;
        } else {
            // Oyundan geri dönüldü: Studio tekrar açılsın
            hiddenForGame = false;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        dm.registerDisplayListener(this, ui);
        if (!hiddenForGame) showStudio(); else updateStatus();
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        dm.unregisterDisplayListener(this);
        dismissStudio();
    }

    // ------------------------------------------------------------------ harici ekran olayları

    @Override
    public void onDisplayAdded(int displayId) {
        hiddenForGame = false;
        if (resumed) showStudio();
    }

    @Override
    public void onDisplayRemoved(int displayId) {
        if (screen != null && screen.getDisplay().getDisplayId() == displayId) dismissStudio();
        hiddenForGame = false;
        updateStatus();
    }

    @Override
    public void onDisplayChanged(int displayId) {
        updateStatus();
        if (screen != null) screen.updateTopBar();
    }

    Display presentationDisplay() {
        Display[] ds = dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION);
        return ds.length > 0 ? ds[0] : null;
    }

    void showStudio() {
        Display d = presentationDisplay();
        if (d == null) {
            dismissStudio();
            return;
        }
        if (screen != null && screen.getDisplay().getDisplayId() == d.getDisplayId()) {
            updateStatus();
            return;
        }
        dismissStudio();
        final StudioScreen s = new StudioScreen(this, d);
        s.setOnDismissListener(x -> {
            if (screen == s) {
                screen = null;
                updateStatus();
            }
        });
        screen = s;
        try {
            s.show();
        } catch (WindowManager.InvalidDisplayException e) {
            screen = null;
        }
        updateStatus();
    }

    void dismissStudio() {
        if (screen != null) {
            StudioScreen s = screen;
            screen = null;
            s.dismiss();
        }
        updateStatus();
    }

    void updateStatus() {
        if (status == null) return;
        Display d = presentationDisplay();
        if (d == null) {
            status.setText("Harici ekran bulunamadı.\nHDMI kabloyu tak, Studio otomatik açılacak.");
            return;
        }
        Display.Mode m = d.getMode();
        String state = screen != null ? "Studio açık" : (hiddenForGame ? "Oyunda" : "Studio kapalı");
        status.setText("Bağlı: " + d.getName() + "\n"
                + m.getPhysicalWidth() + "×" + m.getPhysicalHeight()
                + " @" + Math.round(m.getRefreshRate()) + "Hz   •   " + state);
    }

    // ------------------------------------------------------------------ uygulama listesi

    void loadApps() {
        new Thread(() -> {
            PackageManager pm = getPackageManager();
            Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> ris = pm.queryIntentActivities(q, 0);
            List<AppItem> list = new ArrayList<>();
            for (ResolveInfo ri : ris) {
                if (ri.activityInfo == null) continue;
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(getPackageName())) continue;
                AppItem a = new AppItem();
                a.pkg = pkg;
                a.cn = new ComponentName(pkg, ri.activityInfo.name);
                a.label = String.valueOf(ri.loadLabel(pm));
                a.icon = ri.loadIcon(pm);
                ApplicationInfo ai = ri.activityInfo.applicationInfo;
                //noinspection deprecation
                a.game = ai.category == ApplicationInfo.CATEGORY_GAME
                        || (ai.flags & ApplicationInfo.FLAG_IS_GAME) != 0;
                list.add(a);
            }
            ui.post(() -> {
                allApps.clear();
                allApps.addAll(list);
                appsLoaded = true;
                if (screen != null) screen.reload();
            });
        }).start();
    }

    List<AppItem> visibleApps() {
        boolean only = prefs.getBoolean("onlyGames", true);
        List<AppItem> r = new ArrayList<>();
        for (AppItem a : allApps) if (!only || a.game) r.add(a);
        showingFallback = false;
        if (only && r.isEmpty() && !allApps.isEmpty()) {
            r.addAll(allApps);
            showingFallback = true;
        }
        for (AppItem a : r) a.last = prefs.getLong("last_" + a.pkg, 0);
        // Son oynananlar başta, sonra alfabetik
        Collections.sort(r, (x, y) -> {
            if (x.last != y.last) return Long.compare(y.last, x.last);
            return x.label.compareToIgnoreCase(y.label);
        });
        return r;
    }

    void setOnlyGames(boolean v) {
        prefs.edit().putBoolean("onlyGames", v).apply();
        if (onlyGamesSwitch != null && onlyGamesSwitch.isChecked() != v) onlyGamesSwitch.setChecked(v);
        if (screen != null) screen.reload();
    }

    long launchedAt;

    void launchApp(AppItem a) {
        prefs.edit().putLong("last_" + a.pkg, System.currentTimeMillis()).apply();
        final Display d = screen != null ? screen.getDisplay() : presentationDisplay();
        final boolean modeWasForced = screen != null && prefs.getBoolean("force4k", true);
        hiddenForGame = true;
        dismissStudio();
        Toast.makeText(this, a.label + " açılıyor…", Toast.LENGTH_SHORT).show();
        // Studio kapanınca harici ekran çözünürlüğü eski haline dönüyor.
        // Bu geçiş bitmeden oyunu açmayalım (emülatörler çözünürlük değişiminde çökebiliyor).
        ui.postDelayed(() -> startGame(a, d), modeWasForced ? 2000 : 300);
    }

    void startGame(AppItem a, Display d) {
        // Telefonun ana ekranının kullandığı başlatma isteğinin aynısı
        Intent i = getPackageManager().getLaunchIntentForPackage(a.pkg);
        if (i == null) {
            i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(a.cn);
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        launchedAt = SystemClock.elapsedRealtime();

        boolean started = false;
        if (prefs.getBoolean("external", false) && d != null && d.isValid()) {
            try {
                ActivityOptions o = ActivityOptions.makeBasic();
                o.setLaunchDisplayId(d.getDisplayId());
                startActivity(i, o.toBundle());
                started = true;
            } catch (Exception e) {
                Toast.makeText(this, "Harici ekranda açılamadı, ayna modunda açılıyor", Toast.LENGTH_LONG).show();
            }
        }
        if (!started) {
            try {
                startActivity(i);
                started = true;
            } catch (Exception e) {
                hiddenForGame = false;
                showStudio();
                Toast.makeText(this, "Uygulama açılamadı", Toast.LENGTH_SHORT).show();
            }
        }
        // Oyun Studio'yu oyunun arkasından çek: oyun kendi içinde başka ekran/uygulama açarsa
        // ya da kapanırsa Oyun Studio araya girmesin.
        if (started && !prefs.getBoolean("external", false)) {
            ui.postDelayed(() -> moveTaskToBack(true), 800);
        }
    }

    // ------------------------------------------------------------------ gamepad yönlendirme

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (screen != null && screen.handleKey(e)) return true;
        if (screen == null && e.getAction() == KeyEvent.ACTION_DOWN
                && (e.getKeyCode() == KeyEvent.KEYCODE_BUTTON_MODE
                || e.getKeyCode() == KeyEvent.KEYCODE_BUTTON_START)) {
            hiddenForGame = false;
            showStudio();
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent e) {
        if (screen != null && screen.handleMotion(e)) return true;
        return super.dispatchGenericMotionEvent(e);
    }

    // ------------------------------------------------------------------ telefon arayüzü (kumanda)

    int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    Button button(String s, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setTextColor(Color.WHITE);
        b.setTextSize(18);
        b.setAllCaps(false);
        GradientDrawable g = new GradientDrawable();
        g.setColor(0xFF1C1C24);
        g.setCornerRadius(dp(14));
        g.setStroke(dp(1), 0x33FFFFFF);
        b.setBackground(g);
        b.setOnClickListener(l);
        return b;
    }

    View buildPhoneUi() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        sv.setFillViewport(true);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(22), dp(28), dp(22), dp(28));
        sv.addView(col);

        TextView title = text("● OYUN STUDIO", 28, RED, true);
        title.setLetterSpacing(0.08f);
        col.addView(title);

        status = text("", 15, 0xFFCCCCCC, false);
        status.setPadding(0, dp(10), 0, dp(18));
        col.addView(status);

        Button show = button("Studio'yu harici ekranda göster", v -> {
            hiddenForGame = false;
            showStudio();
            if (presentationDisplay() == null)
                Toast.makeText(this, "Önce HDMI kabloyu tak", Toast.LENGTH_SHORT).show();
        });
        col.addView(show, new LinearLayout.LayoutParams(-1, dp(58)));

        onlyGamesSwitch = new Switch(this);
        onlyGamesSwitch.setText("Sadece oyunları göster");
        onlyGamesSwitch.setTextColor(Color.WHITE);
        onlyGamesSwitch.setTextSize(16);
        onlyGamesSwitch.setChecked(prefs.getBoolean("onlyGames", true));
        onlyGamesSwitch.setPadding(0, dp(18), 0, dp(6));
        onlyGamesSwitch.setOnCheckedChangeListener((c, v) -> setOnlyGames(v));
        col.addView(onlyGamesSwitch);

        Switch ext = new Switch(this);
        ext.setText("Oyunu harici ekranda aç (masaüstü modu)\nKapalıysa oyun telefonda açılır ve ekrana aynalanır");
        ext.setTextColor(Color.WHITE);
        ext.setTextSize(14);
        ext.setChecked(prefs.getBoolean("external", false));
        ext.setPadding(0, dp(10), 0, dp(18));
        ext.setOnCheckedChangeListener((c, v) -> prefs.edit().putBoolean("external", v).apply());
        col.addView(ext);

        Switch k4 = new Switch(this);
        k4.setText("Harici ekranı en yüksek çözünürlüğe (4K) zorla");
        k4.setTextColor(Color.WHITE);
        k4.setTextSize(14);
        k4.setChecked(prefs.getBoolean("force4k", true));
        k4.setPadding(0, 0, 0, dp(18));
        k4.setOnCheckedChangeListener((c, v) -> {
            prefs.edit().putBoolean("force4k", v).apply();
            if (screen != null) { dismissStudio(); showStudio(); }
        });
        col.addView(k4);

        TextView rem = text("KUMANDA", 13, 0xFF888888, true);
        rem.setLetterSpacing(0.2f);
        rem.setPadding(0, dp(8), 0, dp(10));
        col.addView(rem);

        col.addView(remoteRow(null, button("▲", v -> move(0, -1)), null));
        col.addView(remoteRow(button("◀", v -> move(-1, 0)), openButton(), button("▶", v -> move(1, 0))));
        col.addView(remoteRow(null, button("▼", v -> move(0, 1)), null));

        Button filter = button("Oyunlar / Tüm uygulamalar", v -> setOnlyGames(!prefs.getBoolean("onlyGames", true)));
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(-1, dp(52));
        flp.topMargin = dp(14);
        col.addView(filter, flp);

        TextView hint = text("Gamepad bağlıysa direkt onunla gezebilirsin:\n"
                + "Yön tuşları / sol analog = gez,  A = aç,  Y = oyunlar ↔ tümü,  START = Studio'ya dön.\n\n"
                + "Oyundan çıkıp bu uygulamaya döndüğünde Studio tekrar açılır.", 13, 0xFF9A9AA5, false);
        hint.setPadding(0, dp(20), 0, 0);
        col.addView(hint);

        return sv;
    }

    Button openButton() {
        Button b = button("AÇ", v -> {
            if (screen != null) screen.openSelected();
            else { hiddenForGame = false; showStudio(); }
        });
        GradientDrawable g = new GradientDrawable();
        g.setColor(RED);
        g.setCornerRadius(dp(14));
        b.setBackground(g);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        return b;
    }

    LinearLayout remoteRow(View a, View b, View c) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        View[] vs = {a, b, c};
        for (View v : vs) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(88), dp(70));
            lp.setMargins(dp(5), dp(5), dp(5), dp(5));
            row.addView(v != null ? v : new View(this), lp);
        }
        return row;
    }

    void move(int dx, int dy) {
        if (screen == null) {
            hiddenForGame = false;
            showStudio();
            return;
        }
        if (dx != 0) screen.move(dx);
        else screen.move(dy * screen.cols);
    }

    // ================================================================== HARİCİ EKRAN

    static class StudioScreen extends Presentation {

        final MainActivity host;
        final Handler h = new Handler(Looper.getMainLooper());

        FrameLayout root;
        LinearLayout content;
        TextView logo, info, clock, section, hint;
        GridView grid;
        TileAdapter adapter;

        List<AppItem> items = new ArrayList<>();
        int sel = 0;
        int cols = 6;
        int W, H;
        float u = 10f;     // ekran yüksekliğinin %1'i (piksel)
        int tileW = 200, tileH = 220;
        long lastAxis;

        final Runnable tick = new Runnable() {
            @Override
            public void run() {
                updateTopBar();
                h.postDelayed(this, 15000);
            }
        };

        StudioScreen(MainActivity host, Display d) {
            super(host, d);
            this.host = host;
        }

        @Override
        protected void onCreate(Bundle b) {
            super.onCreate(b);
            setCancelable(false);
            if (host.prefs.getBoolean("force4k", true)) pickBestMode();

            root = new FrameLayout(getContext());
            GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    new int[]{0xFF2A060B, 0xFF0A0A0E, 0xFF050507});
            root.setBackground(bg);

            content = new LinearLayout(getContext());
            content.setOrientation(LinearLayout.VERTICAL);
            root.addView(content, new FrameLayout.LayoutParams(-1, -1));

            // Üst çubuk
            LinearLayout top = new LinearLayout(getContext());
            top.setOrientation(LinearLayout.HORIZONTAL);
            top.setGravity(Gravity.CENTER_VERTICAL);

            logo = tv("● OYUN STUDIO", RED, true);
            logo.setLetterSpacing(0.1f);
            top.addView(logo);
            top.addView(new View(getContext()), new LinearLayout.LayoutParams(0, 1, 1f));
            info = tv("", 0xFFAAAAAA, false);
            top.addView(info);
            clock = tv("", Color.WHITE, true);
            top.addView(clock);
            content.addView(top);

            // Kırmızı çizgi
            View line = new View(getContext());
            line.setBackgroundColor(RED);
            line.setTag("line");
            content.addView(line, new LinearLayout.LayoutParams(-1, 3));

            section = tv("YÜKLENİYOR…", Color.WHITE, true);
            section.setLetterSpacing(0.15f);
            content.addView(section);

            grid = new GridView(getContext());
            grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
            grid.setSelector(new ColorDrawable(Color.TRANSPARENT));
            grid.setVerticalScrollBarEnabled(false);
            grid.setFocusable(false);
            grid.setClipToPadding(false);
            adapter = new TileAdapter();
            grid.setAdapter(adapter);
            grid.setOnItemClickListener((p, v, pos, id) -> {
                if (pos == sel) openSelected(); else setSel(pos);
            });
            content.addView(grid, new LinearLayout.LayoutParams(-1, 0, 1f));

            hint = tv("Ⓐ Aç     ✚ Gez     Ⓨ Oyunlar ↔ Tüm uygulamalar", 0xFF8A8A95, false);
            hint.setGravity(Gravity.CENTER);
            content.addView(hint, new LinearLayout.LayoutParams(-1, -2));

            setContentView(root);

            root.addOnLayoutChangeListener((v, l, t, r, bt, ol, ot, or, ob) -> {
                final int w = r - l, hh = bt - t;
                if (w > 0 && hh > 0 && (w != W || hh != H)) root.post(() -> applySizes(w, hh));
            });

            reload();
        }

        @Override
        protected void onStart() {
            super.onStart();
            h.post(tick);
        }

        @Override
        protected void onStop() {
            h.removeCallbacks(tick);
            super.onStop();
        }

        TextView tv(String s, int color, boolean bold) {
            TextView t = new TextView(getContext());
            t.setText(s);
            t.setTextColor(color);
            t.setSingleLine(true);
            if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
            return t;
        }

        /** Ekranın desteklediği en yüksek çözünürlüğü (4K varsa 4K) iste. */
        void pickBestMode() {
            Display.Mode best = null;
            for (Display.Mode m : getDisplay().getSupportedModes()) {
                if (best == null) { best = m; continue; }
                long a = (long) m.getPhysicalWidth() * m.getPhysicalHeight();
                long bb = (long) best.getPhysicalWidth() * best.getPhysicalHeight();
                if (a > bb || (a == bb && m.getRefreshRate() > best.getRefreshRate())) best = m;
            }
            if (best != null) {
                WindowManager.LayoutParams lp = getWindow().getAttributes();
                lp.preferredDisplayModeId = best.getModeId();
                getWindow().setAttributes(lp);
            }
        }

        /** Tüm boyutlar ekran yüksekliğine göre: 1080p ve 4K'da aynı görünür, 4K'da daha keskin. */
        void applySizes(int w, int hh) {
            W = w;
            H = hh;
            u = hh / 100f;
            int pad = Math.round(u * 5);
            content.setPadding(pad, Math.round(u * 4), pad, Math.round(u * 2.5f));

            logo.setTextSize(TypedValue.COMPLEX_UNIT_PX, u * 4f);
            info.setTextSize(TypedValue.COMPLEX_UNIT_PX, u * 2.4f);
            info.setPadding(0, 0, Math.round(u * 3), 0);
            clock.setTextSize(TypedValue.COMPLEX_UNIT_PX, u * 4f);

            View line = content.findViewWithTag("line");
            LinearLayout.LayoutParams llp = (LinearLayout.LayoutParams) line.getLayoutParams();
            llp.height = Math.max(2, Math.round(u * 0.3f));
            llp.topMargin = Math.round(u * 2);
            line.setLayoutParams(llp);

            section.setTextSize(TypedValue.COMPLEX_UNIT_PX, u * 2.3f);
            section.setPadding(0, Math.round(u * 3.5f), 0, Math.round(u * 2.5f));
            hint.setTextSize(TypedValue.COMPLEX_UNIT_PX, u * 2.1f);
            hint.setPadding(0, Math.round(u * 2), 0, 0);

            int gap = Math.round(u * 2.2f);
            cols = Math.max(4, Math.round((float) w / (hh * 0.26f)));
            tileW = (w - 2 * pad - (cols - 1) * gap) / cols;
            tileH = Math.round(tileW * 1.05f);
            grid.setNumColumns(cols);
            grid.setHorizontalSpacing(gap);
            grid.setVerticalSpacing(gap);
            int gp = Math.round(u * 0.8f);
            grid.setPadding(gp, gp, gp, gp);
            adapter.notifyDataSetChanged();
            updateTopBar();
        }

        void updateTopBar() {
            if (clock == null) return;
            clock.setText(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date()));
            String bat = "";
            try {
                Intent bi = host.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                if (bi != null) {
                    int lvl = bi.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                    int sc = bi.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                    int pl = bi.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
                    if (lvl >= 0) bat = "   PİL %" + (lvl * 100 / Math.max(1, sc)) + (pl != 0 ? " ⚡" : "");
                }
            } catch (Exception ignored) { }
            Display.Mode m = getDisplay().getMode();
            String res = m.getPhysicalWidth() >= 3840 ? "4K" : (m.getPhysicalHeight() + "p");
            info.setText(res + " · " + m.getPhysicalWidth() + "×" + m.getPhysicalHeight()
                    + " · " + Math.round(m.getRefreshRate()) + "Hz" + bat);
        }

        void reload() {
            if (section == null) return;
            items = host.visibleApps();
            boolean only = host.prefs.getBoolean("onlyGames", true);
            String title;
            if (!host.appsLoaded) title = "YÜKLENİYOR…";
            else if (host.showingFallback) title = "OYUN BULUNAMADI · TÜM UYGULAMALAR";
            else title = only ? "OYUNLARIM" : "TÜM UYGULAMALAR";
            if (host.appsLoaded) title += "  ·  " + items.size();
            section.setText(title);
            if (sel >= items.size()) sel = Math.max(0, items.size() - 1);
            adapter.notifyDataSetChanged();
        }

        void setSel(int n) {
            sel = n;
            adapter.notifyDataSetChanged();
            grid.smoothScrollToPosition(n);
        }

        void move(int d) {
            int size = items.size();
            if (size == 0) return;
            int n = sel + d;
            if (Math.abs(d) == 1) {
                n = Math.max(0, Math.min(size - 1, n));
            } else {
                if (n < 0) return;
                if (n >= size) {
                    // Alt satır kısa ise son öğeye git
                    if (sel / cols < (size - 1) / cols) n = size - 1; else return;
                }
            }
            if (n != sel) setSel(n);
        }

        void openSelected() {
            if (sel >= 0 && sel < items.size()) host.launchApp(items.get(sel));
        }

        boolean handleKey(KeyEvent e) {
            boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
            boolean first = down && e.getRepeatCount() == 0;
            switch (e.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_LEFT:  if (down) move(-1); return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT: if (down) move(1); return true;
                case KeyEvent.KEYCODE_DPAD_UP:    if (down) move(-cols); return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:  if (down) move(cols); return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                case KeyEvent.KEYCODE_SPACE:
                case KeyEvent.KEYCODE_BUTTON_A:
                    if (first) openSelected();
                    return true;
                case KeyEvent.KEYCODE_BUTTON_Y:
                    if (first) host.setOnlyGames(!host.prefs.getBoolean("onlyGames", true));
                    return true;
                default:
                    return false;
            }
        }

        boolean handleMotion(MotionEvent e) {
            int src = e.getSource();
            boolean pad = (src & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                    || (src & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD;
            if (!pad || e.getAction() != MotionEvent.ACTION_MOVE) return false;
            float x = e.getAxisValue(MotionEvent.AXIS_HAT_X);
            float y = e.getAxisValue(MotionEvent.AXIS_HAT_Y);
            if (Math.abs(x) < 0.5f && Math.abs(y) < 0.5f) {
                x = e.getAxisValue(MotionEvent.AXIS_X);
                y = e.getAxisValue(MotionEvent.AXIS_Y);
            }
            if (Math.abs(x) < 0.6f && Math.abs(y) < 0.6f) {
                lastAxis = 0;
                return true;
            }
            long now = SystemClock.uptimeMillis();
            if (now - lastAxis < 220) return true;
            lastAxis = now;
            if (Math.abs(x) > Math.abs(y)) move(x > 0 ? 1 : -1);
            else move(y > 0 ? cols : -cols);
            return true;
        }

        @Override
        public boolean dispatchKeyEvent(KeyEvent e) {
            return handleKey(e) || super.dispatchKeyEvent(e);
        }

        @Override
        public boolean dispatchGenericMotionEvent(MotionEvent e) {
            return handleMotion(e) || super.dispatchGenericMotionEvent(e);
        }

        // ---------------------------------------------------------- kutucuklar

        static class Holder {
            ImageView icon;
            TextView label;
        }

        class TileAdapter extends BaseAdapter {
            @Override public int getCount() { return items.size(); }
            @Override public Object getItem(int p) { return items.get(p); }
            @Override public long getItemId(int p) { return p; }

            @Override
            public View getView(int p, View cv, ViewGroup parent) {
                Holder hd;
                LinearLayout tile;
                if (cv == null) {
                    tile = new LinearLayout(getContext());
                    tile.setOrientation(LinearLayout.VERTICAL);
                    tile.setGravity(Gravity.CENTER);
                    hd = new Holder();
                    hd.icon = new ImageView(getContext());
                    hd.icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
                    hd.label = new TextView(getContext());
                    hd.label.setTextColor(Color.WHITE);
                    hd.label.setGravity(Gravity.CENTER);
                    hd.label.setSingleLine(true);
                    hd.label.setEllipsize(TextUtils.TruncateAt.END);
                    tile.addView(hd.icon);
                    tile.addView(hd.label);
                    tile.setTag(hd);
                } else {
                    tile = (LinearLayout) cv;
                    hd = (Holder) tile.getTag();
                }

                AppItem a = items.get(p);
                boolean selected = p == sel;

                tile.setLayoutParams(new AbsListView.LayoutParams(-1, tileH));
                int ic = Math.round(tileW * 0.5f);
                hd.icon.setLayoutParams(new LinearLayout.LayoutParams(ic, ic));
                hd.icon.setImageDrawable(a.icon);

                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                lp.topMargin = Math.round(u * 1.4f);
                hd.label.setLayoutParams(lp);
                hd.label.setTextSize(TypedValue.COMPLEX_UNIT_PX, u * 2.2f);
                hd.label.setText(a.label);
                int lpad = Math.round(u);
                hd.label.setPadding(lpad, 0, lpad, 0);

                GradientDrawable g = new GradientDrawable();
                g.setCornerRadius(u * 1.6f);
                if (selected) {
                    g.setColor(0x40E5202E);
                    g.setStroke(Math.max(2, Math.round(u * 0.45f)), RED);
                    hd.label.setTypeface(Typeface.DEFAULT_BOLD);
                } else {
                    g.setColor(0x14FFFFFF);
                    hd.label.setTypeface(Typeface.DEFAULT);
                }
                tile.setBackground(g);
                tile.setAlpha(selected ? 1f : 0.82f);
                return tile;
            }
        }
    }
}
