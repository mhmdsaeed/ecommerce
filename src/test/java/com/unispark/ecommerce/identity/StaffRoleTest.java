package com.unispark.ecommerce.identity;

import static com.unispark.ecommerce.identity.StaffRole.DRIVER;
import static com.unispark.ecommerce.identity.StaffRole.MANAGER;
import static com.unispark.ecommerce.identity.StaffRole.OWNER;
import static com.unispark.ecommerce.identity.StaffRole.STAFF;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

class StaffRoleTest {

    @Test
    void ownersManageEveryoneButOwners() {
        assertThat(manageable(OWNER)).containsExactly(MANAGER, STAFF, DRIVER);
    }

    @Test
    void managersManageStaffAndDrivers() {
        assertThat(manageable(MANAGER)).containsExactly(STAFF, DRIVER);
    }

    @Test
    void staffAndDriversManageNobody() {
        assertThat(manageable(STAFF)).isEmpty();
        assertThat(manageable(DRIVER)).isEmpty();
    }

    @Test
    void onlyDriversStayOutOfTheMerchantAdmin() {
        assertThat(Arrays.stream(StaffRole.values()).filter(StaffRole::usesMerchantAdmin))
                .containsExactly(OWNER, MANAGER, STAFF);
    }

    @Test
    void mapsToSpringSecurityAuthorities() {
        assertThat(DRIVER.authority()).isEqualTo("ROLE_DRIVER");
    }

    private static StaffRole[] manageable(StaffRole actor) {
        return Arrays.stream(StaffRole.values()).filter(actor::canManage).toArray(StaffRole[]::new);
    }
}
