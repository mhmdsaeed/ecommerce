package com.unispark.ecommerce.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ThemePaletteTest {

    @Test
    void hasNoShadesForSomethingThatIsNotAColour() {
        assertThat(ThemePalette.of(null)).isEmpty();
        assertThat(ThemePalette.of("red; background:url(x)")).isEmpty();
    }

    @Test
    void keepsTheChosenColourAsShade500() {
        assertThat(ThemePalette.of("#1e88e5")).get().extracting(ThemePalette::shade500).isEqualTo("#1e88e5");
    }

    @Test
    void darkensLightColoursUntilWhiteTextIsReadable() {
        for (var light : new String[] {"#ffff00", "#ffffff", "#7fdbff", "#ffb300"}) {
            var button = ThemePalette.of(light).orElseThrow().shade600();

            assertThat(ThemePalette.contrastWithWhite(button)).as(light).isGreaterThanOrEqualTo(4.5);
        }
    }

    @Test
    void keepsDarkColoursAsTheyAre() {
        assertThat(ThemePalette.of("#1a237e")).get().extracting(ThemePalette::shade600).isEqualTo("#1a237e");
    }
}
