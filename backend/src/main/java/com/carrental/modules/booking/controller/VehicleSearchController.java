package com.carrental.modules.booking.controller;

import com.carrental.modules.booking.service.VehicleSearchService;
import java.time.Instant;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/vehicles/search")
public class VehicleSearchController {
    private final VehicleSearchService service;
    public VehicleSearchController(VehicleSearchService service) { this.service = service; }

    @ExceptionHandler({org.springframework.web.bind.MissingServletRequestParameterException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    @ResponseStatus(org.springframework.http.HttpStatus.BAD_REQUEST)
    public java.util.Map<String, String> invalidParameter(Exception error) {
        return java.util.Map.of("message", "Điều kiện tìm xe thiếu hoặc không đúng định dạng.");
    }

    @GetMapping
    public VehicleSearchService.Result search(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant startAt,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant endAt,
            @RequestParam String pickupBranchId, @RequestParam String returnBranchId,
            @RequestParam(required = false) Long budget, @RequestParam(required = false) Integer seats,
            @RequestParam(required = false) String transmission, @RequestParam(required = false) String brand,
            @RequestParam(required = false) String category, @RequestParam(required = false) String q,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate pickupDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate returnDate) {
        return service.search(new VehicleSearchService.Input(startAt, endAt, pickupBranchId, returnBranchId, budget, seats, transmission, brand, category, q,pickupDate,returnDate));
    }
}
