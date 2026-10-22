# Echo Spring Boot Starter 接入研究與實測（2026-10-22）

研究主方向為安裝在 Client AP 的 Spring Boot Starter：應用加入依賴並以設定啟用後，由自動配置將指定的對外 HTTP 呼叫導向獨立執行的 Echo Server。應用原本的業務呼叫介面保持原有用法。Starter 是 SDK 的 Spring Boot 接入封裝，不需要在 Client AP 啟動 Echo Server。

本輪已建立 [獨立 Starter 原型](../../sdk/README.md)，在 Boot 3.0～3.5、4.0／4.1 的選定修補版本，以 Java 17／21 實測同一顆普通 JAR、同一份 Echo 設定及相同 AP 原始碼。16 個組合皆通過下述 55 項情境檢查，包含真實 Echo 匹配。SDK 的接入設定可以沿用；AP 仍須升級相容的 Boot／Cloud 依賴，且 OpenFeign 5 移除 OkHttp 自動整合是已確認的框架差異。

下方管理 API 探針保留為選用管理工具的附錄；它們與本輪的 Starter 網路驗證分開計算。SDK 原型尚未公開發布；已接上 Echo 頁面的下載入口，隨 Server 建置內含。

已確認的 Client AP 環境為 **Spring Boot 3、Spring Cloud OpenFeign 的 `@FeignClient`**，未來可能升級 Spring Boot 4。第一版以這個接入方式為主，並把 Boot 4 的相容性列入設計與驗證目標。AP 的 Boot 3 小版本與 Spring Cloud release train 尚待確認，不能用 Echo Server 本身的版本代替。

**應用端接入提案**

Client AP 引用原型 `echo-spring-boot-starter-0.1.0-SNAPSHOT.jar`，保留原本的 Feign interface、下游 URL 與業務呼叫方式，加入下列設定：

```yaml
echo:
  client:
    enabled: true
    server-url: http://echo-host:8080/mock
    include-clients:
      - orderClient
```

假設 AP 的 `@FeignClient` 指定 `contextId = "orderClient"`，應用仍呼叫 `https://api.example.com/orders`；SDK 將此次請求送到 `http://echo-host:8080/mock/orders`，帶入原始 authority `X-Original-Host: api.example.com`，保留原有 method、query、body 及應用表頭。原始 Host 由新連線產生，舊有各種大小寫的 Original Host 表頭會合併為正確的單一值。關閉時保持原有 client 路徑。

`include-clients` 比對 `contextId`，未指定時比對 `name`。也可使用 `include-hosts` 選擇 host 或帶 port 的 authority；兩個清單都有設定時，兩者皆須符合。啟用時至少需要一種選擇條件；設定變更目前透過重啟 AP 生效。

原有 [MockRedirectClient](../../src/main/java/com/echo/client/MockRedirectClient.java) 是手動 Feign decorator 的參考。本輪在 `sdk/` 新增獨立普通 JAR 與 auto-configuration；它不引用 Echo Server 的 fat JAR。`server-url` 契約為完整 mock 基底 URL，包含部署的 context path 與 `/mock`；原始 path／query 接在其後，已指向該 Echo instance 的請求不重複導向。

原型範圍與接入契約：

- **實際 AP 的 client 類型與版本**：第一版聚焦已確認的 Spring Cloud OpenFeign，保留 AP 的 `@FeignClient` 介面與業務呼叫用法；`RestTemplate`、`RestClient`、`WebClient` 留待各自有需求時實作 adapter。自行 `new` 出來的 client 不會因加入依賴就自動接受 Spring builder customization。
- **自動配置與原設定共存**：依 classpath／property 建立設定，在 Feign named context 加入 Capability；本輪驗證原 transport、Options、攔截器、ErrorDecoder、Micrometer observation、CircuitBreaker／fallback 與不同 contextId 共存。SDK 不建立或關閉 AP 的連線池；TLS／mTLS 及公司特殊包裝仍需其環境驗證。
- **導向契約**：精確保留編碼後的 path／query、方法、本文與原始 host／port；防止重複加 `/mock`、重複導向，以及同名但大小寫不同的 Original Host 欄位造成混淆。維持應用 Authorization，管理認證另屬選用功能。
- **啟用與故障行為**：預設不改變應用流量，顯式啟用並符合條件後才導向。SDK 對 Echo 失聯或 mock 503 不新增 retry，也不自動改打真實下游；AP 原有 Retryer、CircuitBreaker 與 fallback 仍由 AP 控制。
- **相容與發行**：以 Client AP 的 Spring Boot／Spring Cloud／Java 版本決定支援矩陣，不以 Echo Server 的版本代替。Starter 的 auto-configuration、底層 routing library 與可選 adapter 可分層打包，無需依賴 Echo 的 Spring Boot fat JAR。

