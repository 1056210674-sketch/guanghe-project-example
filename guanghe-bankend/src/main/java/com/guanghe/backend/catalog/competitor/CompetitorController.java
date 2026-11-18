package com.guanghe.backend.catalog.competitor;

import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.catalog.PageResponse;
import com.guanghe.backend.common.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@ConditionalOnProperty(prefix = "zhixiaohe.catalog", name = "enabled", havingValue = "true", matchIfMissing = true)
public class CompetitorController {

    private final CompetitorService service;

    public CompetitorController(CompetitorService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/spaces/{spaceId}/competitors")
    public ApiResponse<PageResponse<CompetitorDtos.Summary>> list(
            @AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable @Positive long spaceId,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return ApiResponse.success(service.list(account, spaceId, page, size));
    }

    @PostMapping("/api/v1/spaces/{spaceId}/competitors")
    public ResponseEntity<ApiResponse<CompetitorDtos.Response>> create(
            @AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable @Positive long spaceId,
            @Valid @RequestBody CompetitorDtos.CreateRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(service.create(account, spaceId, request)));
    }

    @GetMapping("/api/v1/competitors/{competitorId}")
    public ApiResponse<CompetitorDtos.Response> get(
            @AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable @Positive long competitorId
    ) {
        return ApiResponse.success(service.get(account, competitorId));
    }

    @PatchMapping("/api/v1/competitors/{competitorId}")
    public ApiResponse<CompetitorDtos.Response> update(
            @AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable @Positive long competitorId,
            @Valid @RequestBody CompetitorDtos.UpdateRequest request
    ) {
        return ApiResponse.success(service.update(account, competitorId, request));
    }

    @DeleteMapping("/api/v1/competitors/{competitorId}")
    public ApiResponse<Void> delete(
            @AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable @Positive long competitorId
    ) {
        service.delete(account, competitorId);
        return ApiResponse.success(null);
    }

    @PutMapping("/api/v1/competitors/{competitorId}/aliases")
    public ApiResponse<CompetitorDtos.Response> replaceAliases(
            @AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable @Positive long competitorId,
            @Valid @RequestBody CompetitorDtos.ReplaceAliasesRequest request
    ) {
        return ApiResponse.success(service.replaceAliases(account, competitorId, request));
    }
}
