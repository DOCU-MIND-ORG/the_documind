package com.accenture.intern.docmind.repository;

import com.accenture.intern.docmind.entities.User;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {

    User findByEmail(String email);


    boolean existsByEmail(String email);
}