package app.vezuna

import androidx.test.uiautomator.By
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertTrue

/** Shared navigation for the redesigned shell: bottom tabs (Today, Records, Care, Trends, More) and pushed pages. */
internal fun UiDevice.awaitText(text: String, timeoutMs: Long = 10_000): Boolean = wait(Until.hasObject(By.text(text)), timeoutMs)

/** Home is ready when the Today title is on screen. */
internal fun UiDevice.awaitToday(today: String = "Today"): Boolean = awaitText(today, 15_000)

/** Clicks the last node with this text: on pushed pages a heading and a button can share a label, the button is below. */
internal fun UiDevice.tapText(text: String, timeoutMs: Long = 10_000) {
    assertTrue("'$text' did not appear", awaitText(text, timeoutMs))
    // The page may recompose between lookup and click; look the node up again instead of keeping a stale handle.
    repeat(10) {
        try {
            findObjects(By.text(text)).lastOrNull()?.let { it.click(); waitForIdle(); return }
        } catch (_: StaleObjectException) {
        }
        waitForIdle()
        wait(Until.hasObject(By.text(text)), 500)
    }
    throw AssertionError("'$text' could not be clicked")
}

/** Scrolls until the text is on screen, then clicks it. */
internal fun UiDevice.scrollAndTapText(text: String) {
    assertTrue("'$text' not reachable by scrolling", swipeToText(text))
    tapText(text)
}

/** Scrolls the current page until text is on screen. */
internal fun UiDevice.swipeToText(text: String, attempts: Int = 10): Boolean {
    repeat(attempts) {
        if (hasObject(By.text(text))) return true
        swipe(displayWidth / 2, (displayHeight * 0.7).toInt(), displayWidth / 2, (displayHeight * 0.4).toInt(), 100)
        waitForIdle()
    }
    return hasObject(By.text(text))
}
