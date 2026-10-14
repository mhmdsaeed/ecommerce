package com.unispark.ecommerce.customer.internal.persistence;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

/** Tenant-scoped: only the current store's codes. */
public interface OtpChallengeRepository extends JpaRepository<OtpChallenge, UUID> {

    long countByPhoneAndCreatedAtAfter(String phone, Instant since);

    /** The newest code sent to the phone, locked so concurrent guesses count one by one. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<OtpChallenge> findFirstByPhoneOrderByCreatedAtDescIdDesc(String phone);

    Optional<OtpChallenge> findByVerificationHash(String verificationHash);
}
