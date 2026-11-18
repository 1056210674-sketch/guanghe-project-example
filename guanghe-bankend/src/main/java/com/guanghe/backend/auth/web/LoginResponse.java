package com.guanghe.backend.auth.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record LoginResponse(
        String accessToken,
        String tokenType,
        Instant expiresAt,
        AuthUserResponse user,
        String nextAction,
        String activationToken,
        Agreement terms,
        Agreement privacy
) {
    public LoginResponse(String accessToken,String tokenType,Instant expiresAt,AuthUserResponse user){
        this(accessToken,tokenType,expiresAt,user,null,null,null,null);
    }
    public record Agreement(String version,String url){}
    @Override
    public String toString() {
        return "LoginResponse[accessToken=<redacted>, tokenType=" + tokenType
                + ", expiresAt=" + expiresAt + ", activationToken=<redacted>, user=" + user + "]";
    }
}
