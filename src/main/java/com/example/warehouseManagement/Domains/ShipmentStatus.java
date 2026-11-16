package com.example.warehouseManagement.Domains;

import java.util.EnumSet;
import java.util.Set;

/**
 * Lifecycle of a {@link Shipment}. The legal transitions live in the enum itself
 * (state machine in the type), so entity and service code never hard-code them.
 * DELIVERED and RETURNED are terminal.
 */
public enum ShipmentStatus {
    CREATED("Created"),
    PACKED("Packed"),
    DISPATCHED("Dispatched"),
    IN_TRANSIT("In transit"),
    OUT_FOR_DELIVERY("Out for delivery"),
    DELIVERED("Delivered"),
    EXCEPTION("Delivery exception"),
    RETURNED("Returned");

    private final String label;

    ShipmentStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /** The statuses that may legally follow this one. */
    public Set<ShipmentStatus> allowedNext() {
        switch (this) {
            case CREATED:
                return EnumSet.of(PACKED);
            case PACKED:
                return EnumSet.of(DISPATCHED);
            case DISPATCHED:
                return EnumSet.of(IN_TRANSIT, EXCEPTION);
            case IN_TRANSIT:
                return EnumSet.of(OUT_FOR_DELIVERY, EXCEPTION);
            case OUT_FOR_DELIVERY:
                return EnumSet.of(DELIVERED, EXCEPTION);
            case EXCEPTION:
                // resume transit, retry delivery, or give up and return to sender
                return EnumSet.of(IN_TRANSIT, OUT_FOR_DELIVERY, RETURNED);
            case DELIVERED:
            case RETURNED:
            default:
                return EnumSet.noneOf(ShipmentStatus.class);
        }
    }

    public boolean canTransitionTo(ShipmentStatus next) {
        return next != null && allowedNext().contains(next);
    }

    public boolean isTerminal() {
        return allowedNext().isEmpty();
    }

    /** True once the goods have physically left the warehouse and not come back. */
    public boolean countsAsShipped() {
        return this == DISPATCHED || this == IN_TRANSIT || this == OUT_FOR_DELIVERY
                || this == EXCEPTION || this == DELIVERED;
    }
}
