package com.accenture.intern.docmind.repository;

import com.accenture.intern.docmind.entity.User;
import com.accenture.intern.docmind.entity.UserPreference;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserPreferenceRepository extends JpaRepository<UserPreference, Long> {

    Optional<UserPreference> findByUser(User user);
}
