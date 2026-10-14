package com.unispark.ecommerce.identity.internal;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.unispark.ecommerce.identity.StaffRole;
import com.unispark.ecommerce.identity.internal.Credentials.Email;
import com.unispark.ecommerce.identity.internal.Credentials.LoginIdentifier;
import com.unispark.ecommerce.identity.internal.Credentials.Phone;
import com.unispark.ecommerce.identity.internal.Credentials.Unusable;
import com.unispark.ecommerce.identity.internal.persistence.StaffUser;
import com.unispark.ecommerce.identity.internal.persistence.StaffUserRepository;

/**
 * Staff lookups for authentication and session checks. Tenant-scoped like every staff query: callers enter the
 * store's tenant first, so a login for one store can never match another store's staff.
 */
@Component
@Transactional(readOnly = true)
public class StaffAccounts {

    private final StaffUserRepository staff;

    StaffAccounts(StaffUserRepository staff) {
        this.staff = staff;
    }

    public Optional<StaffLogin> findForLogin(LoginIdentifier identifier) {
        var user = switch (identifier) {
            case Email(var email) -> staff.findByEmail(email);
            case Phone(var phone) -> staff.findByPhone(phone);
            case Unusable unusable -> Optional.<StaffUser>empty();
        };
        return user.map(found -> new StaffLogin(
                found.getId(), found.getName(), found.getPasswordHash(), found.getRole(), found.isActive()));
    }

    public Optional<StaffState> currentState(UUID staffId) {
        return staff.findById(staffId).map(found -> new StaffState(found.getRole(), found.isActive()));
    }

    public record StaffLogin(UUID id, String name, String passwordHash, StaffRole role, boolean active) {
    }

    public record StaffState(StaffRole role, boolean active) {
    }
}
