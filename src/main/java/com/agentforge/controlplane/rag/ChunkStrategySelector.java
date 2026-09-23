package com.agentforge.controlplane.rag;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 按文档画像打分，选出一种分块策略。 */
public class ChunkStrategySelector {

    public static final int MAX_CHARS = 8192;
    private static final Pattern HEADING = Pattern.compile("(?m)^(#{1,6})\\s+(.+)$");
    private static final Pattern QA = Pattern.compile("(?m)^(问|Q)[:：.]\\s*(.+)$");
    private static final Pattern ARTICLE = Pattern.compile("(?m)^第[一二三四五六七八九十百千0-9]+条");
    private static final List<String> PRIORITY = List.of("qa", "article", "table_row", "heading", "paragraph", "sentence");

    public Result select(String text, String override) {
        String body = text == null ? "" : text;
        Profile profile = profile(body);
        String forced = override == null ? "" : override.strip().toLowerCase(Locale.ROOT);
        String strategy;
        String reason;
        if (!forced.isEmpty()) {
            if (!known(forced)) {
                throw new IllegalArgumentException("不支持的分块策略：" + override);
            }
            strategy = forced;
            reason = "使用指定的分块策略 " + strategy + "。";
        } else {
            Map<String, Integer> scores = score(profile);
            strategy = winner(scores);
            reason = reason(strategy, profile);
        }
        List<Chunk> chunks = cut(body, strategy, profile);
        return new Result(strategy, reason, profile.toMap(), chunks);
    }

    private static boolean known(String name) {
        return PRIORITY.contains(name) || "recursive".equals(name);
    }

    private static Profile profile(String text) {
        int chars = text.length();
        int cjk = 0;
        for (int i = 0; i < text.length(); i++) {
            Character.UnicodeScript script = Character.UnicodeScript.of(text.charAt(i));
            if (script == Character.UnicodeScript.HAN || script == Character.UnicodeScript.HIRAGANA
                    || script == Character.UnicodeScript.KATAKANA || script == Character.UnicodeScript.HANGUL) {
                cjk++;
            }
        }
        double cjkRatio = chars == 0 ? 0 : (double) cjk / chars;
        int headingCount = count(HEADING, text);
        List<String> paragraphs = splitParagraphs(text);
        int avg = paragraphs.isEmpty() ? chars : paragraphs.stream().mapToInt(String::length).sum() / paragraphs.size();
        int codeChars = codeChars(text);
        int tableChars = tableChars(text);
        double codeRatio = chars == 0 ? 0 : (double) codeChars / chars;
        double tableRatio = chars == 0 ? 0 : (double) tableChars / chars;
        int qaPairs = countQa(text);
        int articleHits = count(ARTICLE, text);
        int target = cjkRatio >= 0.3 ? 450 : 1000;
        int overlap = cjkRatio >= 0.3 ? 60 : 120;
        return new Profile(chars, cjkRatio, headingCount, avg, codeRatio, tableRatio, qaPairs, articleHits, target, overlap);
    }

    private static Map<String, Integer> score(Profile profile) {
        Map<String, Integer> scores = new LinkedHashMap<>();
        scores.put("qa", profile.qaPairs >= 3 ? 5 : 0);
        scores.put("article", profile.articleHits >= 5 ? 5 : 0);
        int table = 0;
        if (profile.tableRatio >= 0.45) {
            table = 5;
        } else if (profile.tableRatio >= 0.25) {
            table = 3;
        }
        scores.put("table_row", table);
        int heading = 0;
        if (profile.headingCount >= 2) {
            heading = 2;
        }
        if (profile.headingCount >= 4 && profile.chars > 0
                && profile.chars / Math.max(1, profile.headingCount) < profile.target * 4L) {
            heading = 4;
        }
        if (profile.codeRatio >= 0.4) {
            heading += 1;
        }
        scores.put("heading", heading);
        int paragraph = 0;
        if (profile.avgParagraphChars >= profile.target * 0.2 && profile.avgParagraphChars <= profile.target * 1.2) {
            paragraph = 3;
        }
        scores.put("paragraph", paragraph);
        int sentence = profile.avgParagraphChars > profile.target * 2.0 ? 3 : 0;
        if (profile.codeRatio >= 0.4) {
            sentence = 0;
        }
        scores.put("sentence", sentence);
        return scores;
    }

