package com.ghost.agent.core.planning

import com.ghost.agent.Fixtures
import com.ghost.agent.core.model.Action
import com.ghost.agent.core.model.ActionType
import com.ghost.agent.core.model.ParsedAction
import com.ghost.agent.core.model.ScreenSnapshot
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Tests for the model-free fallback planner.
 *
 * These pin down what the fallback *can* do, so nobody mistakes it for the real planner
 * mid-demo. It matches goal keywords against element labels. It has no notion of intent
 * or ordering, and these tests say so out loud.
 */
class HeuristicPlannerTest {

    private val planner = HeuristicPlanner()

    private suspend fun decide(
        goal: String,
        snapshot: ScreenSnapshot,
        history: List<HistoryEntry> = emptyList(),
    ): Action {
        val result = planner.plan(
            PlanRequest(goal, snapshot, history, stepNumber = 1, stepCap = 15),
        )
        assertThat(result.parsed).isInstanceOf(ParsedAction.Ok::class.java)
        return (result.parsed as ParsedAction.Ok).action
    }

    @Test
    fun `taps the button whose label appears in the goal`() = runTest {
        val action = decide("Renew my parking pass", Fixtures.parkingHome())
        assertThat(action.type).isEqualTo(ActionType.TAP)
        assertThat(action.targetId).isEqualTo(1) // "Renew Now"
    }

    @Test
    fun `fills a recipient field from an email address in the goal`() = runTest {
        val action = decide(
            "Email the receipt to accounts@company.com",
            Fixtures.gmailCompose(),
        )
        assertThat(action.type).isEqualTo(ActionType.TYPE)
        assertThat(action.value).isEqualTo("accounts@company.com")
    }

    @Test
    fun `does not re-tap something it already tapped`() = runTest {
        val history = listOf(
            HistoryEntry(
                step = 1,
                action = Action(ActionType.TAP, targetId = 1),
                targetLabel = "Renew Now",
                succeeded = true,
            ),
        )
        val action = decide("Renew my parking pass", Fixtures.parkingHome(), history)
        assertThat(action.targetId).isNotEqualTo(1)
    }

    @Test
    fun `scrolls to look further when nothing on screen matches`() = runTest {
        val snapshot = ScreenSnapshot(
            packageName = Fixtures.PKG_GMAIL,
            elements = listOf(
                Fixtures.element(1, "android.widget.ScrollView", text = "list", scrollable = true),
                Fixtures.element(2, "android.widget.Button", text = "Unrelated", clickable = true),
            ),
        )
        val action = decide("find the quarterly invoice", snapshot)
        assertThat(action.type).isEqualTo(ActionType.SCROLL)
    }

    @Test
    fun `stops cleanly instead of flailing when it has no idea`() = runTest {
        // Honest failure. The real planner would reason about this screen; the fallback
        // cannot, and saying "done" beats tapping something arbitrary.
        val snapshot = ScreenSnapshot(
            packageName = Fixtures.PKG_GMAIL,
            elements = listOf(Fixtures.element(1, "android.widget.Button", text = "Zzz", clickable = true)),
        )
        val action = decide("do something completely unrelated", snapshot)
        assertThat(action.done).isTrue()
    }

    @Test
    fun `reports the heuristic backend so the fallback is never silent`() = runTest {
        val result = planner.plan(
            PlanRequest("goal", Fixtures.parkingHome(), emptyList(), 1, 15),
        )
        assertThat(result.backend).isEqualTo("heuristic")
    }
}
