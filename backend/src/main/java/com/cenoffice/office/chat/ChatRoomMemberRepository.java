package com.cenoffice.office.chat;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatRoomMemberRepository extends JpaRepository<ChatRoomMember, Long> {
    boolean existsByRoomIdAndUsername(Long roomId, String username);

    List<ChatRoomMember> findByUsername(String username);

    List<ChatRoomMember> findByRoomId(Long roomId);
}
