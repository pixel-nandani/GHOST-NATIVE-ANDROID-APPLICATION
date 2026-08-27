package com.ghost.agent.core.planning

import com.ghost.agent.core.model.ActionType
import com.ghost.agent.core.model.Direction
import com.ghost.agent.core.model.ParsedAction
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The "schema parser unit tests" from the testing checklist.
 *
 * Every malformed input here is a real failure mode of small quantized models, not a
 * hypothetical. The contract being tested is: **never throw, always either produce a
 * valid action or an Invalid carrying a hint good enough to retry with.** A crash in
 * this layer takes down the accessibility service and forces the user to re-toggle the
 * permission by hand -- which on demo day means the demo is over.
 */
class ActionParserTest {

    private fun ok(raw: String) = ActionParser.parse(raw).let {
        assertThat(it).isInstanceOf(ParsedAction.Ok::class.java)
        (it as ParsedAction.Ok).action
    }

    private fun invalid(raw: String) = ActionParser.parse(raw).let {
        assertThat(it).isInstanceOf(ParsedAction.Invalid::class.java)
        it as ParsedAction.Invalid
    }

    // ------------------------------------------------------------- happy path

    @Test
    fun `parses the documented tap shape`() {
        val action = ok("""{"action":"tap","target_id":1,"value":null,"done":false}""")
        assertThat(action.type).isEqualTo(ActionType.TAP)
        assertThat(action.targetId).isEqualTo(1)
        assertThat(action.value).isNull()
        assertThat(action.done).isFalse()
    }

    @Test
    fun `parses the documented type shape`() {
        val action = ok("""{"action":"type","target_id":2,"value":"MH31AB1234","done":false}""")
        assertThat(action.type).isEqualTo(ActionType.TYPE)
        assertThat(action.value).isEqualTo("MH31AB1234")
    }

    @Test
    fun `done true with a final tap is honored`() {
        val action = ok("""{"action":"tap","target_id":1,"value":null,"done":true}""")
        assertThat(action.done).isTrue()
        assertThat(action.type).isEqualTo(ActionType.TAP)
    }

    @Test
    fun `bare done true with no action ends the task`() {
        val action = ok("""{"done":true}""")
        assertThat(action.done).isTrue()
    }

    // -------------------------------------------------- real-world sloppiness

    @Test
    fun `strips markdown fences`() {
        val action = ok(
            """
            ```json
            {"action":"tap","target_id":3}
            ```
            """.trimIndent(),
        )
        assertThat(action.targetId).isEqualTo(3)
    }

    @Test
    fun `ignores prose before and after the object`() {
        val action = ok(
            "Sure! I'll tap the Renew button.\n" +
                "{\"action\":\"tap\",\"target_id\":1}\n" +
                "Let me know if that worked.",
        )
        assertThat(action.type).isEqualTo(ActionType.TAP)
    }

    @Test
    fun `accepts a quoted integer target_id`() {
        // Small models quote numbers often enough that rejecting this would burn a
        // retry -- and ~400ms of demo time -- on a purely cosmetic problem.
        val action = ok("""{"action":"tap","target_id":"7"}""")
        assertThat(action.targetId).isEqualTo(7)
    }

    @Test
    fun `accepts a float target_id`() {
        val action = ok("""{"action":"tap","target_id":2.0}""")
        assertThat(action.targetId).isEqualTo(2)
    }

    @Test
    fun `ignores unknown keys the model volunteers`() {
        val action = ok(
            """{"action":"tap","target_id":1,"confidence":0.92,"explanation":"it's the button"}""",
        )
        assertThat(action.targetId).isEqualTo(1)
    }

    @Test
    fun `takes only the first object when the model emits a plan`() {
        // The single most common instruction-following failure: it plans ahead anyway.
        val action = ok(
            """{"action":"tap","target_id":1} {"action":"type","target_id":2,"value":"x"}""",
        )
        assertThat(action.type).isEqualTo(ActionType.TAP)
        assertThat(action.targetId).isEqualTo(1)
    }

    @Test
    fun `braces inside string values do not confuse extraction`() {
        val action = ok("""{"action":"type","target_id":2,"value":"literal {braces} here"}""")
        assertThat(action.value).isEqualTo("literal {braces} here")
    }

    @Test
    fun `escaped quotes inside values survive`() {
        val action = ok("""{"action":"type","target_id":2,"value":"say \"hi\""}""")
        assertThat(action.value).isEqualTo("""say "hi"""")
    }

    @Test
    fun `direction defaults to down for scroll`() {
        val action = ok("""{"action":"scroll"}""")
        assertThat(action.direction).isEqualTo(Direction.DOWN)
    }

    @Test
    fun `explicit direction is respected`() {
        val action = ok("""{"action":"swipe","direction":"left"}""")
        assertThat(action.direction).isEqualTo(Direction.LEFT)
    }

    // ------------------------------------------------------- must fail safely

    @Test
    fun `plain prose is invalid, not a crash`() {
        val result = invalid("I think you should tap the Renew Now button next.")
        assertThat(result.reason).contains("no JSON object")
        assertThat(result.repairHint).isNotEmpty()
    }

    @Test
    fun `empty output is invalid, not a crash`() {
        // This is what a failed inference returns from MediaPipeLlmEngine.
        assertThat(ActionParser.parse("")).isInstanceOf(ParsedAction.Invalid::class.java)
    }

    @Test
    fun `truncated json is invalid, not a crash`() {
        val result = invalid("""{"action":"tap","target_id":""")
        assertThat(result.repairHint).isNotEmpty()
    }

    @Test
    fun `unknown action is rejected with the legal vocabulary in the hint`() {
        val result = invalid("""{"action":"teleport","target_id":1}""")
        assertThat(result.reason).contains("teleport")
        assertThat(result.repairHint).contains("tap")
    }

    @Test
    fun `tap without target_id is rejected`() {
        assertThat(invalid("""{"action":"tap"}""").reason).contains("target_id")
    }

    @Test
    fun `type without value is rejected`() {
        assertThat(invalid("""{"action":"type","target_id":2}""").reason).contains("value")
    }

    @Test
    fun `type with an empty value is rejected`() {
        assertThat(invalid("""{"action":"type","target_id":2,"value":""}""").reason).contains("value")
    }

    @Test
    fun `open_app without a package is rejected`() {
        assertThat(invalid("""{"action":"open_app"}""").reason).contains("package")
    }

    @Test
    fun `invalid results never leak an unbounded raw string`() {
        // Guards the log/UI path: a runaway model can emit tens of thousands of tokens.
        val result = invalid("x".repeat(50_000))
        assertThat(result.raw.length).isAtMost(400)
    }
}
