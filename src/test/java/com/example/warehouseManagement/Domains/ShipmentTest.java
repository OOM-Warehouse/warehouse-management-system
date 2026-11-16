package com.example.warehouseManagement.Domains;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

import com.example.warehouseManagement.Domains.Exceptions.InvalidShipmentDataException;
import com.example.warehouseManagement.Domains.Exceptions.InvalidShipmentStatusTransitionException;
import com.example.warehouseManagement.Domains.Exceptions.ShipmentNotAllowedException;
import com.example.warehouseManagement.Domains.PickingJob.PjStatus;

/** Pure-domain tests: no Spring, no database. */
class ShipmentTest {

    private static PickingJob job(PjStatus status, int qtyPicked) {
        Customer customer = Customer.builder().name("Bob").street("1 Main").city("Town").state("TX").zipcode("75001").build();
        SalesOrder so = SalesOrder.builder().customer(customer).build();
        PickingJob job = PickingJob.builder().salesOrder(so).status(status).build();
        job.getPickingJobLines().add(PickingJobLine.builder().pickingJob(job).qtyToPick(5).qtyPicked(qtyPicked).build());
        return job;
    }

    private static Shipment newShipment() {
        return Shipment.create(job(PjStatus.FULFILLED, 5), "DHL", "trk-1", ShippingMethod.EXPRESS, null);
    }

    @Test
    void statusEnum_encodesTransitionsAndTerminalStates() {
        assertThat(ShipmentStatus.CREATED.canTransitionTo(ShipmentStatus.PACKED)).isTrue();
        assertThat(ShipmentStatus.CREATED.canTransitionTo(ShipmentStatus.DELIVERED)).isFalse();
        assertThat(ShipmentStatus.PACKED.canTransitionTo(ShipmentStatus.DISPATCHED)).isTrue();
        assertThat(ShipmentStatus.EXCEPTION.canTransitionTo(ShipmentStatus.RETURNED)).isTrue();
        assertThat(ShipmentStatus.DELIVERED.isTerminal()).isTrue();
        assertThat(ShipmentStatus.RETURNED.isTerminal()).isTrue();
        assertThat(ShipmentStatus.IN_TRANSIT.isTerminal()).isFalse();
        for (ShipmentStatus s : ShipmentStatus.values()) {
            assertThat(s.canTransitionTo(s)).as("no self transition for %s", s).isFalse();
        }
        assertThat(ShipmentStatus.PACKED.countsAsShipped()).isFalse();
        assertThat(ShipmentStatus.DISPATCHED.countsAsShipped()).isTrue();
        assertThat(ShipmentStatus.RETURNED.countsAsShipped()).isFalse();
    }

    @Test
    void create_recordsFirstTrackingEventAndSnapshotsAddress() {
        Shipment s = newShipment();
        assertThat(s.getStatus()).isEqualTo(ShipmentStatus.CREATED);
        assertThat(s.getTrackingNumber()).isEqualTo("TRK-1");
        assertThat(s.getShippingMethod()).isEqualTo(ShippingMethod.EXPRESS);
        assertThat(s.getTotalUnits()).isEqualTo(5);
        assertThat(s.getShipToAddress()).contains("Town");
        assertThat(s.getTrackingEvents()).hasSize(1);
        assertThat(s.getTrackingEvents().get(0).getStatus()).isEqualTo(ShipmentStatus.CREATED);
    }

    @Test
    void create_rejectsUnfulfilledOrEmptyJobsAndBlankCarrier() {
        assertThatThrownBy(() -> Shipment.create(job(PjStatus.PENDING, 5), "DHL", "T1", null, null))
                .isInstanceOf(ShipmentNotAllowedException.class);
        assertThatThrownBy(() -> Shipment.create(job(PjStatus.FULFILLED, 0), "DHL", "T1", null, null))
                .isInstanceOf(ShipmentNotAllowedException.class);
        assertThatThrownBy(() -> Shipment.create(null, "DHL", "T1", null, null))
                .isInstanceOf(ShipmentNotAllowedException.class);
        assertThatThrownBy(() -> Shipment.create(job(PjStatus.FULFILLED, 5), " ", "T1", null, null))
                .isInstanceOf(InvalidShipmentDataException.class);
        assertThatThrownBy(() -> Shipment.create(job(PjStatus.FULFILLED, 5), "DHL", "T1", null,
                LocalDate.now().minusDays(1))).isInstanceOf(InvalidShipmentDataException.class);
    }

