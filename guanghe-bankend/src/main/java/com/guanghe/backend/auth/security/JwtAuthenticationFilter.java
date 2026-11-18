package com.guanghe.backend.auth.security;

import com.guanghe.backend.auth.account.AccountMapper;
import com.guanghe.backend.auth.account.AccountIdentity;
import com.guanghe.backend.auth.account.AccountRole;
import com.guanghe.backend.auth.account.AccountStatus;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@ConditionalOnProperty(prefix = "zhixiaohe.auth", name = "enabled", havingValue = "true", matchIfMissing = true)
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtDecoder decoder;
    private final AccountMapper accountMapper;

    public JwtAuthenticationFilter(JwtDecoder decoder, AccountMapper accountMapper) {
        this.decoder = decoder;
        this.accountMapper = accountMapper;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String token = bearerToken(request);
        if (token != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            authenticate(token);
        }
        filterChain.doFilter(request, response);
    }

    private void authenticate(String token) {
        try {
            Jwt jwt = decoder.decode(token);
            String userIdClaim = jwt.getClaimAsString("userId");
            String roleClaim = jwt.getClaimAsString("role");
            Number tokenVersionClaim = jwt.getClaim("tokenVersion");
            String tokenType = jwt.getClaimAsString("tokenType");
            if (userIdClaim == null || roleClaim == null || tokenVersionClaim == null
                    || !userIdClaim.equals(jwt.getSubject())
                    || (tokenType != null && !"ACCESS".equals(tokenType))) {
                return;
            }

            long userId = Long.parseLong(userIdClaim);
            AccountRole claimedRole = AccountRole.valueOf(roleClaim);
            int claimedTokenVersion = tokenVersionClaim.intValue();
            AccountIdentity account = accountMapper.findIdentityById(userId).orElse(null);
            if (account == null
                    || account.status() != AccountStatus.ACTIVE
                    || account.tokenVersion() != claimedTokenVersion
                    || account.role() != claimedRole
                    || !"ACTIVE".equals(account.credentialState())) {
                return;
            }

            AuthenticatedAccount principal = AuthenticatedAccount.from(account);
            var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + account.role().name()));
            var authentication = UsernamePasswordAuthenticationToken.authenticated(principal, null, authorities);
            SecurityContextHolder.getContext().setAuthentication(authentication);
        } catch (JwtException | IllegalArgumentException exception) {
            SecurityContextHolder.clearContext();
        }
    }

    private String bearerToken(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            return null;
        }
        String token = authorization.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }
}
