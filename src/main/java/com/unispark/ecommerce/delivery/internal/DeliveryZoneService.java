package com.unispark.ecommerce.delivery.internal;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.unispark.ecommerce.delivery.DeliveryZones;
import com.unispark.ecommerce.delivery.Destination;
import com.unispark.ecommerce.delivery.Governorate;
import com.unispark.ecommerce.delivery.internal.persistence.DeliveryZone;
import com.unispark.ecommerce.delivery.internal.persistence.DeliveryZoneRepository;
import com.unispark.ecommerce.shared.Amounts;
import com.unispark.ecommerce.shared.FieldProblem;
import com.unispark.ecommerce.shared.LocalizedText;
import com.unispark.ecommerce.shared.Money;

/** Zones. A store has a few dozen at most, so matching and coverage load the active zones and work in memory. */
@Service
@Transactional
class DeliveryZoneService implements DeliveryZones {

    private static final Comparator<Zone> DISPLAY_ORDER = Comparator.comparing(Zone::governorate)
            .thenComparing(zone -> Objects.requireNonNullElse(Destination.key(zone.city()), ""))
            .thenComparing(zone -> Objects.requireNonNullElse(Destination.key(zone.area()), ""));

    private final DeliveryZoneRepository zones;

    DeliveryZoneService(DeliveryZoneRepository zones) {
        this.zones = zones;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Zone> list() {
        return zones.findAll().stream().map(DeliveryZoneService::view).sorted(DISPLAY_ORDER).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Zone> find(UUID id) {
        return zones.findById(id).map(DeliveryZoneService::view);
    }

    @Override
    public ZoneResult create(ZoneDraft draft) {
        if (zones.count() >= MAX_ZONES) {
            return new Invalid(List.of(new FieldProblem("destination", "limit")));
        }
        var problems = new ArrayList<FieldProblem>();
        var checked = check(draft, null, problems);
        if (!problems.isEmpty()) {
            return new Invalid(List.copyOf(problems));
        }
        var zone = zones.save(new DeliveryZone(checked.destination(), draft.currency(), checked.fee(),
                checked.minOrder(), checked.freeOver(), checked.eta()));
        return new Saved(zone.getId());
    }

    @Override
    public ZoneResult update(UUID id, ZoneDraft draft) {
        var existing = zones.findById(id);
        if (existing.isEmpty()) {
            return new NotFound();
        }
        var problems = new ArrayList<FieldProblem>();
        var checked = check(draft, id, problems);
        if (!problems.isEmpty()) {
            return new Invalid(List.copyOf(problems));
        }
        existing.get().update(checked.destination(), draft.currency(), checked.fee(), checked.minOrder(),
                checked.freeOver(), checked.eta());
        return new Saved(id);
    }

    @Override
    public ZoneResult setActive(UUID id, boolean active) {
        return zones.findById(id).<ZoneResult>map(zone -> {
            zone.setActive(active);
            return new Saved(id);
        }).orElseGet(NotFound::new);
    }

    @Override
    public ZoneResult delete(UUID id) {
        return zones.findById(id).<ZoneResult>map(zone -> {
            zones.delete(zone);
            return new Saved(id);
        }).orElseGet(NotFound::new);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Zone> match(Destination destination) {
        return ZoneMatcher.match(active(), destination);
    }

    @Override
    @Transactional(readOnly = true)
    public Coverage coverage() {
        var byGovernorate = new TreeMap<Governorate, List<Zone>>();
        for (var zone : active()) {
            byGovernorate.computeIfAbsent(zone.governorate(), governorate -> new ArrayList<>()).add(zone);
        }
        var covered = new ArrayList<CoveredGovernorate>();
        byGovernorate.forEach((governorate, inGovernorate) -> {
            var everywhere = inGovernorate.stream().anyMatch(Zone::coversWholeGovernorate);
            // Each city once, by its folded name, written as its first zone writes it; areas likewise.
            var cities = new LinkedHashMap<String, String>();
            var wholeCities = new HashSet<String>();
            var areas = new LinkedHashMap<String, LinkedHashMap<String, String>>();
            inGovernorate.stream().filter(zone -> zone.city() != null).forEach(zone -> {
                var cityKey = Destination.key(zone.city());
                cities.putIfAbsent(cityKey, zone.city());
                if (zone.area() == null) {
                    wholeCities.add(cityKey);
                } else {
                    areas.computeIfAbsent(cityKey, key -> new LinkedHashMap<>())
                            .putIfAbsent(Destination.key(zone.area()), zone.area());
                }
            });
            var cityList = cities.entrySet().stream()
                    .map(city -> new CoveredCity(city.getValue(), wholeCities.contains(city.getKey()),
                            List.copyOf(areas.getOrDefault(city.getKey(), new LinkedHashMap<>()).values())))
                    .toList();
            covered.add(new CoveredGovernorate(governorate, everywhere, cityList));
        });
        return new Coverage(List.copyOf(covered));
    }

    private List<Zone> active() {
        return zones.findByActiveTrue().stream().map(DeliveryZoneService::view).sorted(DISPLAY_ORDER).toList();
    }

    private record Checked(Destination destination, BigDecimal fee, BigDecimal minOrder, BigDecimal freeOver,
            LocalizedText eta) {
    }

    private Checked check(ZoneDraft draft, UUID id, List<FieldProblem> problems) {
        if (draft.governorate() == null) {
            problems.add(new FieldProblem("governorate", "required"));
        }
        var city = checkName(draft.city(), "city", problems);
        var area = checkName(draft.area(), "area", problems);
        if (area != null && city == null) {
            problems.add(new FieldProblem("area", "needs-city"));
        }
        if (draft.currency() == null) {
            problems.add(new FieldProblem("currency", "required"));
        }
        BigDecimal fee = null;
        if (Destination.clean(draft.fee()) == null) {
            problems.add(new FieldProblem("fee", "required"));
        } else {
            fee = Amounts.parse(draft.fee()).orElse(null);
            if (fee == null) {
                problems.add(new FieldProblem("fee", "invalid"));
            }
        }
        var minOrder = optionalPositive(draft.minOrder(), "minOrder", problems);
        var freeOver = optionalPositive(draft.freeOver(), "freeOver", problems);
        checkLength(draft.etaAr(), "etaAr", problems);
        checkLength(draft.etaEn(), "etaEn", problems);
        if (!problems.isEmpty()) {
            return null;
        }
        var destination = Destination.of(draft.governorate(), city, area);
        var taken = zones.findAll().stream()
                .filter(zone -> !zone.getId().equals(id))
                .anyMatch(zone -> sameDestination(zone.destination(), destination));
        if (taken) {
            problems.add(new FieldProblem("destination", "taken"));
            return null;
        }
        var eta = Destination.clean(draft.etaAr()) == null && Destination.clean(draft.etaEn()) == null
                ? null : LocalizedText.of(Destination.clean(draft.etaAr()), Destination.clean(draft.etaEn()));
        return new Checked(destination, fee, minOrder, freeOver, eta);
    }

    private static String checkName(String input, String field, List<FieldProblem> problems) {
        var name = Destination.clean(input);
        if (name == null) {
            return null;
        }
        if (name.length() > MAX_NAME_LENGTH) {
            problems.add(new FieldProblem(field, "too-long"));
        } else if (Destination.key(name) == null) {
            problems.add(new FieldProblem(field, "invalid"));
        }
        return name;
    }

    private static BigDecimal optionalPositive(String input, String field, List<FieldProblem> problems) {
        if (Destination.clean(input) == null) {
            return null;
        }
        var amount = Amounts.parse(input).filter(value -> value.signum() > 0);
        if (amount.isEmpty()) {
            problems.add(new FieldProblem(field, "invalid"));
        }
        return amount.orElse(null);
    }

    private static void checkLength(String input, String field, List<FieldProblem> problems) {
        var text = Destination.clean(input);
        if (text != null && text.length() > MAX_ETA_LENGTH) {
            problems.add(new FieldProblem(field, "too-long"));
        }
    }

    private static boolean sameDestination(Destination one, Destination other) {
        return one.governorate() == other.governorate() && Objects.equals(one.cityKey(), other.cityKey())
                && Objects.equals(one.areaKey(), other.areaKey());
    }

    private static Zone view(DeliveryZone zone) {
        return new Zone(zone.getId(), zone.getGovernorate(), zone.getCity(), zone.getArea(),
                Money.of(zone.getFee(), zone.getCurrency()), money(zone.getMinOrder(), zone),
                money(zone.getFreeOver(), zone), zone.getEta(), zone.isActive());
    }

    private static Money money(BigDecimal amount, DeliveryZone zone) {
        return amount == null ? null : Money.of(amount, zone.getCurrency());
    }
}
