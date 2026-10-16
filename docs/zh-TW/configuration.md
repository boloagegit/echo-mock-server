# 設定

[← 文件目錄](README.md)

`application.yml` 的主要設定，以及登入與權限。完整設定與說明見 [`src/main/resources/application.yml`](../../src/main/resources/application.yml)。

## application.yml

```yaml
server:
  port: 8080
  servlet:
    session:
      timeout: 180d

echo:
  env-label:                    # 環境標籤（如 DEV、SIT、UAT）
  ui:
    accent: teal                # 介面主色：teal 或 blue（ECHO_UI_ACCENT）
    easter-egg: true            # 介面彩蛋小遊戲（ECHO_UI_EASTER_EGG）
  remember-me:
    key: echo-remember-me-secret  # Remember Me 加密金鑰
    validity: 180d                # Remember Me cookie 有效期
  admin:
    username: admin             # 管理員帳號
    password: admin             # 管理員密碼（支援 {bcrypt} 前綴）
  storage:
    mode: database              # database 或 file
  cache:
    body:
      max-size-mb: 50           # Response body 快取上限 (MB)
      threshold-kb: 5120        # 超過此大小的 body 不快取 (KB)
      expire-minutes: 720       # 快取過期時間 (分鐘)
    sync-interval-ms: 5000      # 多實例 cache 同步間隔 (毫秒)
  jms:
    enabled: false              # 設為 true 啟用 JMS
    forward:
      session-cache-size: 8     # 每個轉送目標保留的 idle Session；0 停用重用
    credential-key: ${ECHO_JMS_CREDENTIAL_KEY} # 資料庫內轉發密碼的加密金鑰
    port: 61616                 # Artemis 監聽埠
    queue: ECHO.REQUEST         # 監聽的 Queue
    endpoint-field: ServiceName # 從訊息 body 提取端點識別欄位
    processing-memory-percent: 25 # JMS 訊息解析最多使用的 heap 比例；超過時 listener 等待
    xml-memory-expansion-factor: 8 # XML 解析期間暫存記憶體的保守估算倍率
    broker-memory-percent: 15     # Artemis heap 比例；超過後 paging 到磁碟
    persistent: true              # 正式環境必須保持 true，避免 large message 撐爆 heap
    consumer-window-size: 65536   # 每個 listener 最多預取的 encoded bytes
    data-directory: ./data/artemis # Artemis paging 與 large-message 檔案目錄
    temporary-queue-idle-delete-ms: 10000 # 沒有 consumer 的暫時回覆佇列閒置多久後刪除（0 = 只在客戶端斷線時刪除）
    target:
      name: ESB-UAT             # 介面與請求記錄顯示的下游名稱（ECHO_JMS_TARGET_NAME）；空白時顯示 application.yml
      enabled: false            # 舊版單一連線相容設定；未建立資料庫連線時使用
      type: tibco               # artemis 或 tibco
      server-url: tcp://esb-server:7222
      timeout-seconds: 30        # 允許 1～300 秒；無效值會在設定綁定時拒絕啟動
      queue: TARGET.REQUEST     # 目標 Queue
  http:
    alias: HTTP                 # HTTP 協定顯示名稱
    transport: native           # 預設加速；ECHO_HTTP_TRANSPORT=servlet 可切回原入口
  stats:
    retention-days: 7           # 統計資料保留天數
  request-log:
    enabled: true               # 只有刻意停止新增請求記錄時才設為 false
    store: database             # memory 或 database
    max-records: 10000          # 最大記錄數
    include-body: true          # 是否記錄請求/回應 body
    max-body-size: 65536        # body 記錄上限 (bytes)
  audit:
    retention-days: 30          # 修訂紀錄保留天數
  cleanup:
    enabled: true               # 啟用定時清除
    cron: "0 0 3 * * *"         # 每天凌晨 3 點執行
    rule-retention-days: 180    # 規則保留天數
    response-retention-days: 180 # 回應保留天數
  backup:
    enabled: true               # 僅對本機 SQLite 啟用檔案備份
    cron: "0 0 3 * * *"         # 每天凌晨 3 點執行
    path: ./backups             # 備份目錄
    retention-days: 7           # 備份保留天數
    on-shutdown: true           # 應用關閉時備份
  builtin-account:
    self-registration: false    # 設為 true 允許自助註冊
  ldap:
    enabled: false
    url: ldap://ldap.example.com:389
    base-dn: dc=example,dc=com
    user-pattern: uid={0},ou=users
```

上面的備份設定只適用於本機 SQLite 檔案。外部資料庫 profile 會將應用程式備份關閉，請使用 DBA 或平台備份服務。

## HTTP 加速

原生 HTTP/1.1 路徑已預設啟用，可調整的預設上限為單筆請求或回應本文 10 MiB、512 筆進行中請求、128 MiB 估計緩衝容量。特殊請求與自訂 Servlet 擴充保留 Servlet 處理。設定 `ECHO_HTTP_TRANSPORT=servlet` 後重啟可切回原入口。限制、逾時、觀測與相容性見 [HTTP 加速](../native-http.md)，執行緒設定見 [HTTP I/O 調校](../http-io-tuning.md)。

## 使用者認證

### 認證模式

| Profile | 說明 | 使用場景 |
|---------|------|----------|
| `dev` | 開發模式，免登入，啟用自助註冊 | 本地開發 |
| `default` | 需登入，預設帳號 admin/admin | 測試環境 |
| LDAP | LDAP 認證（設定 `echo.ldap.enabled=true`） | 正式環境 |

### 權限控管

| 角色 | 權限 |
|------|------|
| ADMIN | 系統設定、批次操作（匯出/匯入/全部刪除）、帳號管理 |
| USER | 管理 Mock 規則、查看統計與紀錄、修改自己的密碼 |
| 訪客 | 唯讀瀏覽規則、回應、統計、修訂紀錄（無需登入） |

### 內建帳號管理

ADMIN 可透過管理介面管理內建帳號：
- 建立/刪除帳號
- 啟用/停用帳號
- 重設密碼（產生臨時密碼，首次登入強制修改）
- 使用者可自行修改密碼（`PUT /api/account/change-password`）
- 忘記密碼請求（公開端點，含速率限制）
- 自助註冊（需啟用 `echo.builtin-account.self-registration`）
