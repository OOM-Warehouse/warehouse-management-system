package com.example.warehouseManagement.Services;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.warehouseManagement.Domains.PickingJob;
import com.example.warehouseManagement.Domains.PickingJob.PjStatus;
import com.example.warehouseManagement.Domains.Shipment;
import com.example.warehouseManagement.Domains.ShipmentStatus;
import com.example.warehouseManagement.Domains.ShippingMethod;
import com.example.warehouseManagement.Domains.TrackingEvent;
import com.example.warehouseManagement.Domains.Exceptions.DuplicateTrackingNumberException;
import com.example.warehouseManagement.Domains.Exceptions.ShipmentNotAllowedException;
import com.example.warehouseManagement.Domains.Exceptions.ShipmentNotFoundException;
import com.example.warehouseManagement.Repositories.PickingJobRepository;
import com.example.warehouseManagement.Repositories.ShipmentRepository;
import com.example.warehouseManagement.Services.shipping.ShipmentStatusChangedEvent;
import com.example.warehouseManagement.Services.shipping.ShippingStrategy;
import com.example.warehouseManagement.Services.shipping.ShippingStrategyFactory;

@Service
public class ShipmentServiceImpl implements ShipmentService {

    private static final String TRACKING_PREFIX = "TRK-";
    private static final String TRACKING_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final int TRACKING_LENGTH = 10;

    private final ShipmentRepository shipmentRepository;
    private final PickingJobRepository pickingJobRepository;
    private final ShippingStrategyFactory strategyFactory;
    private final ApplicationEventPublisher eventPublisher;
    private final SecureRandom random = new SecureRandom();

    public ShipmentServiceImpl(ShipmentRepository shipmentRepository,
                               PickingJobRepository pickingJobRepository,
                               ShippingStrategyFactory strategyFactory,
                               ApplicationEventPublisher eventPublisher) {
        this.shipmentRepository = shipmentRepository;
        this.pickingJobRepository = pickingJobRepository;
        this.strategyFactory = strategyFactory;
        this.eventPublisher = eventPublisher;
    }

    @Override
    @Transactional
    public Shipment createShipment(Long pickingJobId, String carrier, String trackingNumber,
                                   ShippingMethod method, LocalDate expectedDeliveryDate) {
        PickingJob job = pickingJobId == null ? null : pickingJobRepository.findById(pickingJobId).orElse(null);
        if (job == null) {
            throw new ShipmentNotAllowedException("Picking job " + pickingJobId + " does not exist.");
        }
        if (shipmentRepository.existsByPickingJobId(job.getId())) {
            throw new ShipmentNotAllowedException("Picking job " + job.getId() + " already has a shipment.");
        }
        String tracking = (trackingNumber == null || trackingNumber.isBlank())
                ? generateTrackingNumber()
                : normalise(trackingNumber);
        ensureTrackingNumberFree(tracking, null);
        ShippingStrategy strategy = strategyFactory.forMethod(method != null ? method : ShippingMethod.STANDARD);
        LocalDate expected = expectedDeliveryDate != null
                ? expectedDeliveryDate
                : strategy.estimateDeliveryDate(LocalDate.now());
        Shipment shipment = Shipment.create(job, carrier, tracking, method, expected);
        shipment.applyShippingCost(strategy.calculateCost(shipment.getTotalUnits()));
        return shipmentRepository.save(shipment);
    }

    @Override
    @Transactional
    public Shipment createShipment(Long pickingJobId, String carrier) {
        return createShipment(pickingJobId, carrier, null, ShippingMethod.STANDARD, null);
    }

    @Override
    @Transactional
    public Shipment assignCarrier(Long shipmentId, String carrier, String trackingNumber)
            throws ShipmentNotFoundException {
        Shipment shipment = findById(shipmentId);
        String tracking = (trackingNumber == null || trackingNumber.isBlank())
                ? shipment.getTrackingNumber()
                : normalise(trackingNumber);
        ensureTrackingNumberFree(tracking, shipment.getId());
        shipment.assignCarrier(carrier, tracking);
        return shipmentRepository.save(shipment);
    }

