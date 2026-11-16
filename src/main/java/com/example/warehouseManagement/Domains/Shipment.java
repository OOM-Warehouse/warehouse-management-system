package com.example.warehouseManagement.Domains;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.example.warehouseManagement.Domains.Exceptions.InvalidShipmentDataException;
import com.example.warehouseManagement.Domains.Exceptions.InvalidShipmentStatusTransitionException;
import com.example.warehouseManagement.Domains.Exceptions.ShipmentNotAllowedException;
import com.example.warehouseManagement.Domains.PickingJob.PjStatus;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A parcel leaving the warehouse for a customer. One shipment covers one fulfilled
 * {@link PickingJob}, so an order with backorders is shipped in several parts.
 *
 * Encapsulation: status and timestamps change only through {@code transitionTo}.
 * Composition: the shipment owns its {@link TrackingEvent}s (cascade ALL, orphan removal).
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "shipment")
public class Shipment {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    @Column(name = "id")
    private Long id;
    @Column(name = "tracking_number", nullable = false, unique = true, length = 32)
    private String trackingNumber;
    @Column(name = "carrier", nullable = false, length = 64)
    private String carrier;
    @Enumerated(EnumType.STRING)
    @Column(name = "shipping_method", nullable = false, length = 16)
    private ShippingMethod shippingMethod = ShippingMethod.STANDARD;
    @Column(name = "shipping_cost")
    private Double shippingCost;
    @Column(name = "expected_delivery_date")
    private LocalDate expectedDeliveryDate;
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ShipmentStatus status = ShipmentStatus.CREATED;
    @ManyToOne(optional = false)
    @JoinColumn(name = "sales_order_id", nullable = false)
    private SalesOrder salesOrder;
    @ManyToOne(optional = false)
    @JoinColumn(name = "picking_job_id", nullable = false, unique = true)
    private PickingJob pickingJob;
    @Column(name = "ship_to_address", length = 512)
    private String shipToAddress;
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
    @Column(name = "dispatched_at")
    private LocalDateTime dispatchedAt;
    @Column(name = "delivered_at")
    private LocalDateTime deliveredAt;
    @OneToMany(mappedBy = "shipment", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("timestamp ASC, id ASC")
    private List<TrackingEvent> trackingEvents = new ArrayList<>();

    /**
     * Creates a shipment for a fulfilled picking job and records the first tracking event.
     *
     * @throws ShipmentNotAllowedException  if the job is not fulfilled or picked nothing
     * @throws InvalidShipmentDataException on a blank carrier / tracking number or a past delivery date
     */
    public static Shipment create(PickingJob job, String carrier, String trackingNumber,
                                  ShippingMethod method, LocalDate expectedDeliveryDate) {
        if (job == null || job.getSalesOrder() == null) {
            throw new ShipmentNotAllowedException("A shipment needs a picking job that belongs to a sales order.");
        }
        if (job.getStatus() != PjStatus.FULFILLED) {
            throw new ShipmentNotAllowedException("Picking job " + job.getId() + " is not fulfilled yet.");
        }
        if (unitsOf(job) <= 0) {
            throw new ShipmentNotAllowedException("Picking job " + job.getId() + " picked nothing to ship.");
        }
        Shipment s = new Shipment();
        s.pickingJob = job;
        s.salesOrder = job.getSalesOrder();
        s.createdAt = LocalDateTime.now();
        s.assignCarrier(carrier, trackingNumber);
        s.shippingMethod = method != null ? method : ShippingMethod.STANDARD;
        if (expectedDeliveryDate != null && expectedDeliveryDate.isBefore(s.createdAt.toLocalDate())) {
            throw new InvalidShipmentDataException("Expected delivery date cannot be in the past.");
        }
        s.expectedDeliveryDate = expectedDeliveryDate;
        if (s.salesOrder.getCustomer() != null) {
            s.shipToAddress = s.salesOrder.getCustomer().formattedAddress();
        }
        s.addEvent(ShipmentStatus.CREATED, s.createdAt, null, "Shipment created");
        return s;
    }

    /** Overload: standard shipping, no expected date. */
    public static Shipment create(PickingJob job, String carrier, String trackingNumber) {
        return create(job, carrier, trackingNumber, ShippingMethod.STANDARD, null);
    }

    /** Sets carrier and tracking number; allowed only before the parcel is dispatched. */
    public void assignCarrier(String carrier, String trackingNumber) {
        if (status != ShipmentStatus.CREATED && status != ShipmentStatus.PACKED) {
            throw new InvalidShipmentDataException("Carrier details can only change before dispatch.");
        }
        if (carrier == null || carrier.isBlank()) {
            throw new InvalidShipmentDataException("Carrier is required.");
        }
        if (trackingNumber == null || trackingNumber.isBlank()) {
            throw new InvalidShipmentDataException("Tracking number is required.");
        }
        this.carrier = carrier.trim();
        this.trackingNumber = trackingNumber.trim().toUpperCase();
    }

    public void transitionTo(ShipmentStatus next) {
        transitionTo(next, null, null, null);
    }

    public void transitionTo(ShipmentStatus next, String location, String description) {
        transitionTo(next, location, description, null);
    }

    /**
     * Moves the shipment to {@code next}, appends a tracking event and stamps the
     * dispatch / delivery times.
     *
     * @param occurredAt when it happened; defaults to now
     */
    public void transitionTo(ShipmentStatus next, String location, String description, LocalDateTime occurredAt) {
        if (!status.canTransitionTo(next)) {
            throw new InvalidShipmentStatusTransitionException(
                    "Cannot move shipment " + trackingNumber + " from " + status + " to " + next + ".");
        }
        LocalDateTime when = occurredAt != null ? occurredAt : LocalDateTime.now();
        LocalDateTime latest = latestEventTime();
        if (latest != null && when.isBefore(latest)) {
            throw new InvalidShipmentDataException(
                    "The event time cannot be earlier than the previous event (" + latest + ").");
        }
        if (next == ShipmentStatus.DISPATCHED) {
            if (expectedDeliveryDate != null && expectedDeliveryDate.isBefore(when.toLocalDate())) {
                throw new InvalidShipmentDataException("Expected delivery date is earlier than the dispatch date.");
            }
            dispatchedAt = when;
        }
        if (next == ShipmentStatus.DELIVERED) {
            if (dispatchedAt != null && when.isBefore(dispatchedAt)) {
                throw new InvalidShipmentDataException("Delivery cannot be earlier than the dispatch time.");
            }
            deliveredAt = when;
        }
        status = next;
        addEvent(next, when, location, description);
    }

    /** Sets the optional cost (e.g. computed by a pricing strategy). Must not be negative. */
    public void applyShippingCost(Double cost) {
        if (cost != null && cost < 0) {
            throw new InvalidShipmentDataException("Shipping cost cannot be negative.");
        }
        this.shippingCost = cost;
    }

    public List<TrackingEvent> getTrackingEvents() {
        return Collections.unmodifiableList(trackingEvents);
    }

    public int getTotalUnits() {
        return unitsOf(pickingJob);
    }

    private void addEvent(ShipmentStatus st, LocalDateTime when, String location, String description) {
        String text = blankToNull(description);
        trackingEvents.add(new TrackingEvent(this, st, when, blankToNull(location),
                text != null ? text : st.getLabel()));
    }

    private LocalDateTime latestEventTime() {
        return trackingEvents.stream().map(TrackingEvent::getTimestamp)
                .max(LocalDateTime::compareTo).orElse(null);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static int unitsOf(PickingJob job) {
        int total = 0;
        for (PickingJobLine line : job.getPickingJobLines()) {
            total += line.getQtyPicked();
        }
        return total;
    }
}
