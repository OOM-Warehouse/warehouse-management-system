package com.example.warehouseManagement.Controllers;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.example.warehouseManagement.Domains.PickingJob;
import com.example.warehouseManagement.Domains.Shipment;
import com.example.warehouseManagement.Domains.ShipmentStatus;
import com.example.warehouseManagement.Domains.ShippingMethod;
import com.example.warehouseManagement.Domains.DTOs.NewShipmentDto;
import com.example.warehouseManagement.Domains.DTOs.ShipmentStatusUpdateDto;
import com.example.warehouseManagement.Domains.Exceptions.ShipmentException;
import com.example.warehouseManagement.Domains.Exceptions.ShipmentNotFoundException;
import com.example.warehouseManagement.Services.ShipmentService;

import jakarta.validation.Valid;

/**
 * Shipment management and tracking. Business-rule failures ({@link ShipmentException})
 * are shown as a banner on the page the user came from; a missing shipment is handled
 * by {@link ShipmentExceptionHandler}.
 */
@Controller
@RequestMapping("/shipments")
public class ShipmentController {

    private static final String REDIRECT_LIST = "redirect:/shipments";
    private static final String NEW_FORM_VIEW = "shipments/newShipmentForm";
    private static final String ERROR_ATTR = "errorMessage";

    private final ShipmentService shipmentService;

    public ShipmentController(ShipmentService shipmentService) {
        this.shipmentService = shipmentService;
    }

    @GetMapping
    public String list(@RequestParam(value = "status", required = false) ShipmentStatus status,
                       @RequestParam(value = "salesOrderId", required = false) Long salesOrderId,
                       @PageableDefault(size = 25, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable,
                       Model model) {
        model.addAttribute("title", "Shipments");
        model.addAttribute("page", shipmentService.findAll(status, salesOrderId, pageable));
        model.addAttribute("statuses", ShipmentStatus.values());
        model.addAttribute("selectedStatus", status);
        model.addAttribute("salesOrderId", salesOrderId);
        return "shipments/shipments";
    }

    @GetMapping("/new")
    public String newForm(@RequestParam(value = "pickingJobId", required = false) Long pickingJobId, Model model) {
        NewShipmentDto dto = new NewShipmentDto();
        dto.setPickingJobId(pickingJobId);
        populateNewForm(model, dto);
        return NEW_FORM_VIEW;
    }

    @PostMapping("/new")
    public String create(@Valid @ModelAttribute("shipment") NewShipmentDto dto, BindingResult result,
                         Model model, RedirectAttributes redirect) {
        if (result.hasErrors()) {
            populateNewForm(model, dto);
            return NEW_FORM_VIEW;
        }
        try {
            Shipment created = shipmentService.createShipment(dto.getPickingJobId(), dto.getCarrier(),
                    dto.getTrackingNumber(), dto.getShippingMethod(), dto.getExpectedDeliveryDate());
            return "redirect:/shipments/" + created.getId() + "?created";
        } catch (ShipmentException e) {
            model.addAttribute(ERROR_ATTR, e.getMessage());
            populateNewForm(model, dto);
            return NEW_FORM_VIEW;
        }
    }

    @GetMapping("/track")
    public String track(@RequestParam(value = "trackingNumber", required = false) String trackingNumber, Model model) {
        model.addAttribute("title", "Track Shipment");
        model.addAttribute("trackingNumber", trackingNumber);
        if (trackingNumber != null && !trackingNumber.isBlank()) {
            try {
                model.addAttribute("shipment", shipmentService.findByTrackingNumber(trackingNumber));
            } catch (ShipmentNotFoundException e) {
                model.addAttribute("notFound", true);
            }
        }
        return "shipments/track";
    }

    @GetMapping("/{id}")
    public String details(@PathVariable("id") Long id, Model model) throws ShipmentNotFoundException {
        Shipment shipment = shipmentService.findById(id);
        model.addAttribute("title", "Shipment " + shipment.getTrackingNumber());
        model.addAttribute("shipment", shipment);
        model.addAttribute("nextStatuses", List.copyOf(shipment.getStatus().allowedNext()));
        if (!model.containsAttribute("statusUpdate")) {
            model.addAttribute("statusUpdate", new ShipmentStatusUpdateDto());
        }
        return "shipments/shipmentDetails";
    }

    @PostMapping("/{id}/status")
    public String updateStatus(@PathVariable("id") Long id,
                               @Valid @ModelAttribute("statusUpdate") ShipmentStatusUpdateDto dto,
                               BindingResult result, RedirectAttributes redirect) throws ShipmentNotFoundException {
        if (result.hasErrors()) {
            redirect.addFlashAttribute(ERROR_ATTR, result.getAllErrors().get(0).getDefaultMessage());
            return "redirect:/shipments/" + id;
        }
        try {
            shipmentService.updateStatus(id, dto.getStatus(), dto.getLocation(), dto.getNote(), dto.getOccurredAt());
            return "redirect:/shipments/" + id + "?updated";
        } catch (ShipmentException e) {
            redirect.addFlashAttribute(ERROR_ATTR, e.getMessage());
            return "redirect:/shipments/" + id;
        }
    }

    @PostMapping("/{id}/carrier")
    public String assignCarrier(@PathVariable("id") Long id,
                                @RequestParam("carrier") String carrier,
                                @RequestParam(value = "trackingNumber", required = false) String trackingNumber,
                                RedirectAttributes redirect) throws ShipmentNotFoundException {
        try {
            shipmentService.assignCarrier(id, carrier, trackingNumber);
            return "redirect:/shipments/" + id + "?updated";
        } catch (ShipmentException e) {
            redirect.addFlashAttribute(ERROR_ATTR, e.getMessage());
            return "redirect:/shipments/" + id;
        }
    }

    private void populateNewForm(Model model, NewShipmentDto dto) {
        List<PickingJob> eligible = shipmentService.findEligiblePickingJobs();
        model.addAttribute("title", "New Shipment");
        model.addAttribute("shipment", dto);
        model.addAttribute("pickingJobs", eligible);
        model.addAttribute("methods", ShippingMethod.values());
    }
}
