package com.example.warehouseManagement.Domains;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/**
 * A business partner with a name, address and contact data. {@link Vendor} (supplier) and
 * {@link Customer} extend it. Column names are unchanged, so no migration is needed. Ids stay in
 * the subclasses so each entity keeps its own id sequence.
 */
@Getter
@Setter
@NoArgsConstructor
@SuperBuilder
@MappedSuperclass
public abstract class Party {
    @Column(name = "name")
    private String name;
    @Column(name = "street")
    private String street;
    @Column(name = "city")
    private String city;
    @Column(name = "state")
    private String state;
    @Column(name = "zipcode")
    private String zipcode;
    @Column(name = "contact_info")
    private String contactInfo;

    /** Role of this party in the business, "Vendor" or "Customer". */
    public abstract String getPartyType();

    /** Single-line address, e.g. "1 Main St Springfield, IL, 62701". */
    public String formattedAddress() {
        return "%s %s, %s, %s".formatted(street, city, state, zipcode);
    }

    /** Property-style accessor so templates can write {@code vendor.formattedAddress}. */
    public String getFormattedAddress() {
        return formattedAddress();
    }

    /** Address using a custom separator between the street, city, state and zipcode. */
    public String formattedAddress(String separator) {
        return String.join(separator, street, city, state, zipcode);
    }
}
