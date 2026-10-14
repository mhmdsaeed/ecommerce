package com.unispark.ecommerce.storefront.internal.web;

import java.util.UUID;

import com.unispark.ecommerce.delivery.Destination;
import com.unispark.ecommerce.delivery.Governorate;
import com.unispark.ecommerce.order.Checkout.Request;
import com.unispark.ecommerce.payment.PaymentMethod;

/** The checkout form. Lengths and formats are checked by checkout itself, which reports them per field. */
public class CheckoutForm {

    private UUID idempotencyKey;
    private String name;
    private String phone;
    private Governorate governorate;
    private String city;
    private String area;
    private String landmark;
    private String details;
    private PaymentMethod paymentMethod;
    private String notes;
    private String expectedTotal;

    static CheckoutForm start(Destination destination) {
        var form = new CheckoutForm();
        form.idempotencyKey = UUID.randomUUID();
        if (destination != null) {
            form.governorate = destination.governorate();
            form.city = destination.city();
            form.area = destination.area();
        }
        return form;
    }

    Destination destination() {
        return governorate == null ? null : Destination.of(governorate, city, area);
    }

    Request toRequest(String cartToken, String phoneVerification) {
        return new Request(cartToken, idempotencyKey, name, phone, phoneVerification, governorate, city, area,
                landmark, details, paymentMethod, notes, expectedTotal);
    }

    public UUID getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(UUID idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public Governorate getGovernorate() {
        return governorate;
    }

    public void setGovernorate(Governorate governorate) {
        this.governorate = governorate;
    }

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
    }

    public String getArea() {
        return area;
    }

    public void setArea(String area) {
        this.area = area;
    }

    public String getLandmark() {
        return landmark;
    }

    public void setLandmark(String landmark) {
        this.landmark = landmark;
    }

    public String getDetails() {
        return details;
    }

    public void setDetails(String details) {
        this.details = details;
    }

    public PaymentMethod getPaymentMethod() {
        return paymentMethod;
    }

    public void setPaymentMethod(PaymentMethod paymentMethod) {
        this.paymentMethod = paymentMethod;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public String getExpectedTotal() {
        return expectedTotal;
    }

    public void setExpectedTotal(String expectedTotal) {
        this.expectedTotal = expectedTotal;
    }
}
