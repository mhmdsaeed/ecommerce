package com.unispark.ecommerce;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
class PlaceholderPageIT {

    @Autowired
    MockMvc mvc;

    @Test
    void rendersArabicRtlPageByDefault() throws Exception {
        mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(content().string(allOf(
                        containsString("lang=\"ar\""),
                        containsString("dir=\"rtl\""),
                        containsString("متجرك على الإنترنت خلال دقائق"),
                        // Content-hashed asset URLs (SPEC 9.5).
                        matchesPattern("(?s).*/css/app-[0-9a-f]{32}\\.css.*"),
                        matchesPattern("(?s).*/js/htmx\\.min-[0-9a-f]{32}\\.js.*"),
                        not(containsString("??")))));
    }

    @Test
    void switchesToEnglishLtrAndRemembersItInACookie() throws Exception {
        mvc.perform(get("/").param("lang", "en"))
                .andExpect(status().isOk())
                .andExpect(cookie().value("lang", "en"))
                .andExpect(content().string(allOf(
                        containsString("lang=\"en\""),
                        containsString("dir=\"ltr\""),
                        containsString("Your online store in minutes"),
                        not(containsString("??")))));

        mvc.perform(get("/").cookie(new Cookie("lang", "en")))
                .andExpect(content().string(containsString("dir=\"ltr\"")));
    }

    @Test
    void ignoresUnsupportedLanguages() throws Exception {
        mvc.perform(get("/").param("lang", "fr"))
                .andExpect(status().isOk())
                .andExpect(cookie().doesNotExist("lang"))
                .andExpect(content().string(containsString("dir=\"rtl\"")));
    }
}
