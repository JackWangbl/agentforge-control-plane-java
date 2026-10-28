package com.agentforge.controlplane.memory;

import com.agentforge.controlplane.config.AppSettings;
import com.agentforge.controlplane.web.ApiException;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContextCompressionPolicyTest {

    @Test
    void mergeUsesPlatformDefaultsWhenOverrideEmpty() {
        AppSettings.ContextCompression defaults = new AppSettings.ContextCompression();
        ContextCompressionPolicy.Effective policy = ContextCompressionPolicy.merge(defaults, Map.of());
        assertThat(policy.enabled()).isTrue();
        assertThat(policy.contextWindowTokens()).isEqualTo(8192);
        assertThat(policy.minRecentMessages()).isEqualTo(8);
        assertThat(policy.charsPerToken()).isEqualTo(1.5);
    }

    @Test
    void agentOverrideWinsForListedKeys() {
        AppSettings.ContextCompression defaults = new AppSettings.ContextCompression();
        ContextCompressionPolicy.Effective policy = ContextCompressionPolicy.merge(defaults, Map.of(
                "enabled", true,
                "context_window_tokens", 4096,
                "min_recent_messages", 4));
        assertThat(policy.contextWindowTokens()).isEqualTo(4096);
        assertThat(policy.minRecentMessages()).isEqualTo(4);
        assertThat(policy.reservedOutputTokens()).isEqualTo(defaults.getReservedOutputTokens());
    }

    @Test
    void rejectOutOfRangeOverride() {
        assertThatThrownBy(() -> ContextCompressionPolicy.validateOverride(Map.of("context_window_tokens", 12)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("超出安全范围");
    }

    @Test
    void tokenEstimateUsesCharsPerToken() {
        assertThat(ContextCompressionPolicy.tokens("abcdefghij", 2.0)).isEqualTo(5);
        assertThat(ContextCompressionPolicy.clipToTokens("abcdefghij", 2, 2.0)).isEqualTo("abcd");
    }
}
