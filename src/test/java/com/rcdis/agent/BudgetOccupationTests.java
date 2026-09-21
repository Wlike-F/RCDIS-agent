package com.rcdis.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.rcdis.agent.common.exception.BusinessException;
import com.rcdis.agent.entity.ResearchProjectEntity;
import com.rcdis.agent.mapper.ResearchProjectMapper;
import com.rcdis.agent.service.BudgetOccupationService;

/**
 * Unit-level coverage for the single project-level budget occupation primitive:
 * the two-phase freeze/consume/release path plus the direct charge/refund path,
 * and the available-amount guard that prevents over-spending.
 */
@ActiveProfiles("test")
@SpringBootTest
class BudgetOccupationTests {

    @Autowired
    private BudgetOccupationService budget;

    @Autowired
    private ResearchProjectMapper projectMapper;

    private Long seedProject(String totalBudget) {
        ResearchProjectEntity project = new ResearchProjectEntity();
        project.setProjectCode("BP-" + System.nanoTime());
        project.setProjectName("Budget Occupation Test");
        project.setTotalBudget(new BigDecimal(totalBudget));
        project.setStatus("ACTIVE");
        project.setVersion(0);
        projectMapper.insert(project);
        return project.getId();
    }

    @Test
    void freezeThenConsumeMovesFrozenToUsed() {
        Long id = seedProject("100.00");

        budget.freeze(id, new BigDecimal("40.00"));
        ResearchProjectEntity afterFreeze = projectMapper.selectById(id);
        assertThat(afterFreeze.getFrozenAmount()).isEqualByComparingTo("40.00");
        assertThat(afterFreeze.getUsedAmount()).isEqualByComparingTo("0.00");

        budget.consume(id, new BigDecimal("40.00"));
        ResearchProjectEntity afterConsume = projectMapper.selectById(id);
        assertThat(afterConsume.getUsedAmount()).isEqualByComparingTo("40.00");
        assertThat(afterConsume.getFrozenAmount()).isEqualByComparingTo("0.00");
    }

    @Test
    void releaseReturnsFrozenBudget() {
        Long id = seedProject("100.00");
        budget.freeze(id, new BigDecimal("30.00"));
        budget.release(id, new BigDecimal("30.00"));
        assertThat(projectMapper.selectById(id).getFrozenAmount()).isEqualByComparingTo("0.00");
    }

    @Test
    void chargeBooksUsedDirectly() {
        Long id = seedProject("100.00");
        budget.charge(id, new BigDecimal("25.00"));
        assertThat(projectMapper.selectById(id).getUsedAmount()).isEqualByComparingTo("25.00");
    }

    @Test
    void refundUsedReversesCharge() {
        Long id = seedProject("100.00");
        budget.charge(id, new BigDecimal("20.00"));
        budget.refundUsed(id, new BigDecimal("20.00"));
        assertThat(projectMapper.selectById(id).getUsedAmount()).isEqualByComparingTo("0.00");
    }

    @Test
    void freezeOverAvailableThrows() {
        Long id = seedProject("10.00");
        assertThatThrownBy(() -> budget.freeze(id, new BigDecimal("50.00")))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("BUDGET_PROJECT_AVAILABLE_AMOUNT_EXCEEDED"));
    }

    @Test
    void chargeOverAvailableThrows() {
        Long id = seedProject("10.00");
        assertThatThrownBy(() -> budget.charge(id, new BigDecimal("15.00")))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("BUDGET_PROJECT_AVAILABLE_AMOUNT_EXCEEDED"));
    }
}
