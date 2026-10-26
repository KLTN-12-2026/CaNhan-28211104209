package com.carrental.config;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
@RestController public class HealthController {
 @GetMapping("/api/v1/health") public Object health(){return Map.of("status","UP","ai","NOT_IMPLEMENTED");}
}
