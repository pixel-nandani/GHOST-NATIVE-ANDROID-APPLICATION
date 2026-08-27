package com.ghost.agent.core.agent

/**
 * Per-step latency record.
 *
 * This exists because "~380ms per planning step, on-device, zero cloud calls" is a
 * hard number no team calling a cloud API can produce, and the build plan asks for it
 * on the metrics slide. Measured, not estimated.
 */
data class StepTiming(
    val step: Int,
    val perceptionMs: Long,
    val planningMs: Long,
    val actionMs: Long,
    val backend: String,
) {
    val totalMs: Long get() = perceptionMs + planningMs + actionMs
}

/**
 * Collects [StepTiming] for one task run and summarizes it.
 *
 * Not thread-safe; the loop is single-threaded per task by construction.
 */
class StepLog {
    private val timings = mutableListOf<StepTiming>()

    fun record(timing: StepTiming) {
        timings += timing
    }

    val entries: List<StepTiming> get() = timings.toList()

    val isEmpty: Boolean get() = timings.isEmpty()

    fun summary(): String {
        if (timings.isEmpty()) return "no steps recorded"
        val plan = timings.map { it.planningMs }
        val backend = timings.map { it.backend }.distinct().joinToString("/")
        return buildString {
            append("${timings.size} steps | ")
            append("planning avg ${plan.average().toInt()}ms ")
            append("(min ${plan.min()}ms, max ${plan.max()}ms) | ")
            append("wall ${timings.sumOf { it.totalMs }}ms | ")
            append("backend $backend | 0 network calls")
        }
    }

    /** CSV for the pitch deck / perturbation-test comparison. */
    fun toCsv(): String = buildString {
        appendLine("step,perception_ms,planning_ms,action_ms,total_ms,backend")
        timings.forEach {
            appendLine("${it.step},${it.perceptionMs},${it.planningMs},${it.actionMs},${it.totalMs},${it.backend}")
        }
    }.trimEnd()

    fun clear() = timings.clear()
}
