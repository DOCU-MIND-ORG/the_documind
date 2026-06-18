package com.accenture.intern.docmind.repository;

import com.accenture.intern.docmind.entity.Otp;
import com.accenture.intern.docmind.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface OtpRepository extends JpaRepository<Otp, Long> {

    Optional<Otp> findTopByUserOrderByIdDesc(User user);

    Optional<Otp> findByVerificationToken(String verificationToken);

    void deleteByUser(User user);
}
