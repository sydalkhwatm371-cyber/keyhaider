package com.example.mykeyboard

import android.content.ClipDescription
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.inputmethodservice.InputMethodService
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URL
import java.util.Collections
import java.util.concurrent.Executors
import kotlin.math.min

class Pal(
    val bg: Int, val key: Int, val special: Int, val text: Int, val sub: Int,
    val panel: Int, val shadow: Int, val accent: Int
)

class MyKeyboardService : InputMethodService() {

    companion object {
        const val AR = 0
        const val EN = 1
        const val SYM = 2

        const val P_KEYS = 0
        const val P_GIF = 1
        const val P_TR = 2
        const val P_FONT = 3

        val SELECT_BG = 0xFFF57C00.toInt()
        val ACCENTS = intArrayOf(
            0xFF007AFF.toInt(), 0xFF34C759.toInt(), 0xFFAF52DE.toInt(),
            0xFFFF2D55.toInt(), 0xFFFF9500.toInt(), 0xFF00A6A6.toInt()
        )
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }

    private val ui = Handler(Looper.getMainLooper())
    private val io = Executors.newFixedThreadPool(4)
    private lateinit var prefs: SharedPreferences
    private lateinit var root: LinearLayout
    private lateinit var content: LinearLayout

    private var pal = Pal(0, 0, 0, 0, 0, 0, 0, 0)
    private var bgCache: Bitmap? = null
    private var bgStamp = 0L
    private var preview: PopupWindow? = null

    // الحالة
    private var mode = AR
    private var lastLetterMode = AR
    private var shift = false
    private var panel = P_KEYS
    private var showSettings = false
    private var editMode = false
    private var selId: String? = null
    private var activeStyle: Style? = null

    // الإعدادات المحفوظة
    private var heightScale = 1f
    private var gapDp = 5
    private var padL = 0
    private var padR = 0
    private var padB = 0
    private var arVar = 0
    private var enVar = 0
    private val layouts = HashMap<Int, MutableList<MutableList<String>>>()
    private val params = HashMap<String, FloatArray>()

    // GIF
    private var gifGen = 0
    private var gifStrip: LinearLayout? = null
    private var gifStatus: TextView? = null

    // الترجمة
    private var trSource = ""
    private var trWasSel = false
    private var trResult = ""
    private var trGen = 0
    private var trOut: TextView? = null

    private val iraqiMap = mapOf(
        "ج" to "چ", "ك" to "گ", "ب" to "پ", "ف" to "ڤ", "ز" to "ژ"
    )

    private val arVariants = listOf(
        "قياسي" to listOf("ضصثقفغعهخحجد", "شسيبلاتنمكطذ", "ئءؤرىةوزظ"),
        "أبجدي" to listOf("ابتثجحخدذرزس", "شصضطظعغفقكل", "منهويةىءئؤ")
    )
    private val enVariants = listOf(
        "QWERTY" to listOf("qwertyuiop", "asdfghjkl", "zxcvbnm"),
        "AZERTY" to listOf("azertyuiop", "qsdfghjklm", "wxcvbn"),
        "QWERTZ" to listOf("qwertzuiop", "asdfghjkl", "yxcvbnm"),
        "ABC" to listOf("abcdefghij", "klmnopqrs", "tuvwxyz")
    )
    private val symRows = listOf("1234567890", "@#$%&-+()/", "!?:;'\"*=_~")

