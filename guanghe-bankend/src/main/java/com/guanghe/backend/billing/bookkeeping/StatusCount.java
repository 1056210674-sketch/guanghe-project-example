package com.guanghe.backend.billing.bookkeeping;

/**
 * One row of a {@code GROUP BY status} aggregate.
 *
 * <p>Shared by the invoice and feedback mappers so the administrator summary can present both
 * without a second shape for the same idea.
 */
public record StatusCount(String status, long total) {
}
