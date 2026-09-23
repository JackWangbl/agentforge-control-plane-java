package com.agentforge.controlplane.rag;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** 确定性清洗。不调用模型。 */
public class DocumentCleaner {

    private static final Pattern PAGE_NUMBER = Pattern.compile(
            "^(?:第\\s*\\d+\\s*页|[-–—]\\s*\\d+\\s*[-–—]|\\d{1,4})$");
    private static final Pattern HTML_NOISE = Pattern.compile(
            "(?i).*(版权所有|all rights reserved|cookie).*");

    public CleanResult clean(String raw, String mediaType) {
        String text = raw == null ? "" : raw;
        int before = text.length();
        text = text.replace("\uFEFF", "").replace("\u0000", "");
        text = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKC);
        text = text.replace("\r\n", "\n").replace('\r', '\n');
        text = trimLineEnds(text);

        int headers = 0;
        int pageNumbers = 0;
        String kind = mediaType == null ? "" : mediaType.toLowerCase(Locale.ROOT);
        if (kind.contains("html")) {
            LineStrip removed = stripMatchingLines(text, HTML_NOISE);
            headers += removed.count;
            text = removed.text;
        }
        if (text.indexOf('\f') >= 0) {
            PageClean pages = cleanPages(text);
            text = pages.text;
            headers += pages.headers;
            pageNumbers += pages.pageNumbers;
        } else {
            LineStrip numbers = stripMatchingLines(text, PAGE_NUMBER);
            text = numbers.text;
            pageNumbers += numbers.count;
        }

