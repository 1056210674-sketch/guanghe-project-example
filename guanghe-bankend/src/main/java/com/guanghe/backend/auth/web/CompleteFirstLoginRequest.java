package com.guanghe.backend.auth.web;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CompleteFirstLoginRequest(
        @NotBlank @Size(max=4096) String activationToken,
        @NotBlank @Size(min=6,max=128) String newPassword,
        @AssertTrue boolean termsAccepted,
        @AssertTrue boolean privacyAccepted,
        @NotBlank @Size(max=64) String termsVersion,
        @NotBlank @Size(max=64) String privacyVersion
) {
    @JsonAnySetter public void rejectUnknown(String name, JsonNode value){throw new IllegalArgumentException("Unknown activation field");}
    @Override public String toString(){return "CompleteFirstLoginRequest[activationToken=<redacted>,newPassword=<redacted>]";}
}
