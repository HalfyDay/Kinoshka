package hd.kinoshka.app.utils

object SearchQueryUtils {
    private const val EN_CHARS = "qwertyuiop[]asdfghjkl;'zxcvbnm,."
    private const val RU_CHARS = "йцукенгшщзхъфывапролджэячсмитьбю"

    private val enToRuMap = buildMap {
        for (i in EN_CHARS.indices) {
            if (i < RU_CHARS.length) {
                put(EN_CHARS[i], RU_CHARS[i])
            }
        }
    }

    private val ruToEnMap = buildMap {
        for (i in RU_CHARS.indices) {
            if (i < EN_CHARS.length) {
                put(RU_CHARS[i], EN_CHARS[i])
            }
        }
    }

    fun fixKeyboardLayout(input: String): String {
        val sb = StringBuilder()
        for (ch in input) {
            val lower = ch.lowercaseChar()
            val replaced = when {
                enToRuMap.containsKey(lower) -> enToRuMap[lower]
                ruToEnMap.containsKey(lower) -> ruToEnMap[lower]
                else -> ch
            }
            if (replaced != null) {
                sb.append(if (ch.isUpperCase()) replaced.uppercaseChar() else replaced)
            } else {
                sb.append(ch)
            }
        }
        return sb.toString()
    }
}
