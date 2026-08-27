package com.ghost.agent.core.planning

import com.ghost.agent.llm.LlmEngine

/**
 * The real planner: prompt the on-device model, parse one action out of whatever it
 * says back.
 *
 * Note how thin this is. All the hard-won behaviour lives in [PromptBuilder] (what the
 * model sees) and [ActionParser] (surviving what it emits), both of which are pure and
 * unit-tested. This class only sequences them and measures the latency, which is
 * exactly why the model can be swapped without retesting the agent.
 */
class LlmPlanner(
    private val engine: LlmEngine,
    private val clock: () -> Long = System::currentTimeMillis,
) : Planner {

    override val name: String get() = engine.describe

    override val isReady: Boolean get() = engine.isReady

    override suspend fun plan(request: PlanRequest): PlanResult {
        val prompt = PromptBuilder.build(request)

        val started = clock()
        val raw = engine.generate(prompt)
        val latencyMs = clock() - started

        return PlanResult(
            parsed = ActionParser.parse(raw),
            latencyMs = latencyMs,
            backend = engine.backend,
            rawOutput = raw,
        )
    }

    override fun close() = engine.close()
}
