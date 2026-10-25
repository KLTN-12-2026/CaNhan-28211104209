package com.carrental.modules.ai.parser;
import com.carrental.security.Actor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/ai/criteria/parse")
public class CriteriaExtractionController {
    public record Request(@NotBlank @Size(max=1000) String text) {}
    private final CriteriaExtractionService service;
    public CriteriaExtractionController(CriteriaExtractionService service){this.service=service;}
    @PostMapping public CriteriaExtractionService.Result parse(@AuthenticationPrincipal Actor actor,@Valid @RequestBody Request request){return service.extract(actor,request.text());}
}
