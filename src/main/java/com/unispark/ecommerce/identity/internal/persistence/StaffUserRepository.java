package com.unispark.ecommerce.identity.internal.persistence;

import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

/** Tenant-scoped: every query only sees the staff of the store in {@code TenantContext}. */
public interface StaffUserRepository extends JpaRepository<StaffUser, UUID> {

    Optional<StaffUser> findByEmail(String email);

    Optional<StaffUser> findByPhone(String phone);

    boolean existsByEmail(String email);

    boolean existsByPhone(String phone);

    /** The member's row, locked until the transaction ends: work on one member's reset links runs one at a time. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from StaffUser s where s.id = :id")
    Optional<StaffUser> lockById(UUID id);
}
