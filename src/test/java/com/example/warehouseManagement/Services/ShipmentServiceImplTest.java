package com.example.warehouseManagement.Services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;

import com.example.warehouseManagement.Domains.Customer;
import com.example.warehouseManagement.Domains.Item;
import com.example.warehouseManagement.Domains.PickingJob;
import com.example.warehouseManagement.Domains.PickingJob.PjStatus;
import com.example.warehouseManagement.Domains.PickingJobLine;
import com.example.warehouseManagement.Domains.SalesOrder;
import com.example.warehouseManagement.Domains.SalesOrder.SoStatus;
import com.example.warehouseManagement.Domains.SalesOrderLine;
import com.example.warehouseManagement.Domains.Shipment;
import com.example.warehouseManagement.Domains.ShipmentStatus;
import com.example.warehouseManagement.Domains.ShippingMethod;
import com.example.warehouseManagement.Domains.Vendor;
import com.example.warehouseManagement.Domains.Exceptions.DuplicateTrackingNumberException;
import com.example.warehouseManagement.Domains.Exceptions.InvalidShipmentStatusTransitionException;
import com.example.warehouseManagement.Domains.Exceptions.ShipmentNotAllowedException;
import com.example.warehouseManagement.Domains.Exceptions.ShipmentNotFoundException;

@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Import(ShipmentServiceImpl.class)
class ShipmentServiceImplTest {

    @Autowired
    private ShipmentService service;
    @Autowired
    private TestEntityManager em;

    /** Persists an order for orderedQty units and a picking job that picked pickedQty. */
    private PickingJob fixture(int orderedQty, int pickedQty, PjStatus status) {
        Vendor vendor = em.persist(Vendor.builder().name("Acme").build());
        Item item = em.persist(Item.builder().vendor(vendor).sku(100).description("Widget").build());
        Customer customer = em.persist(Customer.builder().name("Bob").city("Town").build());
        SalesOrder so = em.persist(SalesOrder.builder().customer(customer).build());
        em.persist(SalesOrderLine.builder().salesOrder(so).item(item).qty(orderedQty).build());
        return jobFor(so, item, pickedQty, status);
    }

    private PickingJob jobFor(SalesOrder so, Item item, int pickedQty, PjStatus status) {
        PickingJob job = em.persist(PickingJob.builder().salesOrder(so).status(status).build());
        PickingJobLine line = em.persist(PickingJobLine.builder().pickingJob(job).item(item)
                .qtyToPick(pickedQty).qtyPicked(pickedQty).build());
        job.getPickingJobLines().add(line);
        em.flush();
        em.clear();
        return em.find(PickingJob.class, job.getId());
    }

    private void walkToDelivered(Long id) throws Exception {
        service.updateStatus(id, ShipmentStatus.PACKED, null, null, null);
        service.dispatch(id, "Dock");
        service.updateStatus(id, ShipmentStatus.IN_TRANSIT, "Hub", null, null);
        service.updateStatus(id, ShipmentStatus.OUT_FOR_DELIVERY, null, null, null);
        service.recordDelivery(id, "Door", null);
    }

    @Test
    void createShipment_generatesTrackingNumberAndPersistsFirstEvent() throws Exception {
        PickingJob job = fixture(5, 5, PjStatus.FULFILLED);
        Shipment s = service.createShipment(job.getId(), "DHL");
        em.flush();
        em.clear();

        Shipment found = service.findById(s.getId());
        assertThat(found.getTrackingNumber()).startsWith("TRK-").hasSize(14);
        assertThat(found.getStatus()).isEqualTo(ShipmentStatus.CREATED);
        assertThat(found.getShippingMethod()).isEqualTo(ShippingMethod.STANDARD);
        assertThat(service.trackingHistory(s.getId())).hasSize(1);
        assertThat(service.findByTrackingNumber(found.getTrackingNumber().toLowerCase()).getId()).isEqualTo(s.getId());
    }

