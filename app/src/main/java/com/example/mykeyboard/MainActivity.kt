package com.example.mykeyboard

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File

class MainActivity : Activity() {

    private val reqPick = 42
    private var fromKeyboard = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fromKeyboard = intent.getBooleanExtra("pick_bg", false)
        val prefs = getSharedPreferences("kb", Context.MODE_PRIVATE)

        val pad = (20 * resources.displayMetrics.density).toInt()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }

        fun text(t: String, size: Float = 16f, bold: Boolean = false) = TextView(this).apply {
            this.text = t
            textSize = size
            setTextColor(Color.BLACK)
            setPadding(0, pad / 2, 0, pad / 4)
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

        layout.addView(text("كيبوردي ⌨️", 28f, true).apply { gravity = Gravity.CENTER })

        layout.addView(text("١) فعّل الكيبورد   ٢) اختره   ٣) جرّبه في الحقل بالأسفل."))
        layout.addView(Button(this).apply {
            text = "١) تفعيل الكيبورد"
            setOnClickListener { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
        })
        layout.addView(Button(this).apply {
            text = "٢) اختيار الكيبورد"
            setOnClickListener {
                (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .showInputMethodPicker()
            }
        })

        layout.addView(text("🎞️ مفتاح GIPHY (لتشغيل زر GIF)", 18f, true))
        layout.addView(text(
            "١) ادخل developers.giphy.com وسجّل دخول.\n" +
            "٢) Create an App ثم اختر API (وليس SDK).\n" +
            "٣) انسخ المفتاح (API Key) والصقه هنا واضغط حفظ.\n" +
            "لا تحتاج إعادة بناء التطبيق.", 14f
        ))
        val keyField = EditText(this).apply {
            hint = "الصق مفتاح GIPHY هنا"
            setSingleLine(true)
            setText(prefs.getString("giphy", ""))
        }
        layout.addView(keyField)
        layout.addView(Button(this).apply {
            text = "💾 حفظ المفتاح"
            setOnClickListener {
                prefs.edit().putString("giphy", keyField.text.toString().trim()).apply()
                Toast.makeText(this@MainActivity, "تم حفظ المفتاح ✅", Toast.LENGTH_SHORT).show()
            }
        })

        layout.addView(text("🖼️ صورة خلفية للكيبورد", 18f, true))
        layout.addView(Button(this).apply {
            text = "اختيار صورة"
            setOnClickListener { pickImage() }
        })
        layout.addView(Button(this).apply {
            text = "إزالة الصورة"
            setOnClickListener {
                File(filesDir, "kbbg.jpg").delete()
                if (prefs.getInt("theme", 0) == 3) prefs.edit().putInt("theme", 0).apply()
                Toast.makeText(this@MainActivity, "تمت الإزالة", Toast.LENGTH_SHORT).show()
            }
        })

        layout.addView(text("جرّب الكتابة:", 14f))
        layout.addView(EditText(this).apply { hint = "اكتب هنا للتجربة" })

        setContentView(ScrollView(this).apply { addView(layout) })

        if (fromKeyboard) pickImage()
    }

    private fun pickImage() {
        val i = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        @Suppress("DEPRECATION")
        startActivityForResult(i, reqPick)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == reqPick) {
            val uri = data?.data
            if (resultCode == RESULT_OK && uri != null) {
                if (saveBackground(uri)) {
                    Toast.makeText(this, "تم تعيين الصورة ✅ افتح الكيبورد", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "تعذّر قراءة الصورة", Toast.LENGTH_LONG).show()
                }
            }
            if (fromKeyboard) finish()
        }
    }

    private fun saveBackground(uri: Uri): Boolean {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (bounds.outWidth / sample > 1400 || bounds.outHeight / sample > 1400) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            } ?: return false
            File(filesDir, "kbbg.jpg").outputStream().use {
                bmp.compress(Bitmap.CompressFormat.JPEG, 88, it)
            }
            getSharedPreferences("kb", Context.MODE_PRIVATE).edit().putInt("theme", 3).apply()
            true
        } catch (e: Exception) {
            false
        }
    }
}
