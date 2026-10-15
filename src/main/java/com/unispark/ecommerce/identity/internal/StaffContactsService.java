package com.unispark.ecommerce.identity.internal;

import java.util.Comparator;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.unispark.ecommerce.identity.StaffContacts;
import com.unispark.ecommerce.identity.StaffRole;
import com.unispark.ecommerce.identity.internal.persistence.StaffUser;
import com.unispark.ecommerce.identity.internal.persistence.StaffUserRepository;

@Service
@Transactional(readOnly = true)
class StaffContactsService implements StaffContacts {

    private static final Set<StaffRole> MANAGERS = Set.of(StaffRole.OWNER, StaffRole.MANAGER);

    private final StaffUserRepository staff;

    StaffContactsService(StaffUserRepository staff) {
        this.staff = staff;
    }

    @Override
    public List<String> managerEmails() {
        return staff.findAll().stream()
                .filter(user -> user.isActive() && MANAGERS.contains(user.getRole()) && user.getEmail() != null)
                .sorted(Comparator.comparing(StaffUser::getCreatedAt))
                .map(StaffUser::getEmail)
                .toList();
    }
}