    @Test
    void fullHappyPath_addsOneEventPerStatusAndStampsTimes() {
        Shipment s = newShipment();
        s.transitionTo(ShipmentStatus.PACKED);
        s.transitionTo(ShipmentStatus.DISPATCHED, "Dock 1", "Handed to carrier");
        assertThat(s.getDispatchedAt()).isNotNull();
        s.transitionTo(ShipmentStatus.IN_TRANSIT);
        s.transitionTo(ShipmentStatus.OUT_FOR_DELIVERY);
        s.transitionTo(ShipmentStatus.DELIVERED, "Customer door", null);
        assertThat(s.getDeliveredAt()).isNotNull();
        assertThat(s.getTrackingEvents()).extracting(TrackingEvent::getStatus).containsExactly(
                ShipmentStatus.CREATED, ShipmentStatus.PACKED, ShipmentStatus.DISPATCHED,
                ShipmentStatus.IN_TRANSIT, ShipmentStatus.OUT_FOR_DELIVERY, ShipmentStatus.DELIVERED);
        assertThat(s.getTrackingEvents().get(2).getLocation()).isEqualTo("Dock 1");
    }

    @Test
    void invalidTransitions_andRepeatedDelivery_areRejected() {
        Shipment s = newShipment();
        assertThatThrownBy(() -> s.transitionTo(ShipmentStatus.DISPATCHED))
                .isInstanceOf(InvalidShipmentStatusTransitionException.class);
        s.transitionTo(ShipmentStatus.PACKED);
        s.transitionTo(ShipmentStatus.DISPATCHED);
        s.transitionTo(ShipmentStatus.IN_TRANSIT);
        s.transitionTo(ShipmentStatus.OUT_FOR_DELIVERY);
        s.transitionTo(ShipmentStatus.DELIVERED);
        assertThatThrownBy(() -> s.transitionTo(ShipmentStatus.DELIVERED))
                .isInstanceOf(InvalidShipmentStatusTransitionException.class);
        assertThatThrownBy(() -> s.transitionTo(ShipmentStatus.RETURNED))
                .isInstanceOf(InvalidShipmentStatusTransitionException.class);
        assertThat(s.getTrackingEvents()).hasSize(6);
    }

    @Test
    void deliveryBeforeDispatch_isRejected() {
        Shipment s = newShipment();
        s.transitionTo(ShipmentStatus.PACKED);
        s.transitionTo(ShipmentStatus.DISPATCHED);
        s.transitionTo(ShipmentStatus.IN_TRANSIT);
        s.transitionTo(ShipmentStatus.OUT_FOR_DELIVERY);
        LocalDateTime tooEarly = s.getDispatchedAt().minusDays(1);
        assertThatThrownBy(() -> s.transitionTo(ShipmentStatus.DELIVERED, null, null, tooEarly))
                .isInstanceOf(InvalidShipmentDataException.class);
        assertThat(s.getStatus()).isEqualTo(ShipmentStatus.OUT_FOR_DELIVERY);
    }

    @Test
    void exceptionThenReturn_isSupported() {
        Shipment s = newShipment();
        s.transitionTo(ShipmentStatus.PACKED);
        s.transitionTo(ShipmentStatus.DISPATCHED);
        s.transitionTo(ShipmentStatus.EXCEPTION, "Depot", "Address not found");
        s.transitionTo(ShipmentStatus.RETURNED, "Depot", "Returned to sender");
        assertThat(s.getStatus().isTerminal()).isTrue();
    }

    @Test
    void trackingEvents_areExposedReadOnly_andCarrierLockedAfterDispatch() {
        Shipment s = newShipment();
        assertThatThrownBy(() -> s.getTrackingEvents().clear()).isInstanceOf(UnsupportedOperationException.class);
        s.assignCarrier("UPS", "ups-9");
        assertThat(s.getCarrier()).isEqualTo("UPS");
        assertThat(s.getTrackingNumber()).isEqualTo("UPS-9");
        s.transitionTo(ShipmentStatus.PACKED);
        s.transitionTo(ShipmentStatus.DISPATCHED);
        assertThatThrownBy(() -> s.assignCarrier("FedEx", "X")).isInstanceOf(InvalidShipmentDataException.class);
    }

    @Test
    void dispatch_failsWhenExpectedDeliveryAlreadyPast() {
        Shipment s = newShipment();
        s.transitionTo(ShipmentStatus.PACKED);
        LocalDateTime future = LocalDateTime.now().plusDays(3);
        // expected date is null here, so build one with a date that will be passed by the event time
        Shipment withEta = Shipment.create(job(PjStatus.FULFILLED, 5), "DHL", "T2", ShippingMethod.STANDARD, LocalDate.now());
        withEta.transitionTo(ShipmentStatus.PACKED);
        assertThatThrownBy(() -> withEta.transitionTo(ShipmentStatus.DISPATCHED, null, null, future))
                .isInstanceOf(InvalidShipmentDataException.class);
    }
}
