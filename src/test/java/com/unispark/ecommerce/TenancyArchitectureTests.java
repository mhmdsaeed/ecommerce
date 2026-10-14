package com.unispark.ecommerce;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import org.hibernate.annotations.TenantId;
import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.unispark.ecommerce.shared.PlatformDatabase;
import com.unispark.ecommerce.shared.TenantScopedEntity;

/** Code-level guards for the tenancy model (SPEC 4). */
class TenancyArchitectureTests {

    static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.unispark.ecommerce");

    @Test
    void onlyPlatformAdminAndBillingUseTheRlsBypassingDatabase() {
        noClasses().that().resideOutsideOfPackages(
                        "com.unispark.ecommerce.platformadmin..",
                        "com.unispark.ecommerce.billing..",
                        "com.unispark.ecommerce.shared..")
                .should().dependOnClassesThat().areAssignableTo(PlatformDatabase.class)
                .because("PlatformDatabase bypasses row-level security (SPEC 4.3)")
                .check(CLASSES);
    }

    @Test
    void tenantIdIsOnlyMappedByTenantScopedEntity() {
        fields().that().areAnnotatedWith(TenantId.class)
                .should().beDeclaredIn(TenantScopedEntity.class)
                .because("tenant entities extend TenantScopedEntity instead of mapping tenant_id themselves")
                .check(CLASSES);
    }
}
