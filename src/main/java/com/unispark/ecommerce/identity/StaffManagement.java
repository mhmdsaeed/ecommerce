package com.unispark.ecommerce.identity;

import java.util.List;
import java.util.UUID;

/**
 * Staff of the current store (the tenant in context). Owners manage everyone but owners; managers manage staff
 * and drivers ({@link StaffRole#canManage}).
 */
public interface StaffManagement {

    List<StaffMember> list();

    StaffResult add(NewStaffMember member, StaffPrincipal actor);

    StaffResult setActive(UUID staffId, boolean active, StaffPrincipal actor);

    record StaffMember(UUID id, String name, String email, String phone, StaffRole role, boolean active) {
    }

    record NewStaffMember(String name, String email, String phone, StaffRole role, String password) {
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
