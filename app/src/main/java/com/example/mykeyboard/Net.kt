package com.example.mykeyboard

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object Net {

    fun httpGet(u: String): String {
        val c = URL(u).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        c.setRequestProperty("User-Agent", "Mozilla/5.0")
        return try {
            c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    /** ترجمة عبر نقطة Google Translate غير الرسمية (بدون مفتاح). */
    fun translate(text: String, lang: String): String {
        val q = URLEncoder.encode(text.take(1500), "UTF-8")
        val url = "https://translate.googleapis.com/translate_a/single" +
                "?client=gtx&sl=auto&tl=$lang&dt=t&q=$q"
        val segs = JSONArray(httpGet(url)).getJSONArray(0)
        val sb = StringBuilder()
        for (i in 0 until segs.length()) {
            val seg = segs.optJSONArray(i) ?: continue
            sb.append(seg.optString(0, ""))
        }
        return sb.toString()
    }

    /** يرجع قائمة (رابط المصغّرة, رابط الـGIF الكامل). query فارغ = الرائج. */
    fun giphy(query: String, key: String): List<Pair<String, String>> {
        val base = "https://api.giphy.com/v1/gifs/"
        val url = if (query.isEmpty())
            base + "trending?api_key=$key&limit=20&rating=pg-13"
        else
            base + "search?api_key=$key&q=" + URLEncoder.encode(query, "UTF-8") +
                    "&limit=20&rating=pg-13"
        val data = JSONObject(httpGet(url)).getJSONArray("data")
        val out = ArrayList<Pair<String, String>>()
        for (i in 0 until data.length()) {
            val images = data.getJSONObject(i).optJSONObject("images") ?: continue
            val full = images.optJSONObject("fixed_height")?.optString("url") ?: ""
            val thumb = images.optJSONObject("fixed_height_small")?.optString("url") ?: full
            if (full.isNotEmpty()) out.add(Pair(thumb.ifEmpty { full }, full))
        }
        return out
    }
}
