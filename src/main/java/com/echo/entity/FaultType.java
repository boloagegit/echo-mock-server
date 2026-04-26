package com.echo.entity;

/**
 * 故障注入類型
 * <p>
 * 規則匹配成功後，根據 faultType 決定回應行為：
 * <ul>
 *   <li>NONE — 正常回應（預設）</li>
 *   <li>CONNECTION_RESET — HTTP: 關閉連線不回應；JMS: 不送 reply</li>
 *   <li>EMPTY_RESPONSE — HTTP: 回傳 200 空 body；JMS: 送空 reply</li>
 * </ul>
 */
public enum FaultType {
    NONE,
    CONNECTION_RESET,
    EMPTY_RESPONSE
}
