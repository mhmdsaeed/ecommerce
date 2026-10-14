package com.unispark.ecommerce;

import java.util.List;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Exposes the current tenant's probes over HTTP so tests can check isolation end to end. */
@TestConfiguration(proxyBeanMethods = false)
public class IsolationProbeWeb {

    @RestController
    static class IsolationProbeController {

        private final IsolationProbeRepository probes;

        IsolationProbeController(IsolationProbeRepository probes) {
            this.probes = probes;
        }

        @GetMapping("/test/probes")
        List<String> labels() {
            return probes.findAll().stream().map(IsolationProbe::getLabel).sorted().toList();
        }
    }
}
