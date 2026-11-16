package com.example.warehouseManagement.Services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.example.warehouseManagement.Domains.GoodsReceiptNote;
import com.example.warehouseManagement.Domains.GoodsReceiptNote.GrnStatus;
import com.example.warehouseManagement.Domains.Item;
import com.example.warehouseManagement.Domains.ItemCost;
import com.example.warehouseManagement.Domains.PurchaseOrder;
import com.example.warehouseManagement.Domains.PurchaseOrder.PoStatus;
import com.example.warehouseManagement.Domains.PurchaseOrderLine;
import com.example.warehouseManagement.Domains.Stock;
import com.example.warehouseManagement.Domains.Vendor;
import com.example.warehouseManagement.Domains.Warehouse;
import com.example.warehouseManagement.Domains.WarehouseSection;
import com.example.warehouseManagement.Domains.DTOs.GoodsReceiptNoteDto;
import com.example.warehouseManagement.Domains.DTOs.GoodsReceiptNoteLineDto;
import com.example.warehouseManagement.Domains.Exceptions.GoodsReceiptAlreadyFulfilledException;
import com.example.warehouseManagement.Domains.Exceptions.InvalidReceiptQuantityException;
import com.example.warehouseManagement.Repositories.GoodsReceiptNoteRepository;
import com.example.warehouseManagement.Repositories.StockRepository;
import com.example.warehouseManagement.Repositories.WarehouseSectionRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * Integration tests (real H2 + Flyway schema) for the goods receipt workflow:
 * PO status derivation from cumulative quantities and inventory consistency.
 * Each test is rolled back.
 */
@SpringBootTest
@Transactional
class GoodsReceiptNoteServiceTest {

    @Autowired
    private GoodsReceiptNoteService service;
    @Autowired
    private GoodsReceiptNoteRepository grnRepository;
    @Autowired
    private StockRepository stockRepository;
    @Autowired
    private WarehouseSectionRepository sectionRepository;
    @PersistenceContext
    private EntityManager em;

    private Item itemA;
    private Item itemB;
    private PurchaseOrder po;

    @BeforeEach
    void setUp() {
        if (sectionRepository.findBySectionNumber("00-00-0-0").isEmpty()) {
            Warehouse warehouse = em.merge(Warehouse.builder().build());
            em.persist(WarehouseSection.builder().sectionNumber("00-00-0-0").warehouse(warehouse).build());
        }
        Vendor vendor = em.merge(Vendor.builder().name("GRN Test Vendor").build());
        itemA = persistItem(vendor, 910001, "A");
        itemB = persistItem(vendor, 910002, "B");
        po = PurchaseOrder.builder().vendor(vendor).status(PoStatus.IN_TRANSIT).date(LocalDate.now()).build();
        po.getPurchaseOrderLines().add(line(po, itemA, 10));
        po.getPurchaseOrderLines().add(line(po, itemB, 5));
        em.persist(po);
        em.flush();
    }

    @Test
    void completeReceipt_marksPurchaseOrderReceivedAndAddsStock() {
        GoodsReceiptNote grn = service.findOrCreatePending(po);

        GoodsReceiptNote result = service.fulfill(grn, dto(10, 5));

        assertThat(result.getStatus()).isEqualTo(GrnStatus.FULFILLED);
        assertThat(po.getStatus()).isEqualTo(PoStatus.RECEIVED);
        assertThat(stockOf(itemA)).isEqualTo(10);
        assertThat(stockOf(itemB)).isEqualTo(5);
        assertThat(service.findByPurchaseOrder(po.getId())).isEmpty();
    }

    @Test
    void partialReceipt_marksPartiallyReceivedAndOpensNewPendingGrn() {
        GoodsReceiptNote first = service.findOrCreatePending(po);

        service.fulfill(first, dto(10, 2));   // item B is short

        assertThat(po.getStatus()).isEqualTo(PoStatus.PARTIALLY_RECEIVED);
        assertThat(stockOf(itemB)).isEqualTo(2);
        GoodsReceiptNote next = service.findByPurchaseOrder(po.getId()).orElseThrow();
        assertThat(next.getId()).isNotEqualTo(first.getId());

        // the outstanding 3 x B complete the order; cumulative quantities are compared
        service.fulfill(next, dto(0, 3));
        assertThat(po.getStatus()).isEqualTo(PoStatus.RECEIVED);
        assertThat(stockOf(itemB)).isEqualTo(5);
        assertThat(stockOf(itemA)).isEqualTo(10);
    }

