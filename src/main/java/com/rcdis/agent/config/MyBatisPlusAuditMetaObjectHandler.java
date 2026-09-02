package com.rcdis.agent.config;

import java.time.OffsetDateTime;

import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.rcdis.agent.common.context.CurrentUserContextHolder;
import com.rcdis.agent.common.context.CurrentUserTO;

@Component
public class MyBatisPlusAuditMetaObjectHandler implements MetaObjectHandler {

    @Override
    public void insertFill(MetaObject metaObject) {
        OffsetDateTime now = OffsetDateTime.now();
        CurrentUserTO currentUser = CurrentUserContextHolder.currentOrAnonymous();
        setFieldIfNull(metaObject, "createdAt", now);
        setFieldIfNull(metaObject, "updatedAt", now);
        setFieldIfNull(metaObject, "createdBy", currentUser.userId());
        setFieldIfNull(metaObject, "updatedBy", currentUser.userId());
        setFieldIfNull(metaObject, "deleted", Integer.valueOf(0));
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        OffsetDateTime now = OffsetDateTime.now();
        CurrentUserTO currentUser = CurrentUserContextHolder.currentOrAnonymous();
        setField(metaObject, "updatedAt", now);
        setField(metaObject, "updatedBy", currentUser.userId());
        if (isDeleted(metaObject)) {
            setFieldIfNull(metaObject, "deletedAt", now);
            setFieldIfNull(metaObject, "deletedBy", currentUser.userId());
        }
    }

    private void setFieldIfNull(MetaObject metaObject, String fieldName, Object value) {
        if (metaObject.hasSetter(fieldName) && getFieldValByName(fieldName, metaObject) == null) {
            setFieldValByName(fieldName, value, metaObject);
        }
    }

    private void setField(MetaObject metaObject, String fieldName, Object value) {
        if (metaObject.hasSetter(fieldName)) {
            setFieldValByName(fieldName, value, metaObject);
        }
    }

    private boolean isDeleted(MetaObject metaObject) {
        if (!metaObject.hasGetter("deleted")) {
            return false;
        }
        Object deleted = getFieldValByName("deleted", metaObject);
        return Integer.valueOf(1).equals(deleted) || Boolean.TRUE.equals(deleted);
    }
}
