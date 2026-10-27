package com.agentforge.controlplane.recovery;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SensitiveDataSanitizerTest {
    private final SensitiveDataSanitizer sanitizer = new SensitiveDataSanitizer();

    @Test
    void recursivelyMasksSensitiveFields() {
        Object value = sanitizer.sanitize(Map.of(
                "name", "visible",
                "password", "secret-password",
                "nested", List.of(Map.of("api_key", "sk-secret", "token", "bearer-secret"))));
        String rendered = value.toString();
        assertThat(rendered).contains("visible", SensitiveDataSanitizer.MASK);
        assertThat(rendered).doesNotContain("secret-password", "sk-secret", "bearer-secret");
    }

    @Test
    void sanitizesAuthorizationCookiesAndThrowableMessages() {
        Object value = sanitizer.sanitize(Map.of("Authorization", "Bearer abc", "cookie", "sid=abc"));
        assertThat(value.toString()).doesNotContain("Bearer abc", "sid=abc");
        assertThat(sanitizer.safeError(new RuntimeException("token=super-secret")))
                .doesNotContain("super-secret");
    }
}
