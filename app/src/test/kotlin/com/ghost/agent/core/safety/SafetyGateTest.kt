package com.ghost.agent.core.safety

import com.ghost.agent.Fixtures
import com.ghost.agent.core.model.Action
import com.ghost.agent.core.model.ActionType
import com.ghost.agent.core.model.Bounds
import com.ghost.agent.core.model.ScreenSnapshot
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The safety layer is the one component where a false negative is a real-world harm --
 * an unconfirmed "Pay" tap -- and a false positive is a demo that trains the user to
 * dismiss prompts without reading them. Both directions are tested.
 */
class SafetyGateTest {

    private val gate = SafetyGate(SafetyPolicy.DEMO)

    // -------------------------------------------------------------- allow-list

    @Test
    fun `allows a package on the list`() {
        assertThat(gate.checkPackage(Fixtures.PKG_GMAIL)).isEqualTo(PackageVerdict.Allowed)
    }

    @Test
    fun `refuses a package off the list`() {
        val verdict = gate.checkPackage("com.some.bank")
        assertThat(verdict).isInstanceOf(PackageVerdict.Refused::class.java)
        assertThat((verdict as PackageVerdict.Refused).reason).contains("com.some.bank")
    }

    @Test
    fun `fails closed on a null package`() {
        // An unidentifiable foreground app must never be treated as permitted.
        assertThat(gate.checkPackage(null)).isInstanceOf(PackageVerdict.Refused::class.java)
    }

    @Test
    fun `fails closed on a blank package`() {
        assertThat(gate.checkPackage("   ")).isInstanceOf(PackageVerdict.Refused::class.java)
    }

    @Test
    fun `an empty allow-list permits nothing`() {
        val closed = SafetyGate(SafetyPolicy(allowedPackages = emptySet()))
        assertThat(closed.checkPackage(Fixtures.PKG_GMAIL))
            .isInstanceOf(PackageVerdict.Refused::class.java)
    }

    // --------------------------------------------------------------- step cap

    @Test
    fun `step cap is inclusive of the last step`() {
        assertThat(gate.isWithinStepCap(SafetyPolicy.DEMO.stepCap)).isTrue()
        assertThat(gate.isWithinStepCap(SafetyPolicy.DEMO.stepCap + 1)).isFalse()
    }

    // ------------------------------------------------- structural rejection

    @Test
    fun `rejects a hallucinated target_id`() {
        // Doc Section 10's named risk: the model references an id that isn't on screen.
        val verdict = gate.classify(
            Action(ActionType.TAP, targetId = 99),
            Fixtures.parkingHome(),
        )
        assertThat(verdict).isInstanceOf(RiskVerdict.Reject::class.java)
        assertThat((verdict as RiskVerdict.Reject).reason).contains("99")
    }

    @Test
    fun `rejects typing into a non-editable element`() {
        val verdict = gate.classify(
            Action(ActionType.TYPE, targetId = 1, value = "hello"), // [1] is a Button
            Fixtures.parkingHome(),
        )
        assertThat(verdict).isInstanceOf(RiskVerdict.Reject::class.java)
    }

    @Test
    fun `rejects a zero-size element`() {
        val snapshot = ScreenSnapshot(
            packageName = Fixtures.PKG_GMAIL,
            elements = listOf(
                Fixtures.element(
                    1, "android.widget.Button", text = "Ok",
                    clickable = true, bounds = Bounds(10, 10, 10, 10),
                ),
            ),
        )
        val verdict = gate.classify(Action(ActionType.TAP, targetId = 1), snapshot)
        assertThat(verdict).isInstanceOf(RiskVerdict.Reject::class.java)
    }

    @Test
    fun `rejects a disabled element`() {
        val snapshot = ScreenSnapshot(
            packageName = Fixtures.PKG_GMAIL,
            elements = listOf(
                Fixtures.element(1, "android.widget.Button", text = "Ok", clickable = true, enabled = false),
            ),
        )
        assertThat(gate.classify(Action(ActionType.TAP, targetId = 1), snapshot))
            .isInstanceOf(RiskVerdict.Reject::class.java)
    }

