package com.example.warehouseManagement.Repositories;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.example.warehouseManagement.Domains.PickingJob;
import com.example.warehouseManagement.Domains.PickingJob.PjStatus;
import com.example.warehouseManagement.Domains.Shipment;
import com.example.warehouseManagement.Domains.ShipmentStatus;

public interface ShipmentRepository
        extends JpaRepository<Shipment, Long>, JpaSpecificationExecutor<Shipment> {

    Optional<Shipment> findByTrackingNumber(String trackingNumber);

    boolean existsByTrackingNumber(String trackingNumber);

    boolean existsByPickingJobId(Long pickingJobId);

    Optional<Shipment> findByPickingJobId(Long pickingJobId);

    List<Shipment> findBySalesOrderIdOrderByCreatedAtAsc(Long salesOrderId);

    long countByStatus(ShipmentStatus status);

    /** Picking jobs in the given status that do not have a shipment yet. */
    @Query("select pj from PickingJob pj where pj.status = :status "
            + "and not exists (select s.id from Shipment s where s.pickingJob = pj) order by pj.id")
    List<PickingJob> findPickingJobsWithoutShipment(@Param("status") PjStatus status);
}
