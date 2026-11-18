package com.guanghe.backend.auth.web;

import com.guanghe.backend.auth.security.AuthenticatedAccount;
import com.guanghe.backend.auth.service.AuthService;
import com.guanghe.backend.auth.access.AccessService;
import com.guanghe.backend.common.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@ConditionalOnProperty(prefix = "zhixiaohe.auth", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AuthController {

    private final AuthService authService;
    private final TrustedProxyClientIpResolver clientIpResolver;
    private final AccessService accessService;

    public AuthController(AuthService authService, TrustedProxyClientIpResolver clientIpResolver,
                          ObjectProvider<AccessService> accessService) {
        this.authService = authService;
        this.clientIpResolver = clientIpResolver;
        this.accessService = accessService.getIfAvailable();
    }

    @PostMapping("/complete-first-login")
    public ApiResponse<LoginResponse> completeFirstLogin(@Valid @RequestBody CompleteFirstLoginRequest request,
                                                          HttpServletRequest servletRequest) {
        if (accessService == null) throw new com.guanghe.backend.common.api.BusinessException(
                com.guanghe.backend.common.api.ErrorCode.PROVIDER_NOT_CONFIGURED);
        return ApiResponse.success(accessService.complete(request, clientIpResolver.resolve(servletRequest)));
    }

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest servletRequest
    ) {
        return ApiResponse.success(authService.login(request, clientIpResolver.resolve(servletRequest)));
    }

    @GetMapping("/me")
    public ApiResponse<AuthUserResponse> currentUser(@AuthenticationPrincipal AuthenticatedAccount principal) {
        return ApiResponse.success(authService.currentUser(principal));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(@AuthenticationPrincipal AuthenticatedAccount principal) {
        authService.logout(principal);
        return ApiResponse.success(null);
    }
}
