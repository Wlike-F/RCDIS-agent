package com.rcdis.agent.service;

import java.util.List;
import java.util.Optional;

import com.rcdis.agent.common.response.PageResponse;
import com.rcdis.agent.dto.UserCreateRequest;
import com.rcdis.agent.dto.UserPasswordRequest;
import com.rcdis.agent.dto.UserRolesRequest;
import com.rcdis.agent.entity.SysUserEntity;
import com.rcdis.agent.vo.UserVO;

/**
 * Account lookups that back authentication, plus user administration for the ADMIN-only pages.
 */
public interface UserService {

    // ---------- authentication lookups ----------

    /** Finds a non-deleted account by its login name. Login decides whether a disabled account may proceed. */
    Optional<SysUserEntity> findByUsername(String username);

    /** Resolves the active role codes (ADMIN/APPROVER/RESEARCHER) assigned to a user. */
    List<String> findRoleCodes(Long userId);

    /** Verifies a raw password against the stored BCrypt digest. */
    boolean isPasswordValid(SysUserEntity user, String rawPassword);

    /** Records the timestamp of a successful login. */
    void touchLastLogin(Long userId);

    // ---------- administration ----------

    /** Pages users, optionally filtering by a keyword matched against username or display name. */
    PageResponse<UserVO> pageUsers(long current, long size, String keyword);

    /** Loads a single user with its role codes. */
    UserVO getUser(Long id);

    /** Creates an account with a BCrypt-hashed password and the given roles. */
    UserVO createUser(UserCreateRequest request);

    /** Resets a user's password. */
    void updatePassword(Long id, UserPasswordRequest request);

    /** Flips a user between ACTIVE and DISABLED. */
    UserVO toggleStatus(Long id);

    /** Replaces the full set of roles assigned to a user. */
    UserVO assignRoles(Long id, UserRolesRequest request);
}
