package com.unispark.ecommerce.catalog.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.unispark.ecommerce.catalog.Products.OptionDraft;
import com.unispark.ecommerce.shared.FieldProblem;
import com.unispark.ecommerce.shared.LocalizedText;

class VariantMatrixTest {

    @Test
    void parsesCommaListsIncludingTheArabicComma() {
        var parsed = VariantMatrix.parse(List.of(
                new OptionDraft("المقاس", "Size", "S, M ,L", "Small,Medium,Large"),
                new OptionDraft("اللون", null, "أحمر، أزرق", null)));

        assertThat(parsed.problems()).isEmpty();
        assertThat(parsed.options()).hasSize(2);
        assertThat(parsed.options().getFirst().choices())
                .containsExactly(LocalizedText.of("S", "Small"), LocalizedText.of("M", "Medium"), LocalizedText.of("L", "Large"));
        assertThat(parsed.options().getLast().choices())
                .containsExactly(LocalizedText.of("أحمر", null), LocalizedText.of("أزرق", null));
    }

    @Test
    void ignoresBlankRows() {
        var parsed = VariantMatrix.parse(List.of(
                new OptionDraft("", "", "", ""), new OptionDraft("Size", null, "S,M", null), new OptionDraft(null, null, null, null)));

        assertThat(parsed.problems()).isEmpty();
        assertThat(parsed.options()).hasSize(1);
    }

    @Test
    void listsEveryCombinationFirstOptionSlowest() {
        var parsed = VariantMatrix.parse(List.of(
                new OptionDraft("Size", null, "S,M", null), new OptionDraft("Colour", null, "Red,Blue", null)));

        assertThat(VariantMatrix.combinations(parsed.options())).containsExactly(
                List.of("S", "Red"), List.of("S", "Blue"), List.of("M", "Red"), List.of("M", "Blue"));
    }

    @Test
    void hasOneEmptyCombinationWithoutOptions() {
        assertThat(VariantMatrix.combinations(List.of())).containsExactly(List.of());
    }

    @Test
    void identifiesValuesByTheirArabicLabelOrElseEnglish() {
        var parsed = VariantMatrix.parse(List.of(new OptionDraft(null, "Size", null, "S,M")));

        assertThat(VariantMatrix.combinations(parsed.options())).containsExactly(List.of("S"), List.of("M"));
    }

    @Test
    void reportsMissingNamesValuesAndMismatchedTranslations() {
        var parsed = VariantMatrix.parse(List.of(
                new OptionDraft("", "", "S,M", ""),
                new OptionDraft("Colour", null, "", ""),
                new OptionDraft("Fit", null, "a,b,c", "x,y")));

        assertThat(parsed.problems()).containsExactly(
                new FieldProblem("options[0].nameAr", "required"),
                new FieldProblem("options[1].valuesAr", "required"),
                new FieldProblem("options[2].valuesEn", "count-mismatch"));
    }

    @Test
    void reportsDuplicateValuesAndOptionNames() {
        var parsed = VariantMatrix.parse(List.of(
                new OptionDraft("Size", null, "S, s", null), new OptionDraft("Size", null, "x", null)));

        assertThat(parsed.problems()).containsExactly(
                new FieldProblem("options[0].valuesAr", "duplicate"),
                new FieldProblem("options[1].nameAr", "duplicate"));
    }

    @Test
    void capsTheNumberOfVariants() {
        var values = String.join(",", java.util.stream.IntStream.rangeClosed(1, 11).mapToObj(String::valueOf).toList());
        var parsed = VariantMatrix.parse(List.of(
                new OptionDraft("A", null, values, null), new OptionDraft("B", null, values, null)));

        assertThat(parsed.problems()).contains(new FieldProblem("options", "too-many-variants"));
    }
}
