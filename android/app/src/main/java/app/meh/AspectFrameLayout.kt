package app.meh

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout

/** 16:9 box for the player; [fill] makes it take the whole parent (full screen). */
class AspectFrameLayout @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {
    var fill = false
        set(value) { field = value; requestLayout() }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (fill) return super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val w = View.MeasureSpec.getSize(widthMeasureSpec)
        var h = w * 9 / 16
        // Sideways but not full screen: leave room for the title and controls below.
        val avail = View.MeasureSpec.getSize(heightMeasureSpec)
        if (View.MeasureSpec.getMode(heightMeasureSpec) != View.MeasureSpec.UNSPECIFIED && avail > 0) h = minOf(h, (avail * 0.6).toInt())
        super.onMeasure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
    }
}
