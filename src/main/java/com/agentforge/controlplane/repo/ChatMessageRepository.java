package com.agentforge.controlplane.repo;

import com.agentforge.controlplane.domain.ChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {
    List<ChatMessage> findBySessionIdOrderByIdAsc(String sessionId);

    void deleteBySessionIdIn(Collection<String> sessionIds);

    List<ChatMessage> findBySessionIdAndTenantIdOrderByIdAsc(String sessionId, Long tenantId);

    /** 会话列表的关键字搜索：命中任意一条消息就算命中该会话。 */
    @Query("select distinct m.sessionId from ChatMessage m where m.tenantId = :tenantId and lower(cast(m.content as string)) like lower(concat('%', :keyword, '%'))")
    List<String> findSessionIdsByKeyword(Long tenantId, String keyword);
}
