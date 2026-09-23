package com.agentforge.controlplane.rag;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;

import java.io.ByteArrayInputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class DocumentExtractor {

    public static final Set<String> ALLOWED = Set.of("txt", "md", "markdown", "html", "htm", "csv", "docx", "pdf");

    public String extract(String filename, byte[] bytes) {
        String ext = extension(filename);
        if (!ALLOWED.contains(ext)) {
            throw new IllegalArgumentException("不支持的文件类型：" + ext);
        }
        return switch (ext) {
            case "pdf" -> pdf(bytes);
            case "docx" -> docx(bytes);
            case "html", "htm" -> html(decode(bytes));
            case "csv" -> csv(decode(bytes));
            default -> decode(bytes);
        };
    }

    public static String extension(String filename) {
        String name = filename == null ? "" : filename.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return "";
        }
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    public static String mediaType(String filename) {
        return switch (extension(filename)) {
            case "pdf" -> "application/pdf";
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "html", "htm" -> "text/html";
            case "csv" -> "text/csv";
            case "md", "markdown" -> "text/markdown";
            default -> "text/plain";
        };
    }

    private static String decode(byte[] bytes) {
        String utf8 = new String(bytes, StandardCharsets.UTF_8);
        if (!utf8.contains("\uFFFD")) {
            return utf8;
        }
        return new String(bytes, Charset.forName("GB18030"));
    }

    private static String pdf(byte[] bytes) {
        try (PDDocument document = Loader.loadPDF(bytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            int pages = document.getNumberOfPages();
            StringBuilder out = new StringBuilder();
            for (int page = 1; page <= pages; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                if (page > 1) {
                    out.append('\f');
                }
                out.append(stripper.getText(document));
            }
            return out.toString();
        } catch (Exception e) {
            throw new IllegalArgumentException("PDF 无法读取：" + e.getMessage());
        }
    }

    private static String docx(byte[] bytes) {
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            StringBuilder out = new StringBuilder();
            for (IBodyElement element : document.getBodyElements()) {
                if (element instanceof XWPFParagraph paragraph) {
                    String text = paragraph.getText() == null ? "" : paragraph.getText().strip();
                    if (text.isEmpty()) {
                        continue;
                    }
                    String style = paragraph.getStyle() == null ? "" : paragraph.getStyle().toLowerCase(Locale.ROOT);
                    int level = headingLevel(style);
                    if (out.length() > 0) {
                        out.append("\n\n");
                    }
                    if (level > 0) {
                        out.append("#".repeat(level)).append(' ').append(text);
                    } else {
                        out.append(text);
                    }
                } else if (element instanceof XWPFTable table) {
                    String markdown = table(table);
                    if (!markdown.isEmpty()) {
                        if (out.length() > 0) {
                            out.append("\n\n");
                        }
                        out.append(markdown);
                    }
                }
            }
            return out.toString();
        } catch (Exception e) {
            throw new IllegalArgumentException("Word 文档无法读取：" + e.getMessage());
        }
    }

    private static int headingLevel(String style) {
        if (style.contains("heading1") || "1".equals(style)) {
            return 1;
        }
        if (style.contains("heading2") || "2".equals(style)) {
            return 2;
        }
        if (style.contains("heading3") || "3".equals(style)) {
            return 3;
        }
        if (style.contains("heading")) {
            return 2;
        }
        return 0;
    }

    private static String table(XWPFTable table) {
        List<String> rows = new ArrayList<>();
        for (XWPFTableRow row : table.getRows()) {
            List<String> cells = new ArrayList<>();
            for (XWPFTableCell cell : row.getTableCells()) {
                cells.add(cell.getText() == null ? "" : cell.getText().replace("\n", " ").strip());
            }
            rows.add("| " + String.join(" | ", cells) + " |");
        }
        if (rows.isEmpty()) {
            return "";
        }
        int cols = table.getRow(0).getTableCells().size();
        StringBuilder sep = new StringBuilder("|");
        for (int i = 0; i < cols; i++) {
            sep.append(" --- |");
        }
        rows.add(1, sep.toString());
        return String.join("\n", rows);
    }

    private static String html(String raw) {
        String text = raw.replaceAll("(?is)<script[^>]*>.*?</script>", " ");
        text = text.replaceAll("(?is)<style[^>]*>.*?</style>", " ");
        text = text.replaceAll("(?i)<br\\s*/?>", "\n");
        text = text.replaceAll("(?i)</p>", "\n\n");
        text = text.replaceAll("(?is)<h1[^>]*>", "\n\n# ");
        text = text.replaceAll("(?is)<h2[^>]*>", "\n\n## ");
        text = text.replaceAll("(?is)<h3[^>]*>", "\n\n### ");
        text = text.replaceAll("(?is)</h[1-6]>", "\n\n");
        text = text.replaceAll("(?is)<[^>]+>", " ");
        text = text.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">");
        return text;
    }

    private static String csv(String raw) {
        List<String> lines = new ArrayList<>();
        for (String line : raw.split("\\R")) {
            if (!line.isBlank()) {
                lines.add(line);
            }
        }
        if (lines.isEmpty()) {
            return "";
        }
        List<List<String>> rows = new ArrayList<>();
        for (String line : lines) {
            rows.add(splitCsv(line));
        }
        StringBuilder out = new StringBuilder();
        out.append("| ").append(String.join(" | ", rows.get(0))).append(" |\n|");
        for (int i = 0; i < rows.get(0).size(); i++) {
            out.append(" --- |");
        }
        for (int i = 1; i < rows.size(); i++) {
            out.append("\n| ").append(String.join(" | ", rows.get(i))).append(" |");
        }
        return out.toString();
    }

    private static List<String> splitCsv(String line) {
        List<String> cells = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quote = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                quote = !quote;
                continue;
            }
            if (ch == ',' && !quote) {
                cells.add(current.toString().strip());
                current.setLength(0);
                continue;
            }
            current.append(ch);
        }
        cells.add(current.toString().strip());
        return cells;
    }
}