    private static String winner(Map<String, Integer> scores) {
        int best = 0;
        for (int score : scores.values()) {
            best = Math.max(best, score);
        }
        if (best < 1) {
            return "recursive";
        }
        for (String name : PRIORITY) {
            if (scores.getOrDefault(name, 0) == best) {
                return name;
            }
        }
        return "recursive";
    }

    private static String reason(String strategy, Profile profile) {
        return switch (strategy) {
            case "qa" -> "识别到 " + profile.qaPairs + " 组问答，使用问答分块。";
            case "article" -> "识别到 " + profile.articleHits + " 个条款，使用按条款分块。";
            case "table_row" -> "表格内容占比高，使用按行分块。";
            case "heading" -> "识别到 " + profile.headingCount + " 个标题，章节长度接近目标块大小，使用按标题分块。";
            case "paragraph" -> "段落长度接近目标块大小，使用按段落分块。";
            case "sentence" -> "段落很长且缺少空行，使用按句子分块。";
            default -> "文档结构不明显，使用递归分块。";
        };
    }

    private static List<Chunk> cut(String text, String strategy, Profile profile) {
        int overlap = "qa".equals(strategy) || "table_row".equals(strategy) ? 0 : profile.overlap;
        int max = Math.min(MAX_CHARS, (int) (profile.target * 1.6));
        List<Piece> pieces = switch (strategy) {
            case "heading" -> byHeading(text);
            case "qa" -> byQa(text);
            case "article" -> byArticle(text);
            case "table_row" -> byTable(text);
            case "sentence" -> bySentence(text);
            case "paragraph" -> byParagraph(text);
            default -> byRecursive(text);
        };
        if (pieces.isEmpty() && !text.isBlank()) {
            pieces = List.of(new Piece("", text.strip()));
        }
        return pack(pieces, profile.target, max, overlap);
    }

    private static List<Chunk> pack(List<Piece> pieces, int target, int max, int overlap) {
        List<Chunk> chunks = new ArrayList<>();
        StringBuilder body = new StringBuilder();
        String heading = "";
        for (Piece piece : pieces) {
            String part = piece.body == null ? "" : piece.body.strip();
            if (part.isEmpty()) {
                continue;
            }
            if (part.length() > max) {
                flush(chunks, heading, body, overlap, max);
                heading = "";
                for (String slice : hardSplit(part, max)) {
                    push(chunks, piece.heading, slice, overlap, max);
                }
                continue;
            }
            String nextHeading = piece.heading == null || piece.heading.isBlank() ? heading : piece.heading;
            boolean headingChanged = !nextHeading.equals(heading) && !body.isEmpty();
            if (headingChanged || body.length() + part.length() + 2 > target) {
                flush(chunks, heading, body, overlap, max);
            }
            if (!nextHeading.isBlank()) {
                heading = nextHeading;
            }
            if (!body.isEmpty()) {
                body.append("\n\n");
            }
            body.append(part);
        }
        flush(chunks, heading, body, overlap, max);
        for (int i = 0; i < chunks.size(); i++) {
            Chunk chunk = chunks.get(i);
            chunks.set(i, new Chunk(i, chunk.heading, chunk.content));
        }
        return chunks;
    }

    private static void flush(List<Chunk> chunks, String heading, StringBuilder body, int overlap, int max) {
        if (body.isEmpty()) {
            return;
        }
        push(chunks, heading, body.toString().strip(), overlap, max);
        body.setLength(0);
    }

    private static void push(List<Chunk> chunks, String heading, String content, int overlap, int max) {
        String text = content.strip();
        if (text.isEmpty()) {
            return;
        }
        if (!chunks.isEmpty() && overlap > 0) {
            String prev = chunks.get(chunks.size() - 1).content;
            String tail = prev.length() <= overlap ? prev : prev.substring(prev.length() - overlap);
            if (!text.startsWith(tail) && tail.length() + 1 + text.length() <= max) {
                text = tail + "\n" + text;
            }
        }
        if (text.length() > MAX_CHARS) {
            text = text.substring(0, MAX_CHARS);
        }
        chunks.add(new Chunk(chunks.size(), heading == null ? "" : heading, text));
    }

