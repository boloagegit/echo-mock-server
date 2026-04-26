package com.echo.service;

/**
 * 匹配鏈條目的共用介面
 * <p>
 * HTTP 和 JMS 的 MatchChainEntry 都實作此介面，
 * 讓 {@link MatchDescriptionBuilder} 可以統一處理。
 */
public interface MatchChainInfo {

    String getRuleId();

    String getReason();

    String getEndpoint();

    String getDescription();

    String getCondition();

    /** 匹配分數，例如 "2/3"；無分析時回傳 null */
    String getScore();

    /** PASS/FAIL 詳細描述；無分析時回傳 null */
    String getDetail();

    /** 是否為 near-miss（部分條件通過但未全部通過） */
    boolean isNearMiss();
}
