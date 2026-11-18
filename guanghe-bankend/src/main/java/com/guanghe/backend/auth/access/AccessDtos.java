package com.guanghe.backend.auth.access;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Pattern;
import java.time.OffsetDateTime;

public final class AccessDtos {
    private AccessDtos(){}
    public record CreateUserRequest(@Size(min=11,max=32) String phone,
                                    @Size(min=3,max=32) @Pattern(regexp="^[A-Za-z][A-Za-z0-9_]{2,31}$") String username,
                                    @NotBlank @Size(max=64) String displayName,
                                    @Min(0) @Max(1000000) Integer initialPointGrant){
        public CreateUserRequest(String phone,String displayName,Integer initialPointGrant){this(phone,null,displayName,initialPointGrant);}
        @JsonIgnore @AssertTrue(message="Provide exactly one phone or username")
        public boolean isSingleLoginIdentifier(){return (phone!=null && !phone.isBlank() && username==null)
                || (username!=null && !username.isBlank() && phone==null);}
        @JsonAnySetter public void rejectUnknown(String name, JsonNode value){throw new IllegalArgumentException("Unknown user provisioning field");}
    }
    public record UserResponse(String id,String username,String phone,String displayName,String status,String credentialState,
                               int tokenVersion,OffsetDateTime createdAt){}
    public record ProvisionedUserResponse(UserResponse user,String temporaryPassword){
        @Override public String toString(){return "ProvisionedUserResponse[user="+user+",temporaryPassword=<redacted>]";}
    }
    public record ResetPasswordResponse(String userId,String credentialState,String temporaryPassword){
        @Override public String toString(){return "ResetPasswordResponse[userId="+userId+",credentialState="+credentialState+",temporaryPassword=<redacted>]";}
    }
}
