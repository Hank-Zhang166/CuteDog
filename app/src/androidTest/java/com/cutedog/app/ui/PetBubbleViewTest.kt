package com.cutedog.app.ui

import android.content.Context
import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.cutedog.app.pet.PetBubbleView
import org.junit.Assert.*
import org.junit.Test

/** Real Android measurement and native button callbacks, without creating an overlay or changing permissions. */
class PetBubbleViewTest {
    @Test fun largeTextMenuStaysBoundedAndScrollsToItsRealActions() = onMain {
        val context = configuredContext(2f, dark = true)
        val bubble = PetBubbleView(context)
        val actual = mutableListOf<String>()
        bubble.onAction = actual::add
        bubble.showMenu("测试小狗", "这是用于验证两倍字体与受限高度的说明。操作应该可以滚动到达。")
        val maxWidth = dp(context, 260); val maxHeight = dp(context, 180)
        val measured = bubble.measureForBounds(maxWidth, maxHeight)
        bubble.layout(0, 0, measured.width, measured.height)
        assertTrue(measured.width in 1..maxWidth)
        assertTrue(measured.height in 1..maxHeight)
        val scroll = find<ScrollView>(bubble)!!
        assertTrue("Large content must be scrollable instead of clipped", scroll.canScrollVertically(1))
        for (action in listOf("chat", "pet", "open", "hide")) {
            val button = bubble.findViewWithTag<Button>("pet_bubble_$action")
            assertNotNull(button)
            assertTrue(button.minimumHeight >= dp(context, 48))
            assertTrue(button.performClick())
        }
        assertEquals(listOf("chat", "pet", "open", "hide"), actual)
    }

    @Test fun allFourTailDirectionsClampToActualMeasuredBounds() = onMain {
        val context = configuredContext(1f, dark = false)
        val bubble = PetBubbleView(context)
        bubble.showReminder("查看测试约定", "提醒正文")
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

    @Test fun reminderHasExplicitTitleAndDetailsActionAndRebindingClearsOldText() = onMain {
        val bubble = PetBubbleView(configuredContext(1f, dark = false))
        var action: String? = null
        bubble.onAction = { action = it }
        bubble.showReminder("测试事项标题", "仅用于本测试的提醒说明")
        assertTrue(texts(bubble).contains("事项提醒"))
        assertTrue(texts(bubble).contains("测试事项标题"))
        bubble.findViewWithTag<Button>("pet_bubble_details").performClick()
        assertEquals(PetBubbleView.ACTION_DETAILS, action)
        bubble.showMenu("测试小狗")
        assertFalse(texts(bubble).contains("测试事项标题"))
        assertFalse(texts(bubble).contains("仅用于本测试的提醒说明"))
        assertNull(bubble.findViewWithTag<View>("pet_bubble_details"))
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
