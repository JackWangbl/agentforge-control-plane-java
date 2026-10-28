package com.agentforge.controlplane.memory;

import com.agentforge.controlplane.domain.ChatMessage;
import com.agentforge.controlplane.util.Jsons;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

final class ContextHistoryAssembler {
    static final String SUMMARY_PREFIX = "【系统注入的会话摘要，不要向用户复述全文】\n";

    record Result(List<Map<String, Object>> history, String summary, Long coveredThroughId, String contentHash,
                  int tokensBefore, int tokensAfter, int budget, String outcome, int sourceCount, int sourceChars) {}

    interface Compressor {
        String compress(String previousSummary, List<ChatMessage> evicted, ContextCompressionPolicy.Effective policy);
    }

    private ContextHistoryAssembler() {}

    static Result assemble(List<ChatMessage> messages, String existingSummary, Long existingCoveredId,
                           String existingHash, ContextCompressionPolicy.Effective policy,
                           String systemText, String currentUserMessage, Compressor compressor) {
        List<ChatMessage> rows = usable(messages);
        int budget = historyBudget(policy, systemText, currentUserMessage);
        int tokensBefore = tokensOf(rows, policy.charsPerToken());
        if (rows.isEmpty()) {
            return new Result(new ArrayList<>(), "", null, "", 0, 0, budget, "skipped", 0, 0);
        }
        int from = recentStart(rows, policy, tokensBefore, budget);
        List<ChatMessage> recent = new ArrayList<>(rows.subList(from, rows.size()));
        List<ChatMessage> evicted = new ArrayList<>(rows.subList(0, from));
        String summary = existingSummary == null ? "" : existingSummary.strip();
        Long covered = existingCoveredId;
        String hash = existingHash == null ? "" : existingHash;
        String outcome = "unchanged";
        boolean overCount = rows.size() > policy.messageCountTrigger();
        boolean overBudget = tokensBefore > budget;
        boolean overRecent = tokensOf(recent, policy.charsPerToken()) > policy.recentTokenLimit();
        boolean summaryOver = tokens(SUMMARY_PREFIX + summary, policy) + tokensOf(recent, policy.charsPerToken()) > budget;
        boolean needCompress = !evicted.isEmpty() && (overCount || overBudget || overRecent || summaryOver);
        if (needCompress) {
            String nextHash = hashOf(evicted);
            Long lastEvictedId = evicted.get(evicted.size() - 1).getId();
            boolean already = nextHash.equals(hash) && lastEvictedId != null && lastEvictedId.equals(covered);
            if (!already) {
                try {
                    String compressed = compressor == null ? null : compressor.compress(summary, evicted, policy);
                    if (compressed != null && !compressed.isBlank()) {
                        summary = ContextCompressionPolicy.clipToTokens(compressed.strip(), policy.summaryTokenLimit(),
                                policy.charsPerToken());
                        covered = lastEvictedId;
                        hash = nextHash;
                        outcome = "compressed";
                    } else {
                        return fallbackWindow(rows, budget, tokensBefore, "failed");
                    }
                } catch (RuntimeException error) {
                    return fallbackWindow(rows, budget, tokensBefore, "failed");
                }
            }
        }
        List<Map<String, Object>> history = new ArrayList<>();
        if (!summary.isBlank()) {
            history.add(Map.of("role", "user", "content", SUMMARY_PREFIX + summary));
        }
        for (ChatMessage row : recent) {
            history.add(Map.of("role", row.getRole(), "content", row.getContent()));
        }
        history = trimToBudget(history, recent.size(), policy, budget);
        int tokensAfter = historyTokens(history, policy);
        if (tokensAfter > budget && "unchanged".equals(outcome)) outcome = "truncated";
        int chars = rows.stream().mapToInt(row -> row.getContent() == null ? 0 : row.getContent().length()).sum();
        return new Result(history, summary, covered, hash, tokensBefore, tokensAfter, budget, outcome, rows.size(), chars);
    }

