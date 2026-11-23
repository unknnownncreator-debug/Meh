package app.meh

/** The "Minimum length" slider: its steps (seconds), labels, and the filter itself. */
object MinLength {
    /** Uneven steps, so short settings are as easy to hit as long ones. 0 = any length. */
    val STEPS = intArrayOf(0, 5, 10, 15, 30, 45, 60, 90, 120, 180, 300, 600, 900, 1200)

    fun seconds(index: Int): Int = STEPS[index.coerceIn(0, STEPS.size - 1)]

    fun label(index: Int): String {
        val s = seconds(index)
        return if (s == 0) "Any length" else "At least ${short(s)}"
    }

    /** 45 → "45 s", 90 → "1½ min", 120 → "2 min". */
    fun short(s: Int): String = when {
        s < 60 -> "$s s"
        s % 60 == 30 -> "${s / 60}½ min"
        else -> "${s / 60} min"
    }

    /** Unknown lengths pass (checked again once the video loads). */
    fun allows(duration: Double?, minSeconds: Int): Boolean = minSeconds <= 0 || duration == null || duration >= minSeconds

    fun reason(minSeconds: Int) = "shorter than your minimum length (${short(minSeconds)})"
}
