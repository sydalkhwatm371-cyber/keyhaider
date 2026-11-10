package com.example.mykeyboard

class Style(
    val name: String,
    private val sampleText: String,
    val local: Boolean,              // يصلح للكتابة المباشرة حرفاً بحرف
    val joiner: String = "",         // زخرفة تُضاف بعد الحرف (للعربي)
    private val fn: (String) -> String,
    private val post: (String) -> String = { it }
) {
    fun apply(t: String): String = post(fn(t))
    fun applyChar(c: String): String = fn(c)
    val sample: String get() = apply(sampleText)
}

object Styles {

    private fun perCp(t: String, f: (Int) -> String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < t.length) {
            val cp = t.codePointAt(i)
            i += Character.charCount(cp)
            sb.append(f(cp))
        }
        return sb.toString()
    }

    private fun cpStr(cp: Int) = String(Character.toChars(cp))

    private fun alpha(up: Int, low: Int, dig: Int = -1): (String) -> String = { t ->
        perCp(t) { cp ->
            cpStr(
                when {
                    cp in 65..90 -> up + cp - 65
                    cp in 97..122 -> low + cp - 97
                    dig >= 0 && cp in 48..57 -> dig + cp - 48
                    else -> cp
                }
            )
        }
    }

    private const val NONJOIN = "ادذرزوأإآؤةىء"
    private const val SMALL_CAPS = "ᴀʙᴄᴅᴇꜰɢʜɪᴊᴋʟᴍɴᴏᴘǫʀꜱᴛᴜᴠᴡxʏᴢ"
    private const val DECO = "ـﮩ٨ـ"

    private fun isArLetter(c: Char) = c in '\u0621'..'\u064A' || "پچگڤژک".indexOf(c) >= 0
    private fun joins(c: Char) =
        (c in '\u0621'..'\u064A' && NONJOIN.indexOf(c) < 0) || "پچگڤک".indexOf(c) >= 0

    private fun arPerChar(t: String, f: (Char, String) -> String): String =
        perCp(t) { cp ->
            val s = cpStr(cp)
            if (cp < 0x10000) f(cp.toChar(), s) else s
        }

    private fun wrap(l: String, r: String): (String) -> String = { t -> "$l $t $r" }

    val all: List<Style> = listOf(
        // ----- إنجليزي (يمكن الكتابة بها مباشرة) -----
        Style("Bold", "Abc 123", true, fn = alpha(0x1D400, 0x1D41A, 0x1D7CE)),
        Style("Italic", "Abc", true, fn = { t ->
            perCp(t) { cp ->
                cpStr(
                    when {
                        cp == 104 -> 0x210E
                        cp in 65..90 -> 0x1D434 + cp - 65
                        cp in 97..122 -> 0x1D44E + cp - 97
                        else -> cp
                    }
                )
            }
        }),
        Style("Bold Italic", "Abc", true, fn = alpha(0x1D468, 0x1D482)),
        Style("Script", "Abc", true, fn = alpha(0x1D4D0, 0x1D4EA)),
        Style("Fraktur", "Abc", true, fn = alpha(0x1D56C, 0x1D586)),
        Style("Sans Bold", "Abc 123", true, fn = alpha(0x1D5D4, 0x1D5EE, 0x1D7EC)),
        Style("Mono", "Abc 123", true, fn = alpha(0x1D670, 0x1D68A, 0x1D7F6)),
        Style("Wide", "Abc 123", true, fn = alpha(0xFF21, 0xFF41, 0xFF10)),
        Style("Circled", "Abc 123", true, fn = { t ->
            perCp(t) { cp ->
                cpStr(
                    when {
                        cp in 65..90 -> 0x24B6 + cp - 65
                        cp in 97..122 -> 0x24D0 + cp - 97
                        cp in 49..57 -> 0x2460 + cp - 49
                        cp == 48 -> 0x24EA
                        else -> cp
                    }
                )
            }
        }),
        Style("Small Caps", "Abc", true, fn = { t ->
            perCp(t) { cp ->
                when (cp) {
                    in 97..122 -> SMALL_CAPS[cp - 97].toString()
                    in 65..90 -> SMALL_CAPS[cp - 65].toString()
                    else -> cpStr(cp)
                }
            }
        }),
        Style("Strike", "Abc", true, fn = { t ->
            perCp(t) { cp -> if (Character.isWhitespace(cp)) cpStr(cp) else cpStr(cp) + "\u0336" }
        }),
        Style("Underline", "Abc", true, fn = { t ->
            perCp(t) { cp -> if (Character.isWhitespace(cp)) cpStr(cp) else cpStr(cp) + "\u0332" }
        }),

        // ----- عربي (يمكن الكتابة بها مباشرة) -----
        Style("تطويل", "كتابة", true, joiner = "ـ",
            fn = { t -> arPerChar(t) { c, s -> if (joins(c)) s + "ـ" else s } },
            post = { t -> t.replace(Regex("ـ+(?=\\s|$)"), "") }),
        Style("تشكيل", "كتابة", true,
            fn = { t -> arPerChar(t) { c, s -> if (isArLetter(c)) s + "\u064E" else s } }),
        Style("مزخرف", "كتابة", true, joiner = DECO,
            fn = { t -> arPerChar(t) { c, s -> if (joins(c)) s + DECO else s } },
            post = { t -> t.replace(Regex(Regex.escape(DECO) + "(?=\\s|$)"), "") }),

        // ----- أطر (تُطبّق على النص الموجود فقط) -----
        Style("نجوم", "نص", false, fn = wrap("★", "★")),
        Style("زهور", "نص", false, fn = wrap("❀", "❀")),
        Style("أقواس", "نص", false, fn = wrap("『", "』")),
        Style("قرآني", "نص", false, fn = wrap("\uFD3F", "\uFD3E")),
        Style("زخرفة", "نص", false, fn = wrap("༺", "༻"))
    )
}