    static int historyBudget(ContextCompressionPolicy.Effective policy, String systemText, String currentUserMessage) {
        int reserved = policy.reservedOutputTokens() + policy.reservedToolTokens()
                + tokens(systemText, policy) + tokens(currentUserMessage, policy);
        int budget = policy.contextWindowTokens() - reserved;
        return Math.max(policy.minHistoryTokens(), budget);
    }

    private static List<Map<String, Object>> trimToBudget(List<Map<String, Object>> history, int recentCount,
                                                          ContextCompressionPolicy.Effective policy, int budget) {
        List<Map<String, Object>> out = new ArrayList<>(history);
        int keepRecent = Math.max(1, Math.min(recentCount, policy.minRecentMessages()));
        int summarySlots = out.isEmpty() || !Jsons.text(out.get(0).get("content")).startsWith(SUMMARY_PREFIX) ? 0 : 1;
        while (out.size() > summarySlots + keepRecent && historyTokens(out, policy) > budget) {
            out.remove(summarySlots);
        }
        while (out.size() > summarySlots + 1 && historyTokens(out, policy) > budget) {
            out.remove(summarySlots);
        }
        return out;
    }

    private static Result fallbackWindow(List<ChatMessage> rows, int budget, int tokensBefore, String outcome) {
        List<Map<String, Object>> maps = new ArrayList<>();
        for (ChatMessage row : rows) {
            maps.add(Map.of("role", row.getRole(), "content", row.getContent()));
        }
        List<Map<String, Object>> window = MemoryService.applyWindow(maps);
        int after = 0;
        for (Map<String, Object> item : window) {
            after += ContextCompressionPolicy.tokens(Jsons.text(item.get("content")), 1.5);
        }
        int chars = rows.stream().mapToInt(row -> row.getContent() == null ? 0 : row.getContent().length()).sum();
        return new Result(window, "", null, "", tokensBefore, after, budget, outcome, rows.size(), chars);
    }

    private static int recentStart(List<ChatMessage> rows, ContextCompressionPolicy.Effective policy,
                                   int tokensBefore, int budget) {
        int minRecent = Math.min(rows.size(), Math.max(1, policy.minRecentMessages()));
        int from = rows.size() - minRecent;
        boolean tight = rows.size() > policy.messageCountTrigger() || tokensBefore > budget;
        if (tight) return from;
        while (from > 0 && tokensOf(rows.subList(from - 1, rows.size()), policy.charsPerToken()) <= policy.recentTokenLimit()) {
            from--;
        }
        return from;
    }

    private static List<ChatMessage> usable(List<ChatMessage> messages) {
        List<ChatMessage> rows = new ArrayList<>();
        if (messages == null) return rows;
        for (ChatMessage row : messages) {
            if (row == null) continue;
            if (!"user".equals(row.getRole()) && !"assistant".equals(row.getRole())) continue;
            rows.add(row);
        }
        return rows;
    }

    private static int tokensOf(List<ChatMessage> rows, double charsPerToken) {
        int total = 0;
        for (ChatMessage row : rows) {
            total += ContextCompressionPolicy.tokens(row.getContent(), charsPerToken);
        }
        return total;
    }

    private static int historyTokens(List<Map<String, Object>> history, ContextCompressionPolicy.Effective policy) {
        int total = 0;
        for (Map<String, Object> item : history) {
            total += tokens(Jsons.text(item.get("content")), policy);
        }
        return total;
    }

    private static int tokens(String text, ContextCompressionPolicy.Effective policy) {
        return ContextCompressionPolicy.tokens(text, policy.charsPerToken());
    }

    static String hashOf(List<ChatMessage> evicted) {
        StringBuilder raw = new StringBuilder();
        for (ChatMessage row : evicted) {
            raw.append(row.getId()).append('|').append(row.getRole()).append('|').append(row.getContent()).append('\n');
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception error) {
            return Integer.toHexString(raw.toString().hashCode());
        }
    }

}
