package com.carrental.modules.operation.controller;

import com.carrental.modules.booking.service.BookingService;
import com.carrental.modules.operation.service.RentalWorkflow;
import com.carrental.security.Actor;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;

@RestController
@RequestMapping("/api/v1/bookings")
public class RentalWorkflowController {
    private final RentalWorkflow workflow;

    public RentalWorkflowController(RentalWorkflow workflow) {
        this.workflow = workflow;
    }

    @PostMapping("/{id}/actions")
    public Object action(@AuthenticationPrincipal Actor a, @PathVariable String id, @RequestBody BookingService.Action i) {
        return workflow.apply(a, id, i);
    }

    @GetMapping("/{id}/contract")
    public Object contract(@AuthenticationPrincipal Actor a, @PathVariable String id) {
        return workflow.contract(a, id);
    }

    @GetMapping("/{id}/invoice")
    public Object invoice(@AuthenticationPrincipal Actor a, @PathVariable String id) {
        return workflow.invoice(a, id);
    }
}
