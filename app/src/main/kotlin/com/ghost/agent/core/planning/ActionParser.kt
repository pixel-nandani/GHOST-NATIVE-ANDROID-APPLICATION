package com.ghost.agent.core.planning

import com.ghost.agent.core.model.Action
import com.ghost.agent.core.model.ActionType
import com.ghost.agent.core.model.Direction
import com.ghost.agent.core.model.ParsedAction
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Turns raw model text into a validated [Action], or a [ParsedAction.Invalid] that
 * explains itself well enough for the loop to retry.
 *
 * Small quantized models do not emit clean JSON reliably. They wrap it in prose, fence
 * it in markdown, quote their integers, emit two objects when asked for one, and get
 * cut off mid-token. This parser assumes all of that will happen during the demo, and
 * that **crashing is never an acceptable response to any of it** -- a throw here kills
 * the accessibility service and forces the user to re-toggle the OS permission by hand.
 *
 * Uses kotlinx.serialization rather than `org.json` specifically so these paths are
 * covered by plain JVM unit tests; `org.json` is stubbed out in local unit tests.
 */
object ActionParser {

    private val json = Json {
        isLenient = true          // unquoted keys, single quotes
        ignoreUnknownKeys = true  // model volunteers "explanation", "confidence", ...
        coerceInputValues = true  // null -> default instead of throwing
    }

    @Serializable
    private data class ActionDto(
        val action: String? = null,
        @SerialName("target_id") val targetId: JsonLooseInt? = null,
        val value: String? = null,
        val direction: String? = null,
        val done: Boolean = false,
        val reason: String? = null,
    )

    fun parse(raw: String): ParsedAction {
        val candidates = extractJsonObjects(raw)
        if (candidates.isEmpty()) {
            return invalid(
                reason = "no JSON object found in model output",
                raw = raw,
                hint = "Reply with ONLY a JSON object. No prose, no markdown fence.",
            )
        }

        // Decode every balanced candidate and prefer the first that actually looks like
        // a decision. This is what lets a stray `{...}` in the model's prose, or a
        // leading `{"thought": "..."}` block, be skipped instead of derailing the step.
        var firstDecoded: ActionDto? = null
        var chosen: ActionDto? = null

        for (candidate in candidates) {
            val dto = try {
                json.decodeFromString<ActionDto>(candidate)
            } catch (_: Exception) {
                continue
            }
            if (firstDecoded == null) firstDecoded = dto
            if (ActionType.fromWire(dto.action) != null || dto.done) {
                chosen = dto
                break
            }
        }

        val dto = chosen ?: firstDecoded ?: return invalid(
            reason = "no parseable JSON object in model output",
            raw = raw,
            hint = "Your JSON did not parse. Emit exactly: " +
                "{\"action\":\"tap\",\"target_id\":1,\"value\":null,\"done\":false}",
        )

        return validate(dto, raw)
    }

    private fun validate(dto: ActionDto, raw: String): ParsedAction {
        val type = ActionType.fromWire(dto.action)
        if (type == null) {
            // `done: true` with no action is the legal way to end a task.
            if (dto.done) {
                return ParsedAction.Ok(Action(type = ActionType.WAIT, done = true, reason = dto.reason))
            }
            return invalid(
                reason = "unknown action '${dto.action}'",
                raw = raw,
                hint = "\"action\" must be one of ${ActionType.wireNames.joinToString("|")}.",
            )
        }

        val targetId = dto.targetId?.value

        if (type == ActionType.TAP && targetId == null) {
            return invalid(
                reason = "tap without target_id",
                raw = raw,
                hint = "A \"tap\" needs \"target_id\" set to a number from the element list.",
            )
        }

        if (type == ActionType.TYPE) {
            if (targetId == null) {
                return invalid(
                    reason = "type without target_id",
                    raw = raw,
                    hint = "A \"type\" needs \"target_id\" of an `editable` element.",
                )
            }
            if (dto.value.isNullOrEmpty()) {
                return invalid(
                    reason = "type without value",
                    raw = raw,
                    hint = "A \"type\" needs a non-empty \"value\" string to enter.",
                )
            }
        }

        if (type == ActionType.OPEN_APP && dto.value.isNullOrBlank()) {
            return invalid(
                reason = "open_app without package name",
                raw = raw,
                hint = "An \"open_app\" needs \"value\" set to the target package name.",
            )
        }

        return ParsedAction.Ok(
            Action(
                type = type,
                targetId = targetId,
                value = dto.value?.takeIf { it.isNotEmpty() },
                direction = Direction.fromWire(dto.direction) ?: defaultDirection(type),
                done = dto.done,
                reason = dto.reason?.trim()?.takeIf { it.isNotEmpty() },
            ),
        )
    }