    @Test
    void partialReceiptOnEveryLine_isStillPartiallyReceived() {
        service.fulfill(service.findOrCreatePending(po), dto(4, 1));

        assertThat(po.getStatus()).isEqualTo(PoStatus.PARTIALLY_RECEIVED);
    }

    @Test
    void noReceipt_changesNothing() {
        GoodsReceiptNote grn = service.findOrCreatePending(po);

        assertThat(service.fulfill(grn, dto(0, 0))).isNull();

        assertThat(po.getStatus()).isEqualTo(PoStatus.IN_TRANSIT);
        assertThat(grn.getStatus()).isEqualTo(GrnStatus.PENDING);
        assertThat(stockOf(itemA)).isZero();
        assertThat(stockOf(itemB)).isZero();
    }

    @Test
    void excessReceipt_isRejectedAndNothingIsWritten() {
        GoodsReceiptNote grn = service.findOrCreatePending(po);

        assertThatThrownBy(() -> service.fulfill(grn, dto(11, 1)))
                .isInstanceOf(InvalidReceiptQuantityException.class)
                .hasMessageContaining("exceeds the ordered quantity");

        assertThat(po.getStatus()).isEqualTo(PoStatus.IN_TRANSIT);
        assertThat(stockOf(itemA)).isZero();
        assertThat(stockOf(itemB)).isZero();
    }

    @Test
    void excessAcrossTwoReceipts_isRejected() {
        service.fulfill(service.findOrCreatePending(po), dto(8, 5));
        GoodsReceiptNote second = service.findByPurchaseOrder(po.getId()).orElseThrow();

        // 8 already received + 3 = 11 > 10 ordered
        assertThatThrownBy(() -> service.fulfill(second, dto(3, 0)))
                .isInstanceOf(InvalidReceiptQuantityException.class);
        assertThat(stockOf(itemA)).isEqualTo(8);
    }

    @Test
    void duplicateReceiptProcessing_doesNotIncreaseInventoryTwice() {
        GoodsReceiptNote grn = service.findOrCreatePending(po);
        service.fulfill(grn, dto(10, 5));

        assertThatThrownBy(() -> service.fulfill(grn, dto(10, 5)))
                .isInstanceOf(GoodsReceiptAlreadyFulfilledException.class);

        assertThat(stockOf(itemA)).isEqualTo(10);
        assertThat(stockOf(itemB)).isEqualTo(5);
    }

    @Test
    void repeatedFindOrCreatePending_returnsTheSameGrn() {
        GoodsReceiptNote first = service.findOrCreatePending(po);
        GoodsReceiptNote second = service.findOrCreatePending(po);

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(grnRepository.findAllByPurchaseOrderId(po.getId())).hasSize(1);
    }

    @Test
    void negativeQuantity_isRejected() {
        GoodsReceiptNote grn = service.findOrCreatePending(po);

        assertThatThrownBy(() -> service.fulfill(grn, dto(-1, 5)))
                .isInstanceOf(InvalidReceiptQuantityException.class)
                .hasMessageContaining("negative");
        assertThat(stockOf(itemB)).isZero();
    }

    @Test
    void mismatchedLineCount_isRejected() {
        GoodsReceiptNote grn = service.findOrCreatePending(po);

        assertThatThrownBy(() -> service.fulfill(grn, dto(10)))
                .isInstanceOf(InvalidReceiptQuantityException.class);
        assertThatThrownBy(() -> service.fulfill(grn, null))
                .isInstanceOf(InvalidReceiptQuantityException.class);
    }

    // ---- helpers ----

    private int stockOf(Item item) {
        List<Stock> rows = stockRepository.findByItem(item);
        return rows.stream().mapToInt(Stock::getQtyOnHand).sum();
    }

    private static GoodsReceiptNoteDto dto(int... qtys) {
        GoodsReceiptNoteDto dto = GoodsReceiptNoteDto.builder().build();
        for (int q : qtys) {
            dto.getGoodsReceiptNoteLines().add(GoodsReceiptNoteLineDto.builder().qty(q).build());
        }
        return dto;
    }

    private PurchaseOrderLine line(PurchaseOrder order, Item item, int qty) {
        ItemCost cost = em.merge(ItemCost.builder().item(item).cost(1.0)
                .start(LocalDate.now().minusYears(1)).end(LocalDate.now().plusYears(1)).build());
        return PurchaseOrderLine.builder().purchaseOrder(order).item(item).itemCost(cost).qty(qty).build();
    }

    private Item persistItem(Vendor vendor, int sku, String description) {
        return em.merge(Item.builder().vendor(vendor).sku(sku).description(description).build());
    }
}