        int blankBefore = countBlankRuns(text);
        text = text.replaceAll("\\n{3,}", "\n\n");
        int blankRemoved = Math.max(0, blankBefore);
        text = collapseSpacesOutsideCode(text);
        DupFold dup = collapseDuplicateLines(text);
        text = dup.text.strip();

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("removed_blank_lines", blankRemoved);
        summary.put("removed_headers", headers);
        summary.put("removed_page_numbers", pageNumbers);
        summary.put("collapsed_duplicate_lines", dup.removed);
        summary.put("chars_before", before);
        summary.put("chars_after", text.length());
        return new CleanResult(text, summary);
    }

    private static String trimLineEnds(String text) {
        String[] lines = text.split("\n", -1);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                out.append('\n');
            }
            String line = lines[i];
            if ("\f".equals(line)) {
                out.append('\f');
            } else {
                out.append(stripEnd(line));
            }
        }
        return out.toString();
    }

    private static String stripEnd(String line) {
        int end = line.length();
        while (end > 0 && (line.charAt(end - 1) == ' ' || line.charAt(end - 1) == '\t')) {
            end--;
        }
        return line.substring(0, end);
    }

    private static PageClean cleanPages(String text) {
        String[] pages = text.split("\f", -1);
        List<List<String>> lines = new ArrayList<>();
        for (String page : pages) {
            List<String> pageLines = new ArrayList<>();
            for (String line : page.split("\n", -1)) {
                pageLines.add(line);
            }
            lines.add(pageLines);
        }
        int pageCount = lines.size();
        Map<String, Integer> firstHits = new LinkedHashMap<>();
        Map<String, Integer> lastHits = new LinkedHashMap<>();
        if (pageCount >= 2) {
            for (List<String> page : lines) {
                String first = firstContent(page);
                String last = lastContent(page);
                if (!first.isEmpty()) {
                    firstHits.merge(first, 1, Integer::sum);
                }
                if (!last.isEmpty()) {
                    lastHits.merge(last, 1, Integer::sum);
                }
            }
        }
        int threshold = Math.max(1, (pageCount + 1) / 2);
        int headers = 0;
        int pageNumbers = 0;
        StringBuilder out = new StringBuilder();
        for (int p = 0; p < lines.size(); p++) {
            if (p > 0) {
                out.append("\n\n");
            }
            List<String> page = lines.get(p);
            for (int i = 0; i < page.size(); i++) {
                String line = page.get(i);
                String trimmed = line.strip();
                boolean edge = i == indexOfContent(page, true) || i == indexOfContent(page, false);
                if (edge && !trimmed.isEmpty() && pageCount >= 2) {
                    int hits = i == indexOfContent(page, true)
                            ? firstHits.getOrDefault(trimmed, 0)
                            : lastHits.getOrDefault(trimmed, 0);
                    if (hits > pageCount / 2.0 && hits >= threshold) {
                        headers++;
                        continue;
                    }
                }
                if (PAGE_NUMBER.matcher(trimmed).matches()) {
                    pageNumbers++;
                    continue;
                }
                if (out.length() > 0 && out.charAt(out.length() - 1) != '\n') {
                    out.append('\n');
                }
                out.append(line);
            }
        }
        return new PageClean(out.toString(), headers, pageNumbers);
    }

    private static String firstContent(List<String> page) {
        for (String line : page) {
            if (!line.strip().isEmpty()) {
                return line.strip();
            }
        }
        return "";
    }

    private static String lastContent(List<String> page) {
        for (int i = page.size() - 1; i >= 0; i--) {
            if (!page.get(i).strip().isEmpty()) {
                return page.get(i).strip();
            }
        }
        return "";
    }

    private static int indexOfContent(List<String> page, boolean first) {
        if (first) {
            for (int i = 0; i < page.size(); i++) {
                if (!page.get(i).strip().isEmpty()) {
                    return i;
                }
            }
            return -1;
        }
        for (int i = page.size() - 1; i >= 0; i--) {
            if (!page.get(i).strip().isEmpty()) {
                return i;
            }
        }
        return -1;
    }

    private static LineStrip stripMatchingLines(String text, Pattern pattern) {
        String[] lines = text.split("\n", -1);
        StringBuilder out = new StringBuilder();
        int count = 0;
        for (String line : lines) {
            if (pattern.matcher(line.strip()).matches()) {
                count++;
                continue;
            }
            if (out.length() > 0) {
                out.append('\n');
            }
            out.append(line);
        }
        return new LineStrip(out.toString(), count);
    }

    private static int countBlankRuns(String text) {
        int runs = 0;
        int blank = 0;
        for (String line : text.split("\n", -1)) {
            if (line.isBlank()) {
                blank++;
            } else {
                if (blank >= 3) {
                    runs++;
                }
                blank = 0;
            }
        }
        return runs;
    }

    private static String collapseSpacesOutsideCode(String text) {
        String[] lines = text.split("\n", -1);
        StringBuilder out = new StringBuilder();
        boolean code = false;
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                out.append('\n');
            }
            String line = lines[i];
            if (line.strip().startsWith("```")) {
                code = !code;
                out.append(line);
                continue;
            }
            out.append(code ? line : line.replaceAll("[ \\t]{2,}", " "));
        }
        return out.toString();
    }

    private static DupFold collapseDuplicateLines(String text) {
        String[] lines = text.split("\n", -1);
        StringBuilder out = new StringBuilder();
        int removed = 0;
        int i = 0;
        while (i < lines.length) {
            int j = i + 1;
            if (!lines[i].strip().isEmpty()) {
                while (j < lines.length && lines[j].equals(lines[i])) {
                    j++;
                }
            }
            int run = j - i;
            if (run >= 4) {
                removed += run - 1;
                if (out.length() > 0) {
                    out.append('\n');
                }
                out.append(lines[i]);
                i = j;
                continue;
            }
            if (out.length() > 0) {
                out.append('\n');
            }
            out.append(lines[i]);
            i++;
        }
        return new DupFold(out.toString(), removed);
    }

    public record CleanResult(String text, Map<String, Object> summary) {}

    private record LineStrip(String text, int count) {}

    private record PageClean(String text, int headers, int pageNumbers) {}

    private record DupFold(String text, int removed) {}
}
