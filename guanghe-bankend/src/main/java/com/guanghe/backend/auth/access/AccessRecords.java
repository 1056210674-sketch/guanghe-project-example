package com.guanghe.backend.auth.access;

import java.time.OffsetDateTime;

final class AccessRecords {
    private AccessRecords() { }
    record Activation(long id,long userId,String tokenHash,int tokenVersion,String status,
                      OffsetDateTime expiresAt,OffsetDateTime consumedAt,OffsetDateTime createdAt) { }
}
