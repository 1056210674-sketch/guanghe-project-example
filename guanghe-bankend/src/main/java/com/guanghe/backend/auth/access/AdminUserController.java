package com.guanghe.backend.auth.access;

import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.catalog.PageResponse;
import com.guanghe.backend.common.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@Validated
@RestController
@RequestMapping("/api/v1/admin/users")
@ConditionalOnProperty(prefix = "zhixiaohe.auth.account-access", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class AdminUserController {
    private final AccessService service;public AdminUserController(AccessService service){this.service=service;}
    @GetMapping public ApiResponse<PageResponse<AccessDtos.UserResponse>> list(@AuthenticationPrincipal AuthenticatedAccount actor,
        @RequestParam(required=false) @Size(max=64) String search,@RequestParam(defaultValue="1") @Min(1) int page,
        @RequestParam(defaultValue="20") @Min(1) @Max(100) int size){return ApiResponse.success(service.list(actor,search,page,size));}
    @GetMapping("/{userId}") public ApiResponse<AccessDtos.UserResponse> get(@AuthenticationPrincipal AuthenticatedAccount actor,@PathVariable @Positive long userId){return ApiResponse.success(service.get(actor,userId));}
    @PostMapping public ResponseEntity<ApiResponse<AccessDtos.ProvisionedUserResponse>> create(@AuthenticationPrincipal AuthenticatedAccount actor,@Valid @RequestBody AccessDtos.CreateUserRequest request){return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(service.create(actor,request)));}
    @PostMapping("/{userId}/password-reset") public ApiResponse<AccessDtos.ResetPasswordResponse> reset(@AuthenticationPrincipal AuthenticatedAccount actor,@PathVariable @Positive long userId){return ApiResponse.success(service.reset(actor,userId));}
    @PostMapping("/{userId}/disable") public ApiResponse<AccessDtos.UserResponse> disable(@AuthenticationPrincipal AuthenticatedAccount actor,@PathVariable @Positive long userId){return ApiResponse.success(service.disable(actor,userId));}
    @PostMapping("/{userId}/restore") public ApiResponse<AccessDtos.UserResponse> restore(@AuthenticationPrincipal AuthenticatedAccount actor,@PathVariable @Positive long userId){return ApiResponse.success(service.restore(actor,userId));}
}