    private static List<String> hardSplit(String text, int max) {
        List<String> parts = new ArrayList<>();
        List<String> sentences = splitSentences(text);
        StringBuilder current = new StringBuilder();
        for (String sentence : sentences) {
            if (sentence.length() > max) {
                if (!current.isEmpty()) {
                    parts.add(current.toString());
                    current.setLength(0);
                }
                for (int i = 0; i < sentence.length(); i += max) {
                    parts.add(sentence.substring(i, Math.min(sentence.length(), i + max)));
                }
                continue;
            }
            if (current.length() + sentence.length() > max && !current.isEmpty()) {
                parts.add(current.toString());
                current.setLength(0);
            }
            current.append(sentence);
        }
        if (!current.isEmpty()) {
            parts.add(current.toString());
        }
        return parts;
    }

    private static List<Piece> byHeading(String text) {
        Matcher matcher = HEADING.matcher(text);
        List<Piece> pieces = new ArrayList<>();
        List<String> path = new ArrayList<>();
        int last = 0;
        int lastLevel = 0;
        boolean seen = false;
        while (matcher.find()) {
            if (seen) {
                String body = text.substring(last, matcher.start()).strip();
                if (!body.isEmpty()) {
                    pieces.add(new Piece(String.join(" / ", path), body));
                }
            } else if (matcher.start() > 0) {
                String preface = text.substring(0, matcher.start()).strip();
                if (!preface.isEmpty()) {
                    pieces.add(new Piece("", preface));
                }
            }
            int level = matcher.group(1).length();
            while (path.size() >= level) {
                path.remove(path.size() - 1);
            }
            path.add(matcher.group(2).strip());
            last = matcher.end();
            lastLevel = level;
            seen = true;
        }
        if (!seen) {
            return byParagraph(text);
        }
        String tail = text.substring(last).strip();
        if (!tail.isEmpty()) {
            pieces.add(new Piece(String.join(" / ", path), tail));
        }
        if (lastLevel < 0) {
            return pieces;
        }
        return pieces;
    }

    private static List<Piece> byParagraph(String text) {
        List<Piece> pieces = new ArrayList<>();
        for (String paragraph : splitParagraphs(text)) {
            pieces.add(new Piece("", paragraph));
        }
        return pieces;
    }

    private static List<Piece> bySentence(String text) {
        List<Piece> pieces = new ArrayList<>();
        for (String sentence : splitSentences(text)) {
            String part = sentence.strip();
            if (!part.isEmpty()) {
                pieces.add(new Piece("", part));
            }
        }
        return pieces;
    }

    private static List<Piece> byQa(String text) {
        String[] lines = text.split("\n");
        List<Piece> pieces = new ArrayList<>();
        String question = "";
        StringBuilder answer = new StringBuilder();
        for (String line : lines) {
            Matcher matcher = QA.matcher(line);
            if (matcher.matches()) {
                if (!question.isEmpty()) {
                    pieces.add(new Piece(question, "问：" + question + "\n" + answer.toString().strip()));
                }
                question = matcher.group(2).strip();
                answer.setLength(0);
                continue;
            }
            if (!question.isEmpty()) {
                if (!answer.isEmpty()) {
                    answer.append('\n');
                }
                answer.append(line);
            }
        }
        if (!question.isEmpty()) {
            pieces.add(new Piece(question, "问：" + question + "\n" + answer.toString().strip()));
        }
        return pieces.isEmpty() ? byParagraph(text) : pieces;
    }

    private static List<Piece> byArticle(String text) {
        Matcher matcher = ARTICLE.matcher(text);
        List<Piece> pieces = new ArrayList<>();
        int last = 0;
        String heading = "";
        boolean seen = false;
        while (matcher.find()) {
            if (seen) {
                String body = text.substring(last, matcher.start()).strip();
                if (!body.isEmpty()) {
                    pieces.add(new Piece(heading, body));
                }
            }
            heading = matcher.group().strip();
            last = matcher.start();
            seen = true;
        }
        if (!seen) {
            return byParagraph(text);
        }
        String tail = text.substring(last).strip();
        if (!tail.isEmpty()) {
            pieces.add(new Piece(heading, tail));
        }
        return pieces;
    }

