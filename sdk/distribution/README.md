# Echo Spring Boot Starter @sdkVersion@

此 SDK 安裝在呼叫下游服務的 Client AP，透過設定將選定的同步 @FeignClient 請求導向獨立執行的 Echo Server。這是預覽版。

## 需求

- Java 17 或以上。
- AP 已使用 Spring Cloud OpenFeign，並透過相容的 Spring Boot／Spring Cloud BOM 管理框架版本。
- 已實測：Boot 3.0.13／3.1.12 + Cloud 2022.0.5、Boot 3.2.12／3.3.13 + Cloud 2023.0.6、Boot 3.4.13 + Cloud 2024.0.3、Boot 3.5.16 + Cloud 2025.0.3，以及 Boot 4.0.8／4.1.1 + Cloud 2025.1.3；每組均驗證 Java 17／21。
- SDK 依賴的 Spring、Feign、HTTP transport 與 JSON codec 由 AP 提供。這個下載包包含 SDK 自身的所有實作，不包含 AP 的第三方依賴；建置環境需已有其依賴快取或可存取公司套件庫。
- 使用服務名稱與服務探索時，AP 仍需自己的 Spring Cloud LoadBalancer 依賴。

## Gradle 專案

將本包的普通 JAR 放進專案的 libs/，在 build.gradle 加入：

    implementation files('libs/echo-spring-boot-starter-@sdkVersion@.jar')

AP 原有的 spring-cloud-starter-openfeign 與 @EnableFeignClients 保持不變。

## Maven 專案

將普通 JAR 與本包的 POM 安裝到本機 repository，不需要註冊 Maven Central：

    mvn install:install-file -Dfile=echo-spring-boot-starter-@sdkVersion@.jar -DpomFile=echo-spring-boot-starter.pom

在 AP 的 pom.xml 加入：

    <dependency>
        <groupId>com.echo</groupId>
        <artifactId>echo-spring-boot-starter</artifactId>
        <version>@sdkVersion@</version>
    </dependency>

CI 及同事的建置環境也要安裝同一顆 JAR，或將其放入公司的內部 Maven repository。

## 啟用

1. 將 application-echo.yml.example 的內容合併至 AP 的測試環境設定，或更名為 application-echo.yml 並在原有 profile 清單加上 echo。
2. ECHO_SERVER_URL 填入「AP 可連線的完整 Echo mock URL」，包含部署路徑與 /mock；不要誤填原下游 URL。
3. include-clients 填入要導向的 Feign contextId。若 @FeignClient 沒有 contextId，改填 name。保留原本 Feign interface、下游 URL 與業務呼叫方式。
4. 在需要模擬的環境設定 ECHO_CLIENT_ENABLED=true，並重新啟動 AP。預設停用，未選定的 client 仍呼叫原下游。
5. 在 Echo 為實際 path／method 建立規則，例如 /orders/123；不要在匹配路徑加 /mock。若需限制來源，可使用 SDK 傳遞的 X-Original-Host。
6. 呼叫原有業務入口，於 Echo 的「請求紀錄」核對請求、匹配與回應。停用時將 ECHO_CLIENT_ENABLED 改為 false 並重新啟動 AP。

include-hosts 也可選擇原始 host 或 host:port；若同時設定 include-clients 與 include-hosts，兩者都符合才導向。啟用時至少要有一種選擇條件。

SDK 與 Echo Server 分別標示版本，不必使用相同版號。此包由目前提供下載的 Echo 內含；首次接入其他 Echo 版本請用規則與請求紀錄驗證 /mock 與 X-Original-Host 契約。

## Boot 3 升 Boot 4

已測組合使用相同 SDK JAR、echo.client.* 設定與 Feign interface；AP 本身仍須配對升級 Boot 與 Cloud。

Spring Cloud OpenFeign 5 已移除 OkHttp 自動整合，原本的 spring.cloud.openfeign.okhttp.enabled=true 不保證繼續選到 OkHttp。可使用兩代共通的 Apache HC5，或由 AP 自行提供並管理 feign.Client。

原有攔截器、重試、CircuitBreaker 與 fallback 由 AP 管理；SDK 不新增在 Echo 失敗時改呼叫真實下游的 fallback。被導向的請求略過 LoadBalancer 實例選擇及其 request transformer／lifecycle；若認證在該層注入，需驗證模擬流程。公司自訂 codec、OAuth2、TLS／mTLS、Native Image 與特殊 client 包裝需個別驗證。

## 檔案驗證

SHA256SUMS 記錄普通 JAR 與 sources JAR 雜湊。在解壓目錄可用 sha256sum -c SHA256SUMS；Windows PowerShell 可用 Get-FileHash 比對。普通 JAR 才是 AP 依賴，sources JAR 用於閱讀原始碼。
