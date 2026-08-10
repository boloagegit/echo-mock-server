package com.echo.dto;

/** Apply 後的操作類型與伺服器正規化文件。 */
public record RuleApplyResult(String operation, RuleApplyDocument resource) {
}
