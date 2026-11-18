package com.guanghe.backend.catalog.space;

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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/spaces")
@ConditionalOnProperty(prefix = "zhixiaohe.catalog", name = "enabled", havingValue = "true", matchIfMissing = true)
public class BusinessSpaceController {

    private final BusinessSpaceService service;

    public BusinessSpaceController(BusinessSpaceService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<PageResponse<SpaceDtos.Summary>> list(
            @AuthenticationPrincipal AuthenticatedAccount account,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return ApiResponse.success(service.list(account, page, size));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<SpaceDtos.Response>> create(
            @AuthenticationPrincipal AuthenticatedAccount account,
            @Valid @RequestBody SpaceDtos.CreateRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(service.create(account, request)));
    }

    @GetMapping("/{spaceId}")
    public ApiResponse<SpaceDtos.Response> get(
            @AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable @Positive long spaceId
    ) {
        return ApiResponse.success(service.get(account, spaceId));
    }

    @PatchMapping("/{spaceId}")
    public ApiResponse<SpaceDtos.Response> update(
            @AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable @Positive long spaceId,
            @Valid @RequestBody SpaceDtos.UpdateRequest request
    ) {
        return ApiResponse.success(service.update(account, spaceId, request));
    }

    @DeleteMapping("/{spaceId}")
    public ApiResponse<Void> delete(
            @AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable @Positive long spaceId
    ) {
        service.delete(account, spaceId);
        return ApiResponse.success(null);
    }
}
