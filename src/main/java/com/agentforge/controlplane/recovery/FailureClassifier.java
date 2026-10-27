package com.agentforge.controlplane.recovery;

import org.springframework.stereotype.Component;

import java.net.ConnectException;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.TimeoutException;

@Component
public class FailureClassifier {
    public record ClassifiedFailure(String category, String summary) {}

    private final SensitiveDataSanitizer sanitizer;

    public FailureClassifier(SensitiveDataSanitizer sanitizer) {
        this.sanitizer = sanitizer;
    }

    public ClassifiedFailure classify(Throwable error) {
        Throwable effective = error instanceof RecoveryExecutionService.RecoveryFailedException failed
                && failed.getCause() != null ? failed.getCause() : error;
        String category = switch (effective) {
            case RecoveryExecutionService.RecoveryTimeoutException ignored -> "TIMEOUT";
            case HttpTimeoutException ignored -> "TIMEOUT";
            case TimeoutException ignored -> "TIMEOUT";
            case ConnectException ignored -> "UPSTREAM_UNAVAILABLE";
            case SecurityException ignored -> "PERMISSION_CHANGED";
            case IllegalArgumentException ignored -> "INPUT_OR_CONFIG_INVALID";
            default -> "INTERNAL_ERROR";
        };
        return new ClassifiedFailure(category, sanitizer.safeError(effective));
    }
}
