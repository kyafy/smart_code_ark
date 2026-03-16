package com.smartark.gateway.service;

import com.smartark.gateway.common.exception.BusinessException;
import com.smartark.gateway.common.exception.ErrorCodes;
import com.smartark.gateway.common.response.PageResponse;
import com.smartark.gateway.dto.CreateProjectRequest;
import com.smartark.gateway.dto.ProjectResult;
import com.smartark.gateway.entity.ProjectEntity;
import com.smartark.gateway.repository.ProjectRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 项目管理服务
 * <p>
 * 提供项目的创建、查询（列表和详情）等核心功能。
 * 负责维护项目元数据，如名称、描述、状态等。
 * </p>
 */
@Service
public class ProjectService {
    private final ProjectRepository projectRepository;

    /**
     * 构造函数
     *
     * @param projectRepository 项目数据仓库
     */
    public ProjectService(ProjectRepository projectRepository) {
        this.projectRepository = projectRepository;
    }

    /**
     * 创建新项目
     *
     * @param ownerId 项目所有者 ID
     * @param request 创建项目请求参数
     * @return 创建成功的项目详情
     */
    public ProjectResult create(String ownerId, CreateProjectRequest request) {
        Instant now = Instant.now();
        ProjectEntity entity = new ProjectEntity();
        entity.setId(UUID.randomUUID().toString());
        entity.setOwnerId(ownerId);
        entity.setName(request.name());
        entity.setDescription(request.description());
        entity.setStatus("DRAFT");
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        return toResult(projectRepository.save(entity));
    }

    /**
     * 分页查询用户的项目列表
     *
     * @param ownerId 项目所有者 ID
     * @param page    页码（从1开始）
     * @param size    每页数量
     * @return 项目列表的分页结果
     */
    public PageResponse<ProjectResult> listByOwner(String ownerId, int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        Page<ProjectEntity> pageData = projectRepository.findByOwnerId(
                ownerId,
                PageRequest.of(safePage - 1, safeSize, Sort.by(Sort.Direction.DESC, "createdAt"))
        );
        List<ProjectResult> items = pageData.getContent().stream().map(this::toResult).toList();
        return PageResponse.of(items, pageData.getTotalElements(), safePage, safeSize);
    }

    /**
     * 查询项目详情
     *
     * @param ownerId   项目所有者 ID
     * @param projectId 项目 ID
     * @return 项目详细信息
     * @throws BusinessException 如果项目不存在或不属于该用户
     */
    public ProjectResult detail(String ownerId, String projectId) {
        return projectRepository.findByIdAndOwnerId(projectId, ownerId)
                .map(this::toResult)
                .orElseThrow(() -> new BusinessException(ErrorCodes.PROJECT_NOT_FOUND, "项目不存在"));
    }

    private ProjectResult toResult(ProjectEntity entity) {
        return new ProjectResult(
                entity.getId(),
                entity.getOwnerId(),
                entity.getName(),
                entity.getDescription(),
                entity.getStatus(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
