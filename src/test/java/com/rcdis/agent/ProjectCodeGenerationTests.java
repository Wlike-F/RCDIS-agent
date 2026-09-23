package com.rcdis.agent;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.rcdis.agent.common.context.CurrentUserContextHolder;
import com.rcdis.agent.common.context.CurrentUserTO;
import com.rcdis.agent.dto.ProjectCreateRequest;
import com.rcdis.agent.service.ResearchProjectService;
import com.rcdis.agent.vo.ProjectVO;

/**
 * Users should never have to invent project codes: blank codes are minted as
 * {@code P-<year>-<seq>} server-side, while explicit codes keep working for imports.
 */
@ActiveProfiles("test")
@SpringBootTest(properties = {
        "rcdis.feishu.enabled=false",
        "rcdis.feishu.client-type=noop"
})
class ProjectCodeGenerationTests {

    @Autowired
    private ResearchProjectService researchProjectService;

    @AfterEach
    void clearUser() {
        CurrentUserContextHolder.clear();
    }

    @Test
    void blankCodesAreMintedSequentiallyAndExplicitCodesAreHonored() {
        CurrentUserTO owner = CurrentUserTO.of(
                "pcg-u1", "pcg-user", "test", null, Set.of("ADMIN"));
        CurrentUserContextHolder.set(owner);

        ProjectVO first = create(null);
        ProjectVO second = create(null);
        ProjectVO explicit = create("NSFC-2026-001");

        assertThat(first.projectCode()).matches("P-2026-\\d{3,}");
        assertThat(second.projectCode()).matches("P-2026-\\d{3,}");
        int firstSeq = Integer.parseInt(first.projectCode().substring("P-2026-".length()));
        int secondSeq = Integer.parseInt(second.projectCode().substring("P-2026-".length()));
        assertThat(secondSeq).isEqualTo(firstSeq + 1);
        assertThat(explicit.projectCode()).isEqualTo("NSFC-2026-001");
    }

    private ProjectVO create(String code) {
        return researchProjectService.createProject(new ProjectCreateRequest(
                code, "编号生成测试项目" + (code == null ? "(自动)" : "(手动)"),
                "pcg-user", "TEST", new BigDecimal("10000.00"),
                LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1), "ACTIVE", true));
    }
}
