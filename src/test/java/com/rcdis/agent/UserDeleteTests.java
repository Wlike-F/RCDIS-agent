package com.rcdis.agent;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import com.rcdis.agent.common.context.CurrentUserContextHolder;
import com.rcdis.agent.common.context.CurrentUserTO;
import com.rcdis.agent.common.exception.BusinessException;
import com.rcdis.agent.dto.ProjectCreateRequest;
import com.rcdis.agent.dto.ReimbursementCreateRequest;
import com.rcdis.agent.dto.ReimbursementItemInput;
import com.rcdis.agent.dto.UserCreateRequest;
import com.rcdis.agent.dto.UserDeleteRequest;
import com.rcdis.agent.service.AuthService;
import com.rcdis.agent.service.ResearchProjectService;
import com.rcdis.agent.service.UserService;
import com.rcdis.agent.vo.UserVO;

/**
 * Account deletion: the row is soft-deleted (login stops working, history stays), and the two
 * lockout guards hold — nobody deletes their own account, and the last usable admin survives.
 */
@ActiveProfiles("test")
@SpringBootTest(properties = {
        "rcdis.feishu.enabled=false",
        "rcdis.feishu.client-type=noop"
})
// One case deletes the seeded admin to exercise the last-admin guard; drop the shared H2 context
// afterwards so no other test class inherits that state.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class UserDeleteTests {

    @Autowired
    private UserService userService;

    @Autowired
    private AuthService authService;

    @Autowired
    private ResearchProjectService researchProjectService;

    @Autowired
    private com.rcdis.agent.service.ReimbursementService reimbursementService;

    @AfterEach
    void clearUser() {
        CurrentUserContextHolder.clear();
    }

    @Test
    void deletedAccountDisappearsFromListsAndCanNoLongerLogIn() {
        CurrentUserTO admin = CurrentUserTO.of("ud-admin", "ud-admin", "test", null, Set.of("ADMIN"));
        CurrentUserContextHolder.set(admin);
        String username = "ud-" + UUID.randomUUID().toString().substring(0, 8);
        UserVO created = userService.createUser(new UserCreateRequest(
                username, "pass1234", "待删除人员", null, List.of("RESEARCHER")));

        // Login works before deletion.
        assertThat(authService.login(new com.rcdis.agent.dto.AuthLoginRequest(username, "pass1234")))
                .isNotNull();

        userService.deleteUser(created.id(), new UserDeleteRequest("离室人员，已交回账号"));

        assertThat(userService.findByUsername(username)).isEmpty();
        assertThat(userService.pageUsers(1, 50, username).records()).isEmpty();
        assertThatThrownBy(() -> authService.login(
                new com.rcdis.agent.dto.AuthLoginRequest(username, "pass1234")))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("AUTH_LOGIN_FAILED"));
    }

    @Test
    void deletingOwnAccountIsRefused() {
        CurrentUserTO admin = CurrentUserTO.of("ud-self", "ud-self", "test", null, Set.of("ADMIN"));
        CurrentUserContextHolder.set(admin);
        UserVO self = userService.createUser(new UserCreateRequest(
                "ud-self", "pass1234", "自己", null, List.of("ADMIN")));

        assertThatThrownBy(() -> userService.deleteUser(self.id(), new UserDeleteRequest("误操作")))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("USER_DELETE_SELF_FORBIDDEN"));
    }

    @Test
    void lastUsableAdminCannotBeDeleted() {
        // The test profile seeds one ADMIN; create a second one so the seeded account can be removed
        // and the target really becomes the last usable admin.
        CurrentUserTO keeper = CurrentUserTO.of("ud-keeper", "ud-keeper", "test", null, Set.of("ADMIN"));
        CurrentUserContextHolder.set(keeper);
        UserVO keeperAccount = userService.createUser(new UserCreateRequest(
                "ud-keeper", "pass1234", "留守管理员", null, List.of("ADMIN")));
        assertThat(keeperAccount.id()).isNotNull();

        UserVO seeded = userService.pageUsers(1, 50, "admin").records().stream()
                .filter(vo -> "admin".equals(vo.username()))
                .findFirst()
                .orElseThrow();
        userService.deleteUser(seeded.id(), new UserDeleteRequest("用测试账号替换种子管理员"));

        // Now only ud-keeper is a usable admin, and it is not the caller's own account.
        CurrentUserContextHolder.set(CurrentUserTO.of(
                "ud-outsider", "ud-outsider", "test", null, Set.of("RESEARCHER")));
        assertThatThrownBy(() -> userService.deleteUser(
                keeperAccount.id(), new UserDeleteRequest("想删掉最后一个管理员")))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("USER_LAST_ADMIN"));
    }

    @Test
    void deletionKeepsHistoricalRecordsReadableThroughAudit() {
        String username = "ud-data-" + UUID.randomUUID().toString().substring(0, 8);
        // The applicant is forced to the logged-in account for researchers, so file the order as the
        // person who will later be deleted.
        CurrentUserTO owner = CurrentUserTO.of(username, username, "test", null, Set.of("RESEARCHER"));
        CurrentUserContextHolder.set(owner);
        UserVO victim = userService.createUser(new UserCreateRequest(
                username, "pass1234", "有历史数据的人员", null, List.of("RESEARCHER")));
        var project = researchProjectService.createProject(new ProjectCreateRequest(
                "UD-" + UUID.randomUUID(), "删除用户遗留数据项目", username, "TEST",
                new BigDecimal("10000.00"), LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1),
                "ACTIVE", true));
        reimbursementService.createReimbursement(new ReimbursementCreateRequest(
                project.id(), username, "reimbursement",
                List.of(new ReimbursementItemInput(
                        new BigDecimal("88.00"), LocalDate.of(2026, 9, 10), "V", "INV-UD-1",
                        null, "删除前留下的报销", null)),
                "delete-user history", false));

        CurrentUserContextHolder.set(CurrentUserTO.of(
                "ud-admin2", "ud-admin2", "test", null, Set.of("ADMIN")));
        userService.deleteUser(victim.id(), new UserDeleteRequest("人员离室"));

        // The account is gone, but its reimbursement order still exists and keeps its applicant name.
        assertThat(userService.findByUsername(username)).isEmpty();
        var orders = reimbursementService.pageReimbursements(
                new com.rcdis.agent.dto.ReimbursementPageRequest(1, 10, project.id(), null, null));
        assertThat(orders.records()).singleElement().satisfies(order -> {
            assertThat(order.applicant()).isEqualTo(username);
            assertThat(order.totalAmount()).isEqualByComparingTo(new BigDecimal("88.00"));
        });
    }
}
