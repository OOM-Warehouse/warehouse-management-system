package com.example.warehouseManagement.Domains;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.example.warehouseManagement.Domains.PurchaseOrder.PoStatus;
import com.example.warehouseManagement.Domains.SalesOrder.SoStatus;
import com.example.warehouseManagement.Repositories.PurchaseOrderRepository;
import com.example.warehouseManagement.Repositories.SalesOrderRepository;
import com.example.warehouseManagement.Services.OrderSummaryService;
import com.example.warehouseManagement.Services.OrderSummaryService.OrderSummaryRow;

/** Purchase and sales orders are used through the AbstractOrder abstraction and behave differently. */
class AbstractOrderPolymorphismTest {

    private static PurchaseOrder purchaseOrder(PoStatus status) {
        PurchaseOrder po = PurchaseOrder.builder().status(status)
                .vendor(Vendor.builder().name("Acme").street("1 Main").city("Town").state("TX").zipcode("75001").build())
                .build();
        po.addLine(Item.builder().build(), 4, ItemCost.builder().cost(2.5).build()); // 10.0
        return po;
    }

    private static SalesOrder salesOrder(SoStatus status) {
        SalesOrder so = SalesOrder.builder().status(status)
                .customer(Customer.builder().name("Bob").street("2 Elm").city("City").state("CA").zipcode("90001").build())
                .build();
        so.addLine(Item.builder().build(), 3, ItemPrice.builder().price(10.0).build()); // 30.0
        return so;
    }

    @Test
    void sameCallDispatchesToTheConcreteSubclass() {
        List<AbstractOrder<?>> orders = List.of(purchaseOrder(PoStatus.IN_TRANSIT), salesOrder(SoStatus.PENDING));

        assertThat(orders).extracting(AbstractOrder::getOrderType).containsExactly("Purchase Order", "Sales Order");
        assertThat(orders).extracting(AbstractOrder::getTotal).containsExactly(10.0, 30.0);
        assertThat(orders).extracting(o -> o.getCounterparty().getPartyType()).containsExactly("Vendor", "Customer");
    }

    @Test
    void isModifiable_followsEachOrderTypesOwnRule() {
        // purchase order: closed when RECEIVED; sales order: closed when SHIPPED
        assertThat(purchaseOrder(PoStatus.PARTIALLY_RECEIVED).isModifiable()).isTrue();
        assertThat(purchaseOrder(PoStatus.RECEIVED).isModifiable()).isFalse();
        assertThat(salesOrder(SoStatus.PARTIALLY_SHIPPED).isModifiable()).isTrue();
        assertThat(salesOrder(SoStatus.SHIPPED).isModifiable()).isFalse();
    }

    @Test
    void statusLabel_comesFromEachSubclassStatusEnum() {
        List<AbstractOrder<?>> orders = List.of(purchaseOrder(PoStatus.PARTIALLY_RECEIVED), salesOrder(SoStatus.SHIPPED));

        assertThat(orders).extracting(AbstractOrder::getStatusLabel)
                .containsExactly("PARTIALLY_RECEIVED", "SHIPPED");
    }

    @Test
    void summaryService_treatsMixedListUniformly() {
        OrderSummaryService service = new OrderSummaryService(null, null);
        List<AbstractOrder<?>> orders = List.of(purchaseOrder(PoStatus.RECEIVED), salesOrder(SoStatus.PENDING));

        List<OrderSummaryRow> rows = service.summarize(orders);

        assertThat(rows).extracting(OrderSummaryRow::orderType).containsExactly("Purchase Order", "Sales Order");
        assertThat(rows).extracting(OrderSummaryRow::modifiable).containsExactly(false, true);
        assertThat(rows).extracting(OrderSummaryRow::counterparty).containsExactly("Acme", "Bob");
        assertThat(rows.get(0).address()).isEqualTo("1 Main, Town, TX, 75001");
        assertThat(service.grandTotal(orders)).isEqualTo(40.0);
    }

    @Test
    void summaryService_openOrders_excludesClosedOnesFromBothRepositories() {
        PurchaseOrderRepository poRepo = org.mockito.Mockito.mock(PurchaseOrderRepository.class);
        SalesOrderRepository soRepo = org.mockito.Mockito.mock(SalesOrderRepository.class);
        org.mockito.Mockito.when(poRepo.findAll())
                .thenReturn(List.of(purchaseOrder(PoStatus.RECEIVED), purchaseOrder(PoStatus.IN_TRANSIT)));
        org.mockito.Mockito.when(soRepo.findAll())
                .thenReturn(List.of(salesOrder(SoStatus.SHIPPED), salesOrder(SoStatus.PENDING)));

        List<OrderSummaryRow> rows = new OrderSummaryService(poRepo, soRepo).summarizeOpenOrders();

        assertThat(rows).extracting(OrderSummaryRow::status).containsExactly("IN_TRANSIT", "PENDING");
    }
}
