package com.unispark.ecommerce.identity.internal;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.unispark.ecommerce.identity.StaffDirectory;
import com.unispark.ecommerce.identity.internal.persistence.StaffUser;
import com.unispark.ecommerce.identity.internal.persistence.StaffUserRepository;

@Service
@Transactional(readOnly = true)
class StaffDirectoryService implements StaffDirectory {

    private final StaffUserRepository staff;

    StaffDirectoryService(StaffUserRepository staff) {
        this.staff = staff;
    }

    @Override
    public List<StaffCard> all() {
        return staff.findAll().stream()
                .sorted(Comparator.comparing(StaffUser::getName, String.CASE_INSENSITIVE_ORDER))
                .map(StaffDirectoryService::card)
                .toList();
    }

    @Override
    public Optional<StaffCard> find(UUID staffId) {
        return staff.findById(staffId).map(StaffDirectoryService::card);
    }

    private static StaffCard card(StaffUser user) {
        return new StaffCard(user.getId(), user.getName(), user.getRole(), user.isActive());
    }
}
