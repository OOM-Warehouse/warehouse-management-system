package com.example.warehouseManagement.Domains;

import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/**
 * A supplier. Name and address come from {@link Party}.
 *
 * Relationships: a vendor supplies items (aggregation: items are catalogue entries that outlive the vendor
 * record, so removal never cascades) and receives purchase orders (association).
 */
@Getter
@Setter
@NoArgsConstructor
@SuperBuilder
@Entity
@Table(name="vendor")
public class Vendor extends Party {
    // id keeps its setter: the vendor update form binds the id of the vendor being edited
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    @Column(name = "id")
    private Long id;
    @OneToMany(mappedBy = "vendor", cascade = {CascadeType.PERSIST, CascadeType.MERGE}, fetch = FetchType.LAZY)
    @Builder.Default
    private List<Item> items = new ArrayList<>();
    @OneToMany(mappedBy = "vendor", cascade = {CascadeType.PERSIST, CascadeType.MERGE}, fetch = FetchType.LAZY)
    @Builder.Default
    private List<PurchaseOrder> purchaseOrders = new ArrayList<>();

    @Override
    public String getPartyType() {
        return "Vendor";
    }
}