Starter 不自動代表支援 JMS 接入。應用若需要把 JMS 呼叫導向 Echo，還需分別研究既有 ConnectionFactory、queue／destination、provider、transaction、ACK 與 correlation 契約。管理 JMS 規則的 REST API 不能證明這條資料路徑已接通。

Starter 的實測使用獨立 Client AP：透過普通 JAR 自動載入、關閉時維持原路徑、只導向選定 client／host、請求與回應保留、不同 contextId 隔離，以及 Echo 不可用時的行為。完整矩陣與範圍記錄如下；附錄的管理 API 探針不列入這些結果。

官方依據：[Spring Boot 自訂 auto-configuration／Starter](https://docs.spring.io/spring-boot/reference/features/developing-auto-configuration.html)、[OpenFeign named client 與配置](https://docs.spring.io/spring-cloud-openfeign/reference/spring-cloud-openfeign.html)、[Spring Boot HTTP clients](https://docs.spring.io/spring-boot/reference/io/rest-client.html)。這些資料支持接入方式；具體支援版本仍需選定後使用相應版本文件與 consumer 測試驗證。

**Spring Boot 3 與 4 的相容性目標**

SDK 以 Java 17 bytecode 為基線，優先在 AP 實際使用的 JDK 與 Java 21 驗證。Boot 3 與 Boot 4 都以 Java 17 為最低基線，但 SDK 的正式支援清單應列出實測的 JDK／Boot／Cloud 組合。

以下是 Spring 官方的版本配對參考；具體實測的修補版本另列於下一節。表內的 OpenFeign 版本指 **Spring Cloud OpenFeign**，與底層 `io.github.openfeign:feign-core` 的版本編號不同。

| Spring Boot | Spring Cloud release train | Spring Cloud OpenFeign |
|---|---|---|
| 3.0.x／3.1.x | 2022.0.x；3.1 自 2022.0.3 起 | 4.0.x |
| 3.2.x／3.3.x | 2023.0.x；3.3 自 2023.0.2 起 | 4.1.x |
| 3.4.x | 2024.0.x | 4.2.x |
| 3.5.x | 2025.0.x | 4.3.x |
| 4.0.x／4.1.x | 2025.1.x；4.1 自 2025.1.2 起 | 5.0.x |

依據：[Spring Cloud 與 Boot 配對](https://spring.io/projects/spring-cloud/)、[各 release train 的 OpenFeign 版本](https://github.com/spring-cloud/spring-cloud-release/wiki/Supported-Versions)、[Boot 3.5 Java 基線](https://docs.spring.io/spring-boot/3.5/system-requirements.html)、[Boot 4.0 Java 基線](https://docs.spring.io/spring-boot/4.0/system-requirements.html)。實作時使用相應 release train 的修補版本；表格只描述版本配對，維護期限以官方當時的支援政策為準。

本輪共用一份普通 library JAR，核心 routing 與 Spring 接入保持分層。透過 Feign `Capability`／`Client` 包裝既有 transport，並以 `FeignClientSpecification` 在各 named context 註冊 Capability；不依賴 Boot 內部 auto-configuration 類別，也不新增 JSON codec。AP 繼續由自己的 Spring Cloud BOM 管理 Spring／Feign／transport 版本，SDK 不強制引入 Echo Server 使用的 `feign-core:13.17`。同一顆以 Feign 12.5 編譯的 SDK 已在矩陣列出的 Feign 12.5、13.5、13.6.1 執行；本輪不需要分成 Boot 3／4 兩顆 SDK。

兩代 OpenFeign 都提供 Capability 接入：[4.3 文件](https://docs.spring.io/spring-cloud-openfeign/reference/4.3/spring-cloud-openfeign.html#_feign_capability_support)、[5.0 文件](https://docs.spring.io/spring-cloud-openfeign/reference/spring-cloud-openfeign.html#_feign_capability_support)。本輪已分別驗證固定 `url` 與服務名稱：已選定的服務名稱呼叫使用原 transport 直接連 Echo，未選定的呼叫仍走原 LoadBalancer。已導向的請求略過服務實例選擇與 LB transformer／lifecycle；若公司在該層注入必要表頭或認證，需另外驗證，不能將它視同 Feign RequestInterceptor。

**Starter 相容性實測**

Linux；SDK 編譯基線為 Java 17、Boot 3.0.13／Cloud 2022.0.5／Feign 12.5。SDK JAR 為 9,467 bytes，class file major version 為 61，內含 6 個 SDK class 與自動配置註冊資訊，不包含第三方 class。

| Boot 實際執行版本 | Cloud BOM | Spring Cloud OpenFeign | Feign core | Java 17／21 |
|---|---|---|---|---|
| 3.0.13 | 2022.0.5 | 4.0.6 | 12.5 | 均通過 |
| 3.1.12 | 2022.0.5 | 4.0.6 | 12.5 | 均通過 |
| 3.2.12 | 2023.0.6 | 4.1.5 | 13.5 | 均通過 |
| 3.3.13 | 2023.0.6 | 4.1.5 | 13.5 | 均通過 |
| 3.4.13 | 2024.0.3 | 4.2.3 | 13.5 | 均通過 |
| 3.5.16 | 2025.0.3 | 4.3.3 | 13.6.1 | 均通過 |
| 4.0.8 | 2025.1.3 | 5.0.3 | 13.6.1 | 均通過 |
| 4.1.1 | 2025.1.3 | 5.0.3 | 13.6.1 | 均通過 |

16 組各通過 55 項情境檢查，是同一組測試跨版本重複執行，並非 880 種不同測試。每列核對實際 Boot 版本、相同 SDK JAR 及相同 `application.yml`，只替換 AP 的 Boot／Cloud 依賴與 Java runtime；分場景覆寫在各列一致。SDK SHA-256：`6fe7f604c9a30a22c2270adddd9e3bd6478bde0c0289a9ec107006e03a568a88`；設定 SHA-256：`24d9f9e01c3c3ca9caa7dea18d93cc27a3410ddbdd43fcf65d5b769723f424b7`。

額外 32 次缺省依賴探針皆通過：在上述 16 組分別移除 Feign（SDK 退出自動配置、AP 正常啟動），或移除 Spring Retry（選定的服務可導向 Echo，未選定的服務維持 LoadBalancer）。這批探針與其 AP 另外以最舊組合編譯一次，沿用同一份 bytecode 執行。

另於 16 組實際移除 `spring-cloud-loadbalancer` 及其 starter，固定 URL 的 Feign 呼叫仍能透過 SDK 抵達 Echo fixture。SDK 因此不要求固定 URL 的 AP 額外安裝 LoadBalancer；服務名稱型 client 則沿用其原有依賴。

另在 Boot 3.5.16／4.1.1 × Java 17／21 的 4 個 servlet AP 組合完成真實 HTTP 呼叫鏈：HTTP 進 AP controller → `@FeignClient` → Echo fixture／原下游 → AP JSON 回應，皆通過。該測試同時驗證 controller 建構時注入 Feign bean、Web Server 啟動及選定／未選定路徑；上方 16 組中的真實 Echo 規則匹配另以獨立 Server 驗證。

驗證涵蓋自動配置、client／host 範圍、同名不同 contextId、禁止繼承父設定的 Feign context、完整 path／query 與中文編碼、trailing slash、應用認證、JSON record 編解碼、二進位回應、多值回應表頭、原有 Options／攔截器／ErrorDecoder／transport、Micrometer observation、CircuitBreaker／fallback、兩種 LoadBalancer、未啟用與停用、Echo 不可用，以及真正 Echo 的固定 URL／服務名稱規則匹配。

55 項包含已確認的升級差異：**OpenFeign 5 的 `spring.cloud.openfeign.okhttp.enabled=true` 不再選用 OkHttp**，測試到的實際 transport 為 Feign JDK Client。對照 Maven source JAR 與 [官方移除 issue #1205](https://github.com/spring-cloud/spring-cloud-openfeign/issues/1205)，是框架移除自動整合；不能將測試通過解讀為此設定在兩代完全等效。兩代共同的 JDK Client、Apache HC5，以及 AP 明確提供的 OkHttp `feign.Client` 路徑均已驗證。SDK 不補回框架移除的自動整合，也不接管 AP 的池與 TLS 設定。

結論為：**在已測組合，Boot 3 升 Boot 4 可以沿用同一顆 SDK、`echo.client.*` 設定及 `@FeignClient` 業務介面。** AP 的 Boot／Cloud 依賴仍需配對升級；若 AP 原本依賴 OkHttp 自動設定、自訂 Jackson 模組或 LB transformer 等框架功能，需處理其框架升級影響。這不等於任意 AP 所有設定都能零調整。

未驗證公司實際 AP、TLS／mTLS、OAuth2、正式服務註冊中心、Native Image、RefreshScope、任意第三方 Capability／LB 包裝，以及不同 OS。Boot 4 classpath 同時存在 Jackson 2 與 3，實測的 JSON record 往返正常；AP 的自訂 codec／Module 相容性仍需另驗。Echo Server 的既有轉發二進位／回應表頭及原始 scheme 契約也不由 SDK 自動修復。

可重現程式在 [compatibility](../../sdk/compatibility/run_matrix.py) 與 [補充探針](../../sdk/compatibility/run_classpath_checks.py)，完整 JSON、每列解析版本、日誌與相同的原型 JAR 保留於工作區 `/workspace/artifacts/echo-starter-compatibility-20261022/verified/`，分別記錄於 `matrix-results.json`、`classpath-results.json`、`without-loadbalancer-results.json`、`web-results.json`。測試使用獨立 Echo 資料目錄，建立及刪除自己的規則，結束時停止 Server。頁面下載已整合至 Server 建置；正式版本發布尚未進行。

**交付方式：從 Echo 頁面下載**

已完成多 SDK／多版本 catalog、推薦版本、版本化下載、各版本安裝指南與網址選擇同步；新增來源與驗收方式見 [SDK 中心設計](sdk-catalog.md)。

使用者希望先交付可下載的 JAR，並由 Echo 頁面提供下載入口。第一階段不依賴 Maven Central：使用者從自己連線的 Echo 取得 SDK，再加入 Client AP 的依賴與設定。Starter 原型已有普通 JAR；頁面與下載 API 已實作：登入後進入左下角帳號選單 →「SDK 中心」（`/#/sdk`），提供 JAR、完整安裝 ZIP 與接入設定。

帳號選單內的「SDK 中心」入口提供：

- 帶明確版本的普通 Starter JAR，例如 `echo-spring-boot-starter-0.1.0.jar`；範例檔名不代表已發布版本。
- 安裝說明與 Gradle／Maven 本機依賴接入範例，以及 `application.yml` 啟用範例。
- Java、Spring Boot、Spring Cloud／HTTP client 支援版本及適用的 Echo Server 版本範圍；SDK 與 Server 版本分別標示，不假定必須相同。
- 可選的完整 ZIP，包含上述 JAR、必要的自有 adapter JAR、依賴說明與範例。若分成多個自有模組，下載內容必須齊全，不能只交付沒有實作類別的依賴宣告型 Starter。

建置時先產出 Client library 的普通 JAR，再將發行檔與版本資訊一起收進 Echo Server 的資源，頁面透過固定的下載端點取得帶版本檔案。這樣 Echo 部署後不需連外取檔，也不需在收到下載請求時編譯。伺服器可沿用 [ImportExportController](../../src/main/java/com/echo/controller/ImportExportController.java) 以 `Content-Disposition: attachment` 提供檔案的方式；下載權限需在現有 [SecurityConfig](../../src/main/java/com/echo/config/SecurityConfig.java) 明確配置，不能假設新 URL 自動公開。

Client AP 引用的是 SDK library JAR，不能拿目前的 `echo-server-*.jar` 當依賴。Spring 的 auto-configuration 註冊資訊會放在 SDK JAR 內；Spring Boot／Spring Cloud／Feign 等外部依賴仍由使用者專案依相容版本提供，不將整套伺服器或 Spring 套件打進 SDK。Gradle 的 `implementation(files("libs/…jar"))` 不會解析間接依賴，因此下載頁與隨附說明必須列出必要依賴；Maven 專案可提供隨附 POM 的本機 `install-file` 接入方式，不需要申請 Central 帳號。若使用者還需要 AP 完全離線建置，需另外處理其外部依賴快取，不能將「Echo 可離線提供下載」等同於「AP 已具備全部依賴」。

普通 JAR 的獨立 Client AP 接入與開關行為已實測。下載 API 的整合測試檢查登入權限、普通 JAR 的獨立性、檔名／版本／SHA-256、ZIP 中的相同 JAR 及安裝文件。打包後的 JAR 與瀏覽器下載驗收另保留於工作區的 SDK 下載證據。

**附錄：選用管理 API client 的既有研究**

以下管理 API 資料保留供後續選用工具參考，與本輪主方向的 Starter 流量接入分開解讀。

研究基準為本機與遠端 main 同一個 commit：`98f2b8211d7bec5947c43e8b5beb0561199d0c48`。程式碼盤點、官方文件與本輪實測分別記錄；Roadmap 的待評估項目不視為現成 API。診斷回應表頭仍由使用者研究，本研究不依賴該功能。

**使用情境與方案選擇**

| 方案 | 適用情境 | 判斷 |
|---|---|---|
| 遠端 Java REST client | 測試或工具以 Java 設定規則、更新回應、讀取紀錄；連既有或獨立測試 Echo | 首選。現有管理 API 已足以支持基本功能，不必把 Spring、JPA、Undertow 或 JMS provider 帶進使用者程式 |
| Testcontainers 整合 | CI／整合測試需要每次啟動新的 Echo 與資料目錄 | 與 client 搭配；負責啟停、映射埠、等待 readiness 與回收。需要相容的容器執行環境，不是所有遠端 client 使用者的必要依賴 |
| 同 JVM 嵌入式引擎 | 使用者需要在自己的 JVM 啟動 Echo | 暫緩。現有專案仍是單一 Spring Boot 應用；核心邊界不等於已存在可獨立啟動的引擎、儲存與生命週期契約 |

若另外製作不依賴 Spring 的管理 API client，其最低 Java 版本可另定。下列管理探針以 Java 17 驗證；JDK `java.net.http` 自 Java 11 提供，因此伺服器的 Java 17 基線不會自動成為該選用 client 的必要最低版本。若支援 Java 11，需另以相應 bytecode／API 基線驗證；Java 8 則需重新選擇 HTTP transport。此處不改變上方 Starter 的 Java 17 基線。

**現有契約可以直接使用的部分**

| 能力 | 現有入口 | SDK 應保留的行為 |
|---|---|---|
| 規則格式與能力查詢 | `GET /api/admin/rules/schema?apiVersion=echo.mock/v1` 或 `v2` | 以明確版本操作；該 schema 是 Echo 的欄位描述，並非完整 OpenAPI 或 JSON Schema，不能直接當作生成整個 SDK 的規格 |
| 建立規則 | `POST /api/admin/rules/apply` | 使用後端產生的 ID 與 canonical resource；不把固定名稱或 tag 當成冪等鍵 |
| 讀取／匯出規則 | `GET /api/admin/rules/{id}/manifest` | 保留 `apiVersion`、`metadata.resourceVersion`、結構化條件及 canonical 回應內容 |
| 更新規則 | `PUT /api/admin/rules/{id}/apply` | 完整期望狀態，非 PATCH；必帶正確版本，409 交由呼叫者決定如何重新讀取／修改 |
| 刪除單一規則 | `DELETE /api/admin/rules/{id}` | 明確的單項操作；目前沒有條件式刪除版本檢查，也不會連帶清掉所有回應與紀錄 |
| 分頁規則／紀錄查詢 | `/rules/page`、`/logs`、`/logs/{id}/detail` | 明確傳分頁大小，摘要與本文詳情分開，不預設抓取全部資料 |
| Scenario | `/scenarios`、`/scenarios/{name}/reset` | 尊重功能開關；關閉時 404 不等於不存在某個使用者資料 |
| 基本認證與版本資訊 | HTTP Basic、`/api/admin/status`、readiness | 目前可用 Basic；status 即使匿名也可 200，不能把單純 HTTP 200 當成認證成功 |

依據：[RuleApplyController](../../src/main/java/com/echo/controller/RuleApplyController.java)、[RuleApplyDocument](../../src/main/java/com/echo/dto/RuleApplyDocument.java)、[SecurityConfig](../../src/main/java/com/echo/config/SecurityConfig.java)、[RequestLogController](../../src/main/java/com/echo/controller/RequestLogController.java)。

優先使用 `apply` 契約，因為它要求更新版本、明確拒絕未知欄位並回傳 canonical resource。舊的 `PUT /rules/{id}` 仍相容未帶版本的舊腳本；SDK 不應以這條相容行為迴避衝突。也不要建立 SDK 自己的匹配引擎：v2 的 `all/any/not`、selector、operator 與 legacy 條件語意由 Echo 判定。

管理認證與應用流量要各自設定。`/mock/**` 保留應用自己的 Authorization；現有 [MockRedirectClient](../../src/main/java/com/echo/client/MockRedirectClient.java) 是 Feign 的流量導向接入程式。它可作為日後獨立 adapter 的來源，不應變成核心管理 SDK 的必要 Feign 依賴，也不應替應用流量帶上管理帳密。

**若另做管理 client 的建議範圍**

- 提供 client 實例、管理端 URI、Basic 認證、連線與整體請求期限；預設不自動跟隨重新導向。client 設定彼此獨立，不建立全域 default client。
- 提供 HTTP／JMS 規則的建立、讀取、帶版本更新與單項刪除；JMS 規則管理仍是 REST 操作，實際 JMS 發送維持由應用自己的 JMS client 負責。
- 提供常用規則 builder 與完整宣告式文件的進階入口，涵蓋文字／JSON 回應、條件、延遲、fault 與既有 Scenario 欄位。回傳資源需保留原始文件，避免只理解部分欄位的 SDK 在更新時清掉其他設定。
- 提供明確分頁的查詢與錯誤類型：401、403、404、409、400 validation code／path、HTTP 失敗、協定錯誤及 transport timeout；保留安全的錯誤細節，避免把整段含資料的請求或帳密寫進 exception。
- 提供 health／status 與受限的紀錄查詢。若提供「等待觀察到請求」，名稱及結果應表達觀察與期限，不宣稱為完整精確次數驗證。

建議建立獨立 client library artifact，依賴 JDK HTTP transport 與選定的 JSON codec；不引用 server 的 DTO／entity／fat JAR。JSON codec 的版本與公開型別暴露需在實作時驗證使用者依賴相容性；本輪 Java transport 探針只驗證 HTTP 可行性，不是正式序列化或 fluent API 實作。伺服器現有的 OpenAPI parser 用於「匯入使用者的 API spec」，不能視為 Echo 已發布完整管理 API OpenAPI 規格。

**必須先講清楚的限制**

1. **建立請求無法安全地默默重送。** 相同 `POST /rules/apply` 會產生新的 ID；逾時可能已寫入，只是回應未送達。SDK 預設不重試建立／變更操作，transport failure 要能表達結果未確認。409 也不自動拿新版本蓋回去。若日後需要可靠重試，需另外設計伺服器端 operation／idempotency 契約。
2. **共用 Echo 不等於測試沙箱。** 目前沒有以測試 run 為單位的資源隔離；tags 是分類資料，不是授權或匹配邊界。新的 rule ID 可以幫忙追蹤本次建立的規則，卻無法隔離共用路由或 Scenario。預設不因 `close()` 刪除所有規則、紀錄或重設所有 Scenario。
3. **刪除規則不等於完整清理。** 本輪刪完所有規則後仍留下 4 筆孤兒回應。現有刪除 Response 會連帶刪除引用它的規則，不能以「順便清理」對共用資源盲目呼叫。自動化測試優先使用一次性實例；共用實例的 SDK 先提供明確操作，之後再設計有版本／引用檢查的 scoped cleanup。
4. **Request Log 不足以支持通用的 `verify(exactly(n))`。** 既有紀錄有非同步寫入、保留上限、可停用及過載丟棄政策；`wait-for-write=true` 保護的是 spool handoff，也不等於主查詢立刻看得到。本文也可能截斷。`afterId` 只是已寫入紀錄的增量條件，不是請求完成的屏障或測試命名空間。
5. **「測試規則」不是唯讀驗證。** 現有 `POST /rules/{id}/test` 在 HTTP handler 裡真的呼叫 `/mock`；可能增加紀錄、改變 Scenario，或依規則執行轉發。若包裝此 API，需以發送測試請求的語意呈現，不能拿來實作唯讀的請求驗證。

依據：[RuleController](../../src/main/java/com/echo/controller/RuleController.java)、[ResponseController](../../src/main/java/com/echo/controller/ResponseController.java)、[HttpProtocolHandler](../../src/main/java/com/echo/protocol/http/HttpProtocolHandler.java)、[RequestLogService](../../src/main/java/com/echo/service/RequestLogService.java)、[application.yml](../../src/main/resources/application.yml)。

若要和成熟測試 SDK 一樣提供精確次數、沒有呼叫、依完整 body／header 驗證等語意，需先設計伺服器端 verification 能力、紀錄完整性與有效觀察區間；無法取得可靠證據時回報 unavailable／inconclusive。第一版可以交付管理 client，不應靠輪詢目前的 logs 就宣稱完成這項能力。

**Testcontainers 與嵌入式的後續安排**

Testcontainers 模組使用相同 client，另負責固定版本／digest 的映像、每次獨立資料、隨機映射埠及 readiness。位址以 `getHost()` 與 `getMappedPort()` 建立，不寫死 localhost／8080；如果啟用 JMS，還需處理 broker 的實際可達位址與映射。Windows、Linux、CI 與離線映像準備要各有驗收。JUnit 整合也不能先假設共享 container 與平行測試已被隔離。

嵌入式模式要另外決定核心啟動、儲存 adapter、HTTP／JMS 可選依賴、資源回收、全域狀態與埠生命週期；現有架構草案對 `echo-core` 的建議仍有用，但不構成遠端 REST client 的技術前置。

**先前管理 API 探針（與 Starter 驗證分開）**

使用當前來源重新 `bootJar`，Temurin `17.0.20.1+1`、Linux，沒有修改產品程式。JAR build identity 回報上述 commit、`dirty=false`；SHA-256：`702c8049117f858f0ed9b6893cc70fcf028fbaf8764665457760acbbedcae016`。

在兩個不同暫存資料目錄啟動 loopback Echo，使用 Python HTTP 探針與僅依賴 JDK 的 Java 17 consumer，共 28 項檢查通過（含實例身分、認證、fixture 與清理檢查；不是 28 項正式 SDK 單元測試）：

| 觀察 | 結果 | 對設計的影響 |
|---|---|---|
| v1／v2 schema、v2 建立、真實 Mock、manifest、版本更新 | 通過；舊版本更新回 409 `RESOURCE_VERSION_CONFLICT` | 可以以既有 REST 契約交付管理 SDK |
| 匿名／錯密碼、一般 USER 與批次刪除 | 匿名／錯密碼為 401；USER 可建立單項規則，批次刪除為 403 | SDK 不能假設每個管理者都有 ADMIN，清理也不能默用批次 API |
| 受理完全相同的建立文件兩次 | 產生兩個不同 ID | 不提供無條件自動重送或假稱為 upsert |
| 成功回應後立即查紀錄 | 第一次為 0，約 84ms 後為 1；只有單次觀察，不是延遲 SLA | 立即查不到不等於沒呼叫 |
| 停用紀錄後成功呼叫 | Mock 成功，logs API 仍為 200／0 筆 | 零筆不能當作 `verify(0)` 的成功證據 |
| 刪除全部本輪規則 | 規則為 0，孤兒回應為 4 | 完整測試清理需要另外定義 |
| Java 17 consumer | 真實建立 201、Mock 200、刪除 204；應用 Bearer 與管理 Basic 分離，無 server 依賴 | JDK HTTP transport 與管理／流量分離可行 |
| JMS／Scenario 關閉 | JMS 建立回 400；Scenario 入口為 404 | schema 具有欄位不等於該實例已啟用功能 |

探針與 JSON 結果保留在工作區 Git 外的 `/workspace/artifacts/echo-sdk-research-20261022/`。`probe.py` 接受 `ECHO_SDK_RESEARCH_JAVA`（java 執行檔完整路徑）與 `ECHO_SDK_RESEARCH_JAR`；先用 JDK 17 編譯同目錄的 `SdkTransportProbe.java`，再執行 Python 探針（需要 psutil，僅用於這份 Linux 研究工具的隨機埠發現）。JDK、Gradle、JAR、DB 與原始服務日誌沒有加入 Git。

啟動探針初版假設日誌會列出實際埠，之後又漏辨 IPv4-mapped IPv6 socket；修正探針後才完成上述檢查。這兩次啟動探測失敗不計為 Echo 功能故障。最終兩個測試實例均已停止。

本輪不是正式 SDK 發行驗收：尚無已發布的 client artifact、序列化契約與跨版本矩陣；未實測 Windows、Docker／Testcontainers、正向 JMS／EMS、LDAP、反向代理 context path 或網路故障後的完整 client 行為。這些是後續實作的驗收範圍，不影響本輪已確認的 REST 可行性。

**若另做管理工具的交付順序**

先固定 SDK 支援的 Java 與 server 契約矩陣，交付獨立 REST client 的規則生命週期、typed error、認證與分頁；用獨立 consumer 專案驗證依賴與 JSON roundtrip。接著建立 Testcontainers 整合，完成測試資料與生命週期隔離。精確請求驗證與共用實例的原子清理先補伺服器契約，再擴充 SDK。嵌入式模式最後獨立評估。

**官方對照資料**

- [WireMock Java client](https://wiremock.org/docs/java-usage/)：遠端 client 可與 server 生命週期分開，並可建立多個 client 實例。
- [WireMock verification](https://wiremock.org/docs/verifying/)：驗證依靠 request journal 與伺服器端匹配／計數；不是單純取得某筆回應。
- [MockServer clients](https://www.mock-server.com/mock_server/mockserver_clients.html)：設定、查詢、驗證與清理是不同操作，不必將管理 client 和嵌入式 server 綁在一起。
- [JDK 17 HttpClient](https://docs.oracle.com/en/java/javase/17/docs/api/java.net.http/java/net/http/HttpClient.html) 與 [BodyHandlers](https://docs.oracle.com/en/java/javase/17/docs/api/java.net.http/java/net/http/HttpResponse.BodyHandlers.html)：正式 transport 還需設計有界本文、狀態碼處理、期限與取消；`ofString()` 探針不代表已完成這些保護。
- [Testcontainers networking](https://java.testcontainers.org/features/networking/)、[readiness](https://java.testcontainers.org/features/startup_and_waits/)、[JUnit 5 限制](https://java.testcontainers.org/test_framework_integration/junit_5/)：隨機埠／host、啟動完成條件與平行執行需分別處理。
