package com.cenoffice.office.chat;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatRoomRepository extends JpaRepository<ChatRoom, Long> {
    Optional<ChatRoom> findByRoomKey(String roomKey);
}
