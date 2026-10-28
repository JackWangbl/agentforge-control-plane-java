package com.agentforge.controlplane.memory;

import com.agentforge.controlplane.config.AppSettings;
import com.agentforge.controlplane.domain.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ContextHistoryAssemblerTest {

    @Test
    void underBudgetDoesNotCallCompressor() {
        List<ChatMessage> rows = messages(4, "短消息");
        AtomicInteger calls = new AtomicInteger();
        ContextHistoryAssembler.Result result = ContextHistoryAssembler.assemble(
                rows, "", null, "", policy(), "", "本轮",
                (previous, evicted, effective) -> {
                    calls.incrementAndGet();
                    return "不应调用";
                });
        assertThat(calls.get()).isEqualTo(0);
        assertThat(result.outcome()).isEqualTo("unchanged");
        assertThat(result.history()).hasSize(4);
        assertThat(result.history().get(0).get("content")).isEqualTo("短消息0");
    }

    @Test
    void overWindowCompressesEvictedTurnsIntoSummary() {
        List<ChatMessage> rows = messages(24, "工单ABC-99详情".repeat(8));
        ContextHistoryAssembler.Result result = ContextHistoryAssembler.assemble(
                rows, "", null, "", policy(), "", "继续查这个工单",
                (previous, evicted, effective) -> "用户在查工单ABC-99，尚未完成。");
        assertThat(result.outcome()).isEqualTo("compressed");
        assertThat(result.summary()).contains("ABC-99");
        assertThat(result.history().get(0).get("content").toString())
                .startsWith(ContextHistoryAssembler.SUMMARY_PREFIX)
                .contains("ABC-99");
        assertThat(result.history().size()).isLessThan(rows.size());
        assertThat(result.coveredThroughId()).isNotNull();
    }

    @Test
    void compressorFailureFallsBackToWindow() {
        List<ChatMessage> rows = messages(24, "很长的上文");
        ContextHistoryAssembler.Result result = ContextHistoryAssembler.assemble(
                rows, "", null, "", policy(), "", "下一句",
                (previous, evicted, effective) -> {
                    throw new IllegalStateException("模型超时");
                });
        assertThat(result.outcome()).isEqualTo("failed");
        assertThat(result.history()).hasSizeLessThanOrEqualTo(20);
        assertThat(result.summary()).isEmpty();
    }

    @Test
    void sameHashSkipsSecondCompress() {
        List<ChatMessage> rows = messages(24, "重复压缩");
        AtomicInteger calls = new AtomicInteger();
        ContextHistoryAssembler.Compressor compressor = (previous, evicted, effective) -> {
            calls.incrementAndGet();
            return "已压缩摘要";
        };
        ContextHistoryAssembler.Result first = ContextHistoryAssembler.assemble(
                rows, "", null, "", policy(), "", "再问", compressor);
        ContextHistoryAssembler.Result second = ContextHistoryAssembler.assemble(
                rows, first.summary(), first.coveredThroughId(), first.contentHash(), policy(), "", "再问", compressor);
        assertThat(first.outcome()).isEqualTo("compressed");
        assertThat(second.outcome()).isEqualTo("unchanged");
        assertThat(calls.get()).isEqualTo(1);
        assertThat(second.history().get(0).get("content").toString()).contains("已压缩摘要");
    }

    private static ContextCompressionPolicy.Effective policy() {
        AppSettings.ContextCompression defaults = new AppSettings.ContextCompression();
        defaults.setContextWindowTokens(2048);
        defaults.setReservedOutputTokens(128);
        defaults.setReservedToolTokens(0);
        defaults.setMinHistoryTokens(128);
        defaults.setMessageCountTrigger(8);
        defaults.setMinRecentMessages(4);
        defaults.setRecentTokenLimit(400);
        defaults.setSummaryTokenLimit(200);
        return ContextCompressionPolicy.merge(defaults, Map.of());
    }

    private static List<ChatMessage> messages(int count, String body) {
        List<ChatMessage> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ChatMessage row = new ChatMessage();
            row.setId((long) (i + 1));
            row.setRole(i % 2 == 0 ? "user" : "assistant");
            row.setContent(body + i);
            rows.add(row);
        }
        return rows;
    }
}
