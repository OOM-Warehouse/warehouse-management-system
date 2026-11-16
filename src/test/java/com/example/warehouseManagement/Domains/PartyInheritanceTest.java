package com.example.warehouseManagement.Domains;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class PartyInheritanceTest {

    private static Vendor vendor() {
        return Vendor.builder().name("Acme").street("1 Main St").city("Springfield").state("IL").zipcode("62701").build();
    }

    private static Customer customer() {
        return Customer.builder().name("Bob").street("9 Oak Ave").city("Austin").state("TX").zipcode("73301").build();
    }

    @Test
    void subclassesAreParties_withTheirOwnPartyType() {
        List<Party> parties = List.of(vendor(), customer());

        assertThat(parties).extracting(Party::getPartyType).containsExactly("Vendor", "Customer");
        assertThat(parties).extracting(Party::getName).containsExactly("Acme", "Bob");
    }

    @Test
    void formattedAddress_overloads() {
        Party v = vendor();

        assertThat(v.formattedAddress()).isEqualTo("1 Main St Springfield, IL, 62701");
        assertThat(v.formattedAddress(" | ")).isEqualTo("1 Main St | Springfield | IL | 62701");
        assertThat(v.getFormattedAddress()).isEqualTo(v.formattedAddress());
    }

    @Test
    void formBindingSetters_stillWork() {
        Customer c = customer();
        c.setId(7L);
        c.setCity("Dallas");

        assertThat(c.getId()).isEqualTo(7L);
        assertThat(c.formattedAddress()).contains("Dallas");
    }
}
