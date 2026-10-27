package com.agentforge.controlplane.recovery;

import com.agentforge.controlplane.domain.FailureCase;
import org.springframework.stereotype.Component;

@Component
public class RecoveryStateMachine {
    public void start(FailureCase item, boolean administratorForce) {
        if (item.getStatus() == FailureCase.Status.RECOVERED) {
            throw new IllegalStateException("已重拾成功的 Case 不可再次重拾");
        }
        if (item.getStatus() == FailureCase.Status.RECOVERING) {
            throw new IllegalStateException("Case 正在重拾");
        }
        if (item.getStatus() == FailureCase.Status.MANUAL_REQUIRED && !administratorForce) {
            throw new IllegalStateException("该 Case 需要管理员确认风险");
        }
        if (item.getStatus() != FailureCase.Status.RESUMABLE
                && item.getStatus() != FailureCase.Status.MANUAL_REQUIRED) {
            throw new IllegalStateException("Case 当前状态不可重拾");
        }
        item.setStatus(FailureCase.Status.RECOVERING);
    }

    public void succeed(FailureCase item, Long executionId, Long attemptId) {
        requireRecovering(item);
        item.setStatus(FailureCase.Status.RECOVERED);
        item.setResumable(false);
        item.setRecoveredExecutionId(executionId);
        item.setRecoveredAttemptId(attemptId);
    }

    public void fail(FailureCase item, boolean hasSafeCheckpoint, String category, String summary) {
        requireRecovering(item);
        item.setFailureCategory(category);
        item.setErrorSummary(summary);
        item.setResumable(hasSafeCheckpoint);
        item.setStatus(hasSafeCheckpoint ? FailureCase.Status.RESUMABLE : FailureCase.Status.MANUAL_REQUIRED);
    }

    private static void requireRecovering(FailureCase item) {
        if (item.getStatus() != FailureCase.Status.RECOVERING) {
            throw new IllegalStateException("Case 不在重拾状态");
        }
    }
}
