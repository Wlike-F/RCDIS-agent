package com.rcdis.agent.service.impl;

import java.math.BigDecimal;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.rcdis.agent.common.exception.BusinessException;
import com.rcdis.agent.common.util.MoneyUtils;
import com.rcdis.agent.entity.ResearchProjectEntity;
import com.rcdis.agent.mapper.ResearchProjectMapper;
import com.rcdis.agent.service.BudgetOccupationService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Project-level budget occupation. All deltas operate on {@code research_project.used_amount}
 * and {@code research_project.frozen_amount} only, guarded by the {@code available >= 0} rule and
 * the project optimistic-lock version, so over-spending cannot slip through concurrent submissions.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BudgetOccupationServiceImpl implements BudgetOccupationService {

    private final ResearchProjectMapper researchProjectMapper;

    @Override
    public void freeze(Long projectId, BigDecimal amount) {
        ResearchProjectEntity project = load(projectId);
        BigDecimal value = MoneyUtils.normalize(amount);
        ensureAvailable(project, value);
        mutate(project, project.getUsedAmount(), MoneyUtils.add(project.getFrozenAmount(), value), "freeze");
    }

    @Override
    public void consume(Long projectId, BigDecimal amount) {
        ResearchProjectEntity project = load(projectId);
        BigDecimal value = MoneyUtils.normalize(amount);
        mutate(project,
                MoneyUtils.add(project.getUsedAmount(), value),
                MoneyUtils.subtract(project.getFrozenAmount(), value),
                "consume");
    }

    @Override
    public void release(Long projectId, BigDecimal amount) {
        ResearchProjectEntity project = load(projectId);
        BigDecimal value = MoneyUtils.normalize(amount);
        mutate(project, project.getUsedAmount(), MoneyUtils.subtract(project.getFrozenAmount(), value), "release");
    }

    @Override
    public void charge(Long projectId, BigDecimal amount) {
        ResearchProjectEntity project = load(projectId);
        BigDecimal value = MoneyUtils.normalize(amount);
        ensureAvailable(project, value);
        mutate(project, MoneyUtils.add(project.getUsedAmount(), value), project.getFrozenAmount(), "charge");
    }

    @Override
    public void refundUsed(Long projectId, BigDecimal amount) {
        ResearchProjectEntity project = load(projectId);
        BigDecimal value = MoneyUtils.normalize(amount);
        mutate(project, MoneyUtils.subtract(project.getUsedAmount(), value), project.getFrozenAmount(), "refundUsed");
    }

    private void mutate(ResearchProjectEntity project, BigDecimal nextUsed, BigDecimal nextFrozen, String op) {
        BigDecimal normalizedUsed = MoneyUtils.normalize(nextUsed);
        BigDecimal normalizedFrozen = MoneyUtils.normalize(nextFrozen);
        if (normalizedUsed.signum() < 0 || normalizedFrozen.signum() < 0) {
            throw new BusinessException(
                    "BUDGET_AMOUNT_NEGATIVE",
                    "budget used/frozen must not go negative. projectId=" + project.getId()
                            + ", op=" + op + ", nextUsed=" + normalizedUsed + ", nextFrozen=" + normalizedFrozen,
                    HttpStatus.CONFLICT);
        }

        ResearchProjectEntity update = new ResearchProjectEntity();
        update.setId(project.getId());
        update.setUsedAmount(normalizedUsed);
        update.setFrozenAmount(normalizedFrozen);
        update.setVersion(project.getVersion());
        int updated = researchProjectMapper.updateById(update);
        if (updated != 1) {
            throw new BusinessException(
                    "BUDGET_PROJECT_VERSION_CONFLICT",
                    "project budget was changed by another request. projectId=" + project.getId() + ", op=" + op,
                    HttpStatus.CONFLICT);
        }

        log.atInfo()
                .addKeyValue("projectId", project.getId())
                .addKeyValue("op", op)
                .addKeyValue("usedAmount", normalizedUsed)
                .addKeyValue("frozenAmount", normalizedFrozen)
                .log("Project budget occupied");
    }

    private void ensureAvailable(ResearchProjectEntity project, BigDecimal requested) {
        BigDecimal available = available(project);
        if (MoneyUtils.greaterThan(requested, available)) {
            throw new BusinessException(
                    "BUDGET_PROJECT_AVAILABLE_AMOUNT_EXCEEDED",
                    "amount exceeds project available budget. projectId=" + project.getId()
                            + ", requested=" + requested + ", available=" + available,
                    HttpStatus.CONFLICT);
        }
    }

    private BigDecimal available(ResearchProjectEntity project) {
        BigDecimal allocated = nz(project.getTotalBudget());
        return MoneyUtils.subtract(MoneyUtils.subtract(allocated, nz(project.getUsedAmount())), nz(project.getFrozenAmount()));
    }

    private ResearchProjectEntity load(Long projectId) {
        ResearchProjectEntity project = researchProjectMapper.selectById(projectId);
        if (project == null) {
            throw new BusinessException(
                    "RESEARCH_PROJECT_NOT_FOUND",
                    "Research project was not found. projectId=" + projectId,
                    HttpStatus.NOT_FOUND);
        }
        return project;
    }

    private BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
