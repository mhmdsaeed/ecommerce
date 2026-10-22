package com.unispark.ecommerce.shared.internal.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

/**
 * Warns at startup when the Tailwind stylesheet is missing. Only the Maven build's Tailwind step writes it (into
 * target/classes), so a rebuild that skips that step (an IDE's, or {@code -Dtailwind.skip} after a clean) leaves
 * every page unstyled: {@code /css/app.css} answers 404, which browsers refuse as a stylesheet.
 */
@Component
class StylesheetCheck {

    static final String STYLESHEET = "classpath:static/css/app.css";

    private static final Logger log = LoggerFactory.getLogger(StylesheetCheck.class);

    private final ResourceLoader resources;
    private final String location;

    @Autowired
    StylesheetCheck(ResourceLoader resources) {
        this(resources, STYLESHEET);
    }

    StylesheetCheck(ResourceLoader resources, String location) {
        this.resources = resources;
        this.location = location;
    }

    @EventListener(ApplicationReadyEvent.class)
    void warnIfMissing() {
        if (missing()) {
            log.warn("The stylesheet {} is missing, so pages have no styles. Build it with scripts/tailwind.sh "
                    + "(scripts\\tailwind.cmd on Windows), or start without -Dtailwind.skip", location);
        }
    }

    boolean missing() {
        return !resources.getResource(location).exists();
    }
}
