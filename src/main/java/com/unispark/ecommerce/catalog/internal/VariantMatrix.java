package com.unispark.ecommerce.catalog.internal;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import com.unispark.ecommerce.catalog.Products;
import com.unispark.ecommerce.catalog.Products.OptionDraft;
import com.unispark.ecommerce.shared.FieldProblem;
import com.unispark.ecommerce.shared.LocalizedText;

/**
 * Parses option definitions and lists the variant combinations they produce.
 *
 * <p>Values are typed as comma-separated lists; both the Latin comma and the Arabic comma (،) separate. A variant
 * is identified by the label of each of its values, in option order; the label is the Arabic text, or the English
 * text when there is no Arabic. So renaming a value creates a new variant, while adding or removing other values
 * keeps the existing ones and their prices.
 */
final class VariantMatrix {

    private static final Pattern SEPARATOR = Pattern.compile("[,\\u060C]");

    private VariantMatrix() {
    }

    record ParsedOption(LocalizedText name, List<LocalizedText> choices) {
    }

    record Parsed(List<ParsedOption> options, List<FieldProblem> problems) {
    }

    /** Fully blank rows are ignored; field names follow the form ({@code options[1].valuesAr}). */
    static Parsed parse(List<OptionDraft> drafts) {
        var options = new ArrayList<ParsedOption>();
        var problems = new ArrayList<FieldProblem>();
        var names = new HashSet<String>();
        for (int index = 0; index < drafts.size(); index++) {
            var draft = drafts.get(index);
            if (Texts.isBlank(draft.nameAr()) && Texts.isBlank(draft.nameEn())
                    && Texts.isBlank(draft.valuesAr()) && Texts.isBlank(draft.valuesEn())) {
                continue;
            }
            var field = "options[" + index + "].";
            var name = Texts.localizedOrNull(draft.nameAr(), draft.nameEn());
            if (name == null) {
                problems.add(new FieldProblem(field + "nameAr", "required"));
            } else if (!names.add(label(name))) {
                problems.add(new FieldProblem(field + "nameAr", "duplicate"));
            }
            var choices = choices(draft, field, problems);
            if (name != null && !choices.isEmpty()) {
                options.add(new ParsedOption(name, choices));
            }
        }
        if (options.size() > Products.MAX_OPTIONS) {
            problems.add(new FieldProblem("options", "too-many"));
        }
        var combinations = options.stream().mapToLong(option -> option.choices().size()).reduce(1, Math::multiplyExact);
        if (combinations > Products.MAX_VARIANTS) {
            problems.add(new FieldProblem("options", "too-many-variants"));
        }
        return new Parsed(List.copyOf(options), List.copyOf(problems));
    }

    private static List<LocalizedText> choices(OptionDraft draft, String field, List<FieldProblem> problems) {
        var arabic = split(draft.valuesAr());
        var english = split(draft.valuesEn());
        if (arabic.isEmpty() && english.isEmpty()) {
            problems.add(new FieldProblem(field + "valuesAr", "required"));
            return List.of();
        }
        if (!arabic.isEmpty() && !english.isEmpty() && arabic.size() != english.size()) {
            problems.add(new FieldProblem(field + "valuesEn", "count-mismatch"));
            return List.of();
        }
        var count = Math.max(arabic.size(), english.size());
        if (count > Products.MAX_VALUES_PER_OPTION) {
            problems.add(new FieldProblem(field + "valuesAr", "too-many"));
            return List.of();
        }
        var choices = new ArrayList<LocalizedText>();
        var labels = new HashSet<String>();
        for (int i = 0; i < count; i++) {
            var choice = LocalizedText.of(arabic.isEmpty() ? null : arabic.get(i), english.isEmpty() ? null : english.get(i));
            if (!labels.add(label(choice).toLowerCase(Locale.ROOT))) {
                problems.add(new FieldProblem(field + "valuesAr", "duplicate"));
                return List.of();
            }
            choices.add(choice);
        }
        return List.copyOf(choices);
    }

    private static List<String> split(String values) {
        if (Texts.isBlank(values)) {
            return List.of();
        }
        return SEPARATOR.splitAsStream(values).map(String::strip).filter(value -> !value.isEmpty()).toList();
    }

    /** The label identifying a value (or option) inside variants: Arabic when present, else English. */
    static String label(LocalizedText text) {
        return text.ar() != null ? text.ar() : text.en();
    }

    /** Every combination, first option varying slowest; one empty combination when there are no options. */
    static List<List<String>> combinations(List<ParsedOption> options) {
        List<List<String>> result = List.of(List.of());
        for (var option : options) {
            var next = new ArrayList<List<String>>();
            for (var prefix : result) {
                for (var choice : option.choices()) {
                    var combination = new ArrayList<>(prefix);
                    combination.add(label(choice));
                    next.add(List.copyOf(combination));
                }
            }
            result = next;
        }
        return result;
    }
}
