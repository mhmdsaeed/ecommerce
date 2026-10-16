package com.unispark.ecommerce.identity;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.unispark.ecommerce.shared.FieldProblem;

/**
 * Staff of the current store (the tenant in context). Owners manage everyone but owners; managers manage staff
 * and drivers ({@link StaffRole#canManage}).
 */
public interface StaffManagement {

    List<StaffMember> list();

    StaffResult add(NewStaffMember member, StaffPrincipal actor);

    StaffResult setActive(UUID staffId, boolean active, StaffPrincipal actor);

    /** A member the actor may manage; empty for unknown ids, the actor themself, and roles above theirs. */
    Optional<StaffMember> findManageable(UUID staffId, StaffPrincipal actor);

    /**
     * Name, contacts and role. The actor must be allowed to manage both the old and the new role; a new role ends
     * the member's sessions.
     */
    StaffResult update(UUID staffId, StaffDetails details, StaffPrincipal actor);

    /**
     * A new password, for a member who forgot theirs. Ends their sessions, and closes any reset link they were sent.
     */
    StaffResult setPassword(UUID staffId, String password, StaffPrincipal actor);

    record StaffMember(UUID id, String name, String email, String phone, StaffRole role, boolean active) {
    }

    record NewStaffMember(String name, String email, String phone, StaffRole role, String password) {
    }

    record StaffDetails(String name, String email, String phone, StaffRole role) {
    }

    sealed interface StaffResult {
    }

    record Done(UUID staffId) implements StaffResult {
    }

    record Rejected(List<FieldProblem> problems) implements StaffResult {
    }

    /** The actor may not manage that role, or tried to change their own account. */
    record NotAllowed() implements StaffResult {
    }

    record NotFound() implements StaffResult {
    }
}
