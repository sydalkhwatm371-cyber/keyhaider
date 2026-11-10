package com.example.mykeyboard

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

/** يرسم الصورة بقص مركزي (centerCrop) بدون التأثير على قياس الكيبورد. */
class BgDrawable(private val bmp: Bitmap) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    override fun draw(c: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val s = maxOf(b.width().toFloat() / bmp.width, b.height().toFloat() / bmp.height)
        val dx = b.left + (b.width() - bmp.width * s) / 2f
        val dy = b.top + (b.height() - bmp.height * s) / 2f
        c.save()
        c.clipRect(b)
        c.translate(dx, dy)
        c.scale(s, s)
        c.drawBitmap(bmp, 0f, 0f, paint)
        c.restore()
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(cf: ColorFilter?) { paint.colorFilter = cf }
    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}
