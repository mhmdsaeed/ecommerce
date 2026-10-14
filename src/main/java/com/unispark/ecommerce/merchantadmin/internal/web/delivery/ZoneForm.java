package com.unispark.ecommerce.merchantadmin.internal.web.delivery;

import jakarta.validation.constraints.Size;

import com.unispark.ecommerce.delivery.DeliveryZones;
import com.unispark.ecommerce.delivery.DeliveryZones.Zone;
import com.unispark.ecommerce.delivery.DeliveryZones.ZoneDraft;
import com.unispark.ecommerce.delivery.Governorate;
import com.unispark.ecommerce.shared.Currency;
import com.unispark.ecommerce.shared.Money;

/** Delivery zone form backing object. Amounts stay text; the service parses them (Arabic digits, grouping). */
public class ZoneForm {

    private Governorate governorate;

    @Size(max = DeliveryZones.MAX_NAME_LENGTH)
    private String city;

    @Size(max = DeliveryZones.MAX_NAME_LENGTH)
    private String area;

    @Size(max = 30)
    private String fee;

    private Currency currency;

    @Size(max = 30)
    private String minOrder;

    @Size(max = 30)
    private String freeOver;

    @Size(max = DeliveryZones.MAX_ETA_LENGTH)
    private String etaAr;

    @Size(max = DeliveryZones.MAX_ETA_LENGTH)
    private String etaEn;

    static ZoneForm empty(Currency currency) {
        var form = new ZoneForm();
        form.currency = currency;
        return form;
    }

    static ZoneForm of(Zone zone) {
        var form = new ZoneForm();
        form.governorate = zone.governorate();
        form.city = zone.city();
        form.area = zone.area();
        form.fee = plain(zone.fee());
        form.currency = zone.fee().currency();
        form.minOrder = plain(zone.minOrder());
        form.freeOver = plain(zone.freeOver());
        form.etaAr = zone.eta() == null ? null : zone.eta().ar();
        form.etaEn = zone.eta() == null ? null : zone.eta().en();
        return form;
    }

    ZoneDraft toDraft() {
        return new ZoneDraft(governorate, city, area, fee, currency, minOrder, freeOver, etaAr, etaEn);
    }

    /** Western digits without grouping, as inputs show amounts; whole amounts without decimals. */
    private static String plain(Money money) {
        return money == null ? null : money.amount().stripTrailingZeros().toPlainString();
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

    public String getFee() {
        return fee;
    }

    public void setFee(String fee) {
        this.fee = fee;
    }

    public Currency getCurrency() {
        return currency;
    }

    public void setCurrency(Currency currency) {
        this.currency = currency;
    }

    public String getMinOrder() {
        return minOrder;
    }

    public void setMinOrder(String minOrder) {
        this.minOrder = minOrder;
    }

    public String getFreeOver() {
        return freeOver;
    }

    public void setFreeOver(String freeOver) {
        this.freeOver = freeOver;
    }

    public String getEtaAr() {
        return etaAr;
    }

    public void setEtaAr(String etaAr) {
        this.etaAr = etaAr;
    }

    public String getEtaEn() {
        return etaEn;
    }

    public void setEtaEn(String etaEn) {
        this.etaEn = etaEn;
    }
}
