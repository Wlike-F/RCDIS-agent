package com.rcdis.agent.entity;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

/**
 * A file uploaded into an Agent conversation for the model to reason over.
 *
 * <p>Text-based formats (txt / pdf / docx / pptx) are extracted once at upload time and cached in
 * {@code extracted_text}; images are stored but not OCR'd yet ({@code extract_status=UNSUPPORTED}).
 * Append-only metadata; the binary lives on the local file system under {@code stored_path}.</p>
 */
@Getter
@Setter
@TableName("agent_attachment")
public class AgentAttachmentEntity {

    public static final String KIND_TEXT = "TEXT";
    public static final String KIND_PDF = "PDF";
    public static final String KIND_DOCX = "DOCX";
    public static final String KIND_PPTX = "PPTX";
    public static final String KIND_IMAGE = "IMAGE";

    public static final String EXTRACT_OK = "OK";
    public static final String EXTRACT_EMPTY = "EMPTY";
    public static final String EXTRACT_UNSUPPORTED = "UNSUPPORTED";
    public static final String EXTRACT_FAILED = "FAILED";

    @TableId(type = IdType.AUTO)
    private Long id;

    private String conversationId;
    private String originalName;
    private String storedPath;
    private String url;
    private String mime;
    private String ext;
    private Long sizeBytes;
    private String kind;
    private String extractedText;
    private String extractStatus;
    private String createdBy;

    @TableField(value = "created_at")
    private OffsetDateTime createdAt;
}
