package com.echo.pipeline;

import com.echo.entity.BaseRule;

/**
 * 依命中的規則產生回應。Pipeline 依序詢問 {@link #appliesTo}，由第一個適用的 responder 產生 {@link ResponsePlan}。
 * <p>
 * 延遲與 Scenario 狀態轉移屬於規則層級，由 pipeline 統一處理，不屬於個別 responder。
 *
 * @param <T> 規則類型
 */
public interface Responder<T extends BaseRule> {

    /** 顯示在交易診斷中的回應模式，例如 {@code MOCK}、{@code FORWARD}、{@code FAULT}。 */
    String mode();

    boolean appliesTo(T rule, MockRequest request);

    ResponsePlan respond(T rule, MockRequest request);
}
