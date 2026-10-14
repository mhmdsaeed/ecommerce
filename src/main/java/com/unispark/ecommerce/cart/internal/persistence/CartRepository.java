package com.unispark.ecommerce.cart.internal.persistence;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Tenant-scoped. A cart past its expiry is treated as gone even before the cleanup job deletes it. */
public interface CartRepository extends JpaRepository<Cart, UUID> {

    @Query("select c from Cart c where c.sessionKey = :key and c.expiresAt > :now")
    Optional<Cart> findLive(@Param("key") String sessionKey, @Param("now") Instant now);

    /** Locks the cart row, so two requests changing the same cart (two tabs) run one after the other. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Cart c where c.sessionKey = :key and c.expiresAt > :now")
    Optional<Cart> findLiveForUpdate(@Param("key") String sessionKey, @Param("now") Instant now);

    @Query("""
            select coalesce(sum(i.quantity), 0) from CartItem i
            where i.cart.sessionKey = :key and i.cart.expiresAt > :now
            """)
    long countItems(@Param("key") String sessionKey, @Param("now") Instant now);
}
