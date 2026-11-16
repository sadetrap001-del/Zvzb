package com.korstudio.home

import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.ComponentName
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.GridView
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast

/** Reklamsız, hafif Android TV ana ekranı: yalnızca saat ve kurulu uygulamalar. */
class HomeActivity : Activity() {

    private class TvApp(
        val label: String,
        val packageName: String,
        val image: Drawable?,
        val isBanner: Boolean,
        val intent: Intent,
    )

    private lateinit var grid: GridView
    private lateinit var backdrop: ImageView
    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var hiddenButton: Button
    private lateinit var keeperButton: Button
    private val backdrops = HashMap<String, Bitmap>()
    private var backdropPackage: String? = null

    private val prefs by lazy { getSharedPreferences("home", Context.MODE_PRIVATE) }
    private var favorites: Set<String> = emptySet()
    private var hidden: Set<String> = emptySet()
    private var showHidden = false
    private var installed: Map<String, TvApp> = emptyMap()
    private var shown: List<TvApp> = emptyList()
    private var loading = false
    private var reloadPending = false
    private var raised: View? = null

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = loadApps()
    }

    private val adapter = object : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(position: Int) = shown[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(parent.context).inflate(R.layout.item_tile, parent, false)
            val app = shown[position]
            val image = view.findViewById<ImageView>(R.id.image)
            image.setImageDrawable(app.image)
            // Afişler kutuyu doldurur; kare simgeler ortalanıp küçük gösterilir.
            image.scaleType = if (app.isBanner) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
            val pad = if (app.isBanner) 0 else (12 * resources.displayMetrics.density).toInt()
            image.setPadding(pad, pad, pad, pad)
            image.clipToOutline = true
            view.findViewById<View>(R.id.star).visibility =
                if (app.packageName in favorites) View.VISIBLE else View.GONE
            view.alpha = if (app.packageName in hidden) 0.45f else 1f
            view.scaleX = 1f
            view.scaleY = 1f
            return view
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)
        grid = findViewById(R.id.grid)
        backdrop = findViewById(R.id.backdrop)
        title = findViewById(R.id.title)
        subtitle = findViewById(R.id.subtitle)
        hiddenButton = findViewById(R.id.hidden)
        keeperButton = findViewById(R.id.keeper)

        favorites = prefs.getStringSet(KEY_FAVORITES, emptySet())!!.toSet()
        hidden = prefs.getStringSet(KEY_HIDDEN, emptySet())!!.toSet()

        grid.adapter = adapter
        grid.setOnItemClickListener { _, _, position, _ -> launch(shown[position]) }
        grid.setOnItemLongClickListener { _, _, position, _ ->
            showMenu(shown[position])
            true
        }
        grid.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                raise(view)
                shown.getOrNull(position)?.let { showHero(it) }
            }
            override fun onNothingSelected(parent: AdapterView<*>) = raise(null)
        }
        grid.setOnFocusChangeListener { _, focused -> raise(if (focused) grid.selectedView else null) }

        findViewById<Button>(R.id.freeMemory).setOnClickListener { freeMemory() }
        findViewById<Button>(R.id.settings).setOnClickListener { openSettings() }
        keeperButton.setOnClickListener { showKeeperDialog() }
        hiddenButton.setOnClickListener {
            showHidden = !showHidden
            render()
        }

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

    override fun onResume() {
        super.onResume()
        keeperButton.setText(if (isKeeperEnabled()) R.string.keeper_on else R.string.keeper_off)
    }

    override fun onDestroy() {
        unregisterReceiver(packageReceiver)
        super.onDestroy()
    }

    /** Ana ekrandan geri tuşuyla çıkılmaz. */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // Kumandadaki menü tuşu da seçili uygulamanın menüsünü açar.
        if (keyCode == KeyEvent.KEYCODE_MENU && grid.hasFocus()) {
            shown.getOrNull(grid.selectedItemPosition)?.let { showMenu(it) }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    /** Seçili kutuyu hafifçe büyütür, bir öncekini eski hâline getirir. */
    private fun raise(view: View?) {
        if (raised === view) return
        raised?.animate()?.scaleX(1f)?.scaleY(1f)?.setDuration(100)?.start()
        raised = view
        view?.animate()?.scaleX(1.07f)?.scaleY(1.07f)?.setDuration(100)?.start()
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
                installed = apps
                // Kaldırılan uygulamaların favori ve gizli kayıtlarını temizle.
                favorites = AppOrder.prune(favorites, apps.keys)
                hidden = AppOrder.prune(hidden, apps.keys)
                savePrefs()
                render()
                if (reloadPending) {
                    reloadPending = false
                    loadApps()
                }
            }
        }, "kor-apps").start()
    }

    /** TV uygulamaları (leanback) ve yandan yüklenmiş normal uygulamalar; paket başına tek kayıt. */
    private fun queryApps(): Map<String, TvApp> {
        val result = LinkedHashMap<String, TvApp>()
        for (category in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val query = Intent(Intent.ACTION_MAIN).addCategory(category)
            for (resolved in packageManager.queryIntentActivities(query, 0)) {
                val info = resolved.activityInfo
                val pkg = info.packageName
                if (pkg == packageName || pkg in result) continue
                val banner = info.loadBanner(packageManager) ?: info.applicationInfo.loadBanner(packageManager)
                val intent = Intent(Intent.ACTION_MAIN)
                    .addCategory(category)
                    .setClassName(pkg, info.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                result[pkg] = TvApp(
                    resolved.loadLabel(packageManager).toString(), pkg,
                    banner ?: resolved.loadIcon(packageManager), banner != null, intent,
                )
            }
        }
        return result
    }

    private fun render() {
        val selectedPackage = shown.getOrNull(grid.selectedItemPosition)?.packageName
        val order = AppOrder.arrange(
            installed.values.map { Listing(it.packageName, it.label) }, favorites, hidden, showHidden,
        )
        shown = order.mapNotNull { installed[it.packageName] }
        raise(null)
        adapter.notifyDataSetChanged()

        if (shown.isEmpty()) {
            title.text = ""
            subtitle.setText(R.string.empty)
        }

        if (hidden.isEmpty()) {
            showHidden = false
            if (hiddenButton.hasFocus()) grid.requestFocus()
            hiddenButton.visibility = View.GONE
        } else {
            hiddenButton.visibility = View.VISIBLE
            hiddenButton.text =
                if (showHidden) getString(R.string.hide_hidden) else getString(R.string.show_hidden, hidden.size)
        }

        // Liste yeniden sıralansa da seçim aynı uygulamada kalsın.
        val index = shown.indexOfFirst { it.packageName == selectedPackage }
        if (index >= 0) grid.setSelection(index)
        if (currentFocus == null || currentFocus === grid) grid.requestFocus()
        shown.getOrNull(if (index >= 0) index else 0)?.let { showHero(it) }
    }

    /* ---------- Büyük başlık ve arka plan ---------- */

    /** Seçili uygulamanın adını büyük yazar, afişinden bulanık bir arka plan üretir. */
    private fun showHero(app: TvApp) {
        title.text = app.label
        subtitle.setText(if (app.packageName in favorites) R.string.hint_favorite else R.string.hint)
        if (backdropPackage == app.packageName) return
        backdropPackage = app.packageName
        val bitmap = backdrops.getOrPut(app.packageName) { blurred(app.image) }
        backdrop.animate().cancel()
        backdrop.alpha = 0.35f
        backdrop.setImageBitmap(bitmap)
        backdrop.animate().alpha(1f).setDuration(220).start()
    }

    /** Görseli çok küçük bir bitmap'e çizer; ekrana büyütülünce yumuşak, renkli bir zemin olur. */
    private fun blurred(image: Drawable?): Bitmap {
        val bitmap = Bitmap.createBitmap(32, 18, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(0xFF15171D.toInt())
        // Çizilebilir paylaşılıyor olabilir; sınırlarını bozmamak için kopyası kullanılır.
        val copy = image?.constantState?.newDrawable(resources)?.mutate()
        if (copy != null) {
            copy.setBounds(0, 0, bitmap.width, bitmap.height)
            copy.draw(canvas)
        }
        return bitmap
    }

    /* ---------- Kalıcılık ---------- */

    private fun isKeeperEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val mine = ComponentName(this, HomeKeeperService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == mine }
    }

    private fun showKeeperDialog() {
        val enabled = isKeeperEnabled()
        AlertDialog.Builder(this)
            .setTitle(R.string.keeper_title)
            .setMessage(if (enabled) R.string.keeper_enabled_message else R.string.keeper_message)
            .setPositiveButton(R.string.keeper_open) { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: Exception) {
                    openSettings()
                }
            }
            .setNegativeButton(R.string.keeper_cancel, null)
            .show()
    }

    private fun savePrefs() {
        prefs.edit()
            .putStringSet(KEY_FAVORITES, HashSet(favorites))
            .putStringSet(KEY_HIDDEN, HashSet(hidden))
            .apply()
    }

    /* ---------- Eylemler ---------- */

    private fun launch(app: TvApp) {
        try {
            startActivity(app.intent)
        } catch (e: Exception) {
            toast(getString(R.string.err_launch, app.label))
        }
    }

    private fun showMenu(app: TvApp) {
        val isFavorite = app.packageName in favorites
        val isHidden = app.packageName in hidden
        val items = arrayOf(
            getString(if (isFavorite) R.string.menu_unfavorite else R.string.menu_favorite),
            getString(if (isHidden) R.string.menu_unhide else R.string.menu_hide),
            getString(R.string.menu_info),
        )
        AlertDialog.Builder(this)
            .setTitle(app.label)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> favorites = if (isFavorite) favorites - app.packageName else favorites + app.packageName
                    1 -> hidden = if (isHidden) hidden - app.packageName else hidden + app.packageName
                    2 -> openAppInfo(app)
                }
                if (which < 2) {
                    savePrefs()
                    render()
                }
            }
            .show()
    }

    private fun openAppInfo(app: TvApp) {
        try {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", app.packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            toast(getString(R.string.err_settings))
        }
    }

    private fun openSettings() {
        try {
            startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            toast(getString(R.string.err_settings))
        }
    }

    /** Arka planda bekleyen uygulamaları kapatır ve ne kadar bellek açıldığını söyler. */
    private fun freeMemory() {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val packages = installed.keys.toList()
        Thread({
            val before = ActivityManager.MemoryInfo().also { manager.getMemoryInfo(it) }.availMem
            for (pkg in packages) {
                try { manager.killBackgroundProcesses(pkg) } catch (_: Exception) {}
            }
            Thread.sleep(600)
            val after = ActivityManager.MemoryInfo().also { manager.getMemoryInfo(it) }.availMem
            val freedMb = ((after - before) / (1024 * 1024)).toInt()
            runOnUiThread {
                toast(if (freedMb > 0) getString(R.string.memory_freed, freedMb) else getString(R.string.memory_none))
            }
        }, "kor-memory").start()
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    companion object {
        private const val KEY_FAVORITES = "favorites"
        private const val KEY_HIDDEN = "hidden"
    }
}
