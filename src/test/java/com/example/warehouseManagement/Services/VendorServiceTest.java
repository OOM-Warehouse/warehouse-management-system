package com.example.warehouseManagement.Services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;

import com.example.warehouseManagement.Domains.Vendor;
import com.example.warehouseManagement.Domains.DTOs.AdvancedVendorSearchCriteria;
import com.example.warehouseManagement.Domains.Exceptions.VendorNotFoundException;

@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Import(VendorServiceImpl.class)
class VendorServiceTest {
    @Autowired
    private VendorService service;

    private Vendor create(String name, String city) {
        return service.save(Vendor.builder().name(name).city(city).state("TX").build());
    }

    @Test
    void saveAndFindById() {
        Vendor saved = create("Acme", "Austin");
        assertThat(saved.getId()).isNotNull();
        assertThat(service.findById(saved.getId())).get().extracting(Vendor::getName).isEqualTo("Acme");
        assertThat(service.findById(-1L)).isEmpty();
    }

    @Test
    void updateByIdChangesFields() throws Exception {
        Vendor saved = create("Acme", "Austin");
        Vendor changes = Vendor.builder().name("Acme Ltd").city("Dallas").state("TX").build();
        Vendor updated = service.updateById(saved.getId(), changes);
        assertThat(updated.getId()).isEqualTo(saved.getId());
        assertThat(updated.getName()).isEqualTo("Acme Ltd");
        assertThat(updated.getCity()).isEqualTo("Dallas");
    }

    @Test
    void updateUnknownVendorThrowsNotFound() {
        assertThatThrownBy(() -> service.updateById(999999L, Vendor.builder().name("X").build()))
                .isInstanceOf(VendorNotFoundException.class);
    }

    @Test
    void advancedSearchFiltersByCityAndDeleteRemoves() {
        Vendor a = create("Acme", "Austin");
        create("Bolt", "Boston");
        AdvancedVendorSearchCriteria criteria = new AdvancedVendorSearchCriteria();
        criteria.setCity("aust");
        assertThat(service.findAdvanced(criteria, PageRequest.of(0, 10)).getContent())
                .extracting(Vendor::getName).containsExactly("Acme");
        service.delete(a);
        assertThat(service.findById(a.getId())).isEmpty();
    }
}
