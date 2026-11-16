package com.example.warehouseManagement.Services;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import com.example.warehouseManagement.Domains.PickingJob;
import com.example.warehouseManagement.Domains.Shipment;
import com.example.warehouseManagement.Domains.ShipmentStatus;
import com.example.warehouseManagement.Domains.ShippingMethod;
import com.example.warehouseManagement.Domains.TrackingEvent;
import com.example.warehouseManagement.Domains.Exceptions.ShipmentNotFoundException;

public interface ShipmentService {

    /** Creates a shipment for a fulfilled picking job. A blank tracking number is generated. */
    Shipment createShipment(Long pickingJobId, String carrier, String trackingNumber,
                            ShippingMethod method, LocalDate expectedDeliveryDate);

    /** Overload: standard shipping, generated tracking number, no expected date. */
    Shipment createShipment(Long pickingJobId, String carrier);

    /** Changes carrier / tracking number before dispatch. */
    Shipment assignCarrier(Long shipmentId, String carrier, String trackingNumber) throws ShipmentNotFoundException;

    /** Generic validated status change; records a tracking event and syncs the sales order. */
    Shipment updateStatus(Long shipmentId, ShipmentStatus next, String location, String note,
                          LocalDateTime occurredAt) throws ShipmentNotFoundException;

    Shipment dispatch(Long shipmentId, String location) throws ShipmentNotFoundException;

    Shipment recordDelivery(Long shipmentId, String location, LocalDateTime deliveredAt) throws ShipmentNotFoundException;

    Shipment recordException(Long shipmentId, String location, String reason) throws ShipmentNotFoundException;

    Shipment recordReturn(Long shipmentId, String location, String reason) throws ShipmentNotFoundException;

    Shipment findById(Long id) throws ShipmentNotFoundException;

    Shipment findByTrackingNumber(String trackingNumber) throws ShipmentNotFoundException;

    List<TrackingEvent> trackingHistory(Long shipmentId) throws ShipmentNotFoundException;

    Page<Shipment> findAll(ShipmentStatus status, Long salesOrderId, Pageable pageable);

    List<Shipment> findBySalesOrder(Long salesOrderId);

    /** Fulfilled picking jobs that picked something and have no shipment yet. */
    List<PickingJob> findEligiblePickingJobs();
}
