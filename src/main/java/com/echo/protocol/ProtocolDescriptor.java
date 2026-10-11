package com.echo.protocol;

import com.echo.entity.BaseRule;
import com.echo.entity.Protocol;

import java.util.List;

/**
 * 一個協定在協定無關程式（規則查詢、快取失效、管理 API）中需要的資訊，由該協定的 {@link ProtocolHandler} 提供。
 * 新增協定時在 handler 中描述，不需要在各個 service 加上協定分支。
 *
 * @param id             協定
 * @param ruleType       規則 entity 類別（規則清單查詢使用）
 * @param rulesCacheName 規則快取名稱；規則異動與多實例同步時清除
 * @param searchFields   規則清單關鍵字搜尋的欄位（entity 屬性名稱）
 * @param mockAction     規則 {@code action} 欄位代表「回傳 mock」的值
 * @param forwardAction  規則 {@code action} 欄位代表「轉發」的值
 */
public record ProtocolDescriptor(Protocol id,
                                 Class<? extends BaseRule> ruleType,
                                 String rulesCacheName,
                                 List<String> searchFields,
                                 Enum<?> mockAction,
                                 Enum<?> forwardAction) {

    public ProtocolDescriptor {
        searchFields = List.copyOf(searchFields);
    }

    /** 多實例同步時，代表「此協定的規則已變更」的事件類型。 */
    public String cacheEventType() {
        return cacheEventType(id);
    }

    public static String cacheEventType(Protocol protocol) {
        return protocol.name() + "_RULE_CHANGED";
    }
}
