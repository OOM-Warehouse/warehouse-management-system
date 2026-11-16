package com.example.warehouseManagement.Services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;

import com.example.warehouseManagement.Domains.Backorder;
import com.example.warehouseManagement.Domains.Customer;
import com.example.warehouseManagement.Domains.Item;
import com.example.warehouseManagement.Domains.PickingJob;
import com.example.warehouseManagement.Domains.PickingJob.PjStatus;
import com.example.warehouseManagement.Domains.PickingJobLine;
import com.example.warehouseManagement.Domains.SalesOrder;
import com.example.warehouseManagement.Domains.Stock;
import com.example.warehouseManagement.Domains.Vendor;
import com.example.warehouseManagement.Domains.Warehouse;
import com.example.warehouseManagement.Domains.WarehouseSection;
import com.example.warehouseManagement.Domains.DTOs.PickingJobDto;
import com.example.warehouseManagement.Domains.DTOs.PickingJobLineDto;
import com.example.warehouseManagement.Domains.Exceptions.InvalidPickException;
import com.example.warehouseManagement.Domains.Exceptions.PickingJobAlreadyFulfilledException;
import com.example.warehouseManagement.Repositories.BackorderRepository;
import com.example.warehouseManagement.Repositories.PickingJobRepository;
import com.example.warehouseManagement.Repositories.StockRepository;

@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Import({ PickingJobServiceImpl.class, StockServiceImpl.class })
class PickingJobServiceTest {

    @Autowired
    private PickingJobService pickingJobService;
    @Autowired
    private StockService stockService;
    @Autowired
    private StockRepository stockRepository;
    @Autowired
    private BackorderRepository backorderRepository;
    @Autowired
    private PickingJobRepository pickingJobRepository;
    @Autowired
    private TestEntityManager em;

    private SalesOrder salesOrder;
    private Item itemA;
    private Item itemB;
    private Item itemC;
    private WarehouseSection bin1;
    private WarehouseSection bin2;

    @BeforeEach
    void setUp() {
        Vendor vendor = em.persist(Vendor.builder().name("Acme").build());
        itemA = em.persist(Item.builder().vendor(vendor).sku(1).description("A").build());
        itemB = em.persist(Item.builder().vendor(vendor).sku(2).description("B").build());
        itemC = em.persist(Item.builder().vendor(vendor).sku(3).description("C").build());
        Warehouse w = em.persist(Warehouse.builder().address("1 Main").build());
        bin1 = em.persist(WarehouseSection.builder().warehouse(w).sectionNumber("A1").build());
        bin2 = em.persist(WarehouseSection.builder().warehouse(w).sectionNumber("A2").build());
        Customer customer = em.persist(Customer.builder().name("Bob").build());
        salesOrder = em.persist(SalesOrder.builder().customer(customer).build());
    }

    private void stock(WarehouseSection bin, Item item, int qty) {
        em.persist(Stock.builder().warehouseSection(bin).item(item).qtyOnHand(qty).build());
    }

    private PickingJob job(Object... itemAndQty) {
        PickingJob job = em.persist(PickingJob.builder().salesOrder(salesOrder).build());
        for (int i = 0; i < itemAndQty.length; i += 2) {
            PickingJobLine line = em.persist(PickingJobLine.builder().pickingJob(job)
                    .item((Item) itemAndQty[i]).qtyToPick((Integer) itemAndQty[i + 1]).build());
            job.getPickingJobLines().add(line);
        }
        em.flush();
        return job;
    }

    private PickingJobDto dto(PickingJob job, Object... binAndQty) {
        PickingJobDto dto = new PickingJobDto();
        for (int i = 0; i < binAndQty.length; i += 2) {
            WarehouseSection bin = (WarehouseSection) binAndQty[i];
            dto.getPickingJobDtoLines().add(PickingJobLineDto.builder()
                    .pickingJobLineId(job.getPickingJobLines().get(i / 2).getId())
                    .wareHouseSectionId(bin == null ? 0L : bin.getId())
                    .qtyPicked((Integer) binAndQty[i + 1]).build());
        }
        return dto;
    }

