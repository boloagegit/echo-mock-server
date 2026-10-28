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
      bounded-waits-enabled: false # 保留 provider 重試／failover；實際 ESB 故障驗證後才考慮開啟
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
    max-total-bytes: 10737418240 # 已完成的 Echo 備份合計最多 10 GiB
    max-files: 100              # 最多保留的已完成備份數
    min-free-bytes: 536870912   # 開始複製前預留 512 MiB
    housekeeping-interval-ms: 3600000 # 每小時獨立清理
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

備份清理不再依賴新備份成功：啟動 60 秒後開始，每小時獨立執行。保留天數、總容量與檔案數限制可能縮短歷史，但會保留最新一份通過 SQLite 完整性與外鍵驗證的備份，即使它已超過保留天數；更新但損壞的檔案不會取代這個復原點。若沒有任何檔案可驗證，會保留現有檔案。只有符合 Echo 日期命名的普通備份檔可被清理，不刪除其他檔案、符號連結或目錄。

複製前會用 SQLite 的頁數與頁面大小估計快照容量，檢查備份總額度及磁碟可用空間；若無法同時保留最後有效備份與剩餘空間，便明確拒絕新備份。環境變數預設為 `ECHO_BACKUP_MAX_TOTAL_BYTES=10737418240`、`ECHO_BACKUP_MAX_FILES=100`、`ECHO_BACKUP_MIN_FREE_BYTES=536870912`、`ECHO_BACKUP_HOUSEKEEPING_INTERVAL_MS=3600000`。備份與清理不重疊執行；排程備份會等待維護完成，等待可中斷且不先占 JDBC 連線。手動重複請求立即回報忙碌，清理則跳過正在製作的備份。這是開始複製前的檢查，之後資料庫成長、其他程序寫入及程序被強制結束留下的暫存檔仍可能消耗空間；已完成備份的容量限制不包含其他檔案與殘留暫存檔。已驗證並發布的備份，不會因後續清理失敗而回報備份失敗；獨立排程會重試清理。

## 健康檢查

匿名 `GET /actuator/health/liveness` 與 `GET /actuator/health/readiness` 只回傳狀態。liveness 檢查應用程式是否存活，不執行 SQL 或 broker 連線測試；readiness 另外檢查資料庫、磁碟空間與既有 JMS listener 是否收訊，停用 JMS 時視為正常。來源重新連線期間 readiness 回 HTTP 503，liveness 保持 HTTP 200；恢復後 readiness 回 200。探針不另開 TIBCO／Artemis 連線，也不改動交易、ACK 或重試設定。其他 Actuator 路徑仍須登入。

`ECHO_HEALTH_STORAGE_PATH` 在 JAR 部署預設 `.`，Docker 映像預設 `/app/data`；`ECHO_HEALTH_MIN_FREE_BYTES` 預設 536870912（512 MiB）。若可寫入資料位於獨立掛載點，請將檢查路徑指向該檔案系統。readiness 的資料庫檢查沿用 datasource 逾時設定；liveness 不依賴資料庫。listener 狀態無法證明每個下游目標都正常，也無法辨識所有處理中請求卡住的情況。監管程序應以 **liveness** 判斷重啟，以 **readiness** 決定是否導入流量，見[部署說明](install.md#健康檢查與恢復)。

## HTTP 加速

原生 HTTP/1.1 路徑已預設啟用，可調整的預設上限為單筆請求或回應本文 10 MiB、512 筆進行中請求、128 MiB 估計緩衝容量。特殊請求與自訂 Servlet 擴充保留 Servlet 處理。設定 `ECHO_HTTP_TRANSPORT=servlet` 後重啟可切回原入口。限制、逾時、觀測與相容性見 [HTTP 加速](../native-http.md)，I/O 執行緒設定見 [HTTP I/O 調校](../http-io-tuning.md)。

## 管理查詢容量與 JMS 目標設定

請求紀錄清單（`GET` 與 `HEAD /api/admin/logs`）登入後才進入容量保護：同時處理一筆，最多八筆排隊，最多等五秒。佇列已滿或等待逾時會回 HTTP 429 與 `Retry-After: 1`。五秒限制只計算排隊時間，不中斷已執行的查詢或回應傳輸。分頁規格與未傳 size 時的預設值不變，其他管理路徑、HTTP 模擬與 JMS 不進入此佇列。內建介面會合併重複清單刷新，收到容量回應後等待重試。資源快照的 `database.logQueryAdmission` 可查看計數。設定 `ECHO_ADMIN_LOG_QUERY_ADMISSION_ENABLED=false` 後重啟可停用。

單程序 SQLite 部署預設快取 JMS 目標設定；載入後選擇目標不需資料庫連線，首次查詢與設定成功提交後仍需重新讀取。交易回滾不會發布新設定，進行中的交換沿用已選定的目標版本。共用的外部資料庫維持每次交換讀取。請透過 Echo 介面或 API 修改 SQLite 目標設定；不會監看 Echo 外部直接修改資料庫的行為。設定 `ECHO_JMS_TARGET_CATALOG_ENABLED=false` 後重啟可恢復每次讀取資料庫。此設定不改動 JMS provider 連線、ACK 或交易政策。

多個請求等待同一次首次／重新載入時，共用這次讀取的成功或原始錯誤，不再逐筆累加資料庫逾時。失敗結果不會留在快取，資料庫恢復後的新請求可重新嘗試；短暫故障時，部分請求可能較早收到資料庫錯誤，不會自動重送已失敗的業務訊息。設定提交後，過期的載入不能蓋掉新版本。資料庫借連線、SQL 與 provider 本身的等待設定仍適用，這不是整個 JMS 的總期限。

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
