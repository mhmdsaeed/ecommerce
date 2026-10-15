package com.unispark.ecommerce.notification.internal;

import java.nio.charset.StandardCharsets;

import jakarta.mail.MessagingException;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import com.unispark.ecommerce.notification.NotificationLog.Channel;
import com.unispark.ecommerce.notification.internal.Message.Medium;

/**
 * Email over SMTP ({@code MAIL_HOST}, {@code MAIL_PORT}, {@code MAIL_USER}, {@code MAIL_PASSWORD}; Mailpit
 * locally). Without a host it carries nothing and emails fall back to the log.
 */
@Component
final class EmailChannel implements NotificationChannel {

    private final JavaMailSender sender;
    private final String from;

    EmailChannel(ObjectProvider<JavaMailSender> sender, @Value("${spring.mail.host:}") String host,
            NotificationProperties properties) {
        var configured = host != null && !host.isBlank();
        if (configured && (properties.mailFrom() == null || properties.mailFrom().isBlank())) {
            throw new IllegalStateException("MAIL_FROM (app.notification.mail-from) is required when MAIL_HOST is set");
        }
        this.sender = configured ? sender.getIfAvailable() : null;
        this.from = properties.mailFrom();
    }

    @Override
    public Channel kind() {
        return Channel.EMAIL;
    }

    @Override
    public boolean carries(Medium medium) {
        return medium == Medium.EMAIL && sender != null;
    }

    @Override
    public String send(Message message) {
        var mime = sender.createMimeMessage();
        try {
            var helper = new MimeMessageHelper(mime, true, StandardCharsets.UTF_8.name());
            helper.setFrom(from);
            helper.setTo(message.to());
            helper.setSubject(message.subject());
            helper.setText(message.text(), message.html());
        } catch (MessagingException e) {
            throw new MailSendException("Could not build the email", e);
        }
        sender.send(mime);
        try {
            return mime.getMessageID();
        } catch (MessagingException e) {
            return null;
        }
    }
}
