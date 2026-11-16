package com.example.warehouseManagement.Domains;

import java.time.LocalDateTime;

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
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * One immutable entry in a shipment's tracking history. Created only by
 * {@link Shipment} (composition: it cannot exist without its shipment).
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "tracking_event")
public class TrackingEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    @Column(name = "id")
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "shipment_id", nullable = false)
    private Shipment shipment;
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ShipmentStatus status;
    @Column(name = "event_timestamp", nullable = false)
    private LocalDateTime timestamp;
    @Column(name = "location", length = 255)
    private String location;
    @Column(name = "description", length = 500)
    private String description;

    TrackingEvent(Shipment shipment, ShipmentStatus status, LocalDateTime timestamp,
                  String location, String description) {
        this.shipment = shipment;
        this.status = status;
        this.timestamp = timestamp;
        this.location = location;
        this.description = description;
    }
}
