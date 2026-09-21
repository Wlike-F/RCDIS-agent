package com.rcdis.agent.entity;

import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

/** Security metadata for a binary stored outside PostgreSQL. */
@Getter
@Setter
@TableName("uploaded_file")
public class UploadedFileEntity extends BaseEntity {

    private String ownerUserId;
    private String category;
    private String fileName;
    private String storedPath;
    private String mime;
    private Long sizeBytes;
}
