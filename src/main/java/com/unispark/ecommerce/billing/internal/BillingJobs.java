package com.unispark.ecommerce.billing.internal;

import org.jobrunr.jobs.annotations.Recurring;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.modulith.events.ApplicationModuleListener;

import com.unispark.ecommerce.tenant.StoreActivated;
import com.unispark.ecommerce.tenant.StoreRegistered;

/**
 * When billing runs (SPEC 14): daily at 03:00 Syrian time as a JobRunr recurring job, a trial as soon as a store
 * registers, and at startup for stores that have none yet (the local demo stores, stores from before billing).
 * Public only because JobRunr invokes it.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(BillingProperties.class)
@Order(100)
public class BillingJobs implements ApplicationRunner {

    private final BillingCycleService cycle;

    BillingJobs(BillingCycleService cycle) {
        this.cycle = cycle;
    }

    @Recurring(id = "billing-daily", cron = "${app.billing.cron:0 3 * * *}", zoneId = "Asia/Damascus")
    public void runDaily() {
        cycle.run(cycle.today());
    }

    @ApplicationModuleListener
    void on(StoreRegistered event) {
        cycle.startTrial(event.storeId(), cycle.today());
    }

    @ApplicationModuleListener
    void on(StoreActivated event) {
        cycle.resumeBilling(event.storeId(), cycle.today());
    }

    @Override
    public void run(ApplicationArguments args) {
        cycle.startMissingTrials(cycle.today());
    }
}
