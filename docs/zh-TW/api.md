# REST API

[← 文件目錄](README.md)

網頁管理介面背後的 API。除非標示公開，都需要登入；ADMIN 表示只有管理員可用。

## 規則管理

| Method | Path | 說明 |
|--------|------|------|
| GET | /api/admin/rules | 列出所有規則 |
| GET | /api/admin/rules/{id} | 取得規則（含回應 body） |
| POST | /api/admin/rules | 建立規則 |
| PUT | /api/admin/rules/{id} | 更新規則 |
| DELETE | /api/admin/rules/{id} | 刪除規則 |
| POST | /api/admin/rules/{id}/test | 測試規則匹配 |
| PUT | /api/admin/rules/{id}/enable | 啟用規則 |
| PUT | /api/admin/rules/{id}/disable | 停用規則 |
| PUT | /api/admin/rules/{id}/protect | 保護規則 |
| PUT | /api/admin/rules/{id}/unprotect | 取消保護 |
| PUT | /api/admin/rules/{id}/extend | 展延規則 |
| PUT | /api/admin/rules/batch/enable | 批次啟用 |
| PUT | /api/admin/rules/batch/disable | 批次停用 |
| PUT | /api/admin/rules/batch/protect | 批次保護 |
| PUT | /api/admin/rules/batch/unprotect | 批次取消保護 |
| PUT | /api/admin/rules/batch/extend | 批次展延 |
| PUT | /api/admin/rules/tag/{key}/{value}/enable | 依標籤啟用 (ADMIN) |
| PUT | /api/admin/rules/tag/{key}/{value}/disable | 依標籤停用 (ADMIN) |
| GET | /api/admin/rules/export | 匯出全部規則 (ADMIN) |
| GET | /api/admin/rules/{id}/json | 匯出單筆規則 |
| POST | /api/admin/rules/import | 匯入單筆規則 (ADMIN) |
| POST | /api/admin/rules/import-batch | 批次匯入 (ADMIN) |
| POST | /api/admin/rules/import-excel | Excel 匯入 (ADMIN) |
| GET | /api/admin/rules/import-template | 下載 Excel 匯入範本 |
| DELETE | /api/admin/rules/batch | 批次刪除 (ADMIN) |
| DELETE | /api/admin/rules/all | 全部刪除 (ADMIN) |

## 回應管理

| Method | Path | 說明 |
|--------|------|------|
| GET | /api/admin/responses | 列出回應（支援 keyword 搜尋） |
| GET | /api/admin/responses/summary | 回應摘要（含使用數） |
| GET | /api/admin/responses/{id} | 取得回應 |
| GET | /api/admin/responses/{id}/rules | 使用此回應的規則 |
| POST | /api/admin/responses | 建立回應 |
| PUT | /api/admin/responses/{id} | 更新回應 |
| DELETE | /api/admin/responses/{id} | 刪除回應（連帶刪除關聯規則） |
| PUT | /api/admin/responses/{id}/extend | 展延回應 |
| PUT | /api/admin/responses/batch/extend | 批次展延回應 |
| GET | /api/admin/responses/orphan-count | 孤兒回應數量 |
| DELETE | /api/admin/responses/orphans | 刪除孤兒回應 |
| GET | /api/admin/responses/export | 匯出全部回應 (ADMIN) |
| POST | /api/admin/responses/import-batch | 批次匯入回應 (ADMIN) |
| DELETE | /api/admin/responses/batch | 批次刪除回應 (ADMIN) |
| DELETE | /api/admin/responses/all | 全部刪除回應 (ADMIN) |

## 紀錄查詢

| Method | Path | 說明 |
|--------|------|------|
| GET | /api/admin/logs | 請求紀錄（需登入；支援 ruleId/protocol/matched/endpoint 篩選） |
| GET | /api/admin/logs/summary | 請求紀錄摘要（需登入） |
| DELETE | /api/admin/logs/all | 刪除全部請求紀錄 (ADMIN) |
| GET | /api/admin/rules/{id}/audit | 單筆規則修訂紀錄 |
| GET | /api/admin/audit | 全部修訂紀錄 |
| DELETE | /api/admin/audit/all | 刪除全部修訂紀錄 (ADMIN) |

## 系統管理

| Method | Path | 說明 |
|--------|------|------|
| GET | /api/admin/status | 系統狀態（含 JVM、DB、統計） |
| GET | /api/admin/backup/status | 備份狀態與檔案列表 |
| POST | /api/admin/backup | 手動觸發備份 |

## 帳號管理

| Method | Path | 說明 |
|--------|------|------|
| GET | /api/admin/builtin-users | 列出內建帳號 (ADMIN) |
| POST | /api/admin/builtin-users | 建立帳號 (ADMIN) |
| PUT | /api/admin/builtin-users/{id}/enable | 啟用帳號 (ADMIN) |
| PUT | /api/admin/builtin-users/{id}/disable | 停用帳號 (ADMIN) |
| DELETE | /api/admin/builtin-users/{id} | 刪除帳號 (ADMIN) |
| POST | /api/admin/builtin-users/{id}/reset-password | 重設密碼 (ADMIN) |
| POST | /api/admin/builtin-users/forgot-password | 忘記密碼（公開） |
| POST | /api/admin/builtin-users/register | 自助註冊（公開，需啟用） |
| PUT | /api/account/change-password | 修改自己的密碼（已登入） |

## 問題回報

需要開啟 `ECHO_ISSUE_REPORTING_ENABLED`，關閉時這些 API 回 404。

| Method | Path | 說明 |
|--------|------|------|
| GET | /api/admin/issues | 列出問題回報（已登入；舊的不分頁 API） |
| GET | /api/admin/issues/page | 分頁查詢；支援 status/keyword/page/size/sort/direction，並回傳全部未結案數 |
| GET | /api/admin/issues/{id} | 取得單筆問題回報 |
| POST | /api/admin/issues | 建立問題回報（已登入） |
| PUT | /api/admin/issues/{id}/reply | 回覆 (ADMIN) |
| PUT | /api/admin/issues/{id}/resolve | 結案 (ADMIN) |
| PUT | /api/admin/issues/{id}/reopen | 重新開啟 (ADMIN) |
| DELETE | /api/admin/issues/{id} | 刪除 (ADMIN) |

## JMS 測試

| Method | Path | 說明 |
|--------|------|------|
| POST | /api/admin/jms/test | 發送測試訊息到 ECHO.REQUEST |
