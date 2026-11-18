package com.guanghe.backend.auth.security;

import com.guanghe.backend.auth.account.AccountRecord;
import com.guanghe.backend.auth.config.JwtProperties;
import java.time.Clock;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix = "zhixiaohe.auth", name = "enabled", havingValue = "true", matchIfMissing = true)
public class JwtTokenService {

    private final JwtEncoder encoder;
    private final JwtProperties properties;
    private final Clock clock;

    public JwtTokenService(
            JwtEncoder encoder,
            JwtProperties properties,
            @Qualifier("authenticationClock") Clock clock
    ) {
        this.encoder = encoder;
        this.properties = properties;
        this.clock = clock;
    }

    public IssuedJwt issue(AccountRecord account) {
        Instant issuedAt = clock.instant();
        Instant expiresAt = issuedAt.plusSeconds(properties.accessTokenTtlSeconds());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .subject(Long.toString(account.id()))
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .claim("userId", Long.toString(account.id()))
                .claim("role", account.role().name())
                .claim("tokenVersion", account.tokenVersion())
                .claim("tokenType", "ACCESS")
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String value = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new IssuedJwt(value, expiresAt);
    }
}
