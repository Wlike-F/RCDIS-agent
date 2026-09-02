package com.rcdis.agent.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("notification_template")
public class NotificationTemplateEntity extends BaseEntity {

    private String templateCode;
    private String templateName;
    private String scene;
    private String description;
    private String messageType;
    private String content;
    private Integer builtin;
    private String status;

    @Version
    private Integer version;
}
