package com.rcdis.agent.service;

import java.util.List;

import org.springframework.web.multipart.MultipartFile;

import com.rcdis.agent.entity.AgentAttachmentEntity;
import com.rcdis.agent.vo.AgentAttachmentVO;
import java.nio.file.Path;

/**
 * Stores Agent conversation attachments and extracts their text for model context.
 */
public interface AgentAttachmentService {

    /** Validates, stores and text-extracts an uploaded file; returns its client view. */
    AgentAttachmentVO store(MultipartFile file, String conversationId);

    /** Loads attachment entities by id (for injecting extracted text into a turn). */
    List<AgentAttachmentEntity> findByIds(List<Long> ids);

    /** Loads one attachment for download after enforcing current-user ownership. */
    AgentAttachmentEntity findOwnedById(Long id);

    /** Resolves and validates the stored path remains under the configured attachment directory. */
    Path contentPath(AgentAttachmentEntity attachment);

    /**
     * Finds the latest receipt-eligible attachment (image or PDF e-invoice) by display name within
     * one conversation, owned by the given user. Explicit actor parameter: safe on tool threads
     * without request identity.
     */
    AgentAttachmentEntity findLatestRegisterableByName(String originalName, String conversationId, String userId);
}
