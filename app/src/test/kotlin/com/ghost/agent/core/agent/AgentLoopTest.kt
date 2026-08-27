package com.ghost.agent.core.agent

import com.ghost.agent.Fixtures
import com.ghost.agent.core.model.ActionType
import com.ghost.agent.core.model.AgentEvent
import com.ghost.agent.core.model.TaskOutcome
import com.ghost.agent.core.safety.SafetyGate
import com.ghost.agent.core.safety.SafetyPolicy
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * End-to-end tests for the agent loop, on the JVM, with no emulator and no model.
 *
 * This is the payoff for keeping the core Android-free: the control flow that is hardest
 * to verify by hand on a phone -- step caps, hallucinated ids, declined confirmations,
 * failure streaks, mid-task app drift -- is all covered here in milliseconds. Every one
 * of these cases is a way the live demo can go wrong.
 */
class AgentLoopTest {

    private val policy = SafetyPolicy.DEMO

    private fun loop(
        device: FakeDevice,
        planner: ScriptedPlanner,
        confirmer: Confirmer = AutoApprove,
        policy: SafetyPolicy = this.policy,
        events: MutableList<AgentEvent> = mutableListOf(),
    ) = AgentLoop(
        device = device,
        planner = planner,
        gate = SafetyGate(policy),
        confirmer = confirmer,
        clock = { 0L },
        onEvent = { events += it },
    )

    // ------------------------------------------------------------- happy path

    @Test
    fun `completes when the model reports done`() = runTest {
        val device = FakeDevice(listOf(Fixtures.parkingHome(), Fixtures.parkingHome()))
        val planner = ScriptedPlanner(
            listOf(
                """{"action":"type","target_id":2,"value":"MH31AB1234"}""",
                """{"action":"tap","target_id":1,"done":true}""",
            ),
        )

        val outcome = loop(device, planner).run("renew my parking pass")

        assertThat(outcome).isEqualTo(TaskOutcome.Completed)
        assertThat(device.performed.map { it.type })
            .containsExactly(ActionType.TYPE, ActionType.TAP)
    }

    @Test
    fun `re-perceives before every action`() = runTest {
        // The core reliability property: ids are never reused across turns.
        val device = FakeDevice(listOf(Fixtures.parkingHome(), Fixtures.parkingHome()))
        val events = mutableListOf<AgentEvent>()
        val planner = ScriptedPlanner(
            listOf(
                """{"action":"tap","target_id":1}""",
                """{"action":"tap","target_id":1,"done":true}""",
            ),
        )

        loop(device, planner, events = events).run("goal")

        assertThat(events.filterIsInstance<AgentEvent.Perceived>()).hasSize(2)
    }

    @Test
    fun `records a latency measurement per step`() = runTest {
        val device = FakeDevice(listOf(Fixtures.parkingHome()))
        val planner = ScriptedPlanner(listOf("""{"action":"tap","target_id":1,"done":true}"""))
        val loop = loop(device, planner)

        loop.run("goal")

        assertThat(loop.timings.entries).hasSize(1)
        assertThat(loop.timings.summary()).contains("0 network calls")
    }

    // ------------------------------------------------------------ safety layer

    @Test
    fun `stops at the step cap instead of running forever`() = runTest {
        val device = FakeDevice(listOf(Fixtures.parkingHome()))
        // Never emits done -- exactly what a confused model does.
        val planner = ScriptedPlanner(listOf("""{"action":"scroll"}"""))

        val outcome = loop(device, planner, policy = policy.copy(stepCap = 4)).run("goal")

        assertThat(outcome).isEqualTo(TaskOutcome.StepCapReached(4))
        assertThat(device.performed).hasSize(4)
    }

    @Test
    fun `blocks immediately in an app off the allow-list`() = runTest {
        val device = FakeDevice(listOf(Fixtures.emptyScreen("com.some.bank")))
        val planner = ScriptedPlanner(listOf("""{"action":"tap","target_id":1}"""))

        val outcome = loop(device, planner).run("goal")

        assertThat(outcome).isInstanceOf(TaskOutcome.Blocked::class.java)
        assertThat(device.performed).isEmpty()
    }

