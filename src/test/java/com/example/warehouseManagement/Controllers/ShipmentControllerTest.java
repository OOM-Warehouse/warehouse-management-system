package com.example.warehouseManagement.Controllers;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.web.servlet.MockMvc;

import com.example.warehouseManagement.Domains.Exceptions.InvalidShipmentStatusTransitionException;
import com.example.warehouseManagement.Domains.Exceptions.ShipmentNotFoundException;
import com.example.warehouseManagement.Security.TwoFactorAuthenticationSuccessHandler;
import com.example.warehouseManagement.Services.ShipmentService;
import com.example.warehouseManagement.Services.UserService;

@WebMvcTest(ShipmentController.class)
class ShipmentControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockBean private ShipmentService shipmentService;
    // Required by SecurityConfig.
    @MockBean private UserService userService;
    @MockBean private TwoFactorAuthenticationSuccessHandler successHandler;

    @Test
    void list_rendersForAuthenticatedUser() throws Exception {
        given(shipmentService.findAll(any(), any(), any(Pageable.class))).willReturn(new PageImpl<>(List.of()));
        mvc.perform(get("/shipments").with(user("u")))
                .andExpect(status().isOk())
                .andExpect(view().name("shipments/shipments"));
    }

    @Test
    void unknownShipment_redirectsToListWithNotFoundFlag() throws Exception {
        given(shipmentService.findById(99L)).willThrow(new ShipmentNotFoundException("nope"));
        mvc.perform(get("/shipments/99").with(user("u")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/shipments?notFound"));
    }

    @Test
    void invalidTransition_redirectsBackWithMessage() throws Exception {
        given(shipmentService.updateStatus(eq(5L), any(), any(), any(), any()))
                .willThrow(new InvalidShipmentStatusTransitionException("Cannot move"));
        mvc.perform(post("/shipments/5/status").with(user("u")).with(csrf()).param("status", "DELIVERED"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/shipments/5"))
                .andExpect(flash().attribute("errorMessage", "Cannot move"));
    }

    @Test
    void createWithoutCarrier_redisplaysForm() throws Exception {
        given(shipmentService.findEligiblePickingJobs()).willReturn(List.of());
        mvc.perform(post("/shipments/new").with(user("u")).with(csrf()).param("pickingJobId", "1").param("carrier", ""))
                .andExpect(status().isOk())
                .andExpect(view().name("shipments/newShipmentForm"));
    }
}
