# 安裝與部署

[← 文件目錄](README.md)

從單一 JAR 到 Docker、外部資料庫、多台實例搭配負載平衡，以及如何把流量導進 Echo。

## 環境需求

- 執行 JAR 需要 Java 17+；開發與 Gradle toolchain 請安裝 JDK 17
- 使用內建 Gradle wrapper（Windows 為 `gradlew.bat`，Linux 為 `./gradlew`）

## 啟動服務

```bash
# 開發模式
./gradlew dev

# 預設模式 (需登入 admin/admin)
./gradlew bootRun

# 建置 JAR
./gradlew bootJar
java -jar build/libs/echo-server-*.jar
```

Linux/macOS 的 JAR 部署可使用 `start-echo.sh`；它會在 Java 啟動前確認
SQLite 暫存目錄可寫，並套用僅限當前使用者的檔案權限。將建置完成的 JAR
複製到腳本旁並命名為 `echo.jar`，或設定 `ECHO_JAR_PATH`，之後照常傳入 Spring 參數：

```bash
./start-echo.sh
```

可用 `SQLITE_TMPDIR` 覆寫預設暫存路徑。這個設定不會改變資料庫 profile，
也不影響既有啟動方式。

Windows 開發使用 PowerShell，與 Linux 部署共用同一套程式，不需要 WSL、Git Bash 或另外安裝 Gradle。`JAVA_HOME` 與 IDE 的 Gradle JVM 請設為 JDK 17。以下指令從專案根目錄執行，建議使用 `C:\src\echo` 這類較短的本機路徑：

```powershell
.\gradlew.bat dev
# 按 Ctrl+C 停止；以下指令是各自獨立的工作。
.\gradlew.bat bootRun
.\gradlew.bat check -PtestForks=2
# clean 前先停止 Echo；Windows 會鎖住仍在使用的檔案。
.\gradlew.bat clean bootJar
if ($LASTEXITCODE -ne 0) { throw 'Build failed' }
$echoJars = @(Get-ChildItem build/libs/echo-server-*.jar -File)
if ($echoJars.Count -ne 1) { throw 'Expected exactly one packaged Echo JAR' }
java -jar ($echoJars[0].FullName)
```

Windows 也預設啟用 HTTP 加速。PowerShell 用 `$env:ECHO_HTTP_TRANSPORT = 'servlet'` 設定切回舊入口，再重啟 Echo；用 `Remove-Item Env:ECHO_HTTP_TRANSPORT -ErrorAction SilentlyContinue` 移除覆寫後重啟，就恢復預設。完整限制與相容性見 [HTTP 加速](../native-http.md)。

Windows 建置的 Java bytecode 可在相容 Java 版本的 Linux 上執行。部署使用打包的 JAR、Linux 路徑與部署設定，本機 SQLite／Artemis 資料及 `dev` profile 不隨程式搬上去。Linux CI 與容器檢查仍負責部署驗證，效能也在 Linux 量測。首次 Gradle 建置仍需能取得依賴套件，或使用公司已備妥的套件快取。

## 資料庫 Profile

目前支援的 profile 如下：

| Profile | 資料庫 | 目前 Docker 實測基準 |
|---------|--------|----------------------|
| 未指定資料庫 profile（預設） | SQLite WAL 檔案 `./data/mockdb.sqlite` | Xerial JDBC 驅動的 SQLite 3.x |
| `sqlite` | SQLite WAL 檔案 `./mockdb.sqlite`（沿用既有部署的位置） | Xerial JDBC 驅動的 SQLite 3.x |
| `postgresql` | PostgreSQL | PostgreSQL 17 |
| `mysql` | MySQL | MySQL 8.4 |
| `mariadb` | MariaDB | MariaDB 11.8 |
| `sqlserver` | SQL Server | SQL Server 2022 |
| `oracle` | Oracle | Oracle Free 23 |

表列版本是可重現的 Docker smoke test 基準，不代表每個更舊或更新的廠商版本都已認證。Echo 使用的 Hibernate 版本支援 Oracle Database 19c 以上；更舊的 Oracle 版本不在支援範圍內。

