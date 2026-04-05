package com.claude.code.repository;

import com.claude.code.model.entity.SessionMessageEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SessionMessageRepository extends JpaRepository<SessionMessageEntity, Long> {

    List<SessionMessageEntity> findBySessionSessionIdOrderByTimestampAsc(String sessionId);

    void deleteBySessionSessionId(String sessionId);
}
