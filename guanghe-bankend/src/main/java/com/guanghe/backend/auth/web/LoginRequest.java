package com.guanghe.backend.auth.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @NotBlank @Size(max = 64) String username,
        @NotBlank @Size(max = 128) String password
) {
    @Override
    public String toString() {
        return "LoginRequest[username=<redacted>, password=<redacted>]";
    }
}
