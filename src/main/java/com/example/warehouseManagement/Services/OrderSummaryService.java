package com.example.warehouseManagement.Services;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.warehouseManagement.Domains.AbstractOrder;
import com.example.warehouseManagement.Domains.Party;
import com.example.warehouseManagement.Repositories.PurchaseOrderRepository;
import com.example.warehouseManagement.Repositories.SalesOrderRepository;

/**
 * Reports over purchase orders and sales orders as one list of {@link AbstractOrder}s.
 *
 * Runtime polymorphism: this class never asks "is it a purchase or a sales order?". Every call
 * ({@code getTotal()}, {@code isModifiable()}, {@code getStatusLabel()}, {@code getOrderType()},
 * {@code getCounterparty()}) is dispatched to the concrete subclass at run time.
 */
@Service
public class OrderSummaryService {

    /** One printable row of the summary. */
    public record OrderSummaryRow(String orderType, Long id, String counterpartyType, String counterparty,
            String address, String status, double total, boolean modifiable) {
    }

    private final PurchaseOrderRepository purchaseOrderRepository;
    private final SalesOrderRepository salesOrderRepository;

    public OrderSummaryService(PurchaseOrderRepository purchaseOrderRepository,
            SalesOrderRepository salesOrderRepository) {
        this.purchaseOrderRepository = purchaseOrderRepository;
        this.salesOrderRepository = salesOrderRepository;
    }

    /** Every order that can still be edited (not yet received or shipped), of both kinds. */
    @Transactional(readOnly = true)
    public List<OrderSummaryRow> summarizeOpenOrders() {
        List<AbstractOrder<?>> orders = new ArrayList<>();
        orders.addAll(purchaseOrderRepository.findAll());
        orders.addAll(salesOrderRepository.findAll());
        return summarize(orders.stream().filter(AbstractOrder::isModifiable).toList());
    }

    /** Builds a summary row per order, treating all of them uniformly through the abstraction. */
    public List<OrderSummaryRow> summarize(List<? extends AbstractOrder<?>> orders) {
        return orders.stream()
                .map(this::toRow)
                .sorted(Comparator.comparing(OrderSummaryRow::orderType).thenComparing(OrderSummaryRow::id,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    /** Sum of the totals of the given orders, whatever their kind. */
    public double grandTotal(List<? extends AbstractOrder<?>> orders) {
        double total = 0;
        for (AbstractOrder<?> order : orders) {
            total += order.getTotal();
        }
        return total;
    }

    private OrderSummaryRow toRow(AbstractOrder<?> order) {
        Party party = order.getCounterparty();
        return new OrderSummaryRow(order.getOrderType(), order.getId(),
                party == null ? "-" : party.getPartyType(),
                party == null ? "-" : party.getName(),
                party == null ? "-" : party.formattedAddress(", "),
                order.getStatusLabel(), order.getTotal(), order.isModifiable());
    }
}
