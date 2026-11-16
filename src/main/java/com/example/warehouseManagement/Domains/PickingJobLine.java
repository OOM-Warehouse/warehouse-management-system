package com.example.warehouseManagement.Domains;

import com.example.warehouseManagement.Domains.Exceptions.InvalidPickException;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Builder
@Table(name = "picking_job_line")
public class PickingJobLine {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    @Column(name = "id")
    private Long id;
    @ManyToOne
    @JoinColumn(name = "picking_job_id")
    private PickingJob pickingJob;
    @ManyToOne
    @JoinColumn(name = "item_id")
    private Item item;
    @Column(name = "qty_to_pick")
    private int qtyToPick;
    @Column(name = "qty_picked")
    private int qtyPicked;
    @ManyToOne
    @JoinColumn(name = "warehouse_section_id")
    private WarehouseSection warehouseSection;

    /**
     * Records a pick against this line and returns the unpicked remainder.
     * Enforces 0 &lt;= picked &lt;= qtyToPick.
     */
    public int recordPick(WarehouseSection section, int picked) {
        if (picked < 0)
            throw new InvalidPickException("Picked quantity cannot be negative: " + picked);
        if (picked > qtyToPick)
            throw new InvalidPickException("Picked quantity " + picked + " exceeds quantity to pick " + qtyToPick);
        this.qtyPicked = picked;
        this.warehouseSection = picked > 0 ? section : null;
        return qtyToPick - picked;
    }
}