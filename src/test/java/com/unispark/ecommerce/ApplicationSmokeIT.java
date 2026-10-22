package com.unispark.ecommerce;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
class ApplicationSmokeIT {

    @Autowired
    TestRestTemplate http;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void reportsHealthUpIncludingDatabase() {
        var response = http.getForEntity("/actuator/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"UP\"").contains("\"db\"");
    }

    @Test
    void appliesFlywayMigrationsOnStartup() {
        var versions = jdbc.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank", String.class);

        assertThat(versions).contains("1");
    }

    @Test
    void servesTheTailwindStylesheet() {
        var response = http.getForEntity("/css/app.css", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("tailwindcss")
                // Dark admin screens relight the brand text a pseudo-element carries too: a file input's button.
                .contains(":root[data-dark] .file\\:text-brand-700::file-selector-button{color:#4fd8a4}")
                // and a chosen filter stands out as it does in light mode.
                .contains(":root[data-dark] main a[aria-current=page].bg-gray-900{");
    }

    @Test
    void doesNotExposeActuatorEndpointsBeyondHealthAndInfo() {
        assertThat(http.getForEntity("/actuator/env", String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(http.getForEntity("/actuator/prometheus", String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
