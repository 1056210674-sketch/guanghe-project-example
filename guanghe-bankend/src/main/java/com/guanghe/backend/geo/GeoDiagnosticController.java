package com.guanghe.backend.geo;

import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.catalog.PageResponse;
import com.guanghe.backend.common.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.http.CacheControl;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@Validated @RestController @RequestMapping("/api/v1/geo/diagnostics")
@ConditionalOnProperty(prefix="zhixiaohe.paid-task",name={"enabled","geo-diagnostic-enabled"},havingValue="true")
public class GeoDiagnosticController {
    private final GeoDiagnosticService service;
    private final GeoDiagnosticDeliveryService delivery;
    public GeoDiagnosticController(GeoDiagnosticService service,GeoDiagnosticDeliveryService delivery) { this.service=service;this.delivery=delivery; }
    @PostMapping public ResponseEntity<ApiResponse<GeoDiagnosticDtos.Detail>> create(
        @AuthenticationPrincipal AuthenticatedAccount account,@Valid @RequestBody GeoDiagnosticDtos.Create request) {
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(ApiResponse.success(service.create(account,request)));
    }
    @GetMapping public ApiResponse<PageResponse<GeoDiagnosticDtos.Detail>> list(@AuthenticationPrincipal AuthenticatedAccount account,
        @RequestParam(required=false) @Positive Long conversationId,@RequestParam(required=false) @Positive Long projectId,
        @RequestParam(defaultValue="1") @Min(1) int page,@RequestParam(defaultValue="20") @Min(1) @Max(20) int size,
        @RequestParam(defaultValue="false") boolean submittedOnly) {
        return ApiResponse.success(submittedOnly?service.listSubmitted(account,conversationId,projectId,page,size):service.list(account,conversationId,projectId,page,size));
    }
    @GetMapping("/{id}") public ApiResponse<GeoDiagnosticDtos.Detail> get(@AuthenticationPrincipal AuthenticatedAccount account,@PathVariable @Positive long id) {
        return ApiResponse.success(service.get(account,id));
    }
    @PatchMapping("/{id}") public ApiResponse<GeoDiagnosticDtos.Detail> patch(@AuthenticationPrincipal AuthenticatedAccount account,@PathVariable @Positive long id,
        @Valid @RequestBody GeoDiagnosticDtos.Patch request) { return ApiResponse.success(service.patch(account,id,request)); }
    @PostMapping("/{id}/generate") public ResponseEntity<ApiResponse<GeoDiagnosticDtos.Detail>> generate(@AuthenticationPrincipal AuthenticatedAccount account,
        @PathVariable @Positive long id,@Valid @RequestBody GeoDiagnosticDtos.Generate request) {
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(ApiResponse.success(service.generate(account,id,request)));
    }
    @PostMapping("/{id}/quote") public ApiResponse<GeoDiagnosticDtos.Detail> quote(@AuthenticationPrincipal AuthenticatedAccount account,
        @PathVariable @Positive long id,@Valid @RequestBody GeoDiagnosticDtos.QuoteRequest request) {
        return ApiResponse.success(service.quote(account,id,request));
    }
    @PostMapping("/{id}/submit") public ResponseEntity<ApiResponse<GeoDiagnosticDtos.Detail>> submit(@AuthenticationPrincipal AuthenticatedAccount account,
        @PathVariable @Positive long id,@Valid @RequestBody GeoDiagnosticReportDtos.Submit request) {
        delivery.submit(account,id,request);return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(ApiResponse.success(service.get(account,id)));
    }
    @GetMapping("/{id}/report") public ApiResponse<GeoDiagnosticReportDtos.Detail> report(@AuthenticationPrincipal AuthenticatedAccount account,@PathVariable @Positive long id) {
        return ApiResponse.success(delivery.get(account,id));
    }
    @PostMapping("/{id}/report/retry") public ApiResponse<GeoDiagnosticReportDtos.Detail> retry(@AuthenticationPrincipal AuthenticatedAccount account,
        @PathVariable @Positive long id,@Valid @RequestBody GeoDiagnosticReportDtos.Retry request) {return ApiResponse.success(delivery.retry(account,id,request));}
    @GetMapping("/{id}/report/download-url") public ResponseEntity<ApiResponse<GeoDiagnosticReportDtos.Download>> download(@AuthenticationPrincipal AuthenticatedAccount account,
        @PathVariable @Positive long id,@RequestParam @Min(1) int version) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(delivery.download(account,id,version)));
    }
    @GetMapping("/{id}/report/observations") public ApiResponse<PageResponse<GeoTaskDtos.ObservationResponse>> observations(@AuthenticationPrincipal AuthenticatedAccount account,
        @PathVariable @Positive long id,@RequestParam(defaultValue="1") @Min(1) int page,@RequestParam(defaultValue="10") @Min(1) @Max(20) int size) {
        return ApiResponse.success(delivery.observations(account,id,page,size));
    }
}