    private static List<Piece> byTable(String text) {
        List<String> header = new ArrayList<>();
        List<Piece> pieces = new ArrayList<>();
        for (String line : text.split("\n")) {
            String trimmed = line.strip();
            if (!trimmed.startsWith("|")) {
                continue;
            }
            if (trimmed.matches("\\|?\\s*:?-{3,}.*")) {
                continue;
            }
            if (header.isEmpty()) {
                header.add(trimmed);
                continue;
            }
            pieces.add(new Piece(header.get(0), header.get(0) + "\n" + trimmed));
        }
        return pieces.isEmpty() ? byParagraph(text) : pieces;
    }

    private static List<Piece> byRecursive(String text) {
        return byParagraph(text);
    }

    private static List<String> splitParagraphs(String text) {
        List<String> parts = new ArrayList<>();
        for (String part : text.split("\\n\\s*\\n")) {
            String trimmed = part.strip();
            if (!trimmed.isEmpty()) {
                parts.add(trimmed);
            }
        }
        return parts;
    }

    private static List<String> splitSentences(String text) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            current.append(ch);
            if ("。！？；.?!".indexOf(ch) >= 0) {
                parts.add(current.toString());
                current.setLength(0);
            }
        }
        if (!current.isEmpty()) {
            parts.add(current.toString());
        }
        return parts;
    }

    private static int codeChars(String text) {
        int count = 0;
        boolean code = false;
        for (String line : text.split("\n")) {
            if (line.strip().startsWith("```")) {
                code = !code;
                continue;
            }
            if (code) {
                count += line.length();
            }
        }
        return count;
    }

    private static int tableChars(String text) {
        int count = 0;
        for (String line : text.split("\n")) {
            if (line.strip().startsWith("|")) {
                count += line.length();
            }
        }
        return count;
    }

    private static int countQa(String text) {
        int count = 0;
        boolean waiting = false;
        for (String line : text.split("\n")) {
            if (QA.matcher(line).matches()) {
                waiting = true;
                continue;
            }
            if (waiting && !line.isBlank()) {
                count++;
                waiting = false;
            }
        }
        return count;
    }

    private static int count(Pattern pattern, String text) {
        int count = 0;
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    /** 同一标题下的子块拼成章节，最长 2000 字。没有标题时上下文就是子块本身。 */
    public static List<String> sectionContexts(List<Chunk> chunks) {
        List<String> contexts = new ArrayList<>();
        int index = 0;
        while (index < chunks.size()) {
            String heading = chunks.get(index).heading() == null ? "" : chunks.get(index).heading();
            int end = index + 1;
            while (end < chunks.size()) {
                String next = chunks.get(end).heading() == null ? "" : chunks.get(end).heading();
                if (!heading.equals(next)) {
                    break;
                }
                end++;
            }
            if (heading.isBlank()) {
                for (int cursor = index; cursor < end; cursor++) {
                    contexts.add(clipContext(chunks.get(cursor).content()));
                }
            } else {
                StringBuilder section = new StringBuilder(heading);
                for (int cursor = index; cursor < end; cursor++) {
                    section.append('\n').append(chunks.get(cursor).content());
                }
                String context = clipContext(section.toString().strip());
                for (int cursor = index; cursor < end; cursor++) {
                    contexts.add(context);
                }
            }
            index = end;
        }
        return contexts;
    }

    private static String clipContext(String text) {
        if (text == null) {
            return "";
        }
        String value = text.strip();
        return value.length() <= 2000 ? value : value.substring(0, 2000);
    }

    public record Chunk(int ordinal, String heading, String content) {}

    public record Result(String strategy, String reason, Map<String, Object> profile, List<Chunk> chunks) {}

    private record Piece(String heading, String body) {}

    private record Profile(int chars, double cjkRatio, int headingCount, int avgParagraphChars, double codeRatio,
                           double tableRatio, int qaPairs, int articleHits, int target, int overlap) {
        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("chars", chars);
            map.put("cjk_ratio", cjkRatio);
            map.put("heading_count", headingCount);
            map.put("avg_paragraph_chars", avgParagraphChars);
            map.put("code_ratio", codeRatio);
            map.put("table_ratio", tableRatio);
            map.put("qa_pairs", qaPairs);
            map.put("article_hits", articleHits);
            map.put("target", target);
            map.put("overlap", overlap);
            return map;
        }
    }
}