    @Override
    @Transactional
    public Shipment updateStatus(Long shipmentId, ShipmentStatus next, String location, String note,
                                 LocalDateTime occurredAt) throws ShipmentNotFoundException {
        Shipment shipment = findById(shipmentId);
        ShipmentStatus previous = shipment.getStatus();
        shipment.transitionTo(next, location, note, occurredAt);
        Shipment saved = shipmentRepository.save(shipment);
        eventPublisher.publishEvent(new ShipmentStatusChangedEvent(this, saved, previous, next));
        return saved;
    }

    @Override
    @Transactional
    public Shipment dispatch(Long shipmentId, String location) throws ShipmentNotFoundException {
        return updateStatus(shipmentId, ShipmentStatus.DISPATCHED, location, "Dispatched to carrier", null);
    }

    @Override
    @Transactional
    public Shipment recordDelivery(Long shipmentId, String location, LocalDateTime deliveredAt)
            throws ShipmentNotFoundException {
        return updateStatus(shipmentId, ShipmentStatus.DELIVERED, location, "Delivered to customer", deliveredAt);
    }

    @Override
    @Transactional
    public Shipment recordException(Long shipmentId, String location, String reason)
            throws ShipmentNotFoundException {
        return updateStatus(shipmentId, ShipmentStatus.EXCEPTION, location, reason, null);
    }

    @Override
    @Transactional
    public Shipment recordReturn(Long shipmentId, String location, String reason)
            throws ShipmentNotFoundException {
        return updateStatus(shipmentId, ShipmentStatus.RETURNED, location, reason, null);
    }

    @Override
    @Transactional(readOnly = true)
    public Shipment findById(Long id) throws ShipmentNotFoundException {
        return shipmentRepository.findById(id)
                .orElseThrow(() -> new ShipmentNotFoundException("Shipment " + id + " not found."));
    }

    @Override
    @Transactional(readOnly = true)
    public Shipment findByTrackingNumber(String trackingNumber) throws ShipmentNotFoundException {
        String tracking = trackingNumber == null ? "" : normalise(trackingNumber);
        return shipmentRepository.findByTrackingNumber(tracking)
                .orElseThrow(() -> new ShipmentNotFoundException("Tracking number " + tracking + " not found."));
    }

    @Override
    @Transactional(readOnly = true)
    public List<TrackingEvent> trackingHistory(Long shipmentId) throws ShipmentNotFoundException {
        return new ArrayList<>(findById(shipmentId).getTrackingEvents());
    }

    @Override
    @Transactional(readOnly = true)
    public Page<Shipment> findAll(ShipmentStatus status, Long salesOrderId, Pageable pageable) {
        Specification<Shipment> spec = Specification.where(null);
        if (status != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("status"), status));
        }
        if (salesOrderId != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("salesOrder").get("id"), salesOrderId));
        }
        return shipmentRepository.findAll(spec, pageable);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Shipment> findBySalesOrder(Long salesOrderId) {
        return shipmentRepository.findBySalesOrderIdOrderByCreatedAtAsc(salesOrderId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PickingJob> findEligiblePickingJobs() {
        return shipmentRepository.findPickingJobsWithoutShipment(PjStatus.FULFILLED).stream()
                .filter(pj -> pj.getPickingJobLines().stream().anyMatch(l -> l.getQtyPicked() > 0))
                .toList();
    }

    private void ensureTrackingNumberFree(String trackingNumber, Long ownShipmentId) {
        shipmentRepository.findByTrackingNumber(trackingNumber).ifPresent(existing -> {
            if (!existing.getId().equals(ownShipmentId)) {
                throw new DuplicateTrackingNumberException("Tracking number " + trackingNumber + " is already in use.");
            }
        });
    }

    private String generateTrackingNumber() {
        String candidate;
        do {
            StringBuilder sb = new StringBuilder(TRACKING_PREFIX);
            for (int i = 0; i < TRACKING_LENGTH; i++) {
                sb.append(TRACKING_ALPHABET.charAt(random.nextInt(TRACKING_ALPHABET.length())));
            }
            candidate = sb.toString();
        } while (shipmentRepository.existsByTrackingNumber(candidate));
        return candidate;
    }

    private static String normalise(String trackingNumber) {
        return trackingNumber.trim().toUpperCase();
    }
}