    @Test
    void createShipment_rejectsUnknownUnfulfilledAndDuplicatePickingJobs() {
        assertThatThrownBy(() -> service.createShipment(999999L, "DHL"))
                .isInstanceOf(ShipmentNotAllowedException.class);
        assertThatThrownBy(() -> service.createShipment(null, "DHL"))
                .isInstanceOf(ShipmentNotAllowedException.class);
        PickingJob pending = fixture(5, 5, PjStatus.PENDING);
        assertThatThrownBy(() -> service.createShipment(pending.getId(), "DHL"))
                .isInstanceOf(ShipmentNotAllowedException.class);

        PickingJob job = fixture(5, 5, PjStatus.FULFILLED);
        service.createShipment(job.getId(), "DHL");
        assertThatThrownBy(() -> service.createShipment(job.getId(), "UPS"))
                .isInstanceOf(ShipmentNotAllowedException.class)
                .hasMessageContaining("already has a shipment");
    }

    @Test
    void duplicateTrackingNumber_isRejected() {
        PickingJob a = fixture(5, 5, PjStatus.FULFILLED);
        PickingJob b = fixture(5, 5, PjStatus.FULFILLED);
        service.createShipment(a.getId(), "DHL", "ABC123", ShippingMethod.STANDARD, null);
        assertThatThrownBy(() -> service.createShipment(b.getId(), "UPS", "abc123", ShippingMethod.EXPRESS, null))
                .isInstanceOf(DuplicateTrackingNumberException.class);
    }

    @Test
    void assignCarrier_rejectsTrackingNumberOfAnotherShipment() throws Exception {
        PickingJob a = fixture(5, 5, PjStatus.FULFILLED);
        PickingJob b = fixture(5, 5, PjStatus.FULFILLED);
        service.createShipment(a.getId(), "DHL", "AAA1", ShippingMethod.STANDARD, null);
        Shipment second = service.createShipment(b.getId(), "UPS", "BBB2", ShippingMethod.STANDARD, null);
        assertThatThrownBy(() -> service.assignCarrier(second.getId(), "UPS", "AAA1"))
                .isInstanceOf(DuplicateTrackingNumberException.class);
        Shipment ok = service.assignCarrier(second.getId(), "FedEx", "BBB2");
        assertThat(ok.getCarrier()).isEqualTo("FedEx");
    }

    @Test
    void statusFlow_buildsHistoryAndRejectsInvalidAndRepeatedTransitions() throws Exception {
        PickingJob job = fixture(5, 5, PjStatus.FULFILLED);
        Shipment s = service.createShipment(job.getId(), "DHL");
        Long id = s.getId();

        assertThatThrownBy(() -> service.dispatch(id, null))
                .isInstanceOf(InvalidShipmentStatusTransitionException.class);
        walkToDelivered(id);

        assertThat(service.trackingHistory(id)).hasSize(6);
        assertThat(service.findById(id).getDeliveredAt()).isNotNull();
        assertThatThrownBy(() -> service.recordDelivery(id, "Door", null))
                .isInstanceOf(InvalidShipmentStatusTransitionException.class);
        assertThatThrownBy(() -> service.recordReturn(id, null, null))
                .isInstanceOf(InvalidShipmentStatusTransitionException.class);
        assertThat(service.trackingHistory(id)).hasSize(6);
    }

    @Test
    void deliveryEarlierThanDispatch_isRejected() throws Exception {
        PickingJob job = fixture(5, 5, PjStatus.FULFILLED);
        Long id = service.createShipment(job.getId(), "DHL").getId();
        service.updateStatus(id, ShipmentStatus.PACKED, null, null, null);
        service.dispatch(id, null);
        service.updateStatus(id, ShipmentStatus.IN_TRANSIT, null, null, null);
        service.updateStatus(id, ShipmentStatus.OUT_FOR_DELIVERY, null, null, null);
        assertThatThrownBy(() -> service.recordDelivery(id, null, LocalDateTime.now().minusDays(2)))
                .hasMessageContaining("earlier");
        assertThat(service.findById(id).getStatus()).isEqualTo(ShipmentStatus.OUT_FOR_DELIVERY);
    }

