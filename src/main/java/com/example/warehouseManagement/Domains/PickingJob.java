package com.example.warehouseManagement.Domains;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Builder
@Table(name = "picking_job")
public class PickingJob {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    @Column(name = "id")
    @Setter(AccessLevel.NONE)
    private Long id;
    @Builder.Default
    @Column(name = "date")
    private LocalDate date = LocalDate.now();
    @ManyToOne
    @JoinColumn(name = "sales_order_id")
    private SalesOrder salesOrder;
    @OneToMany(mappedBy = "pickingJob", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @Column(name = "picking_job_lines")
    @Builder.Default
    private List<PickingJobLine> pickingJobLines = new ArrayList<>();
    @Builder.Default
    private PjStatus status = PjStatus.PENDING;

    /** Marks the job as fulfilled. A fulfilled job is final. */
    public void markFulfilled() {
        if (status == PjStatus.FULFILLED) {
            throw new IllegalStateException("Picking job " + id + " is already fulfilled");
        }
        this.status = PjStatus.FULFILLED;
    }

    /** Adds a line and keeps both sides of the association in sync. */
    public void addLine(PickingJobLine line) {
        if (line == null) {
            throw new IllegalArgumentException("Line must not be null");
        }
        line.setPickingJob(this);
        pickingJobLines.add(line);
    }

    public enum PjStatus {
        PENDING, //0
        FULFILLED //1
    }
}
