package com.agentforge.controlplane.web;

import com.agentforge.controlplane.access.CurrentUser;
import com.agentforge.controlplane.domain.MemoryItem;
import com.agentforge.controlplane.memory.MemoryService;
import com.agentforge.controlplane.util.Jsons;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 只返回当前租户、当前身份自己的长期记忆。 */
@RestController
public class MemoryController {

    private final MemoryService memories;

    public MemoryController(MemoryService memories) {
        this.memories = memories;
    }

    @GetMapping("/api/memories")
    public List<Map<String, Object>> list(CurrentUser user) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (MemoryItem item : memories.list(user)) {
            rows.add(dump(item));
        }
        return rows;
    }

    @PostMapping("/api/memories")
    public Map<String, Object> create(CurrentUser user, @RequestBody MemoryWrite payload) {
        MemoryItem row = memories.create(user, payload.content(), payload.kind(), "user", null);
        return dump(row);
    }

    @PatchMapping("/api/memories/{id}")
    public Map<String, Object> update(CurrentUser user, @PathVariable Long id, @RequestBody MemoryWrite payload) {
        return dump(memories.update(user, id, payload.content(), payload.kind(), payload.pinned()));
    }

    @DeleteMapping("/api/memories/{id}")
    public Map<String, Object> delete(CurrentUser user, @PathVariable Long id) {
        memories.delete(user, id);
        return Map.of("ok", true);
    }

    private static Map<String, Object> dump(MemoryItem row) {
        return Jsons.ordered(
                "id", row.getId(),
                "content", row.getContent(),
                "kind", row.getKind(),
                "kind_label", MemoryService.kindLabel(row.getKind()),
                "source", row.getSource(),
                "pinned", row.isPinned(),
                "created_at", Jsons.iso(row.getCreatedAt()),
                "updated_at", Jsons.iso(row.getUpdatedAt()));
    }

    public record MemoryWrite(String content, String kind, Boolean pinned) {}
}
