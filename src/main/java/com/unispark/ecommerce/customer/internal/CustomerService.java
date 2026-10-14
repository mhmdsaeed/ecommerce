package com.unispark.ecommerce.customer.internal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.unispark.ecommerce.customer.Customers;
import com.unispark.ecommerce.customer.internal.persistence.Customer;
import com.unispark.ecommerce.customer.internal.persistence.CustomerAddress;
import com.unispark.ecommerce.customer.internal.persistence.CustomerAddressRepository;
import com.unispark.ecommerce.customer.internal.persistence.CustomerRepository;
import com.unispark.ecommerce.delivery.Destination;
import com.unispark.ecommerce.shared.FieldProblem;
import com.unispark.ecommerce.shared.Ids;
import com.unispark.ecommerce.shared.PhoneNumbers;
import com.unispark.ecommerce.shared.TenantContext;

/**
 * Customers and addresses. Registering inserts with {@code ON CONFLICT DO NOTHING} and then reads the row, so two
 * checkouts with one new phone at the same moment end up with the same customer instead of a constraint error.
 */
@Service
@Transactional
class CustomerService implements Customers {

    private static final int LOCATION_SCALE = 6;

    private final CustomerRepository customers;
    private final CustomerAddressRepository addresses;
    private final JdbcClient jdbc;

    CustomerService(CustomerRepository customers, CustomerAddressRepository addresses, JdbcClient jdbc) {
        this.customers = customers;
        this.addresses = addresses;
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CustomerView> find(UUID id) {
        return customers.findById(id).map(CustomerService::view);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CustomerView> findByPhone(String phone) {
        return PhoneNumbers.normalize(phone).flatMap(customers::findByPhone).map(CustomerService::view);
    }

    @Override
    public RegisterResult register(String phone, String name) {
        var problems = new ArrayList<FieldProblem>();
        var normalized = PhoneNumbers.normalize(phone);
        if (normalized.isEmpty()) {
            problems.add(new FieldProblem("phone", Destination.clean(phone) == null ? "required" : "invalid"));
        }
        var cleanName = Destination.clean(name);
        if (cleanName != null && cleanName.length() > MAX_NAME_LENGTH) {
            problems.add(new FieldProblem("name", "too-long"));
        }
        if (!problems.isEmpty()) {
            return new Invalid(List.copyOf(problems));
        }
        jdbc.sql("""
                        INSERT INTO customer (id, tenant_id, phone, name, created_at, updated_at, version)
                        VALUES (:id, :tenant, :phone, :name, now(), now(), 0)
                        ON CONFLICT (tenant_id, phone) DO NOTHING
                        """)
                .param("id", Ids.newId()).param("tenant", TenantContext.requireTenantId())
                .param("phone", normalized.get()).param("name", cleanName)
                .update();
        var customer = customers.findByPhone(normalized.get()).orElseThrow();
        if (cleanName != null && !cleanName.equals(customer.getName())) {
            customer.setName(cleanName);
        }
        return new Registered(view(customer));
    }

    @Override
    @Transactional(readOnly = true)
    public List<AddressView> addresses(UUID customerId) {
        return addresses.findByCustomerIdOrderByCreatedAtAscIdAsc(customerId).stream()
                .map(CustomerService::view).toList();
    }

    @Override
    public AddressResult addAddress(UUID customerId, AddressDraft draft) {
        if (!customers.existsById(customerId)) {
            return new NotFound();
        }
        if (addresses.countByCustomerId(customerId) >= MAX_ADDRESSES) {
            return new Invalid(List.of(new FieldProblem("address", "limit")));
        }
        return save(new CustomerAddress(customerId), draft);
    }

    @Override
    public AddressResult updateAddress(UUID customerId, UUID addressId, AddressDraft draft) {
        return addresses.findByIdAndCustomerId(addressId, customerId)
                .map(address -> save(address, draft))
                .orElseGet(NotFound::new);
    }

    @Override
    public AddressResult removeAddress(UUID customerId, UUID addressId) {
        return addresses.findByIdAndCustomerId(addressId, customerId).<AddressResult>map(address -> {
            addresses.delete(address);
            return new AddressSaved(addressId);
        }).orElseGet(NotFound::new);
    }

    private AddressResult save(CustomerAddress address, AddressDraft draft) {
        var problems = new ArrayList<FieldProblem>();
        if (draft.governorate() == null) {
            problems.add(new FieldProblem("governorate", "required"));
        }
        var city = text(draft.city(), "city", MAX_PLACE_LENGTH, problems);
        if (city == null) {
            problems.add(new FieldProblem("city", "required"));
        } else if (Destination.key(city) == null) {
            problems.add(new FieldProblem("city", "invalid"));
        }
        var area = text(draft.area(), "area", MAX_PLACE_LENGTH, problems);
        var landmark = text(draft.landmark(), "landmark", MAX_LANDMARK_LENGTH, problems);
        var details = text(draft.details(), "details", MAX_DETAILS_LENGTH, problems);
        if (landmark == null && details == null) {
            problems.add(new FieldProblem("landmark", "required"));
        }
        String phone = null;
        if (Destination.clean(draft.phone()) != null) {
            phone = PhoneNumbers.normalize(draft.phone()).orElse(null);
            if (phone == null) {
                problems.add(new FieldProblem("phone", "invalid"));
            }
        }
        var lat = draft.lat();
        var lng = draft.lng();
        if ((lat == null) != (lng == null) || lat != null && (lat.abs().compareTo(BigDecimal.valueOf(90)) > 0
                || lng.abs().compareTo(BigDecimal.valueOf(180)) > 0)) {
            problems.add(new FieldProblem("location", "invalid"));
        }
        if (!problems.isEmpty()) {
            return new Invalid(List.copyOf(problems));
        }
        address.update(draft.governorate(), city, area, landmark, details, phone, scaled(lat), scaled(lng));
        addresses.save(address);
        return new AddressSaved(address.getId());
    }

    /** Free text: stripped with single spaces, null when blank; too long is a problem. */
    private static String text(String input, String field, int maxLength, List<FieldProblem> problems) {
        var value = Destination.clean(input);
        if (value != null && value.length() > maxLength) {
            problems.add(new FieldProblem(field, "too-long"));
        }
        return value;
    }

    private static BigDecimal scaled(BigDecimal coordinate) {
        return coordinate == null ? null : coordinate.setScale(LOCATION_SCALE, RoundingMode.HALF_UP);
    }

    private static CustomerView view(Customer customer) {
        return new CustomerView(customer.getId(), customer.getPhone(), customer.getName(), customer.isBlocked(),
                customer.getOrderCount(), customer.getRefusedCount());
    }

    private static AddressView view(CustomerAddress address) {
        return new AddressView(address.getId(), address.getGovernorate(), address.getCity(), address.getArea(),
                address.getLandmark(), address.getDetails(), address.getPhone(), address.getLat(), address.getLng());
    }
}
