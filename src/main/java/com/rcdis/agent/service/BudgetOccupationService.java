package com.rcdis.agent.service;

import java.math.BigDecimal;

/**
 * Single entry point for project-level budget occupation. Every financial budget delta
 * (freeze / consume / release / charge / refund) goes through here so the two-phase
 * "freeze at submit, consume at approve" rule lives in exactly one place.
 *
 * <p>Methods are not annotated {@code @Transactional} themselves; they run inside the
 * caller's transaction (the reimbursement workflow service) so a status change and its
 * budget delta commit or roll back together.
 */
public interface BudgetOccupationService {

    /** Reserve budget for a submitted-but-not-yet-approved order: {@code frozen += amount}. */
    void freeze(Long projectId, BigDecimal amount);

    /** Convert a frozen reservation into real occupation: {@code frozen -= amount; used += amount}. */
    void consume(Long projectId, BigDecimal amount);

    /** Release a frozen reservation (reject / withdraw): {@code frozen -= amount}. */
    void release(Long projectId, BigDecimal amount);

    /** Book budget directly (public payment, no approval): {@code used += amount}. */
    void charge(Long projectId, BigDecimal amount);

    /** Reverse a direct booking (public payment void): {@code used -= amount}. */
    void refundUsed(Long projectId, BigDecimal amount);
}
