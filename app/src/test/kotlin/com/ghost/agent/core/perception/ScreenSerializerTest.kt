package com.ghost.agent.core.perception

import com.ghost.agent.Fixtures
import com.ghost.agent.core.model.Bounds
import com.ghost.agent.core.model.ScreenSnapshot
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Serialization is the model's entire view of the world, so these tests are really about
 * two things: the format is exactly what the prompt promises, and the element budget is
 * spent on things worth deciding between.
 */
class ScreenSerializerTest {

    @Test
    fun `renders the documented format`() {
        val line = ScreenSerializer.render(
            Fixtures.element(
                1, "android.widget.Button", text = "Renew Now",
                clickable = true, bounds = Bounds(40, 220, 340, 280),
            ),
        )
        assertThat(line).isEqualTo("""[1] Button "Renew Now" clickable bounds=(40,220,340,280)""")
    }

    @Test
    fun `serializes Appendix B's screen with every element addressable`() {
        val text = ScreenSerializer.serialize(Fixtures.parkingHome())
        assertThat(text).contains("[1] Button \"Renew Now\" clickable")
        assertThat(text).contains("[2] EditText \"Vehicle number\" editable")
        assertThat(text).contains("[3] TextView \"Expires: 12 Sept 2026\"")
        assertThat(text).contains("[4] Button \"Cancel\" clickable")
    }

    @Test
    fun `names the app so the model knows where it is`() {
        assertThat(ScreenSerializer.serialize(Fixtures.gmailCompose()))
            .contains(Fixtures.PKG_GMAIL)
    }

    @Test
    fun `ids stay in ascending order in the rendered output`() {
        // Ranking decides which elements survive the cut, but the model indexes into the
        // printed list -- a non-monotonic id column measurably increases misreferences.
        val text = ScreenSerializer.serialize(Fixtures.gmailCompose())
        val ids = Regex("""^\[(\d+)]""", RegexOption.MULTILINE)
            .findAll(text)
            .map { it.groupValues[1].toInt() }
            .toList()
        assertThat(ids).isInStrictOrder()
    }

    @Test
    fun `drops decorative nodes with no label and no affordance`() {
        val snapshot = ScreenSnapshot(
            packageName = Fixtures.PKG_GMAIL,
            elements = listOf(
                Fixtures.element(1, "android.widget.FrameLayout"),
                Fixtures.element(2, "android.widget.Button", text = "Send", clickable = true),
            ),
        )
        val text = ScreenSerializer.serialize(snapshot)
        assertThat(text).doesNotContain("FrameLayout")
        assertThat(text).contains("Send")
    }

    @Test
    fun `drops disabled elements`() {
        val snapshot = ScreenSnapshot(
            packageName = Fixtures.PKG_GMAIL,
            elements = listOf(
                Fixtures.element(1, "android.widget.Button", text = "Greyed", clickable = true, enabled = false),
            ),
        )
        assertThat(ScreenSerializer.serialize(snapshot)).doesNotContain("Greyed")
    }

    @Test
    fun `announces truncation so the model knows to scroll`() {
        // Silent truncation is the failure mode to avoid: the model would conclude the
        // element it needs does not exist rather than scrolling to find it.
        val many = (1..60).map {
            Fixtures.element(it, "android.widget.Button", text = "Item $it", clickable = true)
        }
        val text = ScreenSerializer.serialize(
            ScreenSnapshot(packageName = Fixtures.PKG_GMAIL, elements = many),
        )
        assertThat(text).contains("not shown")
        assertThat(text).contains("scroll")
    }

    @Test
    fun `respects the element budget`() {
        val many = (1..200).map {
            Fixtures.element(it, "android.widget.Button", text = "Item $it", clickable = true)
        }
        val lines = ScreenSerializer.serialize(
            ScreenSnapshot(packageName = Fixtures.PKG_GMAIL, elements = many),
            maxElements = 10,
        ).lines().filter { it.startsWith("[") }
        assertThat(lines).hasSize(10)
    }

    @Test
    fun `prioritises editable fields over plain clickables`() {
        // The agent is usually mid-form; a field it must fill outranks a nav button.
        val elements = (1..39).map {
            Fixtures.element(it, "android.widget.Button", text = "Btn $it", clickable = true)
        } + Fixtures.element(99, "android.widget.EditText", text = "Vehicle number", editable = true)

        val text = ScreenSerializer.serialize(
            ScreenSnapshot(packageName = Fixtures.PKG_GMAIL, elements = elements),
            maxElements = 5,
        )
        assertThat(text).contains("Vehicle number")
    }

    @Test
    fun `collapses whitespace and truncates long labels`() {
        val long = Fixtures.element(
            1, "android.widget.TextView",
            text = "line one\n\n   line two " + "x".repeat(200),
        )
        val line = ScreenSerializer.render(long)
        assertThat(line).doesNotContain("\n")
        assertThat(line.length).isLessThan(140)
    }

    @Test
    fun `an empty tree says so instead of returning nothing`() {
        // Canvas-rendered apps (doc Section 10). The model must be told the tree is
        // empty, not handed a blank screen it will hallucinate elements onto.
        val text = ScreenSerializer.serialize(Fixtures.emptyScreen())
        assertThat(text).contains("empty")
        assertThat(text).contains(Fixtures.PKG_PARKING)
    }
}