    @Test
    void exceptionAndReturn_areRecorded() throws Exception {
        PickingJob job = fixture(5, 5, PjStatus.FULFILLED);
        Long id = service.createShipment(job.getId(), "DHL").getId();
        service.updateStatus(id, ShipmentStatus.PACKED, null, null, null);
        service.dispatch(id, null);
        service.recordException(id, "Depot", "Address not found");
        service.recordReturn(id, "Depot", "Returned to sender");
        Shipment s = service.findById(id);
        assertThat(s.getStatus()).isEqualTo(ShipmentStatus.RETURNED);
        assertThat(s.getTrackingEvents()).extracting(e -> e.getDescription())
                .contains("Address not found", "Returned to sender");
    }

    @Test
    void dispatch_marksFullyPickedOrderShipped() throws Exception {
        PickingJob job = fixture(5, 5, PjStatus.FULFILLED);
        Long soId = job.getSalesOrder().getId();
        Long id = service.createShipment(job.getId(), "DHL").getId();
        service.updateStatus(id, ShipmentStatus.PACKED, null, null, null);
        assertThat(em.find(SalesOrder.class, soId).getStatus()).isEqualTo(SoStatus.PENDING);

        service.dispatch(id, "Dock");
        em.flush();
        em.clear();
        assertThat(em.find(SalesOrder.class, soId).getStatus()).isEqualTo(SoStatus.SHIPPED);
    }

    @Test
    void dispatch_ofPartialPick_marksOrderPartiallyShipped_thenShippedBySecondShipment() throws Exception {
        PickingJob first = fixture(5, 3, PjStatus.FULFILLED);
        SalesOrder so = first.getSalesOrder();
        Long soId = so.getId();
        Long id = service.createShipment(first.getId(), "DHL").getId();
        service.updateStatus(id, ShipmentStatus.PACKED, null, null, null);
        service.dispatch(id, null);
        em.flush();
        em.clear();
        assertThat(em.find(SalesOrder.class, soId).getStatus()).isEqualTo(SoStatus.PARTIALLY_SHIPPED);

        // second (backorder) picking job covers the remaining 2 units
        SalesOrder reloaded = em.find(SalesOrder.class, soId);
        Item item = reloaded.getSaleOrderLines().get(0).getItem();
        PickingJob second = jobFor(reloaded, item, 2, PjStatus.FULFILLED);
        Long id2 = service.createShipment(second.getId(), "UPS").getId();
        service.updateStatus(id2, ShipmentStatus.PACKED, null, null, null);
        service.dispatch(id2, null);
        em.flush();
        em.clear();
        assertThat(em.find(SalesOrder.class, soId).getStatus()).isEqualTo(SoStatus.SHIPPED);
        assertThat(service.findBySalesOrder(soId)).hasSize(2);
    }

    @Test
    void eligibleJobs_excludeShippedAndEmptyPicks() {
        PickingJob ready = fixture(5, 5, PjStatus.FULFILLED);
        PickingJob nothingPicked = fixture(5, 0, PjStatus.FULFILLED);
        PickingJob shipped = fixture(5, 5, PjStatus.FULFILLED);
        service.createShipment(shipped.getId(), "DHL");
        em.flush();
        em.clear();
        assertThat(service.findEligiblePickingJobs()).extracting(PickingJob::getId)
                .contains(ready.getId()).doesNotContain(nothingPicked.getId(), shipped.getId());
    }

    @Test
    void findAll_filtersByStatusAndSalesOrder_andUnknownIdsThrowNotFound() throws Exception {
        PickingJob job = fixture(5, 5, PjStatus.FULFILLED);
        Shipment s = service.createShipment(job.getId(), "DHL");
        em.flush();
        assertThat(service.findAll(ShipmentStatus.CREATED, job.getSalesOrder().getId(), PageRequest.of(0, 10))
                .getContent()).extracting(Shipment::getId).contains(s.getId());
        assertThat(service.findAll(ShipmentStatus.DELIVERED, null, PageRequest.of(0, 10)).getContent())
                .extracting(Shipment::getId).doesNotContain(s.getId());
        assertThatThrownBy(() -> service.findById(424242L)).isInstanceOf(ShipmentNotFoundException.class);
        assertThatThrownBy(() -> service.findByTrackingNumber("NOPE")).isInstanceOf(ShipmentNotFoundException.class);
        assertThat(LocalDate.now()).isNotNull();
    }
}
