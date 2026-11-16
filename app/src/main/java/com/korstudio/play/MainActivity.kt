package com.korstudio.play

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.ListView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** HDMI ile bağlı TV'yi gösterir ve seçilen oyunu doğrudan o ekranda başlatır. */
class MainActivity : Activity() {

    private class App(val label: String, val packageName: String, val icon: Drawable, val isGame: Boolean)

    private lateinit var displayName: TextView
    private lateinit var displayInfo: TextView
    private lateinit var displayVerdict: TextView
    private lateinit var testButton: Button
    private lateinit var listLabel: TextView
    private lateinit var showAll: Switch
    private lateinit var empty: TextView
    private lateinit var list: ListView

    private val displayManager by lazy { getSystemService(Context.DISPLAY_SERVICE) as DisplayManager }
    private var external: Display? = null
    private var presentation: TestPresentation? = null
    private var allApps: List<App> = emptyList()
    private var shown: List<App> = emptyList()
    private var loaded = false

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = refreshDisplay()
        override fun onDisplayRemoved(displayId: Int) = refreshDisplay()
        override fun onDisplayChanged(displayId: Int) = refreshDisplay()
    }

    private val adapter = object : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(position: Int) = shown[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
            val app = shown[position]
            view.findViewById<ImageView>(R.id.icon).setImageDrawable(app.icon)
            view.findViewById<TextView>(R.id.name).text = app.label
            return view
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        displayName = findViewById(R.id.displayName)
        displayInfo = findViewById(R.id.displayInfo)
        displayVerdict = findViewById(R.id.displayVerdict)
        testButton = findViewById(R.id.test)
        listLabel = findViewById(R.id.listLabel)
        showAll = findViewById(R.id.showAll)
        empty = findViewById(R.id.empty)
        list = findViewById(R.id.list)

        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ -> launch(shown[position]) }
        showAll.setOnCheckedChangeListener { _, _ -> renderApps() }
        testButton.setOnClickListener { toggleTest() }
    }

    override fun onStart() {
        super.onStart()
        displayManager.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
        refreshDisplay()
        loadApps()
    }

    override fun onStop() {
        displayManager.unregisterDisplayListener(displayListener)
        dismissTest()
        super.onStop()
    }

    /* ---------- Bağlı ekran ---------- */

    /** Telefonun kendi ekranı dışındaki ilk kullanılabilir ekran (HDMI ile bağlı TV). */
    private fun findExternal(): Display? =
        displayManager.displays.firstOrNull { it.displayId != Display.DEFAULT_DISPLAY && it.state != Display.STATE_OFF }

    private fun refreshDisplay() {
        val display = findExternal()
        if (display?.displayId != external?.displayId) dismissTest()
        external = display
        testButton.isEnabled = display != null

        if (display == null) {
            displayName.setText(R.string.no_display)
            displayInfo.setText(R.string.no_display_hint)
            displayVerdict.visibility = View.GONE
            return
        }

        val current = display.mode.toInfo()
        val supported = display.supportedModes.map { it.toInfo() }
        val best = Modes.best(supported) ?: current
        displayName.text = display.name
        displayInfo.text = getString(R.string.display_mode, current.label()) + "\n" +
            getString(R.string.display_best, best.label())
        displayVerdict.visibility = View.VISIBLE
        displayVerdict.setText(
            when (Modes.verdict(current, supported)) {
                Modes.Verdict.ACTIVE_4K -> R.string.display_is_4k
                Modes.Verdict.AVAILABLE_4K -> R.string.display_can_4k
                Modes.Verdict.NO_4K -> R.string.display_no_4k
            }
        )
    }

    private fun Display.Mode.toInfo() = ModeInfo(physicalWidth, physicalHeight, refreshRate)

    private fun toggleTest() {
        if (presentation != null) {
            dismissTest()
            return
        }
        val display = external ?: return
        try {
            presentation = TestPresentation(this, display).apply {
                setOnDismissListener {
                    presentation = null
                    testButton.setText(R.string.test)
                }
                show()
            }
            testButton.setText(R.string.test_stop)
        } catch (e: Exception) {
            presentation = null
            toast(getString(R.string.err_test, e.message ?: e.javaClass.simpleName))
        }
    }

    private fun dismissTest() {
        presentation?.dismiss()
        presentation = null
        testButton.setText(R.string.test)
    }

    /* ---------- Uygulama listesi ---------- */

    private fun loadApps() {
        if (loaded) return
        loaded = true
        Thread({
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val apps = packageManager.queryIntentActivities(launcher, 0)
                .map { it.activityInfo.applicationInfo }
                .distinctBy { it.packageName }
                .filter { it.packageName != packageName }
                .map { info ->
                    @Suppress("DEPRECATION")
                    val game = info.category == ApplicationInfo.CATEGORY_GAME ||
                        info.flags and ApplicationInfo.FLAG_IS_GAME != 0
                    App(info.loadLabel(packageManager).toString(), info.packageName, info.loadIcon(packageManager), game)
                }
                .sortedBy { it.label.lowercase() }
            runOnUiThread {
                allApps = apps
                renderApps()
            }
        }, "kor-apps").start()
    }

    private fun renderApps() {
        val all = showAll.isChecked
        shown = if (all) allApps else allApps.filter { it.isGame }
        listLabel.setText(if (all) R.string.label_apps else R.string.label_games)
        empty.setText(R.string.empty_games)
        empty.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        adapter.notifyDataSetChanged()
    }

    /* ---------- Başlatma ---------- */

    private fun launch(app: App) {
        val display = external
        if (display == null) {
            toast(getString(R.string.err_no_display))
            return
        }
        val intent = packageManager.getLaunchIntentForPackage(app.packageName)
        if (intent == null) {
            toast(getString(R.string.err_launch, app.label, PackageManager.NameNotFoundException().javaClass.simpleName))
            return
        }
        dismissTest()
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            // Oyun TV ekranında açılınca o ekranın çözünürlüğünde çizilir.
            val options = ActivityOptions.makeBasic().setLaunchDisplayId(display.displayId)
            startActivity(intent, options.toBundle())
            toast(getString(R.string.launched, app.label))
        } catch (e: Exception) {
            // Bazı telefonlar ya da oyunlar ikinci ekranda açılmaya izin vermez.
            toast(getString(R.string.err_launch, app.label, e.message ?: e.javaClass.simpleName))
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
