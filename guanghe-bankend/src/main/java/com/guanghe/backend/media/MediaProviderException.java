package com.guanghe.backend.media;

final class MediaProviderException extends RuntimeException {
    private final String code;
    private final boolean uncertain;
    MediaProviderException(String code, boolean uncertain) { this(code, uncertain, null); }
    MediaProviderException(String code, boolean uncertain, Throwable cause) {
        super(code, cause); this.code = code; this.uncertain = uncertain;
    }
    String code() { return code; }
    boolean uncertain() { return uncertain; }
}
