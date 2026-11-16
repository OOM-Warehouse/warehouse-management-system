package com.example.warehouseManagement.Controllers;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import com.example.warehouseManagement.Domains.Exceptions.ShipmentException;
import com.example.warehouseManagement.Domains.Exceptions.ShipmentNotFoundException;

/**
 * Shipment-specific counterpart of {@link GlobalExceptionHandler}, using the same
 * redirect-to-list-with-banner convention. It is scoped to {@link ShipmentController}
 * so the shipment module stays self-contained.
 */
@ControllerAdvice(assignableTypes = ShipmentController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ShipmentExceptionHandler {

    @ExceptionHandler(ShipmentNotFoundException.class)
    public String handleShipmentNotFound() {
        return "redirect:/shipments?notFound";
    }

    /** Safety net for business-rule failures a controller method did not handle itself. */
    @ExceptionHandler(ShipmentException.class)
    public String handleShipmentRule() {
        return "redirect:/shipments?invalid";
    }
}