    private int onHand(WarehouseSection bin, Item item) {
        em.flush();
        em.clear();
        return stockService.availableAt(bin.getId(), item.getId());
    }

    private List<Backorder> backorders() {
        em.flush();
        em.clear();
        return backorderRepository.findBySalesOrder(salesOrder);
    }

    @Test
    void fullyAvailable_picksEverythingAndCreatesNoBackorder() {
        stock(bin1, itemA, 10);
        PickingJob job = job(itemA, 4);

        PickingJob result = pickingJobService.fulfill(job, dto(job, bin1, 4));

        assertThat(result.getStatus()).isEqualTo(PjStatus.FULFILLED);
        assertThat(result.getPickingJobLines()).hasSize(1);
        assertThat(result.getPickingJobLines().get(0).getQtyPicked()).isEqualTo(4);
        assertThat(onHand(bin1, itemA)).isEqualTo(6);
        assertThat(backorders()).isEmpty();
        assertThat(pickingJobRepository.count()).isEqualTo(1);
    }

    @Test
    void noneAvailable_throwsAndLeavesJobPendingWithoutSideEffects() {
        PickingJob job = job(itemA, 5);

        assertThatThrownBy(() -> pickingJobService.fulfill(job, dto(job, (Object) null, 0)))
                .isInstanceOf(InvalidPickException.class);
        // chose a bin that holds no stock of the item
        assertThatThrownBy(() -> pickingJobService.fulfill(job, dto(job, bin1, 5)))
                .isInstanceOf(InvalidPickException.class);

        assertThat(job.getStatus()).isEqualTo(PjStatus.PENDING);
        assertThat(backorders()).isEmpty();
        assertThat(pickingJobRepository.count()).isEqualTo(1);
    }

    @Test
    void partiallyAvailable_picksWhatIsThereAndBackordersTheRest() {
        stock(bin1, itemA, 4);
        PickingJob job = job(itemA, 10);

        PickingJob result = pickingJobService.fulfill(job, dto(job, bin1, 10));

        PickingJobLine line = result.getPickingJobLines().get(0);
        assertThat(line.getQtyPicked()).isEqualTo(4); // not treated as fully fulfilled
        assertThat(line.getQtyToPick()).isEqualTo(10);
        assertThat(onHand(bin1, itemA)).isZero();
        List<Backorder> bos = backorders();
        assertThat(bos).hasSize(1);
        assertThat(bos.get(0).getQty()).isEqualTo(6);
        List<PickingJob> jobs = pickingJobRepository.findAll();
        assertThat(jobs).hasSize(2);
        PickingJob backorderJob = jobs.stream().filter(PickingJob::isPending).findFirst().orElseThrow();
        assertThat(backorderJob.getPickingJobLines()).hasSize(1);
        assertThat(backorderJob.getPickingJobLines().get(0).getQtyToPick()).isEqualTo(6);
    }

    @Test
    void multipleLinesOfSameItemShareTheBinWithoutOverdrawing() {
        stock(bin1, itemA, 5);
        PickingJob job = job(itemA, 3, itemA, 4);

        PickingJob result = pickingJobService.fulfill(job, dto(job, bin1, 3, bin1, 4));

        assertThat(result.getPickingJobLines()).hasSize(2);
        assertThat(result.getPickingJobLines().get(0).getQtyPicked()).isEqualTo(3);
        assertThat(result.getPickingJobLines().get(1).getQtyPicked()).isEqualTo(2);
        assertThat(onHand(bin1, itemA)).isZero(); // never negative
        List<Backorder> bos = backorders();
        assertThat(bos).hasSize(1);
        assertThat(bos.get(0).getQty()).isEqualTo(2);
    }

