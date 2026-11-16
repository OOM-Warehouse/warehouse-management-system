package com.example.warehouseManagement.Repositories;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import com.example.warehouseManagement.Domains.Customer;
import com.example.warehouseManagement.Domains.Item;
import com.example.warehouseManagement.Domains.ItemCost;
import com.example.warehouseManagement.Domains.ItemPrice;
import com.example.warehouseManagement.Domains.PurchaseOrder;
import com.example.warehouseManagement.Domains.PurchaseOrderLine;
import com.example.warehouseManagement.Domains.SalesOrder;
import com.example.warehouseManagement.Domains.SalesOrderLine;
import com.example.warehouseManagement.Domains.Vendor;

/**
 * Lifecycle semantics of the relationships (see docs/RELATIONSHIPS.md): order lines are owned by their
 * order (composition), while items, vendors and customers have a life of their own.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
class RelationshipLifecycleTest {

    @Autowired
    private TestEntityManager em;

    private Vendor vendor;
    private Item item;

    private void fixture() {
        vendor = em.persist(Vendor.builder().name("Acme").build());
        item = em.persist(Item.builder().vendor(vendor).sku(1).description("Widget").build());
    }

    private PurchaseOrder persistPoWithTwoLines() {
        ItemCost cost = em.persist(ItemCost.builder().item(item).cost(5.0).build());
        PurchaseOrder po = PurchaseOrder.builder().vendor(vendor).build();
        po.addLine(item, 1, cost);
        po.addLine(item, 2, cost);
        po = em.persist(po); // lines are saved by cascade
        em.flush();
        return po;
    }

    @Test
    void purchaseOrder_savingTheOrderSavesItsLines() {
        fixture();
        PurchaseOrder po = persistPoWithTwoLines();

        assertThat(po.getPurchaseOrderLines()).allSatisfy(l -> assertThat(l.getId()).isNotNull());
    }

    @Test
    void purchaseOrder_removingALineFromTheOrderDeletesIt() {
        fixture();
        PurchaseOrder po = persistPoWithTwoLines();
        PurchaseOrderLine removed = po.getPurchaseOrderLines().get(0);
        Long removedId = removed.getId();

        po.removeLine(removed);
        em.flush();
        em.clear();

        assertThat(em.find(PurchaseOrderLine.class, removedId)).isNull();
        assertThat(em.find(PurchaseOrder.class, po.getId()).getPurchaseOrderLines()).hasSize(1);
    }

    @Test
    void purchaseOrder_deletingTheOrderDeletesAllItsLines() {
        fixture();
        PurchaseOrder po = persistPoWithTwoLines();
        Long lineId = po.getPurchaseOrderLines().get(0).getId();

        em.remove(po);
        em.flush();
        em.clear();

        assertThat(em.find(PurchaseOrderLine.class, lineId)).isNull();
        // the item is only referenced by the lines, it must survive
        assertThat(em.find(Item.class, item.getId())).isNotNull();
    }

    @Test
    void salesOrder_removingALineFromTheOrderDeletesIt() {
        fixture();
        ItemPrice price = em.persist(ItemPrice.builder().item(item).price(9.0).build());
        Customer customer = em.persist(Customer.builder().name("Bob").build());
        SalesOrder so = SalesOrder.builder().customer(customer).build();
        so.addLine(item, 1, price);
        so.addLine(item, 4, price);
        so = em.persist(so);
        em.flush();
        SalesOrderLine removed = so.getSaleOrderLines().get(1);
        Long removedId = removed.getId();

        so.removeLine(removed);
        em.flush();
        em.clear();

        assertThat(em.find(SalesOrderLine.class, removedId)).isNull();
        assertThat(em.find(SalesOrder.class, so.getId()).getSaleOrderLines()).hasSize(1);
        assertThat(em.find(Customer.class, customer.getId())).isNotNull();
    }

    @Test
    void vendor_removeDoesNotCascadeToItsItems() {
        fixture();
        Long itemId = item.getId();
        Long vendorId = vendor.getId();
        em.flush();
        em.clear();

        // Aggregation: the vendor record is removed on its own. Items are re-pointed first
        // (the controller blocks deleting a vendor that still has items or orders).
        Item managed = em.find(Item.class, itemId);
        managed.setVendor(null);
        em.flush();
        em.remove(em.find(Vendor.class, vendorId));
        em.flush();
        em.clear();

        assertThat(em.find(Item.class, itemId)).isNotNull();
        assertThat(em.find(Vendor.class, vendorId)).isNull();
    }
}
