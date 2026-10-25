package com.carrental.modules.ai.recommendation;

import com.carrental.exception.DomainException;
import com.carrental.security.Actor;
import java.io.IOException;
import com.fasterxml.jackson.databind.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/ai/recommendations")
public class RecommendationController {
    private final RecommendationService service;
    private final ObjectReader reader;
    public RecommendationController(RecommendationService service,ObjectMapper mapper){
        this.service=service;
        this.reader=mapper.readerFor(RecommendationModels.Request.class)
                .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .without(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
    }
    @PostMapping
    public RecommendationModels.Response recommend(@AuthenticationPrincipal Actor actor,@RequestBody JsonNode input){
        actor.require("USER");
        try {return service.recommend(actor,reader.readValue(input));}
        catch(IOException error){throw DomainException.bad("Tiêu chí gợi ý không đúng định dạng.");}
    }
}
