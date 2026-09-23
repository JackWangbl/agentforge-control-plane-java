package com.agentforge.controlplane.rag;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RagPipelineTest {

    private final DocumentCleaner cleaner = new DocumentCleaner();
    private final ChunkStrategySelector selector = new ChunkStrategySelector();

    @Test
    void headingDocumentUsesHeadingStrategy() {
        String text = """
                # 总则
                这是总则说明，用来介绍制度范围。

                # 账号
                账号章节说明如何开通。

                # 权限
                权限章节说明谁可以访问。

                # 审计
                审计章节说明日志保留多久。
                """;
        ChunkStrategySelector.Result result = selector.select(text, "");
        assertEquals("heading", result.strategy());
        assertTrue(result.reason().contains("4"));
        assertFalse(result.chunks().isEmpty());
        assertTrue(result.chunks().get(0).content().contains("总则") || result.chunks().get(0).heading().contains("总则"));
    }

    @Test
    void articleDocumentUsesArticleStrategy() {
        StringBuilder text = new StringBuilder();
        for (int i = 1; i <= 5; i++) {
            text.append("第").append(i).append("条 条款正文说明权利义务。\n");
        }
        ChunkStrategySelector.Result result = selector.select(text.toString(), "");
        assertEquals("article", result.strategy());
        assertTrue(result.chunks().size() >= 5);
    }

    @Test
    void qaDocumentKeepsOnePairPerChunk() {
        String text = """
                问：如何开通？
                答：提交申请后开通。
                问：费用是多少？
                答：每年一百元。
                问：如何关闭？
                答：在设置里关闭。
                """;
        ChunkStrategySelector.Result result = selector.select(text, "");
        assertEquals("qa", result.strategy());
        assertEquals(3, result.chunks().size());
        assertTrue(result.chunks().get(0).content().contains("如何开通"));
        assertTrue(result.chunks().get(0).content().contains("提交申请"));
        assertFalse(result.chunks().get(0).content().contains("费用"));
    }

    @Test
    void repeatedPdfHeaderIsRemoved() {
        String page = "机密页眉\n正文第一段说明。\n- 1 -";
        String raw = page + "\f" + "机密页眉\n正文第二段说明。\n- 2 -" + "\f" + "机密页眉\n正文第三段说明。\n- 3 -";
        DocumentCleaner.CleanResult cleaned = cleaner.clean(raw, "application/pdf");
        assertFalse(cleaned.text().contains("机密页眉"));
        assertFalse(cleaned.text().contains("- 1 -"));
        assertTrue(cleaned.text().contains("正文第一段"));
        assertTrue(((Number) cleaned.summary().get("removed_headers")).intValue() >= 3);
    }

    @Test
    void overrideStrategyIsKept() {
        String text = """
                # 一
                段落甲

                # 二
                段落乙

                # 三
                段落丙

                # 四
                段落丁
                """;
        ChunkStrategySelector.Result result = selector.select(text, "paragraph");
        assertEquals("paragraph", result.strategy());
        assertTrue(result.reason().contains("指定"));
    }

    @Test
    void sameHeadingSharesSectionContext() {
        ChunkStrategySelector.Result result = selector.select("""
                # 账号
                开通说明第一段。

                # 权限
                谁可以访问。
                """, "");
        var contexts = ChunkStrategySelector.sectionContexts(result.chunks());
        assertEquals(result.chunks().size(), contexts.size());
        assertTrue(contexts.get(0).startsWith("# 账号") || contexts.get(0).contains("账号"));
        assertTrue(contexts.get(0).contains("开通"));
    }

    @Test
    void retrievalDoesNotWidenForAdmin() {
        assertFalse(KnowledgeAcl.canRetrieve(false, false, true));
        assertTrue(KnowledgeAcl.canRetrieve(true, false, true));
        assertTrue(KnowledgeAcl.canRetrieve(false, true, true));
        assertTrue(KnowledgeAcl.canViewInConsole(false, false, false, true));
        assertFalse(KnowledgeAcl.canManage("editor", true));
        assertTrue(KnowledgeAcl.canManage("owner", true));
        assertTrue(KnowledgeAcl.canEditDocuments("editor", true));
        assertFalse(KnowledgeAcl.canEditDocuments("viewer", true));
    }
}