    @Test
    fun `rejects open_app for a package off the list`() {
        val verdict = gate.classify(
            Action(ActionType.OPEN_APP, value = "com.some.bank"),
            Fixtures.gmailCompose(),
        )
        assertThat(verdict).isInstanceOf(RiskVerdict.Reject::class.java)
    }

    @Test
    fun `allows open_app for a package on the list`() {
        assertThat(
            gate.classify(
                Action(ActionType.OPEN_APP, value = Fixtures.PKG_GMAIL),
                Fixtures.gmailCompose(),
            ),
        ).isEqualTo(RiskVerdict.Proceed)
    }

    // ------------------------------------------------ confirm-before-submit

    @Test
    fun `tapping Send needs confirmation`() {
        val verdict = gate.classify(Action(ActionType.TAP, targetId = 5), Fixtures.gmailCompose())
        assertThat(verdict).isInstanceOf(RiskVerdict.NeedsConfirmation::class.java)
        assertThat((verdict as RiskVerdict.NeedsConfirmation).matchedKeyword).isEqualTo("send")
    }

    @Test
    fun `tapping Submit payment needs confirmation`() {
        val verdict = gate.classify(Action(ActionType.TAP, targetId = 2), Fixtures.parkingForm())
        assertThat(verdict).isInstanceOf(RiskVerdict.NeedsConfirmation::class.java)
    }

    @Test
    fun `the prompt names the control the user will be approving`() {
        val verdict = gate.classify(
            Action(ActionType.TAP, targetId = 2), Fixtures.parkingForm(),
        ) as RiskVerdict.NeedsConfirmation
        assertThat(verdict.prompt).contains("Submit payment")
    }

    @Test
    fun `tapping Renew Now does not need confirmation`() {
        assertThat(gate.classify(Action(ActionType.TAP, targetId = 1), Fixtures.parkingHome()))
            .isEqualTo(RiskVerdict.Proceed)
    }

    @Test
    fun `the word Sender does not trigger a Send confirmation`() {
        // The word-boundary case. A naive contains("send") fires on every email list row
        // and turns the checkpoint into noise users learn to tap through -- which costs
        // more safety than it buys.
        assertThat(gate.classify(Action(ActionType.TAP, targetId = 4), Fixtures.gmailCompose()))
            .isEqualTo(RiskVerdict.Proceed)
    }

    @Test
    fun `typing the word delete is not a destructive act`() {
        // Only committing gestures are scanned; typing "delete my account" into a search
        // box must not prompt.
        assertThat(
            gate.classify(
                Action(ActionType.TYPE, targetId = 2, value = "delete and submit everything"),
                Fixtures.gmailCompose(),
            ),
        ).isEqualTo(RiskVerdict.Proceed)
    }

    @Test
    fun `scrolling never needs confirmation`() {
        assertThat(gate.classify(Action(ActionType.SCROLL), Fixtures.gmailCompose()))
            .isEqualTo(RiskVerdict.Proceed)
    }

    @Test
    fun `confirmation can be disabled for automated stress runs`() {
        val unattended = SafetyGate(SafetyPolicy.DEMO.copy(requireConfirmForRisky = false))
        assertThat(unattended.classify(Action(ActionType.TAP, targetId = 5), Fixtures.gmailCompose()))
            .isEqualTo(RiskVerdict.Proceed)
    }

    @Test
    fun `structural rejection beats risk classification`() {
        // A hallucinated id on a risky-sounding action must be rejected outright, never
        // surfaced to the user as a prompt about an element that does not exist.
        val verdict = SafetyGate(SafetyPolicy.DEMO)
            .classify(Action(ActionType.TAP, targetId = 404), Fixtures.gmailCompose())
        assertThat(verdict).isInstanceOf(RiskVerdict.Reject::class.java)
    }
}