    // ================= دورة الحياة =================

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("kb", MODE_PRIVATE)
        loadPrefs()
        cleanCache()
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::root.isInitialized) renderAll()
    }

    override fun onCreateInputView(): View {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        renderAll()
        return root
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        shift = false
        if (::root.isInitialized) renderAll()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        dismissPreview()
        super.onFinishInputView(finishingInput)
    }

    // ================= المظهر =================

    private fun isNight() =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES

    /** 1 = نهاري، 2 = ليلي، 3 = صورة */
    private fun themeKind(): Int = when (prefs.getInt("theme", 0)) {
        1 -> 1
        2 -> 2
        3 -> if (loadBg() != null) 3 else 1
        else -> if (isNight()) 2 else 1
    }

    private fun loadBg(): Bitmap? {
        val f = File(filesDir, "kbbg.jpg")
        if (!f.exists()) return null
        val stamp = f.lastModified()
        if (bgCache == null || stamp != bgStamp) {
            bgCache = try { BitmapFactory.decodeFile(f.path) } catch (e: Exception) { null }
            bgStamp = stamp
        }
        return bgCache
    }

    private fun computePal(): Pal {
        val acc = ACCENTS[prefs.getInt("accent", 0).coerceIn(0, ACCENTS.size - 1)]
        return when (themeKind()) {
            2 -> Pal(
                0xFF2A2A2C.toInt(), 0xFF6A6A6D.toInt(), 0xFF4A4A4D.toInt(), Color.WHITE,
                0xFFC7C7CC.toInt(), 0xFF1C1C1E.toInt(), 0xFF0A0A0B.toInt(), acc
            )
            3 -> Pal(
                0x00000000, 0xD9FFFFFF.toInt(), 0xB3AEB4BE.toInt(), Color.BLACK,
                0xFF2C2C2E.toInt(), 0xDDF2F2F7.toInt(), 0x66000000, acc
            )
            else -> Pal(
                0xFFD1D4DA.toInt(), Color.WHITE, 0xFFADB3BC.toInt(), Color.BLACK,
                0xFF3C3C43.toInt(), 0xFFE8EAEE.toInt(), 0xFF898A8D.toInt(), acc
            )
        }
    }

    private fun makeBackground(): Drawable {
        if (themeKind() == 3) {
            val b = loadBg()
            if (b != null) return BgDrawable(b)
        }
        return ColorDrawable(pal.bg)
    }

    private fun applyPadding() {
        root.setPadding(dp(3 + padL), dp(4), dp(3 + padR), dp(6 + padB))
    }

    // ================= الحفظ والتحميل =================

    private fun loadPrefs() {
        heightScale = prefs.getFloat("hs", 1f).coerceIn(0.5f, 1.6f)
        gapDp = prefs.getInt("gap", 5).coerceIn(0, 16)
        padL = prefs.getInt("padL", 0).coerceAtLeast(0)
        padR = prefs.getInt("padR", 0).coerceAtLeast(0)
        padB = prefs.getInt("padB", 0).coerceAtLeast(0)
        arVar = prefs.getInt("arVar", 0).coerceIn(0, arVariants.size - 1)
        enVar = prefs.getInt("enVar", 0).coerceIn(0, enVariants.size - 1)
        try {
            prefs.getString("layouts3", null)?.let { s ->
                val o = JSONObject(s)
                o.keys().forEach { k ->
                    val ra = o.getJSONArray(k)
                    val rows = mutableListOf<MutableList<String>>()
                    for (i in 0 until ra.length()) {
                        val r = ra.getJSONArray(i)
                        val l = mutableListOf<String>()
                        for (j in 0 until r.length()) l.add(r.getString(j))
                        rows.add(l)
                    }
                    layouts[k.toInt()] = rows
                }
            }
            prefs.getString("params3", null)?.let { s ->
                val o = JSONObject(s)
                o.keys().forEach { k ->
                    val a = o.getJSONArray(k)
                    params[k] = floatArrayOf(a.getDouble(0).toFloat(), a.getDouble(1).toFloat())
                }
            }
        } catch (e: Exception) {
            layouts.clear()
            params.clear()
        }
    }

    private fun savePrefs() {
        val lj = JSONObject()
        for ((m, rows) in layouts) {
            val ra = JSONArray()
            rows.forEach { r -> ra.put(JSONArray(r)) }
            lj.put(m.toString(), ra)
        }
        val pj = JSONObject()
        for ((k, v) in params) {
            pj.put(k, JSONArray().put(v[0].toDouble()).put(v[1].toDouble()))
        }
        prefs.edit()
            .putString("layouts3", lj.toString())
            .putString("params3", pj.toString())
            .putFloat("hs", heightScale)
            .putInt("gap", gapDp)
            .putInt("padL", padL)
            .putInt("padR", padR)
            .putInt("padB", padB)
            .putInt("arVar", arVar)
            .putInt("enVar", enVar)
            .apply()
    }

    private fun resetAll() {
        layouts.clear()
        params.clear()
        heightScale = 1f
        gapDp = 5
        padL = 0; padR = 0; padB = 0
        arVar = 0; enVar = 0
        selId = null
        savePrefs()
    }

    private fun giphyKey(): String {
        val k = (prefs.getString("giphy", "") ?: "").trim()
        if (k.isNotEmpty()) return k
        return if (Config.GIPHY_KEY.startsWith("PUT_")) "" else Config.GIPHY_KEY
    }

    // ================= تخطيط الأزرار =================

    private fun lk(): Int = when (mode) {
        AR -> 100 + arVar
        EN -> 200 + enVar
        else -> 300
    }

    private fun defaultLayoutFor(k: Int): MutableList<MutableList<String>> {
        fun chars(s: String) = s.map { it.toString() }.toMutableList()
        val rows = mutableListOf<MutableList<String>>()
        if (k >= 300) {
            rows.add(chars(symRows[0]))
            rows.add(chars(symRows[1]))
            rows.add((chars(symRows[2]) + "::DEL").toMutableList())
        } else {
            val isAr = k < 200
            val src = if (isAr) arVariants[(k - 100).coerceIn(0, arVariants.size - 1)].second
            else enVariants[(k - 200).coerceIn(0, enVariants.size - 1)].second
            // نخزّن الترتيب كما يظهر على الشاشة (من اليسار لليمين)
            fun v(s: String) = if (isAr) chars(s).reversed().toMutableList() else chars(s)
            rows.add(chars("1234567890"))
            rows.add(v(src[0]))
            rows.add(v(src[1]))
            rows.add((listOf("::SHIFT") + v(src[2]) + "::DEL").toMutableList())
        }
        rows.add(mutableListOf("::SYM", "::LANG", "::COMMA", "::SPACE", "::DOT", "::ENTER"))
        return rows
    }

    private fun layoutFor(k: Int = lk()) = layouts.getOrPut(k) { defaultLayoutFor(k) }

    private fun param(id: String) = params.getOrPut("${lk()}|$id") { floatArrayOf(1f, 1f) }

    private fun find(id: String): Pair<Int, Int>? {
        val rows = layoutFor()
        for (r in rows.indices) {
            val i = rows[r].indexOf(id)
            if (i >= 0) return Pair(r, i)
        }
        return null
    }

    private fun moveH(d: Int) {
        val id = selId ?: return
        val (r, i) = find(id) ?: return
        val row = layoutFor()[r]
        val j = i + d
        if (j !in row.indices) return
        Collections.swap(row, i, j)
        savePrefs(); renderContent()
    }

    private fun moveV(d: Int) {
        val id = selId ?: return
        val (r, i) = find(id) ?: return
        val rows = layoutFor()
        rows[r].removeAt(i)
        val t = r + d
        when {
            t < 0 -> rows.add(0, mutableListOf(id))
            t >= rows.size -> rows.add(mutableListOf(id))
            else -> rows[t].add(min(i, rows[t].size), id)
        }
        rows.removeAll { it.isEmpty() }
        savePrefs(); renderContent()
    }

    private fun resize(dw: Float, dh: Float) {
        val id = selId ?: return
        val p = param(id)
        p[0] = (p[0] + dw).coerceIn(0.4f, 3f)
        p[1] = (p[1] + dh).coerceIn(0.4f, 3f)
        savePrefs(); renderContent()
    }

    private fun setVariant(i: Int) {
        val base = if (mode == SYM) lastLetterMode else mode
        if (base == AR) arVar = i else enVar = i
        selId = null
        savePrefs()
        renderAll()
    }

    // ================= الرسم العام =================

    private fun renderAll() {
        dismissPreview()
        pal = computePal()
        root.background = makeBackground()
        applyPadding()
        root.removeAllViews()
        root.addView(toolbar())
        if (showSettings && panel == P_KEYS) root.addView(settingsPanel())
        root.addView(content)
        renderContent()
    }

    private fun renderContent() {
        dismissPreview()
        content.removeAllViews()
        when (panel) {
            P_KEYS -> renderKeys()
            P_GIF -> content.addView(gifPanel())
            P_TR -> content.addView(translatePanel())
            P_FONT -> content.addView(fontsPanel())
        }
    }

    private fun togglePanel(p: Int) {
        panel = if (panel == p) P_KEYS else p
        showSettings = false
        editMode = false
        selId = null
        renderAll()
    }

    private fun backToKeys() {
        panel = P_KEYS
        showSettings = false
        editMode = false
        selId = null
        savePrefs()
        renderAll()
    }

    private fun toolbar(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        if (panel != P_KEYS || showSettings) {
            row.addView(toolBtn("⌨ رجوع", true, 1.2f) { backToKeys() })
        }
        row.addView(toolBtn("GIF", panel == P_GIF, 1f) { togglePanel(P_GIF) })
        row.addView(toolBtn("文A ترجمة", panel == P_TR, 1.5f) { togglePanel(P_TR) })
        row.addView(toolBtn("𝓐 زخرفة", panel == P_FONT || activeStyle != null, 1.5f) { togglePanel(P_FONT) })
        row.addView(toolBtn("⚙ ضبط", showSettings, 1.2f) {
            panel = P_KEYS
            showSettings = !showSettings
            if (!showSettings) { editMode = false; selId = null }
            renderAll()
        })
        return row
    }

    private fun toolBtn(text: String, active: Boolean, weight: Float, click: () -> Unit): TextView =
        TextView(this).apply {
            this.text = text
            gravity = Gravity.CENTER
            textSize = 14f
            setTextColor(if (active) Color.WHITE else pal.text)
            background = if (active) rounded(pal.accent, 16) else null
            layoutParams = LinearLayout.LayoutParams(0, dp(34), weight).apply {
                setMargins(dp(2), dp(2), dp(2), dp(2))
            }
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                click()
            }
        }

    // ================= لوحة الضبط والتعديل =================

    private fun settingsPanel(): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(pal.panel, 12)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(0, dp(2), 0, dp(4)) }
        }

        val btns = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        btns.addView(pill("✏️ تعديل الأزرار", 1.6f, if (editMode) SELECT_BG else pal.key, 38, 13f) {
            editMode = !editMode
            if (!editMode) selId = null
            renderAll()
        })
        btns.addView(pill("↺ افتراضي", 1.2f, pal.key, 38, 13f) { resetAll(); renderAll() })
        btns.addView(pill("✔ إغلاق", 1f, pal.accent, 38, 13f) {
            showSettings = false; editMode = false; selId = null
            savePrefs(); renderAll()
        })
        box.addView(btns)
        if (editMode) box.addView(editBar())

        val dm = resources.displayMetrics
        val maxSide = (dm.widthPixels / dm.density * 0.45f).toInt()
        val sideStep = (dm.widthPixels / dm.density * 0.25f).toInt()
        val base = if (mode == SYM) lastLetterMode else mode

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(sliderRow("↕ الارتفاع", 50, 160, (heightScale * 100).toInt(), { "$it%" }) {
            heightScale = it / 100f; renderContent()
        })
        col.addView(sliderRow("↔ الفراغ", 0, 16, gapDp, { "$it" }) {
            gapDp = it; renderContent()
        })
        col.addView(sliderRow("◀ من اليسار", 0, maxSide, padL, { "$it" }) {
            padL = it; applyPadding()
        })
        col.addView(sliderRow("▶ من اليمين", 0, maxSide, padR, { "$it" }) {
            padR = it; applyPadding()
        })
        col.addView(sliderRow("▼ من الأسفل", 0, 60, padB, { "$it" }) {
            padB = it; applyPadding()
        })

        col.addView(section("وضع اليد الواحدة"))
        col.addView(hScroll(
            chip("كامل", padL == 0 && padR == 0) { padL = 0; padR = 0; savePrefs(); renderAll() },
            chip("يد يمين", padL > 0 && padR == 0) { padL = sideStep; padR = 0; savePrefs(); renderAll() },
            chip("يد يسار", padR > 0 && padL == 0) { padL = 0; padR = sideStep; savePrefs(); renderAll() }
        ))

        val variants = if (base == AR) arVariants.map { it.first } else enVariants.map { it.first }
        val curVar = if (base == AR) arVar else enVar
        col.addView(section(if (base == AR) "ترتيب الأحرف (العربي)" else "ترتيب الأحرف (English)"))
        col.addView(hScroll(*variants.mapIndexed { i, n ->
            chip(n, i == curVar) { setVariant(i) }
        }.toTypedArray()))

        val th = prefs.getInt("theme", 0)
        col.addView(section("المظهر"))
        col.addView(hScroll(
            chip("🌗 تلقائي", th == 0) { applyKbTheme(0) },
            chip("☀️ نهاري", th == 1) { applyKbTheme(1) },
            chip("🌙 ليلي", th == 2) { applyKbTheme(2) },
            chip("🖼️ صورة", th == 3) {
                if (File(filesDir, "kbbg.jpg").exists()) applyKbTheme(3) else openPicker()
            },
            chip("📂 تغيير الصورة", false) { openPicker() }
        ))

        val ac = prefs.getInt("accent", 0)
        col.addView(section("لون التمييز"))
        col.addView(hScroll(*ACCENTS.mapIndexed { i, c ->
            colorChip(c, i == ac) {
                prefs.edit().putInt("accent", i).apply(); renderAll()
            }
        }.toTypedArray()))

        box.addView(ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, dp(if (editMode) 110 else 165))
            addView(col)
        })
        return box
    }

    private fun applyKbTheme(t: Int) {
        prefs.edit().putInt("theme", t).apply()
        renderAll()
    }

    private fun openPicker() {
        try {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra("pick_bg", true)
            )
        } catch (e: Exception) {
            toast("افتح تطبيق كيبوردي واختر الصورة منه")
        }
    }

    private fun section(t: String): TextView =
        label(t, 12f).apply { setPadding(0, dp(8), 0, dp(2)) }

    private fun sliderRow(
        name: String, lo: Int, hi: Int, value: Int,
        fmt: (Int) -> String, onChange: (Int) -> Unit
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        val nameTv = label(name, 13f, pal.text).apply {
            layoutParams = LinearLayout.LayoutParams(dp(92), WRAP)
        }
        val valueTv = label(fmt(value), 13f, pal.text).apply {
            layoutParams = LinearLayout.LayoutParams(dp(46), WRAP)
            gravity = Gravity.CENTER
        }
        val sb = SeekBar(this).apply {
            max = hi - lo
            progress = (value - lo).coerceIn(0, hi - lo)
            layoutParams = LinearLayout.LayoutParams(0, dp(36), 1f)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    if (fromUser) {
                        val v = p + lo
                        valueTv.text = fmt(v)
                        onChange(v)
                    }
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) { savePrefs() }
            })
        }
        row.addView(nameTv); row.addView(sb); row.addView(valueTv)
        return row
    }

    private fun editBar(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val sel = selId
        col.addView(
            label(
                if (sel == null) "✏️ اضغط على أي زر في الكيبورد لتحديده" else "المحدد: ${keyName(sel)}",
                13f, SELECT_BG
            ).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(3), 0, dp(3))
            }
        )
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        fun b(l: String, f: () -> Unit) {
            r.addView(pill(l, 1f, pal.key, 38, 15f) { if (selId != null) f() })
        }
        b("◀") { moveH(-1) }
        b("▶") { moveH(1) }
        b("▲") { moveV(-1) }
        b("▼") { moveV(1) }
        b("↔+") { resize(0.15f, 0f) }
        b("↔−") { resize(-0.15f, 0f) }
        b("↕+") { resize(0f, 0.15f) }
        b("↕−") { resize(0f, -0.15f) }
        col.addView(r)
        return col
    }

    private fun keyName(id: String): String = when (id) {
        "::SHIFT" -> "Shift"
        "::DEL" -> "حذف"
        "::SYM" -> "أرقام/رموز"
        "::LANG" -> "🌐 اللغة"
        "::SPACE" -> "مسافة"
        "::ENTER" -> "إدخال"
        "::COMMA" -> "فاصلة"
        "::DOT" -> "نقطة"
        else -> id
    }

    // ================= الأزرار =================

    private fun renderKeys() {
        for (r in layoutFor()) {
            if (r.isEmpty()) continue
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutDirection = View.LAYOUT_DIRECTION_LTR
            }
            r.forEach { row.addView(keyFor(it)) }
            content.addView(row)
        }
    }

    private fun keyFor(id: String): View {
        val p = param(id)
        val w = p[0]
        val h = p[1]
        return when (id) {
            "::SHIFT" -> key(id, "⇧", 1.5f * w, h, if (shift) pal.accent else pal.special, 20f) {
                shift = !shift; renderContent()
            }
            "::DEL" -> key(id, "⌫", 1.5f * w, h, pal.special, 20f, repeat = true) { deleteOne() }
            "::SYM" -> key(id, symLabel(), 1.4f * w, h, pal.special, 16f) { toggleSym() }
            "::LANG" -> key(id, "🌐", 1.2f * w, h, pal.special, 18f) {
                val base = if (mode == SYM) lastLetterMode else mode
                mode = if (base == AR) EN else AR
                lastLetterMode = mode
                shift = false
                selId = null
                renderContent()
            }
            "::COMMA" -> {
                val c = if (mode == AR) "،" else ","
                key(id, c, w, h, pal.key, 20f) { type(c) }
            }
            "::DOT" -> key(id, ".", w, h, pal.key, 20f) { type(".") }
            "::SPACE" -> key(id, if (mode == AR) "مسافة" else "space", 4f * w, h, pal.key, 14f) { type(" ") }
            "::ENTER" -> {
                val (txt, isAction) = enterLabel()
                key(id, txt, 1.6f * w, h, if (isAction) pal.accent else pal.special, 15f) { pressEnter() }
            }
            else -> {
                val s = display(id)
                key(id, s, w, h, pal.key, 22f, preview = true) { type(s) }
            }
        }
    }

    private fun enterLabel(): Pair<String, Boolean> {
        val opts = currentInputEditorInfo?.imeOptions ?: 0
        if ((opts and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0) return Pair("↵", false)
        val ar = mode == AR || (mode == SYM && lastLetterMode == AR)
        return when (opts and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_SEND -> Pair(if (ar) "إرسال" else "Send", true)
            EditorInfo.IME_ACTION_SEARCH -> Pair("🔍", true)
            EditorInfo.IME_ACTION_GO -> Pair(if (ar) "اذهب" else "Go", true)
            EditorInfo.IME_ACTION_NEXT -> Pair(if (ar) "التالي" else "Next", true)
            EditorInfo.IME_ACTION_DONE -> Pair(if (ar) "تم" else "Done", true)
            else -> Pair("↵", false)
        }
    }

    private fun display(ch: String): String = when {
        mode == AR && shift -> iraqiMap[ch] ?: ch
        mode == EN && shift -> ch.uppercase()
        else -> ch
    }

    private fun symLabel() =
        if (mode == SYM) (if (lastLetterMode == AR) "أبج" else "ABC") else "123"

    private fun toggleSym() {
        if (mode == SYM) {
            mode = lastLetterMode
        } else {
            lastLetterMode = mode
            mode = SYM
        }
        shift = false
        selId = null
        renderContent()
    }

    private fun key(
        id: String, text: String, weight: Float, hMul: Float, bg: Int, size: Float,
        repeat: Boolean = false, preview: Boolean = false, act: () -> Unit
    ): TextView {
        val v = TextView(this)
        v.text = text
        v.gravity = Gravity.CENTER
        v.setTextColor(if (bg == pal.accent || bg == SELECT_BG) Color.WHITE else pal.text)
        v.textSize = size * (0.75f + 0.25f * heightScale)
        val isSel = editMode && id == selId
        v.background = keyDrawable(if (isSel) SELECT_BG else bg)
        v.isClickable = true
        val g = dp(gapDp) / 2
        val hPx = (dp(46) * heightScale * hMul).toInt().coerceAtLeast(dp(18))
        v.layoutParams = LinearLayout.LayoutParams(0, hPx, weight).apply { setMargins(g, g, g, g) }

        var pressed = false
        val repeater = object : Runnable {
            override fun run() {
                act()
                ui.postDelayed(this, 50)
            }
        }
        val slack = dp(14)
        v.setOnTouchListener { view, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    if (editMode) {
                        selId = id
                        renderAll()
                    } else {
                        pressed = true
                        view.alpha = 0.75f
                        if (preview) showPreview(view, text)
                        if (repeat) {
                            act()
                            ui.postDelayed(repeater, 400)
                        }
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    val inside = ev.x >= -slack && ev.x <= view.width + slack &&
                            ev.y >= -slack && ev.y <= view.height + slack
                    if (pressed && !inside) {
                        pressed = false
                        view.alpha = 1f
                        dismissPreview()
                        ui.removeCallbacks(repeater)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    val was = pressed
                    pressed = false
                    view.alpha = 1f
                    dismissPreview()
                    ui.removeCallbacks(repeater)
                    if (was && !repeat) act()
                }
                MotionEvent.ACTION_CANCEL -> {
                    pressed = false
                    view.alpha = 1f
                    dismissPreview()
                    ui.removeCallbacks(repeater)
                }
            }
            true
        }
        return v
    }

    private fun showPreview(anchor: View, text: String) {
        try {
            dismissPreview()
            val bubble = TextView(this).apply {
                this.text = text
                textSize = 30f
                gravity = Gravity.CENTER
                setTextColor(pal.text)
                background = keyDrawable(pal.key)
            }
            val w = maxOf(anchor.width + dp(16), dp(54))
            val h = anchor.height + dp(26)
            val pw = PopupWindow(bubble, w, h, false)
            pw.isTouchable = false
            pw.isFocusable = false
            pw.isClippingEnabled = false
            val loc = IntArray(2)
            anchor.getLocationOnScreen(loc)
            pw.showAtLocation(anchor, Gravity.NO_GRAVITY, loc[0] - (w - anchor.width) / 2, loc[1] - h)
            preview = pw
        } catch (e: Exception) {
            preview = null
        }
    }

    private fun dismissPreview() {
        try { preview?.dismiss() } catch (e: Exception) { }
        preview = null
    }

    // ================= أدوات واجهة =================

    private fun keyDrawable(color: Int): Drawable {
        val r = dp(6).toFloat()
        val shadow = GradientDrawable().apply { setColor(pal.shadow); cornerRadius = r }
        val face = GradientDrawable().apply { setColor(color); cornerRadius = r }
        return LayerDrawable(arrayOf<Drawable>(shadow, face)).apply {
            setLayerInset(1, 0, 0, 0, dp(1))
        }
    }

    private fun pill(text: String, weight: Float, bg: Int, hDp: Int, size: Float, click: () -> Unit): TextView {
        return TextView(this).apply {
            this.text = text
            gravity = Gravity.CENTER
            setTextColor(if (bg == pal.accent || bg == SELECT_BG) Color.WHITE else pal.text)
            textSize = size
            background = rounded(bg)
            layoutParams = LinearLayout.LayoutParams(0, dp(hDp), weight).apply {
                setMargins(dp(2), dp(2), dp(2), dp(2))
            }
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                click()
            }
        }
    }

    private fun chip(text: String, active: Boolean, click: () -> Unit): TextView {
        return TextView(this).apply {
            this.text = text
            gravity = Gravity.CENTER
            setTextColor(if (active) Color.WHITE else pal.text)
            textSize = 14f
            setPadding(dp(14), 0, dp(14), 0)
            background = rounded(if (active) pal.accent else pal.key)
            layoutParams = LinearLayout.LayoutParams(WRAP, dp(36)).apply {
                setMargins(dp(3), dp(3), dp(3), dp(3))
            }
            setOnClickListener { click() }
        }
    }

    private fun colorChip(color: Int, active: Boolean, click: () -> Unit): TextView {
        return TextView(this).apply {
            text = if (active) "✓" else ""
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 16f
            background = rounded(color, 18)
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(36)).apply {
                setMargins(dp(3), dp(3), dp(3), dp(3))
            }
            setOnClickListener { click() }
        }
    }

    private fun label(text: String, size: Float = 13f, color: Int = pal.sub): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
        }

    private fun hScroll(vararg views: View): HorizontalScrollView {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        views.forEach { row.addView(it) }
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }
    }

    private fun rounded(color: Int, r: Int = 8) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(r).toFloat()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun panelHeight(): Int =
        (dp(46) * heightScale * 5 + dp(gapDp) * 5).toInt().coerceIn(dp(210), dp(380))

    private fun panelBox(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(MATCH, panelHeight())
        background = rounded(pal.panel, 12)
        setPadding(dp(6), dp(4), dp(6), dp(4))
    }

    // ================= الإدخال =================

    private fun type(s: String) {
        val ic = currentInputConnection ?: return
        val st = activeStyle
        if (st != null && st.local) {
            if (s.isNotEmpty() && s[0].isWhitespace() && st.joiner.isNotEmpty()) {
                val prev = ic.getTextBeforeCursor(st.joiner.length, 0)
                if (prev != null && prev.toString() == st.joiner) {
                    ic.deleteSurroundingText(st.joiner.length, 0)
                }
            }
            ic.commitText(st.applyChar(s), 1)
        } else {
            ic.commitText(s, 1)
        }
        if (shift) {
            shift = false
            renderContent()
        }
    }

    private fun deleteOne() {
        val ic = currentInputConnection ?: return
        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) ic.commitText("", 1)
        else ic.deleteSurroundingText(1, 0)
    }

    private fun pressEnter() {
        val ic = currentInputConnection ?: return
        val info = currentInputEditorInfo
        val opts = info?.imeOptions ?: 0
        val action = opts and EditorInfo.IME_MASK_ACTION
        val noEnterAction = (opts and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        if (!noEnterAction && action != EditorInfo.IME_ACTION_NONE &&
            action != EditorInfo.IME_ACTION_UNSPECIFIED
        ) {
            ic.performEditorAction(action)
        } else {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        }
    }

    /** النص المحدد، وإن لم يوجد فكل نص الحقل. */
    private fun fieldText(): Pair<String, Boolean> {
        val ic = currentInputConnection ?: return Pair("", false)
        val sel = ic.getSelectedText(0)
        if (!sel.isNullOrEmpty()) return Pair(sel.toString(), true)
        val b = ic.getTextBeforeCursor(5000, 0) ?: ""
        val a = ic.getTextAfterCursor(5000, 0) ?: ""
        return Pair(b.toString() + a.toString(), false)
    }

    private fun replaceFieldText(newText: String, wasSelection: Boolean) {
        val ic = currentInputConnection ?: return
        if (wasSelection) {
            ic.commitText(newText, 1)
        } else {
            val b = ic.getTextBeforeCursor(5000, 0)?.length ?: 0
            val a = ic.getTextAfterCursor(5000, 0)?.length ?: 0
            ic.deleteSurroundingText(b, a)
            ic.commitText(newText, 1)
        }
    }

    // ================= الزخرفة =================

    private fun fontsPanel(): View {
        val box = panelBox()

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        top.addView(pill("🔄 زخرف النص الحالي", 1.6f, pal.accent, 38, 13f) {
            val st = activeStyle
            if (st == null) {
                toast("اختر زخرفة أولاً")
            } else {
                val (t, sel) = fieldText()
                if (t.isNotEmpty()) replaceFieldText(st.apply(t), sel)
            }
        })
        top.addView(pill("✖ إيقاف", 1f, pal.key, 38, 13f) {
            activeStyle = null
            renderAll()
        })
        box.addView(top)

        val st = activeStyle
        box.addView(
            label(
                if (st != null) "مفعّلة: ${st.name} ← كل ما تكتبه سيُزخرف"
                else "اختر زخرفة ثم اكتب، أو حوّل نصاً موجوداً. (الأطر تُطبَّق على النص مباشرة)",
                12f
            ).apply { setPadding(dp(4), dp(2), dp(4), dp(2)) }
        )

        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        Styles.all.chunked(3).forEach { group ->
            val r = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutDirection = View.LAYOUT_DIRECTION_LTR
            }
            group.forEach { s ->
                val active = activeStyle === s
                r.addView(pill(s.sample, 1f, if (active) pal.accent else pal.key, 44, 15f) {
                    if (s.local) {
                        activeStyle = if (activeStyle === s) null else s
                        renderAll()
                    } else {
                        val (t, sel) = fieldText()
                        if (t.isNotEmpty()) replaceFieldText(s.apply(t), sel) else toast("اكتب نصاً أولاً")
                    }
                })
            }
            repeat(3 - group.size) {
                r.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f) })
            }
            grid.addView(r)
        }
        box.addView(ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
            addView(grid)
        })
        return box
    }

    // ================= الترجمة =================

    private fun translatePanel(): View {
        val box = panelBox()
        val (txt, sel) = fieldText()
        trSource = txt
        trWasSel = sel
        trResult = ""

        box.addView(label(if (sel) "النص المحدد:" else "نص الحقل:", 12f))
        box.addView(label(
            if (txt.isBlank()) "اكتب نصاً في الحقل ثم اختر لغة الترجمة" else txt, 15f, pal.text
        ).apply {
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            background = rounded(pal.key)
            setPadding(dp(8), dp(6), dp(8), dp(6))
        })

        val langs = listOf(
            "العربية" to "ar", "English" to "en", "Türkçe" to "tr", "فارسی" to "fa",
            "کوردی" to "ckb", "Français" to "fr", "Español" to "es", "Deutsch" to "de",
            "Русский" to "ru", "Italiano" to "it", "中文" to "zh-CN", "हिन्दी" to "hi"
        )
        box.addView(hScroll(*langs.map { (name, code) ->
            chip(name, false) { doTranslate(code) }
        }.toTypedArray()))

        val out = label("", 16f, pal.text).apply {
            maxLines = 4
            minHeight = dp(56)
            background = rounded(pal.key)
            setPadding(dp(8), dp(6), dp(8), dp(6))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(0, dp(4), 0, dp(4)) }
        }
        trOut = out
        box.addView(out)

        val acts = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        acts.addView(pill("⌨ رجوع للكيبورد", 1.5f, pal.accent, 40, 13f) { backToKeys() })
        acts.addView(pill("🔄 تحديث", 1f, pal.key, 40, 13f) { renderContent() })
        box.addView(acts)
        return box
    }

    private fun doTranslate(lang: String) {
        if (trSource.isBlank()) { trOut?.text = "لا يوجد نص للترجمة"; return }
        val gen = ++trGen
        trOut?.text = "جاري الترجمة…"
        val src = trSource
        io.execute {
            try {
                val res = Net.translate(src, lang)
                ui.post {
                    if (gen == trGen) {
                        trResult = res
                        // يستبدل النص القديم بالترجمة فقط ثم يرجع للكيبورد
                        replaceFieldText(res, trWasSel)
                        backToKeys()
                    }
                }
            } catch (e: Exception) {
                ui.post { if (gen == trGen) trOut?.text = "تعذّرت الترجمة، تأكد من الإنترنت" }
            }
        }
    }

    // ================= GIF =================

    private fun gifPanel(): View {
        val box = panelBox()
        val status = label("", 12f).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, dp(2))
        }
        val strip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        gifStatus = status
        gifStrip = strip

        val cats = listOf(
            "🔥 رائج" to "", "🔍 بحث بنص الحقل" to "::FIELD", "😂" to "funny", "❤️" to "love",
            "👍" to "thumbs up", "😢" to "sad", "🎉" to "party", "😮" to "wow",
            "😡" to "angry", "🥰" to "cute", "🤝" to "thanks"
        )
        box.addView(hScroll(*cats.map { (name, q) ->
            chip(name, false) {
                if (q == "::FIELD") {
                    val t = fieldText().first.trim().take(50)
                    if (t.isEmpty()) status.text = "اكتب كلمة البحث في الحقل أولاً" else loadGifs(t)
                } else loadGifs(q)
            }
        }.toTypedArray()))
        box.addView(status)

        if (giphyKey().isEmpty()) {
            status.text = "⚠️ زر GIF يحتاج مفتاح GIPHY مجاني (مرة واحدة فقط)"
            box.addView(pill("🔑 أدخل المفتاح الآن", 1f, pal.accent, 44, 15f) { openKeyScreen() }.apply {
                layoutParams = LinearLayout.LayoutParams(MATCH, dp(44)).apply { setMargins(dp(4), dp(6), dp(4), dp(4)) }
            })
        } else {
            box.addView(HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
                addView(strip)
            })
            loadGifs("")
        }
        return box
    }

    private fun openKeyScreen() {
        try {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            toast("افتح تطبيق كيبوردي وضع المفتاح")
        }
    }

    private fun loadGifs(q: String) {
        val key = giphyKey()
        if (key.isEmpty()) return
        val gen = ++gifGen
        gifStatus?.text = "جاري التحميل…"
        gifStrip?.removeAllViews()
        io.execute {
            try {
                val items = Net.giphy(q, key)
                ui.post {
                    if (gen != gifGen) return@post
                    gifStatus?.text =
                        if (items.isEmpty()) "لا توجد نتائج" else "اضغط على الصورة لإرسالها • Powered by GIPHY"
                    items.forEach { (thumb, full) -> addGifThumb(thumb, full, gen) }
                }
            } catch (e: Exception) {
                val msg = (e.message ?: e.javaClass.simpleName).take(70)
                ui.post {
                    if (gen == gifGen) gifStatus?.text = "تعذّر التحميل: $msg"
                }
            }
        }
    }

    private fun addGifThumb(thumbUrl: String, fullUrl: String, gen: Int) {
        val iv = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = rounded(pal.key)
            layoutParams = LinearLayout.LayoutParams(dp(130), dp(130)).apply {
                setMargins(dp(3), dp(3), dp(3), dp(3))
            }
            setOnClickListener { sendGif(fullUrl) }
        }
        gifStrip?.addView(iv)
        io.execute {
            try {
                val bmp = URL(thumbUrl).openStream().use { BitmapFactory.decodeStream(it) }
                if (bmp != null) ui.post { if (gen == gifGen) iv.setImageBitmap(bmp) }
            } catch (e: Exception) {
            }
        }
    }

    private fun sendGif(url: String) {
        gifStatus?.text = "جاري تجهيز الـGIF…"
        io.execute {
            try {
                val dir = File(cacheDir, "gifs").apply { mkdirs() }
                val f = File(dir, "gif_${System.currentTimeMillis()}.gif")
                URL(url).openStream().use { i -> f.outputStream().use { o -> i.copyTo(o) } }
                ui.post { commitGif(f, url) }
            } catch (e: Exception) {
                ui.post { gifStatus?.text = "تعذّر تحميل الـGIF" }
            }
        }
    }

    private fun commitGif(f: File, url: String) {
        val ic = currentInputConnection
        val info = currentInputEditorInfo
        if (ic == null || info == null) return
        val supported = EditorInfoCompat.getContentMimeTypes(info)
            .any { ClipDescription.compareMimeTypes("image/gif", it) }
        if (!supported) {
            ic.commitText(url, 1)
            gifStatus?.text = "هذا التطبيق لا يقبل GIF مباشرة، أُدرج الرابط بدلاً منه"
            return
        }
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
            val desc = ClipDescription("gif", arrayOf("image/gif"))
            val contentInfo = InputContentInfoCompat(uri, desc, Uri.parse(url))
            var flags = 0
            if (Build.VERSION.SDK_INT >= 25) {
                flags = flags or InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION
            }
            val ok = InputConnectionCompat.commitContent(ic, info, contentInfo, flags, null)
            if (!ok) {
                ic.commitText(url, 1)
                gifStatus?.text = "تعذّر إرسال الصورة، أُدرج الرابط"
            }
        } catch (e: Exception) {
            ic.commitText(url, 1)
        }
    }

    private fun cleanCache() {
        try {
            File(cacheDir, "gifs").listFiles()?.forEach {
                if (System.currentTimeMillis() - it.lastModified() > 24L * 3600 * 1000) it.delete()
            }
        } catch (e: Exception) {
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
