package com.korstudio.telefon

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.GridView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs

/** Reklamsız, hızlı telefon ana ekranı: saat, arama, uygulamalar ve alta sabitlenenler. */
class HomeActivity : Activity() {

    private class PhoneApp(val label: String, val packageName: String, val icon: Drawable, val intent: Intent)

    private lateinit var grid: GridView
    private lateinit var search: EditText
    private lateinit var clear: ImageButton
    private lateinit var empty: TextView
    private lateinit var dockBar: View
    private lateinit var dock: LinearLayout
    private lateinit var dockHint: TextView

    private val prefs by lazy { getSharedPreferences("home", Context.MODE_PRIVATE) }
    private var favorites: List<String> = emptyList()
    private var hidden: Set<String> = emptySet()
    private var showHidden = false
    private var installed: Map<String, PhoneApp> = emptyMap()
    private var shown: List<PhoneApp> = emptyList()
    private var loaded = false
    private var loading = false
    private var reloadPending = false

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = loadApps()
    }

    private val adapter = object : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(position: Int) = shown[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
            val app = shown[position]
            view.findViewById<ImageView>(R.id.icon).setImageDrawable(app.icon)
            view.findViewById<TextView>(R.id.label).text = app.label
            view.alpha = if (app.packageName in hidden) 0.5f else 1f
            return view
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        drawBehindSystemBars()
        setContentView(R.layout.activity_home)
        grid = findViewById(R.id.grid)
        search = findViewById(R.id.search)
        clear = findViewById(R.id.clear)
        empty = findViewById(R.id.empty)
        dockBar = findViewById(R.id.dockBar)
        dock = findViewById(R.id.dock)
        dockHint = findViewById(R.id.dockHint)

        favorites = prefs.getString(KEY_FAVORITES, "")!!.split('\n').filter { it.isNotEmpty() }
        hidden = prefs.getStringSet(KEY_HIDDEN, emptySet())!!.toSet()

        grid.adapter = adapter
        grid.setOnItemClickListener { _, _, position, _ -> launch(shown[position]) }
        grid.setOnItemLongClickListener { _, _, position, _ ->
            showMenu(shown[position])
            true
        }
        // Liste kaydırılınca klavye kapansın.
        grid.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: AbsListView, state: Int) {
                if (state == AbsListView.OnScrollListener.SCROLL_STATE_TOUCH_SCROLL) hideKeyboard()
            }
            override fun onScroll(view: AbsListView, first: Int, visible: Int, total: Int) {}
        })

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                render()
                grid.setSelection(0)
            }
        })
        // Klavyedeki "Git": ilk sonucu açar.
        search.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                shown.firstOrNull()?.let { launch(it) }
                true
            } else {
                false
            }
        }
        clear.setOnClickListener { resetSearch() }
        findViewById<ImageButton>(R.id.more).setOnClickListener { showMoreMenu() }

        findViewById<View>(R.id.clock).setOnClickListener { open(Intent(AlarmClock.ACTION_SHOW_ALARMS)) }
        findViewById<View>(R.id.date).setOnClickListener {
            val uri = ContentUris.appendId(CalendarContract.CONTENT_URI.buildUpon().appendPath("time"), System.currentTimeMillis()).build()
            open(Intent(Intent.ACTION_VIEW, uri))
        }
        setUpSwipeDown(findViewById(R.id.header))

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(packageReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(packageReceiver, filter)
        }
        loadApps()
    }

    override fun onDestroy() {
        unregisterReceiver(packageReceiver)
        super.onDestroy()
    }

    /** Ana ekrandayken Home'a basınca: arama temizlenir, liste başa döner. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (Intent.ACTION_MAIN == intent.action) {
            resetSearch()
            grid.setSelection(0)
        }
    }

    override fun onPause() {
        super.onPause()
        hideKeyboard()
    }

    /** Geri tuşu yalnızca aramayı temizler; ana ekrandan çıkılmaz. */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (search.text.isNotEmpty()) resetSearch()
    }

    @Suppress("DEPRECATION")
    private fun drawBehindSystemBars() {
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    }

    /* ---------- Uygulama listesi ---------- */

    private fun loadApps() {
        if (loading) {
            reloadPending = true
            return
        }
        loading = true
        Thread({
            val apps = queryApps()
            runOnUiThread {
                loading = false
                loaded = true
                installed = apps
                // Kaldırılan uygulamaların kayıtlarını temizle.
                favorites = AppList.prune(favorites, apps.keys)
                hidden = hidden.intersect(apps.keys)
                savePrefs()
                render()
                if (reloadPending) {
                    reloadPending = false
                    loadApps()
                }
            }
        }, "kor-apps").start()
    }

    private fun queryApps(): Map<String, PhoneApp> {
        val result = HashMap<String, PhoneApp>()
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        for (resolved in packageManager.queryIntentActivities(query, 0)) {
            val info = resolved.activityInfo
            val pkg = info.packageName
            if (pkg == packageName || pkg in result) continue
            val intent = Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setClassName(pkg, info.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            result[pkg] = PhoneApp(
                resolved.loadLabel(packageManager).toString().trim(), pkg, resolved.loadIcon(packageManager), intent,
            )
        }
        return result
    }

    private fun render() {
        val query = search.text.toString()
        clear.visibility = if (query.isEmpty()) View.GONE else View.VISIBLE

        val order = AppList.arrange(installed.values.map { Entry(it.packageName, it.label) }, hidden, showHidden, query)
        shown = order.mapNotNull { installed[it.packageName] }
        adapter.notifyDataSetChanged()

        empty.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        empty.text = when {
            !loaded -> getString(R.string.loading)
            query.isNotBlank() -> getString(R.string.no_results, query.trim())
            else -> ""
        }
        if (hidden.isEmpty()) showHidden = false

        // Ararken alt çubuk gizlenir, sonuçlara yer açılır.
        dockBar.visibility = if (query.isEmpty()) View.VISIBLE else View.GONE
        renderDock()
    }

    private fun renderDock() {
        dock.removeAllViews()
        val apps = favorites.mapNotNull { installed[it] }
        dockHint.visibility = if (apps.isEmpty() && loaded) View.VISIBLE else View.GONE
        val inflater = LayoutInflater.from(this)
        for (app in apps) {
            val view = inflater.inflate(R.layout.item_app, dock, false)
            view.findViewById<ImageView>(R.id.icon).setImageDrawable(app.icon)
            view.findViewById<TextView>(R.id.label).visibility = View.GONE
            view.contentDescription = app.label
            view.setOnClickListener { launch(app) }
            view.setOnLongClickListener {
                showMenu(app)
                true
            }
            dock.addView(view, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
    }

    private fun savePrefs() {
        prefs.edit()
            .putString(KEY_FAVORITES, favorites.joinToString("\n"))
            .putStringSet(KEY_HIDDEN, HashSet(hidden))
            .apply()
    }

    /* ---------- Arama ---------- */

    private fun resetSearch() {
        if (search.text.isNotEmpty()) search.text.clear()
        search.clearFocus()
        hideKeyboard()
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(search.windowToken, 0)
    }

    /* ---------- Hareketler ---------- */

    /** Saat alanında parmağı aşağı kaydırmak bildirim panelini açar. */
    private fun setUpSwipeDown(area: View) {
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                val start = e1 ?: return false
                val dy = e2.y - start.y
                if (dy > 60 * resources.displayMetrics.density && abs(dy) > abs(e2.x - start.x)) {
                    openNotifications()
                    return true
                }
                return false
            }
        })
        area.setOnTouchListener { _, event -> detector.onTouchEvent(event) }
    }

    private fun openNotifications() {
        try {
            val statusBar = getSystemService("statusbar")
            statusBar.javaClass.getMethod("expandNotificationsPanel").invoke(statusBar)
        } catch (_: Exception) {
        }
    }

    /* ---------- Eylemler ---------- */

    private fun launch(app: PhoneApp) {
        try {
            startActivity(app.intent)
        } catch (e: Exception) {
            toast(getString(R.string.err_launch, app.label))
        }
    }

    private fun open(intent: Intent) {
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            toast(getString(R.string.err_open))
        }
    }

    private fun showMenu(app: PhoneApp) {
        val isFavorite = app.packageName in favorites
        val isHidden = app.packageName in hidden
        val items = arrayOf(
            getString(if (isFavorite) R.string.menu_unpin else R.string.menu_pin),
            getString(if (isHidden) R.string.menu_unhide else R.string.menu_hide),
            getString(R.string.menu_info),
            getString(R.string.menu_uninstall),
        )
        AlertDialog.Builder(this)
            .setTitle(app.label)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> {
                        if (isFavorite) {
                            favorites = favorites - app.packageName
                        } else {
                            val added = AppList.addFavorite(favorites, app.packageName, MAX_DOCK)
                            if (added == null) {
                                toast(getString(R.string.dock_full, MAX_DOCK))
                            } else {
                                favorites = added
                            }
                        }
                    }
                    1 -> {
                        hidden = if (isHidden) hidden - app.packageName else hidden + app.packageName
                        if (!isHidden) toast(getString(R.string.hidden_toast, app.label))
                    }
                    2 -> open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", app.packageName, null)))
                    3 -> open(Intent(Intent.ACTION_DELETE, Uri.fromParts("package", app.packageName, null)))
                }
                if (which < 2) {
                    savePrefs()
                    render()
                }
            }
            .show()
    }

    private fun showMoreMenu() {
        val actions = ArrayList<Pair<String, () -> Unit>>()
        if (hidden.isNotEmpty()) {
            val label = if (showHidden) getString(R.string.more_hide_hidden) else getString(R.string.more_show_hidden, hidden.size)
            actions += label to {
                showHidden = !showHidden
                render()
            }
        }
        actions += getString(R.string.more_wallpaper) to {
            open(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), getString(R.string.more_wallpaper)))
        }
        actions += getString(R.string.more_default_home) to { openDefaultHomeSettings() }
        actions += getString(R.string.more_settings) to { open(Intent(Settings.ACTION_SETTINGS)) }

        AlertDialog.Builder(this)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
            .show()
    }

    /** Varsayılan ana ekran ayarı; MIUI'de olmayabilir, o zaman varsayılan uygulamalar ekranı açılır. */
    private fun openDefaultHomeSettings() {
        val candidates = listOf(
            Intent(Settings.ACTION_HOME_SETTINGS),
            Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        )
        for (intent in candidates) {
            try {
                startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: Exception) {
            }
        }
        toast(getString(R.string.err_open))
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    companion object {
        private const val KEY_FAVORITES = "favorites"
        private const val KEY_HIDDEN = "hidden"
        private const val MAX_DOCK = 5
    }
}
