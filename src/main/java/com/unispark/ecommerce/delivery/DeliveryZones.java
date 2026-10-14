package com.unispark.ecommerce.delivery;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.unispark.ecommerce.shared.Currency;
import com.unispark.ecommerce.shared.FieldProblem;
import com.unispark.ecommerce.shared.LocalizedText;
import com.unispark.ecommerce.shared.Money;

/**
 * The current store's delivery zones (SPEC 8). A zone covers a whole governorate, one city, or one area of a city.
 * One zone per destination: names are compared after folding spelling variants, so "المزة" and "المزه" are the
 * same area.
 */
public interface DeliveryZones {

    int MAX_ZONES = 500;
    int MAX_NAME_LENGTH = 80;
    int MAX_ETA_LENGTH = 60;

    /** By governorate, then city and area; a governorate's own zone before its cities, a city's before its areas. */
    List<Zone> list();

    Optional<Zone> find(UUID id);

    ZoneResult create(ZoneDraft draft);

    ZoneResult update(UUID id, ZoneDraft draft);

    ZoneResult setActive(UUID id, boolean active);

    ZoneResult delete(UUID id);

    /** The most specific active zone covering the destination: its area, else its city, else its governorate. */
    Optional<Zone> match(Destination destination);

    /** Where the active zones deliver, for address forms. */
    Coverage coverage();

    /**
     * As typed in the admin form: amounts are text in the zone's currency ({@code 15,000} or Arabic digits). Fee 0
     * is free delivery; blank minimum and free-over mean none.
     */
    record ZoneDraft(Governorate governorate, String city, String area, String fee, Currency currency,
            String minOrder, String freeOver, String etaAr, String etaEn) {
    }

    /**
     * @param eta delivery time as the merchant words it ("خلال 24 ساعة"); null when not given
     */
    record Zone(UUID id, Governorate governorate, String city, String area, Money fee, Money minOrder,
            Money freeOver, LocalizedText eta, boolean active) {

        public Destination destination() {
            return Destination.of(governorate, city, area);
        }

        public boolean coversWholeGovernorate() {
            return city == null;
        }

        public boolean coversWholeCity() {
            return city != null && area == null;
        }
    }

    sealed interface ZoneResult {
    }

    record Saved(UUID id) implements ZoneResult {
    }

    record Invalid(List<FieldProblem> problems) implements ZoneResult {
    }

    record NotFound() implements ZoneResult {
    }

    /** Governorates with at least one active zone, in {@link Governorate} order. */
    record Coverage(List<CoveredGovernorate> governorates) {

        public static final Coverage NONE = new Coverage(List.of());

        public boolean isEmpty() {
            return governorates.isEmpty();
        }

        public Optional<CoveredGovernorate> of(Governorate governorate) {
            return governorates.stream().filter(covered -> covered.governorate() == governorate).findFirst();
        }

        /** City names zones mention in the governorate, for suggestions. */
        public List<String> cities(Governorate governorate) {
            return of(governorate).map(covered -> covered.cities().stream().map(CoveredCity::name).toList())
                    .orElse(List.of());
        }

        /** Area names zones mention in the city (any spelling of it), for suggestions. */
        public List<String> areas(Governorate governorate, String city) {
            var key = Destination.key(city);
            return of(governorate).flatMap(covered -> covered.cities().stream()
                            .filter(candidate -> Destination.key(candidate.name()).equals(key)).findFirst())
                    .map(CoveredCity::areas).orElse(List.of());
        }
    }

    /** @param everywhere a zone covers the whole governorate, so any city in it is delivered to */
    record CoveredGovernorate(Governorate governorate, boolean everywhere, List<CoveredCity> cities) {
    }

    /** @param everywhere a zone covers the whole city, so any area in it is delivered to */
    record CoveredCity(String name, boolean everywhere, List<String> areas) {
    }
}
