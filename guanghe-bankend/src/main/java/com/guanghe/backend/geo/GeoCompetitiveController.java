package com.guanghe.backend.geo;

import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.catalog.PageResponse;
import com.guanghe.backend.common.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@Validated
@RestController
@ConditionalOnProperty(prefix="zhixiaohe.paid-task",name="enabled",havingValue="true")
public class GeoCompetitiveController {
    private final GeoCompetitiveService service;
    public GeoCompetitiveController(GeoCompetitiveService service){this.service=service;}
    @GetMapping("/api/v1/geo/reports/{reportId}/competitive-insights")
    public ApiResponse<GeoCompetitiveDtos.Insights> get(@AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable @Positive long reportId,@RequestParam(defaultValue="DISCOVERY") String questionScope){
        return ApiResponse.success(service.get(account,reportId,questionScope));
    }
    @PostMapping("/api/v1/geo/reports/{reportId}/competitive-insights/analyze")
    public ApiResponse<GeoCompetitiveDtos.Insights> analyze(@AuthenticationPrincipal AuthenticatedAccount account,@PathVariable @Positive long reportId){
        return ApiResponse.success(service.analyze(account,reportId));
    }
    @GetMapping("/api/v1/geo/topics/{topicId}/competitor-candidates")
    public ApiResponse<PageResponse<GeoCompetitiveDtos.Candidate>> candidates(@AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable @Positive long topicId,@RequestParam(defaultValue="PENDING") String status,
            @RequestParam(defaultValue="1") @Min(1) int page,@RequestParam(defaultValue="20") @Min(1) @Max(100) int size){
        return ApiResponse.success(service.candidates(account,topicId,status,page,size));
    }
    @PostMapping("/api/v1/geo/competitor-candidates/{candidateId}/confirm")
    public ApiResponse<GeoCompetitiveDtos.Confirmation> confirm(@AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable @Positive long candidateId,@Valid @RequestBody GeoCompetitiveDtos.ConfirmRequest request){
        return ApiResponse.success(service.confirm(account,candidateId,request));
    }
    @PostMapping("/api/v1/geo/competitor-candidates/{candidateId}/ignore")
    public ApiResponse<GeoCompetitiveDtos.Candidate> ignore(@AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable @Positive long candidateId,@Valid @RequestBody GeoCompetitiveDtos.IgnoreRequest request){
        return ApiResponse.success(service.ignore(account,candidateId,request));
    }
}