每個程序只能啟用一個資料庫 profile。`dev` 與 `test` 是非資料庫 profile，可與一個資料庫 profile 一起使用。若未啟用任何資料庫 profile，系統會使用 SQLite，資料檔為 `./data/mockdb.sqlite`（即 Docker volume 掛載點）。啟動時會拒絕多個資料庫 profile，也會拒絕 JDBC URL 廠商與所選 profile 不一致的設定。

目前各 profile 的文件與實測路徑都以「新建空資料庫」啟動為準。Profile 使用 Hibernate `ddl-auto=update` 方便建立結構；這不是通用遷移系統，也不會自動在 SQLite 與外部資料庫之間搬移既有資料。H2-to-SQLite 工具（見[從 H2 遷移](#從-h2-遷移)）是獨立的 SQLite 專用離線工具，不是通用的多資料庫轉換器；使用前應檢查資料表與版本相容性、另行備份並驗證結果。既有正式資料庫請由 DBA／平台以具版本控管的遷移方式處理，再設定 `SPRING_JPA_HIBERNATE_DDL_AUTO=validate`。

## 從 H2 遷移

H2 已不再支援，也不會打包在 `echo-server.jar` 中。使用 `h2` profile 或 `jdbc:h2:` URL 時 Echo 會拒絕啟動；工作目錄中仍有舊 H2 資料檔 `mockdb.mv.db`、但 SQLite 資料庫尚未建立時也會拒絕啟動，避免升級後以空資料庫啟動、看起來像規則全部消失。

請在 Echo 停止時轉換既有資料：

```bash
python3 scripts/migrate-h2-to-sqlite.py --h2-path ./mockdb
./gradlew bootRun   # SQLite 已是預設；轉換後的檔案為 ./data/mockdb.sqlite
```

腳本不會修改 H2 檔案，會比對每張表的筆數與 digest，並以 staging 資料庫實際啟動 Echo 做 smoke test，全部通過後才發布 `./data/mockdb.sqlite`。新環境穩定運行前請保留原 H2 檔案。Docker 使用者注意：舊版預設的 H2 資料位於 `/app/mockdb`，不在 `/app/data` volume 內，重建容器前請先把它複製出來。確定要在殘留 `mockdb.mv.db` 的情況下以空資料庫啟動時，可設定 `ECHO_DATABASE_IGNORELEGACYH2=true`。

#### 標準環境變數

```bash
SPRING_PROFILES_ACTIVE=postgresql \
SPRING_DATASOURCE_URL='jdbc:postgresql://db-host:5432/appdb' \
SPRING_DATASOURCE_USERNAME='app_user' \
SPRING_DATASOURCE_PASSWORD='<password>' \
SPRING_JPA_HIBERNATE_DDL_AUTO=validate \
./gradlew bootRun
```

| 變數 | 用途 |
|------|------|
| `SPRING_PROFILES_ACTIVE` | 選擇一個資料庫 profile（`sqlite`、`postgresql`、`mysql`、`mariadb`、`sqlserver` 或 `oracle`），可另外加 `dev`／`test` |
| `SPRING_DATASOURCE_URL` | 覆寫 profile 的 JDBC URL |
| `SPRING_DATASOURCE_USERNAME` | 外部資料庫使用者名稱 |
| `SPRING_DATASOURCE_PASSWORD` | 外部資料庫密碼；請透過部署平台的 secret 機制注入 |
| `SPRING_JPA_HIBERNATE_DDL_AUTO` | 目前 profile 預設為 `update`；完成受控 schema 遷移後使用 `validate` |
| `ECHO_REQUEST_LOG_ENABLED` | 設為 `false` 會停止建立請求記錄、Log Agent 與本機 spool；預設為 `true` |
| `ECHO_REQUEST_LOG_SPOOL_PATH` | 每實例本機 SQLite request-log spool 路徑；預設 `./data/request-log-spool.sqlite` |
| `ECHO_CACHE_TEMPLATE_MAX_WEIGHT_MB` | 編譯模板快取容量（MiB），預設 `16`；超大模板仍可渲染但不保留於快取 |
| `ECHO_DB_POOL_MAX_SIZE`／`ECHO_DB_POOL_MIN_IDLE` | 外部 profile 的 Hikari connection pool 大小 |

其餘 Hikari timeout 與 lifetime 設定使用 profile 檔案中的 `ECHO_DB_*` 變數。請勿在 `SPRING_PROFILES_ACTIVE` 放入多個資料庫 profile。

#### 備份與 request-log spool 邊界

應用程式備份功能只處理本機 SQLite 檔案；外部資料庫 profile 會關閉此功能。PostgreSQL、MySQL／MariaDB、SQL Server 與 Oracle 的備份、PITR、保留政策及還原演練由 DBA 或平台備份服務負責。

即使主資料庫是外部資料庫，request-log 的耐久化仍會先寫入每個 Echo 實例自己的本機 SQLite spool。請將 spool 放在可寫入且具持久性的 volume，每個實例使用不同檔案；它不是共用資料庫，也不是跨實例 queue，不能取代外部資料庫的備份政策。

若要單獨量測核心規則比對與回應吞吐量，可用
`ECHO_REQUEST_LOG_ENABLED=false` 啟動 Echo。此模式會略過請求記錄物件、背景處理、資料庫寫入與本機 spool，但不會停用 mock 比對與回應。這同時會改變可觀測性與 JMS 耐久記錄的驗收條件，因此正式容量評估仍應另外測試啟用 request log 的實際部署設定。已存在的資料庫記錄仍可查詢，只有新記錄停止寫入。

搭配內附的 RPS 腳本時，請加上 `--request-log-disabled`，避免等待刻意不啟動的 Log Agent queue：

```bash
ECHO_REQUEST_LOG_ENABLED=false java -jar build/libs/echo-server-*.jar
python3 scripts/stress-test-rps.py http://localhost:8080 10 20 \
  --request-log-disabled
```

`ddl-auto=update` 只適合新空資料庫或開發初始化，不是有版本的遷移工具，也不能可靠表達所有資料庫的欄位更名、回填、型別轉換、index／constraint 變更，以及 identity／LOB／boolean／date／JSON 等廠商差異。它也需要 DDL 權限；既有資料與新增非 NULL 欄位衝突時可能啟動失敗。

## Docker RDBMS smoke test

專用的 RDBMS Compose 檔會逐一測試單一資料庫 profile，包含 readiness、API matching、大型回應持久化、重啟後持久化與日誌收集。先建置應用程式 artifact，讓 Dockerfile 可以打包，再一次選一個 profile 執行：

```bash
./gradlew bootJar
python3 scripts/test-rdbms-matrix.py --databases postgresql
```

將 `postgresql` 替換為 `sqlite`、`mysql`、`mariadb`、`sqlserver` 或 `oracle`。腳本可接受多個名稱並依序執行，但每一次 Compose 執行仍只啟用一個資料庫 profile。結果會寫在 `artifacts/rdbms-matrix/<run>/`，包括 `matrix-result.json`。

手動 smoke test：

```bash
docker compose -f docker-compose.rdbms.yml --profile postgresql up --build --wait --detach echo-postgresql
curl -fsS http://localhost:18080/api/admin/status
docker compose -f docker-compose.rdbms.yml --profile postgresql logs -f echo-postgresql
docker compose -f docker-compose.rdbms.yml --profile postgresql down --volumes --remove-orphans
```

Compose 服務只供拋棄式測試資料使用；`down --volumes` 會刪除測試 volume，不能當作外部資料庫備份。SQL Server 使用 amd64 映像，Oracle／SQL Server 可能需要額外 Docker CPU 與記憶體。

## Docker 部署

```bash
# 建置並啟動
./gradlew bootJar
docker compose up -d

# 或直接拉取 (如有推送到 registry)
docker compose pull && docker compose up -d

# 查看日誌
docker compose logs -f

# 停止
docker compose down
```

環境變數配置：
| 變數 | 預設值 | 說明 |
|------|--------|------|
| `ECHO_ADMIN_USERNAME` | admin | 管理員帳號 |
| `ECHO_ADMIN_PASSWORD` | admin | 管理員密碼 |
| `ECHO_ENV_LABEL` | DOCKER | 環境標籤 |
| `ECHO_UI_ACCENT` | teal | 介面主色：`teal`（青綠）或 `blue`（藍），用來區分不同部署環境（例如 SIT 青綠、UAT 藍） |
| `ECHO_UI_EASTER_EGG` | true | 介面彩蛋（輸入 ↑↑↓↓←→←→AB 或快速連點 Echo 圖示 5 下開啟小遊戲）；正式環境可設 `false` 關閉 |
| `TZ` | Asia/Taipei | 時區 |

JVM 參數在 Dockerfile 中設定（預設 `-Xms256m -Xmx512m`，OOM 時保留 heap dump 並退出），可透過 docker-compose.yml 的 `environment` 加入 `JAVA_OPTS` 覆蓋。
若產線直接用 `java -jar` 啟動，請同樣加上 `-XX:+HeapDumpOnOutOfMemoryError -XX:+ExitOnOutOfMemoryError`，記憶體不足時才會留下 heap dump 供查明原因。

## 健康檢查與恢復

用 `GET /actuator/health/liveness` 檢查程序存活，用 `GET /actuator/health/readiness` 檢查 Echo 是否具備可用的資料庫、磁碟空間與 JMS 來源 listener。兩者可匿名讀取，只回傳狀態。重啟應依據 liveness，流量導入依據 readiness。外部 broker 中斷時 readiness 回 503，liveness 保持 200，避免重新連線期間反覆重啟；broker 恢復後，Echo 不必重啟就能回到 readiness 200。

Docker 健康檢查使用 readiness。`restart: unless-stopped` 會重啟已退出的程序，包括已設定的 OOM／資料庫損壞退出；**Docker Compose 不會僅因容器被標記 unhealthy 就重啟仍在執行的程序**。若部署要求程序無回應時自動重啟，需要由監管程序／編排平台以 liveness、逾時與連續失敗門檻判斷。不要只因 readiness 失敗就重啟；應用程式也不會因探針失敗而強制中斷交易。

容器直接以 Java 作為 PID 1，停止訊號會送到 JVM；Compose 預留 60 秒關閉。若交易或關閉時備份需要更久，應調整寬限時間。強制終止仍可能中斷備份，而開始複製前的空間檢查無法替其他程序保留磁碟額度。仍需保留排程產生且驗證有效的快照並實測復原，不能只依賴關閉時備份。資料掛載點應容納主資料庫、spool、broker 資料、日誌、新快照及保留快照，見[備份限制與健康設定](configuration.md#健康檢查)。

```bash
curl --fail http://localhost:8080/actuator/health/liveness
curl --fail http://localhost:8080/actuator/health/readiness
```

Windows／PowerShell 可使用 `Invoke-RestMethod http://localhost:8080/actuator/health/readiness`。開發使用相同端點與檔案系統 API，不需要 Linux shell 或 Docker。

## 選用功能

以下使用者功能預設關閉，可依部署需求開啟：

| 環境變數 | 預設值 | 說明 |
|----------|--------|------|
| `ECHO_BULK_IMPORT_EXPORT_ENABLED` | `false` | 顯示並啟用批次匯入／匯出操作 |
| `ECHO_ISSUE_REPORTING_ENABLED` | `false` | 顯示問題回報頁面並啟用其 API；既有回報資料保留 |
| `ECHO_SCENARIOS_ENABLED` | `false` | 啟用 Scenario 狀態機規則與管理功能 |

也可在部署設定檔設定 `echo.features.issue-reporting-enabled: true` 啟用問題回報，
修改後須重新啟動 Echo。關閉時隱藏導覽入口，直接進入 `#/issues` 會返回規則列表，
已登入使用者呼叫問題回報 API 會得到 404；不刪除既有資料，開啟後沿用原本權限。

## 存取服務

| 服務 | URL | 說明 |
|------|-----|------|
| 管理介面 | http://localhost:8080/ | Mock 規則管理 |
| 登入頁面 | http://localhost:8080/login.html | 使用者登入 |
| Mock 端點 | http://localhost:8080/mock/** | 攔截 HTTP 請求 |
| 資料庫 | — | 預設使用 SQLite（`./data/mockdb.sqlite`）；外部資料庫請選擇唯一的資料庫 profile |

## 多實例部署

Database 模式支援多實例部署，透過 DB 同步 cache 失效事件。

### 快取同步機制

每個實例把規則、回應內容與 HTTP 轉送連線設定放在本地 Caffeine 快取，變更透過 `cache_events` 表通知：

```
實例 A 修改規則 → 在同一個交易內寫入一筆 cache_events
                          ↓
實例 B 每 N 秒輪詢 → 讀取編號更大的事件 → 清除受影響的本地快取
```

- **同步範圍**: 規則變更（依協定）、回應內容修改或刪除、HTTP 轉送連線設定變更，以及每日自動清理刪掉的規則與回應
- **輪詢間隔**: 預設 5 秒，可調整 `echo.cache.sync-interval-ms`；變更通常在這段時間內傳到其他實例
- **依事件編號而不是時間**: 各台時鐘或時區不同也不影響。跳過的編號（較晚提交的交易）最多再等 2 分鐘
- **保底機制**: 實例超過 5 分鐘無法輪詢，或事件編號重新開始時，會清空上述所有快取一次。即使漏掉通知，規則與 HTTP 轉送連線設定最晚每 `echo.cache.rules.max-age-minutes`（預設 10）分鐘、回應內容每 `echo.cache.body.max-age-minutes`（預設 60）分鐘也會重新讀取
- **事件清理**: 每小時清理 10 分鐘前的舊事件，但保留最新一筆，事件編號不會重新開始
- **效能影響**: 極小，每次輪詢只是一個主鍵範圍查詢

登入狀態存在各實例的記憶體，負載平衡器請設定同一位使用者固定導向同一台（sticky session）。JMS 多實例請見[從共用 broker 接收 JMS](jms.md#從共用-broker-接收-jms多實例)。

### 設定範例

啟動各實例前，請將 `SPRING_PROFILES_ACTIVE` 設為唯一一個資料庫 profile；只有 JDBC URL 並不會選擇資料庫廠商。以下範例使用 `postgresql`，帳密請透過環境變數或部署平台的 secret 機制提供。

```yaml
echo:
  storage:
    mode: database
  cache:
    sync-interval-ms: 10000  # 改為 10 秒 (預設 5000)

spring:
  datasource:
    url: jdbc:postgresql://db-host:5432/echo  # 共用資料庫
```

主資料庫可供規則、回應及 cache event 共用；request-log spool 仍是每個實例獨立的本機 SQLite 檔案，不可放在共用路徑。

### 啟用條件

- `echo.storage.mode=database` → 啟用快取同步
- `echo.storage.mode=file` → 停用（單實例不需要）

## 使用方式

### 直接呼叫

```bash
# 有匹配規則 → 回傳 Mock 回應
curl http://localhost:8080/mock/api/users \
  -H "X-Original-Host: api.example.com"

# 無匹配規則 → Proxy 轉發到 google.com
curl http://localhost:8080/mock/search?q=test \
  -H "X-Original-Host: google.com"
```

### 透過 Nginx 代理

```nginx
location /api/ {
    proxy_set_header X-Original-Host api.example.com;
    proxy_pass http://echo-server:8080/mock/;
}
```

### 下游 HTTPS 憑證驗證

HTTP 轉發預設維持**內網相容模式**：不驗證憑證鏈與主機名稱，讓內部網路中的
私有或自簽憑證仍可正常使用。已儲存的 HTTP 連線可在「系統設定」中個別切換為
**嚴格憑證驗證**；舊有的 `X-Original-Host` 轉發則維持內網相容模式。

此預設只適合受信任的內部網路。若下游服務會經過不受信任的網路，請建立已儲存
的 HTTP 連線並開啟嚴格憑證驗證。
