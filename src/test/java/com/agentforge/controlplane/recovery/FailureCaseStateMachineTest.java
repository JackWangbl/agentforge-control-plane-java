package com.agentforge.controlplane.recovery;

import com.agentforge.controlplane.domain.FailureCase;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FailureCaseStateMachineTest {
    private final RecoveryStateMachine machine = new RecoveryStateMachine();

    @Test
    void recoveredIsIrreversible() {
        FailureCase item = new FailureCase();
        item.setStatus(FailureCase.Status.RECOVERED);
        assertThatThrownBy(() -> machine.start(item, false)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void failedAttemptReturnsToResumableOnlyWithSafeCheckpoint() {
        FailureCase item = new FailureCase();
        item.setStatus(FailureCase.Status.RECOVERING);
        machine.fail(item, true, "UPSTREAM", "temporary");
        assertThat(item.getStatus()).isEqualTo(FailureCase.Status.RESUMABLE);
        item.setStatus(FailureCase.Status.RECOVERING);
        machine.fail(item, false, "SIDE_EFFECT_UNKNOWN", "manual review");
        assertThat(item.getStatus()).isEqualTo(FailureCase.Status.MANUAL_REQUIRED);
    }

    @Test
    void manualRequiredNeedsAdministratorForce() {
        FailureCase item = new FailureCase();
        item.setStatus(FailureCase.Status.MANUAL_REQUIRED);
        assertThatThrownBy(() -> machine.start(item, false)).isInstanceOf(IllegalStateException.class);
        machine.start(item, true);
        assertThat(item.getStatus()).isEqualTo(FailureCase.Status.RECOVERING);
    }
}
