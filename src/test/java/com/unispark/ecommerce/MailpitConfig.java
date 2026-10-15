package com.unispark.ecommerce;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The same Mailpit as docker-compose, as the application's SMTP server, with chaos mode on so tests can make
 * deliveries fail. {@link Mailbox} reads what arrived through Mailpit's API.
 */
@TestConfiguration(proxyBeanMethods = false)
public class MailpitConfig {

    static final int SMTP_PORT = 1025;
    static final int API_PORT = 8025;

    @Bean
    GenericContainer<?> mailpit() {
        return new GenericContainer<>("axllent/mailpit:v1.31")
                .withEnv("MP_ENABLE_CHAOS", "true")
                .withExposedPorts(SMTP_PORT, API_PORT)
                .waitingFor(Wait.forHttp("/livez").forPort(API_PORT));
    }

    @Bean
    DynamicPropertyRegistrar mailProperties(GenericContainer<?> mailpit) {
        return registry -> {
            registry.add("spring.mail.host", mailpit::getHost);
            registry.add("spring.mail.port", () -> mailpit.getMappedPort(SMTP_PORT));
            registry.add("spring.mail.properties.mail.smtp.auth", () -> "false");
            registry.add("spring.mail.properties.mail.smtp.starttls.enable", () -> "false");
            registry.add("spring.mail.properties.mail.smtp.starttls.required", () -> "false");
            registry.add("app.notification.mail-from", () -> "orders@lvh.me");
        };
    }

    @Bean
    Mailbox mailbox(GenericContainer<?> mailpit) {
        return new Mailbox("http://" + mailpit.getHost() + ":" + mailpit.getMappedPort(API_PORT));
    }

    /** Mailpit's API. */
    public static final class Mailbox {

        private final String base;
        private final HttpClient http = HttpClient.newHttpClient();
        private final ObjectMapper json = new ObjectMapper();

        Mailbox(String base) {
            this.base = base;
        }

        /** The emails to {@code address}, newest first. */
        public List<Mail> to(String address) {
            var found = get("/api/v1/search?query=" + URLEncoder.encode("to:\"" + address + "\"", StandardCharsets.UTF_8));
            var mails = new ArrayList<Mail>();
            for (var summary : found.path("messages")) {
                var message = get("/api/v1/message/" + summary.path("ID").asText());
                mails.add(new Mail(message.path("Subject").asText(), message.path("From").path("Address").asText(),
                        message.path("Text").asText(), message.path("HTML").asText()));
            }
            return mails;
        }

        /** Every recipient is refused with 451 while {@code on}. */
        public void refuseRecipients(boolean on) {
            send(HttpRequest.newBuilder(URI.create(base + "/api/v1/chaos"))
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(
                            "{\"Recipient\":{\"ErrorCode\":451,\"Probability\":" + (on ? 100 : 0) + "}}"))
                    .build());
        }

        private JsonNode get(String path) {
            return send(HttpRequest.newBuilder(URI.create(base + path)).GET().build());
        }

        private JsonNode send(HttpRequest request) {
            try {
                var response = http.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    throw new IllegalStateException("Mailpit answered " + response.statusCode() + ": " + response.body());
                }
                return json.readTree(response.body());
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
    }

    public record Mail(String subject, String from, String text, String html) {
    }
}
