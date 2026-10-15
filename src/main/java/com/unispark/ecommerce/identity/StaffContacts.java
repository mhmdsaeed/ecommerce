package com.unispark.ecommerce.identity;

import java.util.List;

/**
 * How to reach the current store's staff, for messages to them (SPEC 12). Kept apart from {@link StaffDirectory},
 * which every staff role can read and which holds no contact details.
 */
public interface StaffContacts {

    /** Email addresses of the active owners and managers, who hear about new orders. */
    List<String> managerEmails();
}