    @Test
    fun `blocks on drifting into a disallowed app mid-task`() = runTest {
        // Why the allow-list is checked every turn rather than once at task start.
        val device = FakeDevice(
            listOf(Fixtures.parkingHome(), Fixtures.emptyScreen("com.some.bank")),
        )
        val planner = ScriptedPlanner(listOf("""{"action":"tap","target_id":1}"""))

        val outcome = loop(device, planner).run("goal")

        assertThat(outcome).isInstanceOf(TaskOutcome.Blocked::class.java)
        assertThat(device.performed).hasSize(1)
    }

    @Test
    fun `pauses for confirmation before tapping Send`() = runTest {
        val device = FakeDevice(listOf(Fixtures.gmailCompose(), Fixtures.gmailCompose()))
        val events = mutableListOf<AgentEvent>()
        val planner = ScriptedPlanner(listOf("""{"action":"tap","target_id":5,"done":true}"""))

        loop(device, planner, confirmer = AutoApprove, events = events).run("email accounts")

        assertThat(events.filterIsInstance<AgentEvent.AwaitingConfirmation>()).hasSize(1)
        assertThat(device.performed).hasSize(1)
    }

    @Test
    fun `a declined confirmation cancels without performing the action`() = runTest {
        val device = FakeDevice(listOf(Fixtures.gmailCompose()))
        val planner = ScriptedPlanner(listOf("""{"action":"tap","target_id":5}"""))

        val outcome = loop(device, planner, confirmer = AutoDecline).run("email accounts")

        assertThat(outcome).isInstanceOf(TaskOutcome.Cancelled::class.java)
        assertThat(device.performed).isEmpty() // the whole point
    }

    @Test
    fun `a safe action is never gated`() = runTest {
        val device = FakeDevice(listOf(Fixtures.parkingHome()))
        val events = mutableListOf<AgentEvent>()
        val planner = ScriptedPlanner(listOf("""{"action":"tap","target_id":1,"done":true}"""))

        loop(device, planner, events = events).run("renew")

        assertThat(events.filterIsInstance<AgentEvent.AwaitingConfirmation>()).isEmpty()
    }

    // -------------------------------------------------------------- recovery

    @Test
    fun `retries with a repair hint when the model emits prose`() = runTest {
        val device = FakeDevice(listOf(Fixtures.parkingHome()))
        val planner = ScriptedPlanner(
            listOf(
                "I'll tap the renew button for you!",
                """{"action":"tap","target_id":1,"done":true}""",
            ),
        )
        val events = mutableListOf<AgentEvent>()

        val outcome = loop(device, planner, events = events).run("renew")

        assertThat(outcome).isEqualTo(TaskOutcome.Completed)
        assertThat(events.filterIsInstance<AgentEvent.PlannerRecovered>()).hasSize(1)
        assertThat(planner.prompts).isNotEmpty() // a hint was actually fed back
    }

    @Test
    fun `gives up cleanly when the model never emits valid JSON`() = runTest {
        val device = FakeDevice(listOf(Fixtures.parkingHome()))
        val planner = ScriptedPlanner(listOf("nope, still prose"))

        val outcome = loop(device, planner).run("renew")

        assertThat(outcome).isInstanceOf(TaskOutcome.Failed::class.java)
        assertThat(device.performed).isEmpty()
    }

    @Test
    fun `survives a hallucinated target_id by re-perceiving`() = runTest {
        // Doc Section 10's named risk. The step is spent, but the task continues.
        val device = FakeDevice(List(3) { Fixtures.parkingHome() })
        val planner = ScriptedPlanner(
            listOf(
                """{"action":"tap","target_id":404}""",
                """{"action":"tap","target_id":1,"done":true}""",
            ),
        )

        val outcome = loop(device, planner).run("renew")

        assertThat(outcome).isEqualTo(TaskOutcome.Completed)
        assertThat(device.performed).hasSize(1) // the bogus tap was never dispatched
    }

    @Test
    fun `aborts after a streak of invalid actions`() = runTest {
        val device = FakeDevice(List(10) { Fixtures.parkingHome() })
        val planner = ScriptedPlanner(listOf("""{"action":"tap","target_id":404}"""))

        val outcome = loop(device, planner).run("renew")

        assertThat(outcome).isInstanceOf(TaskOutcome.Failed::class.java)
        assertThat((outcome as TaskOutcome.Failed).reason).contains("in a row")
    }

