package com.example.warehouseManagement.Controllers;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.warehouseManagement.Services.OrderSummaryService;
import com.example.warehouseManagement.Services.OrderSummaryService.OrderSummaryRow;

/**
 * GET /reports/order-summary
 *
 * JSON list of every open (still editable) purchase order and sales order in one uniform shape.
 */
@RestController
@RequestMapping("/reports")
public class OrderSummaryController {

    private final OrderSummaryService orderSummaryService;

    public OrderSummaryController(OrderSummaryService orderSummaryService) {
        this.orderSummaryService = orderSummaryService;
    }

    @GetMapping("/order-summary")
    public List<OrderSummaryRow> openOrders() {
        return orderSummaryService.summarizeOpenOrders();
    }
}
