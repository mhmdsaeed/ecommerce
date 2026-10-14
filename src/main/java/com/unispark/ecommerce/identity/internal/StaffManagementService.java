package com.unispark.ecommerce.identity.internal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.unispark.ecommerce.identity.FieldProblem;
import com.unispark.ecommerce.identity.StaffManagement;
import com.unispark.ecommerce.identity.StaffPrincipal;
import com.unispark.ecommerce.identity.internal.persistence.StaffUser;
import com.unispark.ecommerce.identity.internal.persistence.StaffUserRepository;
import com.unispark.ecommerce.shared.PhoneNumbers;
import com.unispark.ecommerce.shared.TenantContext;

@Service
@Transactional
@PreAuthorize("hasAnyRole('OWNER', 'MANAGER')")
class StaffManagementService implements StaffManagement {

    private static final Comparator<StaffUser> BY_ROLE_THEN_NAME =
            Comparator.comparing(StaffUser::getRole).thenComparing(StaffUser::getName, String.CASE_INSENSITIVE_ORDER);

    private final StaffUserRepository staff;
    private final PasswordEncoder passwords;

    StaffManagementService(StaffUserRepository staff, PasswordEncoder passwords) {
        this.staff = staff;
        this.passwords = passwords;
    }

    @Override
    @Transactional(readOnly = true)
    public List<StaffMember> list() {
        return staff.findAll().stream()
                .sorted(BY_ROLE_THEN_NAME)
                .map(user -> new StaffMember(user.getId(), user.getName(), user.getEmail(), user.getPhone(),
                        user.getRole(), user.isActive()))
                .toList();
    }

    /**
     * No surrounding transaction: the duplicate-key fallback below must catch the insert failure without leaving
     * an outer transaction marked rollback-only. The checks and the insert each run in their own.
     */
    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public StaffResult add(NewStaffMember member, StaffPrincipal actor) {
        requireActorInContext(actor);
        if (!actor.role().canManage(member.role())) {
            return new NotAllowed();
        }
        var problems = new ArrayList<FieldProblem>();
        String email = null;
        if (member.email() != null && !member.email().isBlank()) {
            email = Credentials.normalizeEmail(member.email()).orElse(null);
            if (email == null) {
                problems.add(new FieldProblem("email", "invalid"));
            } else if (staff.existsByEmail(email)) {
                problems.add(new FieldProblem("email", "taken"));
            }
        }
        String phone = null;
        if (member.phone() != null && !member.phone().isBlank()) {
            phone = PhoneNumbers.normalize(member.phone()).orElse(null);
            if (phone == null) {
                problems.add(new FieldProblem("phone", "invalid"));
            } else if (staff.existsByPhone(phone)) {
                problems.add(new FieldProblem("phone", "taken"));
            }
        }
        if (isBlank(member.email()) && isBlank(member.phone())) {
            problems.add(new FieldProblem("phone", "required"));
        }
        Credentials.passwordProblem(member.password())
                .ifPresent(code -> problems.add(new FieldProblem("password", code)));
        if (!problems.isEmpty()) {
            return new Rejected(List.copyOf(problems));
        }
        try {
            var saved = staff.saveAndFlush(new StaffUser(
                    member.name().strip(), email, phone, passwords.encode(member.password()), member.role()));
            return new Done(saved.getId());
        } catch (DataIntegrityViolationException e) {
            // Someone else added the same email or phone a moment ago.
            return new Rejected(List.of(new FieldProblem(email != null ? "email" : "phone", "taken")));
        }
    }

    @Override
    public StaffResult setActive(UUID staffId, boolean active, StaffPrincipal actor) {
        requireActorInContext(actor);
        var target = staff.findById(staffId);
        if (target.isEmpty()) {
            return new NotFound();
        }
        var user = target.get();
        if (user.getId().equals(actor.staffId()) || !actor.role().canManage(user.getRole())) {
            return new NotAllowed();
        }
        user.setActive(active);
        return new Done(user.getId());
    }

    /** The session filter enters the actor's store; anything else is a programming error. */
    private static void requireActorInContext(StaffPrincipal actor) {
        if (!actor.tenantId().equals(TenantContext.requireTenantId())) {
            throw new IllegalStateException("Staff actor does not belong to the current tenant");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
