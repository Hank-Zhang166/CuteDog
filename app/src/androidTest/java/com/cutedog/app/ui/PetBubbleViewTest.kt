package com.cutedog.app.ui

import android.content.Context
import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.cutedog.app.pet.PetBubbleView
import org.junit.Assert.*
import org.junit.Test

/** Real Android measurement and native button callbacks, without creating an overlay or changing permissions. */
class PetBubbleViewTest {
    @Test fun largeReminderStaysBoundedAndItsCheckboxIsReachable() = onMain {
        val context = configuredContext(2f, dark = true)
        val bubble = PetBubbleView(context)
        var completions = 0
        bubble.onComplete = { completions++ }
        bubble.showReminder("这是用于验证两倍字体与受限高度的长事项标题。勾选框应该可以滚动到达。", "14:30")
        val maxWidth = dp(context, 260); val maxHeight = dp(context, 180)
        val measured = bubble.measureForBounds(maxWidth, maxHeight)
        bubble.layout(0, 0, measured.width, measured.height)
        assertTrue(measured.width in 1..maxWidth)
        assertTrue(measured.height in 1..maxHeight)
        val scroll = find<ScrollView>(bubble)!!
        assertTrue("Large content must be scrollable instead of clipped", scroll.canScrollVertically(1))
        val check = find<CheckBox>(bubble)!!
        assertTrue(check.minimumHeight >= dp(context, 48))
        assertTrue(check.performClick())
        assertTrue(check.isChecked)
        assertEquals(1, completions)
    }

    @Test fun allFourTailDirectionsClampToActualMeasuredBounds() = onMain {
        val context = configuredContext(1f, dark = false)
        val bubble = PetBubbleView(context)
        bubble.showReminder("查看测试约定", "14:30")
        for (edge in PetBubbleView.TailEdge.entries) {
            for (offset in listOf(-10000f, Float.NaN, 10000f)) {
                bubble.setTail(edge, offset)
                val measured = bubble.measureForBounds(dp(context, 300), dp(context, 220))
                bubble.layout(0, 0, measured.width, measured.height)
                val tip = bubble.tailTip()
                assertTrue(tip.x in 0f..measured.width.toFloat())
                assertTrue(tip.y in 0f..measured.height.toFloat())
                when (edge) {
                    PetBubbleView.TailEdge.TOP -> assertTrue(tip.y < measured.height / 2)
                    PetBubbleView.TailEdge.BOTTOM -> assertTrue(tip.y > measured.height / 2)
                    PetBubbleView.TailEdge.LEFT -> assertTrue(tip.x < measured.width / 2)
                    PetBubbleView.TailEdge.RIGHT -> assertTrue(tip.x > measured.width / 2)
                }
            }
        }
    }

    @Test fun reminderHasOnlyOneCompletionControlAndRebindingClearsOldText() = onMain {
        val bubble = PetBubbleView(configuredContext(1f, dark = false))
        var completions = 0
        bubble.onComplete = { completions++ }
        bubble.showReminder("测试事项标题", "14:30")
        assertTrue(texts(bubble).contains("待办提醒 · 14:30"))
        assertTrue(texts(bubble).contains("测试事项标题"))
        val check = find<CheckBox>(bubble)!!
        assertEquals(listOf(check), allViews(bubble).filter { it.isClickable })
        check.performClick()
        assertEquals(1, completions)
        bubble.showReminder("新事项", "15:00")
        assertFalse(check.isChecked)
        assertFalse(texts(bubble).contains("测试事项标题"))
        assertTrue(texts(bubble).contains("新事项"))
    }

    private fun configuredContext(fontScale: Float, dark: Boolean): Context {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val config = Configuration(base.resources.configuration).apply {
            this.fontScale = fontScale
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        }
        return base.createConfigurationContext(config)
    }
    private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    private fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density + .5f).toInt()
    private inline fun <reified T : View> find(root: View): T? = allViews(root).filterIsInstance<T>().firstOrNull()
    private fun texts(root: View) = allViews(root).filterIsInstance<TextView>().map { it.text.toString() }
    private fun allViews(root: View): List<View> = buildList {
        add(root)
        if (root is ViewGroup) for (index in 0 until root.childCount) addAll(allViews(root.getChildAt(index)))
    }
}
