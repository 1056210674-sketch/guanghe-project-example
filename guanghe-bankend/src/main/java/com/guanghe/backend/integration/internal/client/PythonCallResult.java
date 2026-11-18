package com.guanghe.backend.integration.internal.client;

public record PythonCallResult<T>(
        State state,
        Integer httpStatus,
        Reason reason,
        T payload
) {

    public enum State {
        AVAILABLE,
        UNAVAILABLE
    }

    public enum Reason {
        NONE,
        CONNECTION_FAILED,
        TIMEOUT,
        HTTP_ERROR,
        INVALID_REQUEST,
        INVALID_RESPONSE
    }

    public static <T> PythonCallResult<T> available(int httpStatus, T payload) {
        return new PythonCallResult<>(State.AVAILABLE, httpStatus, Reason.NONE, payload);
    }

    public static <T> PythonCallResult<T> unavailable(Integer httpStatus, Reason reason) {
        return new PythonCallResult<>(State.UNAVAILABLE, httpStatus, reason, null);
    }

    public boolean available() {
        return state == State.AVAILABLE;
    }
}