    @Test
    void multipleItemsWithDifferentAvailability() {
        stock(bin1, itemA, 10);
        stock(bin2, itemB, 2);
        PickingJob job = job(itemA, 5, itemB, 6, itemC, 3);

        PickingJob result = pickingJobService.fulfill(job, dto(job, bin1, 5, bin2, 6, (Object) null, 0));

        assertThat(result.getPickingJobLines()).extracting(PickingJobLine::getQtyPicked)
                .containsExactly(5, 2, 0);
        assertThat(onHand(bin1, itemA)).isEqualTo(5);
        assertThat(onHand(bin2, itemB)).isZero();
        List<Backorder> bos = backorders();
        assertThat(bos).extracting(b -> b.getItem().getSku(), Backorder::getQty)
                .containsExactlyInAnyOrder(tuple(2, 4), tuple(3, 3));
        // one single follow-up picking job for all remainders
        assertThat(pickingJobRepository.findAll()).hasSize(2);
    }

    @Test
    void duplicateFulfilmentIsRejectedAndDoesNotDeductOrBackorderTwice() {
        stock(bin1, itemA, 4);
        PickingJob job = job(itemA, 10);
        PickingJobDto dto = dto(job, bin1, 10);
        pickingJobService.fulfill(job, dto);

        assertThatThrownBy(() -> pickingJobService.fulfill(job, dto))
                .isInstanceOf(PickingJobAlreadyFulfilledException.class);

        assertThat(onHand(bin1, itemA)).isZero();
        List<Backorder> bos = backorders();
        assertThat(bos).hasSize(1);
        assertThat(bos.get(0).getQty()).isEqualTo(6);
        assertThat(pickingJobRepository.count()).isEqualTo(2);
    }

    @Test
    void insufficientInventoryNeverMakesStockNegative() {
        stock(bin1, itemA, 3);
        PickingJob job = job(itemA, 8);

        pickingJobService.fulfill(job, dto(job, bin1, 8));

        assertThat(onHand(bin1, itemA)).isZero();
        assertThat(stockRepository.findAll()).allSatisfy(s -> assertThat(s.getQtyOnHand()).isPositive());
    }

    @Test
    void invalidQuantitiesAreRejectedWithoutTouchingStock() {
        stock(bin1, itemA, 10);
        PickingJob job = job(itemA, 5);

        assertThatThrownBy(() -> pickingJobService.fulfill(job, dto(job, bin1, -1)))
                .isInstanceOf(InvalidPickException.class);
        assertThatThrownBy(() -> pickingJobService.fulfill(job, dto(job, bin1, 6))) // more than asked
                .isInstanceOf(InvalidPickException.class);
        assertThatThrownBy(() -> pickingJobService.fulfill(job, new PickingJobDto())) // size mismatch
                .isInstanceOf(InvalidPickException.class);

        assertThat(job.getStatus()).isEqualTo(PjStatus.PENDING);
        assertThat(onHand(bin1, itemA)).isEqualTo(10);
    }

    @Test
    void fulfillingTheBackorderJobResolvesTheBackorderWithoutDuplicates() {
        stock(bin1, itemA, 4);
        PickingJob job = job(itemA, 10);
        pickingJobService.fulfill(job, dto(job, bin1, 10));
        em.flush();
        em.clear();
        PickingJob backorderJob = pickingJobRepository.findAll().stream()
                .filter(PickingJob::isPending).findFirst().orElseThrow();
        stock(bin1, itemA, 20);
        em.flush();

        pickingJobService.fulfill(backorderJob, dto(backorderJob, bin1, 6));

        assertThat(backorders()).isEmpty();
        assertThat(onHand(bin1, itemA)).isEqualTo(14);
        assertThat(pickingJobRepository.count()).isEqualTo(2);
    }

    @Test
    void decreaseAtConsumesMultipleRowsOldestFirst() {
        stock(bin1, itemA, 2);
        stock(bin1, itemA, 5);
        em.flush();

        stockService.decreaseAt(bin1.getId(), itemA.getId(), 4);

        assertThat(onHand(bin1, itemA)).isEqualTo(3);
        assertThatThrownBy(() -> stockService.decreaseAt(bin1.getId(), itemA.getId(), 4))
                .isInstanceOf(InvalidPickException.class);
        assertThat(onHand(bin1, itemA)).isEqualTo(3);
    }
}
