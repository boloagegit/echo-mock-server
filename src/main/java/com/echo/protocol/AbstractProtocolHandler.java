package com.echo.protocol;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 協定處理器抽象基底類別
 * <p>
 * 定義子類別必須實作的批次操作方法，由子類別直接呼叫 Repository 的批次 SQL。
 */
public abstract class AbstractProtocolHandler implements ProtocolHandler {

    @Override
    public abstract int updateEnabled(List<String> ids, boolean enabled);

    @Override
    public abstract int updateProtected(List<String> ids, boolean isProtected);

    @Override
    public abstract int extendRules(List<String> ids, LocalDateTime extendedAt);
}
