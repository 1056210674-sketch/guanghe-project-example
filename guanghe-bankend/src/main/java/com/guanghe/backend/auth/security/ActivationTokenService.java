package com.guanghe.backend.auth.security;

import com.guanghe.backend.auth.account.AccountRecord;
import com.guanghe.backend.auth.config.AccountAccessProperties;
import com.guanghe.backend.auth.config.JwtProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix = "zhixiaohe.auth", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ActivationTokenService {
    private final JwtEncoder encoder; private final JwtDecoder decoder; private final JwtProperties jwt;
    private final AccountAccessProperties access; private final Clock clock;
    public ActivationTokenService(JwtEncoder encoder,JwtDecoder decoder,JwtProperties jwt,
                                  AccountAccessProperties access,@Qualifier("authenticationClock") Clock clock){
        this.encoder=encoder;this.decoder=decoder;this.jwt=jwt;this.access=access;this.clock=clock;
    }
    public IssuedActivation issue(AccountRecord account){
        Instant now=clock.instant(),expires=now.plus(access.activationTokenTtl());String jti=UUID.randomUUID().toString();
        var claims=JwtClaimsSet.builder().issuer(jwt.issuer()).subject(Long.toString(account.id())).audience(List.of("first-login"))
                .issuedAt(now).expiresAt(expires).id(jti).claim("userId",Long.toString(account.id()))
                .claim("tokenVersion",account.tokenVersion()).claim("tokenType","FIRST_LOGIN")
                .claim("scope","complete:first-login").build();
        String value=encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),claims)).getTokenValue();
        return new IssuedActivation(value,hash(value),expires);
    }
    public ActivationClaims decode(String value){
        Jwt token=decoder.decode(value);
        if(!"FIRST_LOGIN".equals(token.getClaimAsString("tokenType"))
                ||!"complete:first-login".equals(token.getClaimAsString("scope"))
                ||token.getAudience()==null||!token.getAudience().contains("first-login")) throw new IllegalArgumentException("wrong token scope");
        Number version=token.getClaim("tokenVersion");String userId=token.getClaimAsString("userId");
        if(version==null||userId==null||!userId.equals(token.getSubject()))throw new IllegalArgumentException("invalid activation token");
        return new ActivationClaims(Long.parseLong(userId),version.intValue(),hash(value));
    }
    public String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    public record IssuedActivation(String value,String hash,Instant expiresAt){@Override public String toString(){return "IssuedActivation[value=<redacted>, expiresAt="+expiresAt+"]";}}
    public record ActivationClaims(long userId,int tokenVersion,String tokenHash){}
}
