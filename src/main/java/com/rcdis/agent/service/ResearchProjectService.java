package com.rcdis.agent.service;

import com.rcdis.agent.common.response.PageResponse;
import com.rcdis.agent.dto.ProjectCreateRequest;
import com.rcdis.agent.dto.ProjectDeleteRequest;
import com.rcdis.agent.dto.ProjectPageRequest;
import com.rcdis.agent.dto.ProjectUpdateRequest;
import com.rcdis.agent.vo.ProjectVO;

public interface ResearchProjectService {

    PageResponse<ProjectVO> pageProjects(ProjectPageRequest request);

    ProjectVO getProject(Long id);

    ProjectVO createProject(ProjectCreateRequest request);

    ProjectVO updateProject(Long id, ProjectUpdateRequest request);

    void deleteProject(Long id, ProjectDeleteRequest request);
}