    private fun defaultDirection(type: ActionType): Direction? =
        if (type == ActionType.SCROLL || type == ActionType.SWIPE) Direction.DOWN else null

    private fun invalid(reason: String, raw: String, hint: String) =
        ParsedAction.Invalid(reason = reason, raw = raw.take(MAX_RAW_CHARS), repairHint = hint)

    /** First balanced `{...}` in [raw], or null. Kept for callers that want just one. */
    fun extractJsonObject(raw: String): String? = extractJsonObjects(raw).firstOrNull()

    /**
     * Every balanced `{...}` region in [raw], outermost-first.
     *
     * Brace counting rather than a regex, because it must survive braces inside string
     * values (`{"value":"see {here}"}`) and escaped quotes. Strings are tracked so
     * braces inside them never affect depth. Markdown fences need no special handling:
     * they simply are not braces.
     */
    fun extractJsonObjects(raw: String, limit: Int = MAX_CANDIDATES): List<String> {
        val found = mutableListOf<String>()
        var searchFrom = 0

        while (found.size < limit) {
            val start = raw.indexOf('{', searchFrom)
            if (start < 0) break

            val end = matchingBrace(raw, start)
            if (end == null) {
                // Unbalanced from here on (model was cut off mid-emit). Any later '{'
                // is nested inside this one, so there is nothing more to find.
                break
            }
            found += raw.substring(start, end + 1)
            searchFrom = end + 1
        }
        return found
    }

    /** Index of the `}` closing the `{` at [start], or null if unbalanced. */
    private fun matchingBrace(text: String, start: Int): Int? {
        var depth = 0
        var inString = false
        var escaped = false

        for (i in start until text.length) {
            val c = text[i]
            when {
                escaped -> escaped = false
                c == '\\' && inString -> escaped = true
                c == '"' -> inString = !inString
                inString -> Unit
                c == '{' -> depth++
                c == '}' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return null
    }

    /** Cap on the raw text retained in an Invalid, so a runaway model cannot flood logs. */
    private const val MAX_RAW_CHARS = 400

    /** Cap on candidate objects scanned. Two is normal; more means the model is confused. */
    private const val MAX_CANDIDATES = 6
}

/**
 * Accepts `1`, `"1"` and `1.0` for `target_id`.
 *
 * Small models quote numbers roughly a third of the time. Rejecting those outputs would
 * burn a retry -- and a few hundred ms of demo time -- on a purely cosmetic problem.
 */
@Serializable(with = JsonLooseIntSerializer::class)
data class JsonLooseInt(val value: Int?)

private object JsonLooseIntSerializer : kotlinx.serialization.KSerializer<JsonLooseInt> {
    override val descriptor = kotlinx.serialization.descriptors.PrimitiveSerialDescriptor(
        "JsonLooseInt",
        kotlinx.serialization.descriptors.PrimitiveKind.STRING,
    )

    override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder): JsonLooseInt {
        // Safe for both `1` and `"1"` because the Json instance is lenient.
        val rawValue = decoder.decodeString().trim()
        return JsonLooseInt(rawValue.toIntOrNull() ?: rawValue.toDoubleOrNull()?.toInt())
    }

    override fun serialize(encoder: kotlinx.serialization.encoding.Encoder, value: JsonLooseInt) {
        encoder.encodeString(value.value?.toString() ?: "null")
    }
}
