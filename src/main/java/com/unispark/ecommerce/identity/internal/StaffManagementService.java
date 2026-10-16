package com.unispark.ecommerce.identity.internal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.unispark.ecommerce.identity.StaffManagement;
import com.unispark.ecommerce.identity.StaffPrincipal;
import com.unispark.ecommerce.identity.internal.persistence.StaffUser;
import com.unispark.ecommerce.identity.internal.persistence.StaffUserRepository;
import com.unispark.ecommerce.shared.FieldProblem;
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
    private final PasswordChanges passwordChanges;

    StaffManagementService(StaffUserRepository staff, PasswordEncoder passwords, PasswordChanges passwordChanges) {
        this.staff = staff;
        this.passwords = passwords;
        this.passwordChanges = passwordChanges;
    }

    @Override
    @Transactional(readOnly = true)
    public List<StaffMember> list() {
        return staff.findAll().stream().sorted(BY_ROLE_THEN_NAME).map(StaffManagementService::member).toList();
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
        var contacts = contacts(member.email(), member.phone(), null, problems);
        Credentials.passwordProblem(member.password())
                .ifPresent(code -> problems.add(new FieldProblem("password", code)));
        if (!problems.isEmpty()) {
            return new Rejected(List.copyOf(problems));
        }
        try {
            var saved = staff.saveAndFlush(new StaffUser(member.name().strip(), contacts.email(), contacts.phone(),
                    passwords.encode(member.password()), member.role()));
            return new Done(saved.getId());
        } catch (DataIntegrityViolationException e) {
            // Someone else added the same email or phone a moment ago.
            return new Rejected(List.of(new FieldProblem(contacts.email() != null ? "email" : "phone", "taken")));
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
        if (!manageable(user, actor)) {
            return new NotAllowed();
        }
        user.setActive(active);
        return new Done(user.getId());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<StaffMember> findManageable(UUID staffId, StaffPrincipal actor) {
        requireActorInContext(actor);
        return staff.findById(staffId).filter(user -> manageable(user, actor)).map(StaffManagementService::member);
    }

    /**
     * Contacts are checked against the other members first. Two managers saving the same email at the same moment
     * would still hit the database's unique keys; that fails the request rather than the data.
     */
    @Override
    public StaffResult update(UUID staffId, StaffDetails details, StaffPrincipal actor) {
        requireActorInContext(actor);
        var target = staff.findById(staffId);
        if (target.isEmpty()) {
            return new NotFound();
        }
        var user = target.get();
        if (!manageable(user, actor) || !actor.role().canManage(details.role())) {
            return new NotAllowed();
        }
        var problems = new ArrayList<FieldProblem>();
        if (details.name() == null || details.name().isBlank()) {
            problems.add(new FieldProblem("name", "required"));
        }
        var contacts = contacts(details.email(), details.phone(), user, problems);
        if (!problems.isEmpty()) {
            return new Rejected(List.copyOf(problems));
        }
        user.updateDetails(details.name().strip(), contacts.email(), contacts.phone(), details.role());
        return new Done(user.getId());
    }

    @Override
    public StaffResult setPassword(UUID staffId, String password, StaffPrincipal actor) {
        requireActorInContext(actor);
        var target = staff.findById(staffId);
        if (target.isEmpty()) {
            return new NotFound();
        }
        var user = target.get();
        if (!manageable(user, actor)) {
            return new NotAllowed();
        }
        var problem = Credentials.passwordProblem(password);
        if (problem.isPresent()) {
            return new Rejected(List.of(new FieldProblem("password", problem.get())));
        }
        passwordChanges.change(user, password);
        return new Done(user.getId());
    }

    /** Others only, and only roles below the actor's (owners manage everyone but owners). */
    private static boolean manageable(StaffUser user, StaffPrincipal actor) {
        return !user.getId().equals(actor.staffId()) && actor.role().canManage(user.getRole());
    }

    /**
     * Normalized email and phone, at least one of them, neither used by another member than {@code self} (null
     * when adding). Problems go to {@code problems}.
     */
    private Contacts contacts(String emailInput, String phoneInput, StaffUser self, List<FieldProblem> problems) {
        String email = null;
        if (!isBlank(emailInput)) {
            email = Credentials.normalizeEmail(emailInput).orElse(null);
            if (email == null) {
                problems.add(new FieldProblem("email", "invalid"));
            } else if (!email.equals(self == null ? null : self.getEmail()) && staff.existsByEmail(email)) {
                problems.add(new FieldProblem("email", "taken"));
            }
        }
        String phone = null;
        if (!isBlank(phoneInput)) {
            phone = PhoneNumbers.normalize(phoneInput).orElse(null);
            if (phone == null) {
                problems.add(new FieldProblem("phone", "invalid"));
            } else if (!phone.equals(self == null ? null : self.getPhone()) && staff.existsByPhone(phone)) {
                problems.add(new FieldProblem("phone", "taken"));
            }
        }
        if (isBlank(emailInput) && isBlank(phoneInput)) {
            problems.add(new FieldProblem("phone", "required"));
        }
        return new Contacts(email, phone);
    }

    private record Contacts(String email, String phone) {
    }

    private static StaffMember member(StaffUser user) {
        return new StaffMember(user.getId(), user.getName(), user.getEmail(), user.getPhone(), user.getRole(),
                user.isActive());
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
