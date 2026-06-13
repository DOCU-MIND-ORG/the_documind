package com.accenture.intern.docmind.repository;

import com.accenture.intern.docmind.entities.RefreshToken;
import com.accenture.intern.docmind.entities.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {
    Optional<RefreshToken> findByToken(String token);
    void deleteByUser(User user);
}
