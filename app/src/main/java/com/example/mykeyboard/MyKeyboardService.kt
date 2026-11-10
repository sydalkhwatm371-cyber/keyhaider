package com.example.mykeyboard

import android.content.ClipDescription
import android.content.Context
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
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.TextUtils
import android.text.style.RelativeSizeSpan
import android.text.style.SuperscriptSpan
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

    // إعدادات جديدة
    private var fontScale = 1f
    private var sugOn = true
    private var hapticOn = true
    private var soundOn = false
    private var trLang = "en"
    private val merged = HashMap<String, MutableList<String>>()
    private val saveRun = Runnable { savePrefs() }

    // الكتابة داخل الكيبورد (للترجمة وبحث GIF)
    private val kbBuf = StringBuilder()
    private var bufView: TextView? = null
    private var bufHint = ""
    private var cachedPanel: View? = null
    private var cachedKind = -1
    private var altPopup: PopupWindow? = null

    // الاقتراحات وذاكرة الكلمات
    private var fieldNoSug = false
    private var sugBar: LinearLayout? = null
    private var sugViews: List<TextView> = emptyList()
    private val sugCur = arrayOf("", "", "")
    private val dict = HashMap<String, Int>()
    private val bigrams = HashMap<String, Int>()
    private var dictDirty = 0

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
        "أبجدي" to listOf("ابتثجحخدذرزس", "شصضطظعغفقكل", "منهويةىءئؤ"),
        "أبجد هوز" to listOf("ابجدهوزحطيء", "كلمنسعفصقرش", "تثخذضظغةىئؤ"),
        "عراقي" to listOf("ضصثقفغعهخحجچ", "شسيبپلاتنمكگ", "دذطرزژوةڤ"),
        "مبسط" to listOf("ضصقفغعهخحج", "شسيبلاتنمك", "دطرةوز")
    )

    // أحرف تُدمج تلقائياً (ضغطة مطولة) لبعض الترتيبات: رقم الترتيب -> (الحرف -> المدموج معه)
    private val arMerges: Map<Int, Map<String, List<String>>> = mapOf(
        3 to mapOf(
            "ا" to listOf("ء", "أ", "إ", "آ"), "و" to listOf("ؤ"),
            "ي" to listOf("ئ", "ى"), "ط" to listOf("ظ")
        ),
        4 to mapOf(
            "ا" to listOf("ء", "أ", "إ", "آ"), "و" to listOf("ؤ"),
            "ي" to listOf("ئ", "ى"), "ط" to listOf("ظ"),
            "د" to listOf("ذ"), "ت" to listOf("ث")
        )
    )

    private val enVariants = listOf(
        "QWERTY" to listOf("qwertyuiop", "asdfghjkl", "zxcvbnm"),
        "AZERTY" to listOf("azertyuiop", "qsdfghjklm", "wxcvbn"),
        "QWERTZ" to listOf("qwertzuiop", "asdfghjkl", "yxcvbnm"),
        "ABC" to listOf("abcdefghij", "klmnopqrs", "tuvwxyz"),
        "Dvorak" to listOf("pyfgcrl", "aoeuidhtns", "qjkxbmwvz"),
        "Colemak" to listOf("qwfpgjluy", "arstdhneio", "zxcvbkm")
    )
    private val symRows = listOf("1234567890", "@#$%&-+()/", "!?:;'\"*=_~")

    // ================= دورة الحياة =================

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("kb", MODE_PRIVATE)
        loadPrefs()
        loadDict()
        cleanCache()
    }

    override fun onDestroy() {
        saveDict(true)
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
        fieldNoSug = computeNoSug(info)
        if (::root.isInitialized) renderAll()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        dismissPreview()
        saveDict()
        super.onFinishInputView(finishingInput)
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        if (::root.isInitialized) updateSuggestions()
    }

    private fun computeNoSug(info: EditorInfo?): Boolean {
        val t = info?.inputType ?: return false
        val cls = t and InputType.TYPE_MASK_CLASS
        val v = t and InputType.TYPE_MASK_VARIATION
        if (cls != InputType.TYPE_CLASS_TEXT) return true
        if (v == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            v == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
            v == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
            v == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
            v == InputType.TYPE_TEXT_VARIATION_URI
        ) return true
        return (t and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0
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
        fontScale = prefs.getFloat("fs", 1f).coerceIn(0.6f, 1.5f)
        sugOn = prefs.getBoolean("sug", true)
        hapticOn = prefs.getBoolean("hap", true)
        soundOn = prefs.getBoolean("snd", false)
        trLang = prefs.getString("trLang", "en") ?: "en"
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
            prefs.getString("merged3", null)?.let { s ->
                val o = JSONObject(s)
                o.keys().forEach { k ->
                    val a = o.getJSONArray(k)
                    val l = mutableListOf<String>()
                    for (i in 0 until a.length()) l.add(a.getString(i))
                    merged[k] = l
                }
            }
        } catch (e: Exception) {
            layouts.clear()
            params.clear()
            merged.clear()
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
        val mj = JSONObject()
        for ((k, v) in merged) mj.put(k, JSONArray(v))
        prefs.edit()
            .putString("layouts3", lj.toString())
            .putString("merged3", mj.toString())
            .putFloat("fs", fontScale)
            .putBoolean("sug", sugOn)
            .putBoolean("hap", hapticOn)
            .putBoolean("snd", soundOn)
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
        merged.clear()
        fontScale = 1f
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
            if (isAr) arMerges[k - 100]?.forEach { (t, ex) -> merged["$k|$t"] = ex.toMutableList() }
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
        cachedPanel = null
        cachedKind = -1
        sugViews = emptyList()
        sugBar = null
        pal = computePal()
        root.background = makeBackground()
        applyPadding()
        root.removeAllViews()
        root.addView(toolbar())
        if (sugActive()) root.addView(suggestionBar())
        if (showSettings && panel == P_KEYS) root.addView(settingsPanel())
        root.addView(content)
        renderContent()
        updateSuggestions()
    }

    private fun renderContent() {
        dismissPreview()
        content.removeAllViews()
        when (panel) {
            P_KEYS -> renderKeys()
            P_GIF, P_TR -> {
                val old = cachedPanel
                val pv: View = if (old != null && cachedKind == panel) old else {
                    val nv = if (panel == P_GIF) gifPanel() else translatePanel()
                    cachedPanel = nv
                    cachedKind = panel
                    nv
                }
                content.addView(pv)
                renderKeys()
            }
            P_FONT -> content.addView(fontsPanel())
        }
    }

    private fun togglePanel(p: Int) {
        panel = if (panel == p) P_KEYS else p
        showSettings = false
        editMode = false
        selId = null
        kbBuf.setLength(0)
        renderAll()
    }

    private fun backToKeys() {
        panel = P_KEYS
        kbBuf.setLength(0)
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
                fx(it)
                click()
            }
            pressFx(this)
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
        col.addView(sliderRow("↕ الارتفاع", 50, 160, (heightScale * 100).toInt(), { "$it%" }, 5) {
            heightScale = it / 100f; renderContent()
        })
        col.addView(sliderRow("🔠 الأحرف", 60, 150, (fontScale * 100).toInt(), { "$it%" }, 5) {
            fontScale = it / 100f; renderContent()
        })
        col.addView(sliderRow("↔ الفراغ", 0, 16, gapDp, { "$it" }, 1) {
            gapDp = it; renderContent()
        })
        col.addView(sliderRow("◀ يسار", 0, maxSide, padL, { "$it" }, 4) {
            padL = it; applyPadding()
        })
        col.addView(sliderRow("▶ يمين", 0, maxSide, padR, { "$it" }, 4) {
            padR = it; applyPadding()
        })
        col.addView(sliderRow("▼ أسفل", 0, 60, padB, { "$it" }, 2) {
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

        col.addView(section("الاقتراحات والتفاعل  •  محفوظ: ${dict.size} كلمة"))
        col.addView(hScroll(
            chip(if (sugOn) "💡 الاقتراحات: تشغيل" else "💡 الاقتراحات: إيقاف", sugOn) {
                sugOn = !sugOn; savePrefs(); renderAll()
            },
            chip(if (hapticOn) "📳 اهتزاز: تشغيل" else "📳 اهتزاز: إيقاف", hapticOn) {
                hapticOn = !hapticOn; savePrefs(); renderAll()
            },
            chip(if (soundOn) "🔊 صوت: تشغيل" else "🔊 صوت: إيقاف", soundOn) {
                soundOn = !soundOn; savePrefs(); renderAll()
            },
            chip("🗑 مسح الكلمات المحفوظة", false) {
                dict.clear(); bigrams.clear(); dictDirty = 1; saveDict()
                toast("تم مسح الكلمات المحفوظة"); renderAll()
            }
        ))

        box.addView(ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, dp(if (editMode) 110 else 175))
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

    private fun saveSoon() {
        ui.removeCallbacks(saveRun)
        ui.postDelayed(saveRun, 500)
    }

    /** زر يتكرر عند الضغط المطوّل (للزائد والناقص) */
    private fun holdBtn(text: String, step: () -> Unit): TextView {
        val tv = TextView(this)
        tv.text = text
        tv.gravity = Gravity.CENTER
        tv.setTextColor(pal.text)
        tv.textSize = 20f
        tv.background = rounded(pal.key, 8)
        tv.layoutParams = LinearLayout.LayoutParams(dp(38), dp(34)).apply {
            setMargins(dp(2), 0, dp(2), 0)
        }
        val rep = object : Runnable {
            override fun run() {
                step()
                ui.postDelayed(this, 90)
            }
        }
        tv.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    fx(v)
                    v.animate().scaleX(0.9f).scaleY(0.9f).setDuration(50).start()
                    step()
                    ui.postDelayed(rep, 400)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    ui.removeCallbacks(rep)
                    v.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
                }
            }
            true
        }
        return tv
    }

    private fun sliderRow(
        name: String, lo: Int, hi: Int, value: Int,
        fmt: (Int) -> String, step: Int, onChange: (Int) -> Unit
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        var cur = value
        val nameTv = label(name, 13f, pal.text).apply {
            layoutParams = LinearLayout.LayoutParams(dp(66), WRAP)
        }
        val valueTv = label(fmt(value), 13f, pal.text).apply {
            layoutParams = LinearLayout.LayoutParams(dp(44), WRAP)
            gravity = Gravity.CENTER
        }
        val sb = SeekBar(this)
        sb.max = hi - lo
        sb.progress = (value - lo).coerceIn(0, hi - lo)
        sb.layoutParams = LinearLayout.LayoutParams(0, dp(36), 1f)

        fun setValue(nv: Int) {
            val c = nv.coerceIn(lo, hi)
            if (c == cur) return
            cur = c
            sb.progress = c - lo
            valueTv.text = fmt(c)
            onChange(c)
            saveSoon()
        }

        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) {
                    cur = p + lo
                    valueTv.text = fmt(cur)
                    onChange(cur)
                }
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) { savePrefs() }
        })
        row.addView(nameTv)
        row.addView(holdBtn("−") { setValue(cur - step) })
        row.addView(sb)
        row.addView(holdBtn("+") { setValue(cur + step) })
        row.addView(valueTv)
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

        val r2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        r2.addView(pill("🔗 ادمج بالأيسر", 1f, pal.key, 38, 12f) {
            if (selId != null) mergeInto(-1) else toast("حدد حرفاً أولاً")
        })
        r2.addView(pill("🔗 ادمج بالأيمن", 1f, pal.key, 38, 12f) {
            if (selId != null) mergeInto(1) else toast("حدد حرفاً أولاً")
        })
        r2.addView(pill("↩ فك الدمج", 1f, pal.key, 38, 12f) {
            if (selId != null) unmerge() else toast("حدد حرفاً أولاً")
        })
        col.addView(r2)
        return col
    }

    /** يدمج الحرف المحدد داخل الزر المجاور: يختفي ويظهر بالضغط المطوّل على الزر المجاور */
    private fun mergeInto(d: Int) {
        val id = selId ?: return
        if (id.startsWith("::")) { toast("الدمج للأحرف فقط"); return }
        val (r, i) = find(id) ?: return
        val row = layoutFor()[r]
        val j = i + d
        if (j !in row.indices || row[j].startsWith("::")) { toast("ما كو حرف بهذا الاتجاه"); return }
        val target = row[j]
        val lst = merged.getOrPut("${lk()}|$target") { mutableListOf() }
        lst.add(id)
        merged.remove("${lk()}|$id")?.let { lst.addAll(it) }
        row.removeAt(i)
        selId = target
        savePrefs()
        renderAll()
    }

    private fun unmerge() {
        val id = selId ?: return
        val ex = merged.remove("${lk()}|$id")
        if (ex == null) { toast("ما كو أحرف مدموجة بهذا الزر"); return }
        val (r, i) = find(id) ?: return
        layoutFor()[r].addAll(i + 1, ex)
        savePrefs()
        renderAll()
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
            // أثناء الكتابة داخل الكيبورد نخفي صف الأرقام لتوفير المساحة
            if (internalMode() && mode != SYM && r.all { it.length == 1 && it[0].isDigit() }) continue
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
                val ex = merged["${lk()}|$id"]?.map { display(it) } ?: emptyList()
                key(id, s, w, h, pal.key, 22f, preview = true, extras = ex,
                    hint = ex.take(2).joinToString("")) { type(s) }
            }
        }
    }

    private fun enterLabel(): Pair<String, Boolean> {
        if (internalMode()) return Pair(if (panel == P_GIF) "🔍" else "ترجم", true)
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
        repeat: Boolean = false, preview: Boolean = false,
        extras: List<String> = emptyList(), hint: String = "", act: () -> Unit
    ): TextView {
        val v = TextView(this)
        if (hint.isNotEmpty()) {
            val sp = SpannableStringBuilder(text)
            val st = sp.length
            sp.append(hint)
            sp.setSpan(RelativeSizeSpan(0.5f), st, sp.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            sp.setSpan(SuperscriptSpan(), st, sp.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            v.text = sp
        } else {
            v.text = text
        }
        v.gravity = Gravity.CENTER
        v.setTextColor(if (bg == pal.accent || bg == SELECT_BG) Color.WHITE else pal.text)
        v.textSize = size * (if (preview) fontScale else 1f) * (0.75f + 0.25f * heightScale)
        val isSel = editMode && id == selId
        v.background = keyDrawable(if (isSel) SELECT_BG else bg)
        v.isClickable = true
        val g = dp(gapDp) / 2
        val hPx = (dp(46) * heightScale * hMul).toInt().coerceAtLeast(dp(18))
        v.layoutParams = LinearLayout.LayoutParams(0, hPx, weight).apply { setMargins(g, g, g, g) }

        var pressed = false
        var lpActive = false
        var lpSel = -1
        var lpX0 = 0
        var lpViews: List<TextView> = emptyList()
        val optW = dp(40)

        val repeater = object : Runnable {
            override fun run() {
                act()
                ui.postDelayed(this, 50)
            }
        }

        // الضغط المطوّل: يظهر شريط الأحرف المدموجة
        val lpRun = Runnable {
            if (pressed && extras.isNotEmpty() && !lpActive) {
                try {
                    dismissPreview()
                    val rowV = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        layoutDirection = View.LAYOUT_DIRECTION_LTR
                        background = rounded(pal.panel, 10)
                        setPadding(dp(2), dp(2), dp(2), dp(2))
                    }
                    val tvs = extras.map { e ->
                        TextView(this).apply {
                            this.text = e
                            textSize = 22f
                            gravity = Gravity.CENTER
                            setTextColor(pal.text)
                            layoutParams = LinearLayout.LayoutParams(optW, dp(44))
                        }
                    }
                    tvs.forEach { rowV.addView(it) }
                    val w = optW * extras.size + dp(4)
                    val h = dp(48)
                    val pw = PopupWindow(rowV, w, h, false)
                    pw.isTouchable = false
                    pw.isFocusable = false
                    pw.isClippingEnabled = false
                    val loc = IntArray(2)
                    v.getLocationOnScreen(loc)
                    val screenW = resources.displayMetrics.widthPixels
                    val x = (loc[0] + (v.width - w) / 2).coerceIn(0, maxOf(0, screenW - w))
                    pw.showAtLocation(v, Gravity.NO_GRAVITY, x, loc[1] - h - dp(6))
                    altPopup = pw
                    lpX0 = x + dp(2)
                    lpViews = tvs
                    lpSel = -1
                    lpActive = true
                    fx(v)
                } catch (e: Exception) {
                    lpActive = false
                }
            }
        }

        val slack = dp(14)
        v.setOnTouchListener { view, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    fx(view)
                    if (editMode) {
                        selId = id
                        renderAll()
                    } else {
                        pressed = true
                        view.alpha = 0.8f
                        view.animate().scaleX(0.92f).scaleY(0.92f).setDuration(50).start()
                        if (preview) showPreview(view, text)
                        if (repeat) {
                            act()
                            ui.postDelayed(repeater, 400)
                        }
                        if (extras.isNotEmpty()) ui.postDelayed(lpRun, 380)
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (lpActive) {
                        val rel = ev.rawX - lpX0
                        val idx = if (rel < 0f) -1 else (rel / optW).toInt().let { if (it >= lpViews.size) -1 else it }
                        if (idx != lpSel) {
                            lpSel = idx
                            lpViews.forEachIndexed { i, t ->
                                t.background = if (i == idx) rounded(pal.accent, 8) else null
                                t.setTextColor(if (i == idx) Color.WHITE else pal.text)
                            }
                        }
                    } else {
                        val inside = ev.x >= -slack && ev.x <= view.width + slack &&
                                ev.y >= -slack && ev.y <= view.height + slack
                        if (pressed && !inside) {
                            pressed = false
                            view.alpha = 1f
                            view.animate().scaleX(1f).scaleY(1f).setDuration(90).start()
                            dismissPreview()
                            ui.removeCallbacks(repeater)
                            ui.removeCallbacks(lpRun)
                        }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    ui.removeCallbacks(lpRun)
                    ui.removeCallbacks(repeater)
                    view.alpha = 1f
                    view.animate().scaleX(1f).scaleY(1f).setDuration(90).start()
                    dismissPreview()
                    if (lpActive) {
                        val sel = lpSel
                        lpActive = false
                        lpSel = -1
                        pressed = false
                        if (sel >= 0 && sel < extras.size) type(extras[sel]) else act()
                    } else {
                        val was = pressed
                        pressed = false
                        if (was && !repeat) act()
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    pressed = false
                    lpActive = false
                    view.alpha = 1f
                    view.animate().scaleX(1f).scaleY(1f).setDuration(90).start()
                    dismissPreview()
                    ui.removeCallbacks(repeater)
                    ui.removeCallbacks(lpRun)
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
        try { altPopup?.dismiss() } catch (e: Exception) { }
        altPopup = null
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
                fx(it)
                click()
            }
            pressFx(this)
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
            setOnClickListener { fx(it); click() }
            pressFx(this)
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
            setOnClickListener { fx(it); click() }
            pressFx(this)
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

    // ================= التفاعل (اهتزاز / صوت / حركة) =================

    private fun fx(v: View) {
        if (hapticOn) v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        if (soundOn) {
            try {
                (getSystemService(Context.AUDIO_SERVICE) as AudioManager)
                    .playSoundEffect(AudioManager.FX_KEYPRESS_STANDARD, 0.6f)
            } catch (e: Exception) {
            }
        }
    }

    /** تصغير خفيف عند اللمس ثم رجوع، يعطي إحساس التفاعل */
    private fun pressFx(v: View) {
        v.setOnTouchListener { view, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN ->
                    view.animate().scaleX(0.93f).scaleY(0.93f).setDuration(60).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    view.animate().scaleX(1f).scaleY(1f).setDuration(110).start()
            }
            false
        }
    }

    // ================= الكتابة داخل الكيبورد =================

    private fun internalMode() = panel == P_TR || panel == P_GIF

    private fun compactBox(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(0, dp(2), 0, dp(4)) }
        background = rounded(pal.panel, 12)
        setPadding(dp(6), dp(4), dp(6), dp(4))
    }

    /** مربع نص داخل الكيبورد: ما يكتبه المستخدم يظهر هنا وليس بالحقل */
    private fun inputBox(hint: String): TextView {
        val tv = TextView(this)
        tv.textSize = 16f
        tv.minHeight = dp(40)
        tv.maxLines = 3
        tv.gravity = Gravity.CENTER_VERTICAL
        tv.background = GradientDrawable().apply {
            setColor(pal.key)
            cornerRadius = dp(8).toFloat()
            setStroke(dp(2), pal.accent)
        }
        tv.setPadding(dp(8), dp(4), dp(8), dp(4))
        tv.layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        bufHint = hint
        bufView = tv
        refreshBuf()
        return tv
    }

    private fun refreshBuf() {
        val tv = bufView ?: return
        if (kbBuf.isEmpty()) {
            tv.setTextColor(pal.sub)
            tv.text = bufHint
        } else {
            tv.setTextColor(pal.text)
            tv.text = kbBuf.toString().takeLast(160) + "▏"
        }
    }

    private fun smallBtn(text: String, wDp: Int, bg: Int, click: () -> Unit): TextView =
        pill(text, 1f, bg, 40, 16f, click).apply {
            layoutParams = LinearLayout.LayoutParams(dp(wDp), dp(40)).apply {
                setMargins(dp(2), 0, dp(2), 0)
            }
        }

    // ================= الاقتراحات وذاكرة الكلمات =================

    private val seedAr = "السلام عليكم ورحمة الله وبركاته شلونك شلونكم شكو ماكو اكو شنو وين ليش شكد هسه اني انت انتة انتي " +
        "هذا هذه هاي هذولة هواية زين خوش حبيبي حبيبتي يعني بس مرة بعد قبل اليوم باجر امس الان دقيقة ساعة يوم اسبوع شهر سنة " +
        "شكرا مشكور تسلم الله يخليك يعطيك العافية الحمد لله ان شاء الله ماشاء الله جزاك خير بارك فيك اهلا مرحبا صباح مساء " +
        "الخير النور تمام طيب حسنا ممكن لو سمحت اسف عذرا اريد اروح رايح جاي جاية وصلت وصلنا راح اجي اكلت شربت نمت صحيت " +
        "شغل دوام بيت مدرسة جامعة سيارة طريق سوق مطعم مستشفى دكتور صديقي اخوي اختي امي ابوي ولدي بنتي اهلي عائلة موبايل " +
        "تلفون رسالة صورة فيديو مكالمة واتساب انستغرام فيسبوك الانترنت شبكة كهرباء ماي ماء اكل شاي قهوة خبز لحم دجاج سمك " +
        "رز تمر فلوس دينار دولار سعر كلش شوية هواي مو لا نعم ايه كلا اكيد صدك والله بالله عليك عندي عندك عنده عندها وياك " +
        "وياي وياه ويانه منك مني منه منها لان لكن او ثم حتى مع عن على الى من في هل ماذا كيف متى اين لماذا كم الذي التي " +
        "هناك هنا الجمعة السبت الاحد الاثنين الثلاثاء الاربعاء الخميس البصرة بغداد الزبير العراق"

    private val seedEn = "the be to of and in that have it for not on with he as you do at this but his by from they we say her " +
        "she or an will my one all would there their what so up out if about who get which go me when make can like time no " +
        "just him know take people into year your good some could them see other than then now look only come its over think " +
        "also back after use two how our work first well way even new want because any these give day most us is are was " +
        "were been has had did does hello thanks thank please sorry okay yes where why here today tomorrow tonight morning " +
        "night friend home phone message call send love great nice really very much more still need help find tell ask feel " +
        "try leave put mean keep let show hear play run move live believe bring happen write provide sit stand lose pay meet " +
        "include continue set learn change lead understand watch follow stop create speak read allow add spend grow open " +
        "walk win offer remember consider appear buy wait serve expect build stay fall cut reach remain"

    private val seeds: List<String> by lazy {
        (seedAr + " " + seedEn).split(" ").filter { it.isNotEmpty() }.distinct()
    }

    private fun isWordChar(c: Char) = c.isLetter() || c.category == CharCategory.NON_SPACING_MARK

    private fun sugActive() = sugOn && (internalMode() || !fieldNoSug)

    private fun textBefore(): String =
        if (internalMode()) kbBuf.toString().takeLast(60)
        else currentInputConnection?.getTextBeforeCursor(60, 0)?.toString() ?: ""

    /** الكلمة الحالية قبل المؤشر + الكلمة السابقة لها */
    private fun curWordAndPrev(): Pair<String, String> {
        val before = textBefore()
        var i = before.length
        while (i > 0 && isWordChar(before[i - 1])) i--
        val cur = before.substring(i)
        var j = i
        while (j > 0 && before[j - 1].isWhitespace()) j--
        var k = j
        while (k > 0 && isWordChar(before[k - 1])) k--
        val prev = if (j < i) before.substring(k, j) else ""
        return Pair(cur, prev)
    }

    private fun suggest(cur: String, prev: String): List<String> {
        val out = LinkedHashSet<String>()
        val p = prev.lowercase()
        if (cur.isEmpty()) {
            if (p.isNotEmpty()) {
                val pre = "$p "
                bigrams.entries.filter { it.key.startsWith(pre) }
                    .sortedByDescending { it.value }
                    .take(3)
                    .forEach { out.add(it.key.substring(pre.length)) }
            }
            if (out.isEmpty()) {
                dict.entries.filter { it.value >= 3 }
                    .sortedByDescending { it.value }
                    .take(3)
                    .forEach { out.add(it.key) }
            }
            return out.toList()
        }
        val lc = cur.lowercase()
        dict.entries.filter { it.key.length > lc.length && it.key.startsWith(lc) }
            .sortedByDescending { it.value + (bigrams["$p ${it.key}"] ?: 0) * 5 }
            .take(3)
            .forEach { out.add(it.key) }
        if (out.size < 3) {
            for (w in seeds) {
                if (w.length > lc.length && w.startsWith(lc)) {
                    out.add(w)
                    if (out.size >= 3) break
                }
            }
        }
        val cap = cur[0].isUpperCase()
        return out.map { if (cap) it.substring(0, 1).uppercase() + it.substring(1) else it }
    }

    private fun suggestionBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH, dp(36)).apply { setMargins(0, 0, 0, dp(2)) }
            background = rounded(pal.panel, 8)
        }
        val slots = List(3) { idx ->
            TextView(this).apply {
                gravity = Gravity.CENTER
                textSize = 16f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(pal.text)
                layoutParams = LinearLayout.LayoutParams(0, MATCH, 1f)
                setOnClickListener {
                    val w = sugCur[idx]
                    if (w.isNotEmpty()) { fx(it); applySuggestion(w) }
                }
                // ضغطة مطولة = حذف الكلمة من الذاكرة
                setOnLongClickListener {
                    val w = sugCur[idx].lowercase()
                    if (w.isNotEmpty() && dict.containsKey(w)) {
                        dict.remove(w)
                        bigrams.keys.removeAll { k -> k.endsWith(" $w") || k.startsWith("$w ") }
                        dictDirty++
                        saveDict()
                        toast("حُذفت من الذاكرة: $w")
                        updateSuggestions()
                    }
                    true
                }
            }
        }
        // الأفضل بالوسط
        bar.addView(slots[1]); bar.addView(slots[0]); bar.addView(slots[2])
        sugBar = bar
        sugViews = slots
        return bar
    }

    private fun updateSuggestions() {
        if (sugViews.isEmpty() || sugBar == null) return
        val (cur, prev) = curWordAndPrev()
        val list = suggest(cur, prev)
        for (i in 0..2) {
            val w = list.getOrNull(i) ?: ""
            sugCur[i] = w
            sugViews[i].text = w
        }
    }

    private fun applySuggestion(w: String) {
        val (cur, prev) = curWordAndPrev()
        if (internalMode()) {
            kbBuf.setLength(maxOf(0, kbBuf.length - cur.length))
            kbBuf.append(w).append(' ')
            refreshBuf()
        } else {
            val ic = currentInputConnection ?: return
            if (cur.isNotEmpty()) ic.deleteSurroundingText(cur.length, 0)
            ic.commitText("$w ", 1)
        }
        if (sugOn) learn(w, prev)
        ui.post { updateSuggestions() }
    }

    private fun learn(w0: String, prev0: String) {
        val w = w0.lowercase().trim()
        if (w.length < 2 || w.length > 24 || !w.any { it.isLetter() }) return
        dict[w] = (dict[w] ?: 0) + 1
        val p = prev0.lowercase().trim()
        if (p.length >= 2) {
            val k = "$p $w"
            bigrams[k] = (bigrams[k] ?: 0) + 1
        }
        dictDirty++
        if (dictDirty >= 10) saveDict()
    }

    private fun learnCurrent() {
        if (!sugOn) return
        if (!internalMode() && (fieldNoSug || activeStyle != null)) return
        val (cur, prev) = curWordAndPrev()
        if (cur.isNotEmpty()) learn(cur, prev)
    }

    private fun prune() {
        if (dict.size > 6000) {
            val keep = dict.entries.sortedByDescending { it.value }.take(5000).map { it.key }.toHashSet()
            dict.keys.retainAll(keep)
        }
        if (bigrams.size > 8000) {
            val keep = bigrams.entries.sortedByDescending { it.value }.take(6000).map { it.key }.toHashSet()
            bigrams.keys.retainAll(keep)
        }
    }

    private fun saveDict(sync: Boolean = false) {
        if (dictDirty == 0) return
        dictDirty = 0
        prune()
        val d = HashMap(dict)
        val b = HashMap(bigrams)
        val job = Runnable {
            try {
                val u = JSONObject()
                for ((k, v) in d) u.put(k, v)
                val bb = JSONObject()
                for ((k, v) in b) bb.put(k, v)
                val o = JSONObject()
                o.put("u", u)
                o.put("b", bb)
                File(filesDir, "dict.json").writeText(o.toString())
            } catch (e: Exception) {
            }
        }
        if (sync) job.run() else io.execute(job)
    }

    private fun loadDict() {
        try {
            val f = File(filesDir, "dict.json")
            if (!f.exists()) return
            val o = JSONObject(f.readText())
            o.optJSONObject("u")?.let { u -> u.keys().forEach { k -> dict[k] = u.getInt(k) } }
            o.optJSONObject("b")?.let { b -> b.keys().forEach { k -> bigrams[k] = b.getInt(k) } }
        } catch (e: Exception) {
            dict.clear()
            bigrams.clear()
        }
    }

    // ================= الإدخال =================

    private fun type(s: String) {
        // الكتابة داخل الكيبورد (ترجمة / بحث GIF)
        if (internalMode()) {
            if (s.isNotEmpty() && !isWordChar(s[0])) learnCurrent()
            kbBuf.append(s)
            refreshBuf()
            updateSuggestions()
            if (shift) {
                shift = false
                renderContent()
            }
            return
        }
        val ic = currentInputConnection ?: return
        val st = activeStyle
        if (st == null && s.isNotEmpty() && !isWordChar(s[0])) learnCurrent()
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
        ui.post { updateSuggestions() }
    }

    private fun deleteOne() {
        if (internalMode()) {
            if (kbBuf.isNotEmpty()) kbBuf.setLength(kbBuf.length - 1)
            refreshBuf()
            updateSuggestions()
            return
        }
        val ic = currentInputConnection ?: return
        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) ic.commitText("", 1)
        else ic.deleteSurroundingText(1, 0)
    }

    private fun pressEnter() {
        if (internalMode()) {
            learnCurrent()
            if (panel == P_TR) doTranslate(trLang) else gifSearch()
            return
        }
        learnCurrent()
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
        val box = compactBox()
        trResult = ""

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(inputBox("اكتب النص هنا بالكيبورد ثم اختر لغة الترجمة"))
        top.addView(smallBtn("📋", 44, pal.key) {
            kbBuf.setLength(0)
            kbBuf.append(fieldText().first)
            refreshBuf()
            updateSuggestions()
        })
        top.addView(smallBtn("🗑", 44, pal.key) {
            kbBuf.setLength(0)
            refreshBuf()
            updateSuggestions()
        })
        box.addView(top)

        val langs = listOf(
            "العربية" to "ar", "English" to "en", "Türkçe" to "tr", "فارسی" to "fa",
            "کوردی" to "ckb", "Français" to "fr", "Español" to "es", "Deutsch" to "de",
            "Русский" to "ru", "Italiano" to "it", "中文" to "zh-CN", "हिन्दी" to "hi"
        )
        box.addView(hScroll(*langs.map { (name, code) ->
            chip(name, code == trLang) { doTranslate(code) }
        }.toTypedArray()))

        val out = label("اضغط على اللغة وتنكتب الترجمة بالحقل تلقائياً", 12f).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(1), 0, dp(1))
        }
        trOut = out
        box.addView(out)
        return box
    }

    private fun doTranslate(lang: String) {
        trLang = lang
        prefs.edit().putString("trLang", lang).apply()
        val fromBuf = kbBuf.isNotBlank()
        val src: String
        val wasSel: Boolean
        if (fromBuf) {
            src = kbBuf.toString()
            wasSel = false
        } else {
            // إذا مربع الكيبورد فارغ نترجم نص الحقل (المحدد أو الكل)
            val (t, sel) = fieldText()
            src = t
            wasSel = sel
        }
        if (src.isBlank()) { trOut?.text = "اكتب النص بالكيبورد أولاً"; return }
        val gen = ++trGen
        trOut?.text = "جاري الترجمة…"
        io.execute {
            try {
                val res = Net.translate(src, lang)
                ui.post {
                    if (gen != trGen) return@post
                    trResult = res
                    if (fromBuf) {
                        currentInputConnection?.commitText(res, 1)
                        kbBuf.setLength(0)
                    } else {
                        replaceFieldText(res, wasSel)
                    }
                    backToKeys()
                }
            } catch (e: Exception) {
                ui.post { if (gen == trGen) trOut?.text = "تعذّرت الترجمة، تأكد من الإنترنت" }
            }
        }
    }

    // ================= GIF =================

    private fun gifPanel(): View {
        val box = compactBox()
        val status = label("", 12f).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(1), 0, dp(1))
        }
        val strip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        gifStatus = status
        gifStrip = strip

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(inputBox("اكتب كلمة البحث هنا بالكيبورد"))
        top.addView(smallBtn("🗑", 44, pal.key) {
            kbBuf.setLength(0)
            refreshBuf()
            updateSuggestions()
        })
        top.addView(smallBtn("🔍", 52, pal.accent) { gifSearch() })
        box.addView(top)

        val cats = listOf(
            "🔥 رائج" to "", "😂" to "funny", "❤️" to "love",
            "👍" to "thumbs up", "😢" to "sad", "🎉" to "party", "😮" to "wow",
            "😡" to "angry", "🥰" to "cute", "🤝" to "thanks"
        )
        box.addView(hScroll(*cats.map { (name, q) ->
            chip(name, false) { loadGifs(q) }
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
                layoutParams = LinearLayout.LayoutParams(MATCH, dp(96))
                addView(strip)
            })
            loadGifs("")
        }
        return box
    }

    private fun gifSearch() {
        val q = kbBuf.toString().trim().take(50)
        if (q.isEmpty()) {
            gifStatus?.text = "اكتب كلمة البحث بالكيبورد أولاً"
            return
        }
        loadGifs(q)
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
            layoutParams = LinearLayout.LayoutParams(dp(90), dp(90)).apply {
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