    @Test
    fun `aborts after a streak of failed dispatches`() = runTest {
        val device = FakeDevice(
            script = List(10) { Fixtures.parkingHome() },
            failActions = setOf(1, 2, 3),
        )
        val planner = ScriptedPlanner(listOf("""{"action":"tap","target_id":1}"""))

        val outcome = loop(device, planner, policy = policy.copy(maxConsecutiveFailures = 3))
            .run("renew")

        assertThat(outcome).isInstanceOf(TaskOutcome.Failed::class.java)
    }

    @Test
    fun `fails cleanly when no window content is available`() = runTest {
        val device = FakeDevice(listOf(null))
        val planner = ScriptedPlanner(listOf("""{"action":"tap","target_id":1}"""))

        assertThat(loop(device, planner).run("goal"))
            .isInstanceOf(TaskOutcome.Failed::class.java)
    }

    @Test
    fun `waits once then gives up on a screen with no accessibility nodes`() = runTest {
        // A canvas-rendered app. Must not spin on a tree that will never populate.
        val device = FakeDevice(List(5) { Fixtures.emptyScreen() })
        val planner = ScriptedPlanner(listOf("""{"action":"tap","target_id":1}"""))

        val outcome = loop(device, planner).run("goal")

        assertThat(outcome).isInstanceOf(TaskOutcome.Failed::class.java)
        assertThat((outcome as TaskOutcome.Failed).reason).contains("accessibility nodes")
        assertThat(planner.callCount).isEqualTo(0) // never wasted an inference on it
    }

    // ------------------------------------------------------------ kill switch

    @Test
    fun `kill switch during a confirmation cancels without acting`() = runTest {
        // The worst possible moment to be interrupted: the confirm dialog for "Send" is
        // up and the user hits STOP. Nothing may be dispatched, and the loop must report
        // Cancelled before letting the cancellation propagate.
        val device = FakeDevice(listOf(Fixtures.gmailCompose()))
        val planner = ScriptedPlanner(listOf("""{"action":"tap","target_id":5}"""))
        val events = mutableListOf<AgentEvent>()
        val killSwitch = object : Confirmer {
            override suspend fun confirm(prompt: String): Boolean =
                throw CancellationException("stopped by kill switch")
        }

        var cancelled = false
        try {
            loop(device, planner, confirmer = killSwitch, events = events).run("email accounts")
        } catch (_: CancellationException) {
            cancelled = true
        }

        assertThat(cancelled).isTrue()
        assertThat(device.performed).isEmpty()
        val finished = events.filterIsInstance<AgentEvent.Finished>().last()
        assertThat(finished.outcome).isInstanceOf(TaskOutcome.Cancelled::class.java)
    }

    // ----------------------------------------------------------------- events

    @Test
    fun `emits a coherent event sequence for one step`() = runTest {
        val device = FakeDevice(listOf(Fixtures.parkingHome()))
        val planner = ScriptedPlanner(listOf("""{"action":"tap","target_id":1,"done":true}"""))
        val events = mutableListOf<AgentEvent>()

        loop(device, planner, events = events).run("renew")

        val kinds = events.map { it::class.simpleName }
        assertThat(kinds).containsAtLeast(
            "TaskStarted", "Perceived", "Planned", "Acting", "ActionCompleted", "Finished",
        ).inOrder()
    }

    @Test
    fun `the UI state reducer tracks a whole run without an emulator`() = runTest {
        val device = FakeDevice(listOf(Fixtures.gmailCompose()))
        val planner = ScriptedPlanner(listOf("""{"action":"tap","target_id":5,"done":true}"""))
        val captured = mutableListOf<AgentEvent>()

        loop(device, planner, events = captured).run("email accounts")

        // Fold the real event stream through the same reducer the overlay and the screen
        // use, so a state-machine regression fails here rather than on stage.
        val state = captured.fold(GhostState()) { acc, event -> acc.reduce(event) }

        assertThat(state.phase).isEqualTo(GhostPhase.FINISHED)
        assertThat(state.outcome).isEqualTo(TaskOutcome.Completed)
        assertThat(state.transcript.first()).contains("email accounts")
        assertThat(state.isRunning).isFalse()
    }
}
