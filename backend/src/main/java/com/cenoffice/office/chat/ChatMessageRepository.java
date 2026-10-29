package com.cenoffice.office.chat;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {
    @Query("select m from ChatMessage m where m.roomId = :roomId and (:before is null or m.id < :before) order by m.id desc")
    List<ChatMessage> page(@Param("roomId") Long roomId, @Param("before") Long before, Pageable pageable);
}
