package com.agentforge.controlplane.agent;

import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;

import java.util.ArrayList;
import java.util.List;

/** 把本平台的一轮回复收成 A2A 能看见的事件。阻塞请求认最终消息，流式请求认文本增量。 */
final class A2aMessages {

    private A2aMessages() {}

    static String textOf(List<Msg> messages) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (Msg message : messages) {
            if (message == null) {
                continue;
            }
            String text = message.getTextContent();
            if (text == null || text.isBlank()) {
                continue;
            }
            if (!out.isEmpty()) {
                out.append('\n');
            }
            out.append(text.strip());
        }
        return out.toString();
    }

    static List<AgentEvent> replyEvents(String text) {
        String body = text == null ? "" : text;
        String replyId = "a2a-reply";
        List<AgentEvent> events = new ArrayList<>();
        events.add(new TextBlockDeltaEvent(replyId, "text", body));
        events.add(new AgentResultEvent(Msg.builder()
                .id(replyId)
                .role(MsgRole.ASSISTANT)
                .textContent(body)
                .build()));
        return events;
    }

    static boolean failed(String text) {
        if (text == null) {
            return false;
        }
        String value = text.strip();
        return value.startsWith("Agent execution failed:") || value.startsWith("Handle Agent execute error:");
    }
}
