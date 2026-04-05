package com.claude.code.repository;

import com.claude.code.model.entity.SessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SessionRepository extends JpaRepository<SessionEntity, Long> {

    Optional<SessionEntity> findBySessionId(String sessionId);

    List<SessionEntity> findAllByOrderByUpdatedAtDesc();

    void deleteBySessionId(String sessionId);
}
