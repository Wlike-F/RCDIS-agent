package com.rcdis.agent.service.impl;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import com.rcdis.agent.common.context.CurrentUserContextHolder;
import com.rcdis.agent.common.exception.BusinessException;
import com.rcdis.agent.config.StorageProperties;
import com.rcdis.agent.entity.AgentAttachmentEntity;
import com.rcdis.agent.infrastructure.document.DocumentTextExtractor;
import com.rcdis.agent.mapper.AgentAttachmentMapper;
import com.rcdis.agent.service.AgentAttachmentService;
import com.rcdis.agent.vo.AgentAttachmentVO;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentAttachmentServiceImpl implements AgentAttachmentService {

    private static final String SUBDIR = "agent-attachments";
    private static final String URL_PREFIX = "/api/files/agent-attachments";
    private static final long MAX_SIZE = 25L * 1024 * 1024;
    private static final int MAX_EXTRACT_CHARS = 20000;
    private static final int MAX_ATTACHMENTS_PER_TURN = 10;

    // extension -> kind. Legacy .doc/.ppt intentionally absent (convert to docx/pptx first).
    private static final Map<String, String> EXT_KIND = Map.ofEntries(
            Map.entry("txt", AgentAttachmentEntity.KIND_TEXT),
            Map.entry("md", AgentAttachmentEntity.KIND_TEXT),
            Map.entry("pdf", AgentAttachmentEntity.KIND_PDF),
            Map.entry("docx", AgentAttachmentEntity.KIND_DOCX),
            Map.entry("pptx", AgentAttachmentEntity.KIND_PPTX),
            Map.entry("png", AgentAttachmentEntity.KIND_IMAGE),
            Map.entry("jpg", AgentAttachmentEntity.KIND_IMAGE),
            Map.entry("jpeg", AgentAttachmentEntity.KIND_IMAGE),
            Map.entry("gif", AgentAttachmentEntity.KIND_IMAGE),
            Map.entry("webp", AgentAttachmentEntity.KIND_IMAGE));

    private final AgentAttachmentMapper agentAttachmentMapper;
    private final DocumentTextExtractor documentTextExtractor;
    private final StorageProperties storageProperties;

    @Override
    public AgentAttachmentVO store(MultipartFile file, String conversationId) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("FILE_REQUIRED", "上传文件不能为空", HttpStatus.BAD_REQUEST);
        }
        if (file.getSize() > MAX_SIZE) {
            throw new BusinessException("FILE_TOO_LARGE", "附件不能超过 25MB", HttpStatus.BAD_REQUEST);
        }
        String originalName = file.getOriginalFilename() == null ? "file" : file.getOriginalFilename();
        String ext = extensionOf(originalName);
        String kind = EXT_KIND.get(ext);
        if (kind == null) {
            throw new BusinessException(
                    "FILE_TYPE_NOT_ALLOWED",
                    "暂不支持该格式：" + ext + "。支持 txt/md/pdf/docx/pptx/png/jpg/jpeg/gif/webp（旧版 .doc/.ppt 请先转存）",
                    HttpStatus.BAD_REQUEST);
        }

        String fileName = UUID.randomUUID() + "." + ext;
        Path targetDir = uploadDir().resolve(SUBDIR);
        Path target = targetDir.resolve(fileName);
        try {
            Files.createDirectories(targetDir);
            file.transferTo(target);
        } catch (IOException exception) {
            throw new BusinessException(
                    "FILE_STORAGE_FAILED", "附件存储失败：" + exception.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }

        String extracted = null;
        String extractStatus;
        if (AgentAttachmentEntity.KIND_IMAGE.equals(kind)) {
            extractStatus = AgentAttachmentEntity.EXTRACT_UNSUPPORTED;
        } else {
            extracted = documentTextExtractor.extract(target, kind);
            if (extracted == null) {
                extractStatus = AgentAttachmentEntity.EXTRACT_FAILED;
            } else if (!StringUtils.hasText(extracted)) {
                extractStatus = AgentAttachmentEntity.EXTRACT_EMPTY;
            } else {
                extractStatus = AgentAttachmentEntity.EXTRACT_OK;
                if (extracted.length() > MAX_EXTRACT_CHARS) {
                    extracted = extracted.substring(0, MAX_EXTRACT_CHARS);
                }
            }
        }

        AgentAttachmentEntity entity = new AgentAttachmentEntity();
        entity.setConversationId(StringUtils.hasText(conversationId) ? conversationId : null);
        entity.setOriginalName(truncate(originalName, 255));
        entity.setStoredPath(target.toString());
        entity.setUrl("PENDING");
        entity.setMime(file.getContentType());
        entity.setExt(ext);
        entity.setSizeBytes(file.getSize());
        entity.setKind(kind);
        entity.setExtractedText(extracted);
        entity.setExtractStatus(extractStatus);
        entity.setCreatedBy(CurrentUserContextHolder.currentOrAnonymous().userId());
        entity.setCreatedAt(OffsetDateTime.now());
        agentAttachmentMapper.insert(entity);
        entity.setUrl(URL_PREFIX + "/" + entity.getId() + "/content");
        agentAttachmentMapper.updateById(entity);

        log.atInfo()
                .addKeyValue("attachmentId", entity.getId())
                .addKeyValue("kind", kind)
                .addKeyValue("extractStatus", extractStatus)
                .log("Agent attachment stored");
        return toVO(entity);
    }

    @Override
    public List<AgentAttachmentEntity> findByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        if (ids.size() > MAX_ATTACHMENTS_PER_TURN) {
            throw new BusinessException("ATTACHMENT_COUNT_EXCEEDED", "单次对话最多引用 10 个附件", HttpStatus.BAD_REQUEST);
        }
        String currentUserId = CurrentUserContextHolder.currentOrAnonymous().userId();
        List<AgentAttachmentEntity> result = new ArrayList<>();
        for (Long id : ids) {
            AgentAttachmentEntity entity = agentAttachmentMapper.selectById(id);
            if (entity == null) {
                throw new BusinessException("ATTACHMENT_NOT_FOUND", "附件不存在。attachmentId=" + id, HttpStatus.NOT_FOUND);
            }
            if (!currentUserId.equals(entity.getCreatedBy())) {
                throw new BusinessException("ATTACHMENT_FORBIDDEN", "附件属于其他用户。attachmentId=" + id, HttpStatus.FORBIDDEN);
            }
            result.add(entity);
        }
        return result;
    }

    @Override
    public AgentAttachmentEntity findOwnedById(Long id) {
        AgentAttachmentEntity entity = agentAttachmentMapper.selectById(id);
        if (entity == null) {
            throw new BusinessException("ATTACHMENT_NOT_FOUND", "附件不存在。attachmentId=" + id, HttpStatus.NOT_FOUND);
        }
        String currentUserId = CurrentUserContextHolder.currentOrAnonymous().userId();
        if (!currentUserId.equals(entity.getCreatedBy())) {
            throw new BusinessException("ATTACHMENT_FORBIDDEN", "附件属于其他用户。attachmentId=" + id, HttpStatus.FORBIDDEN);
        }
        return entity;
    }

    @Override
    public Path contentPath(AgentAttachmentEntity attachment) {
        Path allowedRoot = uploadDir().resolve(SUBDIR).normalize();
        Path content = Path.of(attachment.getStoredPath()).toAbsolutePath().normalize();
        if (!content.startsWith(allowedRoot) || !Files.isRegularFile(content)) {
            throw new BusinessException("ATTACHMENT_CONTENT_NOT_FOUND", "附件内容不存在", HttpStatus.NOT_FOUND);
        }
        return content;
    }

    @Override
    public AgentAttachmentEntity findLatestRegisterableByName(String originalName, String conversationId, String userId) {
        if (!StringUtils.hasText(originalName) || !StringUtils.hasText(conversationId) || !StringUtils.hasText(userId)) {
            throw new BusinessException(
                    "ATTACHMENT_NOT_FOUND", "附件名、对话与用户均不能为空", HttpStatus.BAD_REQUEST);
        }
        AgentAttachmentEntity entity = agentAttachmentMapper.selectOne(
                new LambdaQueryWrapper<AgentAttachmentEntity>()
                        .eq(AgentAttachmentEntity::getConversationId, conversationId)
                        .eq(AgentAttachmentEntity::getOriginalName, originalName)
                        .eq(AgentAttachmentEntity::getCreatedBy, userId)
                        .in(AgentAttachmentEntity::getKind,
                                AgentAttachmentEntity.KIND_IMAGE, AgentAttachmentEntity.KIND_PDF)
                        .orderByDesc(AgentAttachmentEntity::getId)
                        .last("LIMIT 1"));
        if (entity == null) {
            throw new BusinessException(
                    "ATTACHMENT_NOT_FOUND",
                    "当前对话中未找到该图片或 PDF 附件：" + originalName,
                    HttpStatus.NOT_FOUND);
        }
        return entity;
    }

    // ---------- helpers ----------

    private Path uploadDir() {
        return Path.of(storageProperties.getUploadDir()).toAbsolutePath().normalize();
    }

    private static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return "";
        }
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private AgentAttachmentVO toVO(AgentAttachmentEntity entity) {
        return new AgentAttachmentVO(
                entity.getId(),
                entity.getConversationId(),
                entity.getOriginalName(),
                entity.getUrl(),
                entity.getMime(),
                entity.getExt(),
                entity.getSizeBytes(),
                entity.getKind(),
                entity.getExtractStatus(),
                entity.getExtractedText() == null ? 0 : entity.getExtractedText().length());
    }
}
