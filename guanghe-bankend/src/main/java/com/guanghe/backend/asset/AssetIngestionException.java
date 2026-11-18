package com.guanghe.backend.asset;

public class AssetIngestionException extends RuntimeException {
    private final String errorCode;
    private final boolean retryable;
    private final boolean objectCreatedByCaller;

    public AssetIngestionException(String errorCode, boolean retryable) {
        super(errorCode);
        this.errorCode = errorCode;
        this.retryable = retryable;
        this.objectCreatedByCaller = false;
    }

    public AssetIngestionException(String errorCode, boolean retryable, Throwable cause) {
        super(errorCode, cause);
        this.errorCode = errorCode;
        this.retryable = retryable;
        this.objectCreatedByCaller = false;
    }

    public AssetIngestionException(String errorCode, boolean retryable, boolean objectCreatedByCaller) {
        super(errorCode);
        this.errorCode = errorCode;
        this.retryable = retryable;
        this.objectCreatedByCaller = objectCreatedByCaller;
    }

    public AssetIngestionException(String errorCode, boolean retryable, boolean objectCreatedByCaller,
                                   Throwable cause) {
        super(errorCode, cause);
        this.errorCode = errorCode;
        this.retryable = retryable;
        this.objectCreatedByCaller = objectCreatedByCaller;
    }

    public String errorCode() { return errorCode; }
    public boolean retryable() { return retryable; }
    public boolean objectCreatedByCaller() { return objectCreatedByCaller; }
}
