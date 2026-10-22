# Echo OpenFeign Starter 相容性原型

此原型安裝在 Client AP，以設定將選定的 `@FeignClient` 請求導向獨立執行的 Echo。普通 JAR 內含自動配置與導向程式，不包含 Echo Server、Spring、Feign、HTTP transport 或 JSON codec。登入 Echo 後，可從左下角帳號選單 →「SDK 中心」取得此 Echo 內含的 JAR 與完整 ZIP。

Java bytecode 基線為 17。Spring Boot／Spring Cloud OpenFeign／transport 由 AP 的原有依賴及 BOM 提供；相容性以實際通過的 Boot／Cloud／JDK 組合為準，詳見 [研究與實測](../docs/design/java-sdk-research-2026-10-22.md)。

## Echo 內含下載

一般 Echo 建置命令會從 SDK 原始碼產生普通 JAR、含 README／POM／YAML 範例／sources／SHA256SUMS 的 ZIP，以及版本與雜湊資訊，作為資源打包進 Echo：

```sh
./gradlew bootJar
```

部署只需原有的 Echo Server JAR；啟動後從左下角帳號選單 →「SDK 中心」（`/#/sdk`）操作。SDK 不是 Echo 的 runtime dependency，下載時不編譯，也不連 Maven Central。一般登入使用者即可下載：

- `GET /api/admin/sdk`：完整 SDK 目錄、推薦與保留版本、安裝說明、檔名、大小及 SHA-256。
- `GET /api/admin/sdk/{sdkId}/releases/{version}/artifacts/{artifactId}`：下載選定 SDK、版本與格式。

多 SDK 登錄、舊版本保留、指南格式及新增打包來源見 [SDK 中心設計](../docs/design/sdk-catalog.md)。

SDK 與 Echo 版本獨立；這個預覽版仍以 `0.1.0-SNAPSHOT` 標示，頁面會顯示實際內含版本。

## 本機安裝

從 repository 根目錄建置：

```sh
./gradlew -p sdk jar sourcesJar
```

將 `sdk/build/libs/echo-spring-boot-starter-0.1.0-SNAPSHOT.jar` 複製至 AP 的 `libs/`，Gradle 引用：

```groovy
implementation files('libs/echo-spring-boot-starter-0.1.0-SNAPSHOT.jar')
```

AP 應已加入 `spring-cloud-starter-openfeign`。本機檔案依賴不會下載間接依賴；使用服務名稱的 client 還需要 AP 原有的 `spring-cloud-starter-loadbalancer`。SDK 不額外要求 Spring Retry、Actuator、Resilience4j 或特定 HTTP transport。

Maven 專案可安裝到本機 repository，無需註冊 Maven Central：

```sh
mvn install:install-file \
  -Dfile=libs/echo-spring-boot-starter-0.1.0-SNAPSHOT.jar \
  -DgroupId=com.echo -DartifactId=echo-spring-boot-starter \
  -Dversion=0.1.0-SNAPSHOT -Dpackaging=jar -DgeneratePom=true
```

接著加入 `com.echo:echo-spring-boot-starter:0.1.0-SNAPSHOT` 依賴。這些座標僅用於本機原型，不表示已公開發布。CI 與同事的環境也需要取得並安裝同一個 JAR。

## AP 設定

保留原本的 Feign interface、下游 URL 與業務呼叫方式，例如：

```java
@FeignClient(name = "orders", contextId = "orderClient", url = "${downstream.orders-url}")
public interface OrderClient {
    @GetMapping("/orders/{id}")
    Order get(@PathVariable("id") String id);
}
```

加入下列設定，並在需要 mock 的環境啟用：

```yaml
echo:
  client:
    enabled: ${ECHO_CLIENT_ENABLED:false}
    server-url: ${ECHO_SERVER_URL:http://localhost:8080/mock}
    include-clients:
      - orderClient
```

`include-clients` 比對 Feign 的 `contextId`；沒有指定 `contextId` 時使用 `name`。因此同名但不同 `contextId` 的 client 可以各自選擇。也可改用 `include-hosts`，填入原始 host，或帶 port 的 authority；兩個清單都有設定時，兩者皆須符合。

`server-url` 是完整的 HTTP mock 基底 URL，包含部署的 context path 與 `/mock`。SDK 在其後接上原始 path／query，透過 `X-Original-Host` 傳遞原始 authority。服務探索型 client 傳遞服務名稱；選定的請求使用原 transport 直接連 Echo，不要求真實下游先在服務註冊中心有可用實例。

已導向 Echo 的請求會略過服務實例選擇及 LoadBalancer 的 request transformer／lifecycle；未導向的請求沿用原流程。若 AP 依賴該層注入認證或其他必要表頭，需另外驗證其 mock 路徑，不能假定這類處理與 Feign RequestInterceptor 相同。

未啟用時不加入導向；啟用卻沒有任何選擇條件時啟動失敗。關閉或修改 SDK 設定目前需要重啟 AP，不提供熱切換。SDK 沒有在 Echo 不可用時自動呼叫真實下游，也不新增 retry；AP 原有的 Retryer、CircuitBreaker 與 fallback 仍由 AP 控制。

## Boot 3 升 Boot 4

設計目標為沿用同一顆 SDK JAR、同一份 `echo.client.*` 設定及相同的 Feign interface。AP 必須升級到與 Boot 相容的 Spring Cloud；SDK 不替 AP 升級框架依賴。

已確認的框架差異是 Spring Cloud OpenFeign 5 移除 OkHttp 自動整合（[官方 issue](https://github.com/spring-cloud/spring-cloud-openfeign/issues/1205)）。原本只靠 `spring.cloud.openfeign.okhttp.enabled=true` 選擇 OkHttp 的 AP，升級後不能假定仍使用 OkHttp。可以評估兩代共通的 Apache HC5，或由 AP 明確提供並管理自己的 `feign.Client`。SDK 沿用該 client，並不接管連線池。

相容性探針涵蓋預設 JSON codec、既有 Feign 攔截器／ErrorDecoder／Options、CircuitBreaker／fallback、Micrometer observation 及數種 transport。自訂 Jackson 模組、OAuth2、TLS／mTLS、正式服務註冊中心、Native Image、RefreshScope 與公司特殊的 client 包裝仍需要其環境驗證。

原始 HTTP scheme 的轉發契約、Echo Server 轉發時的二進位／回應表頭限制仍屬 Server 的行為；SDK 的 byte[]／表頭保留測試不代表已修復 Server 的所有轉發能力。此原型僅處理同步 HTTP OpenFeign，不含 JMS 接入。

## 重現實測

真實 Echo、Boot 3／4 AP 的啟用／停用對照、原生 UI 截圖與逐筆 Request ID 證據，另見 [截圖證明工具](compatibility/evidence/README.md)。

`compatibility/` 是獨立 AP build，透過檔案依賴使用同一顆 SDK。`application.yml` 與 AP 原始碼在不同矩陣列保持不變，只替換 Boot／Cloud 依賴版本與 Java runtime。每列會核對實際 Boot 版本、SDK SHA-256 及設定 SHA-256；HTTP fixture 使用 loopback，真實 Echo 則以獨立資料目錄啟動。

```sh
python3 sdk/compatibility/run_matrix.py \
  --gradle /path/to/gradle/bin/gradle \
  --java17-home /path/to/jdk17 \
  --java21 /path/to/jdk21/bin/java \
  --echo-server-jar /path/to/echo-server.jar \
  --output /path/to/results

python3 sdk/compatibility/run_classpath_checks.py \
  --java17 /path/to/jdk17/bin/java \
  --java21 /path/to/jdk21/bin/java \
  --matrix-output /path/to/results

python3 sdk/compatibility/run_classpath_checks.py \
  --java17 /path/to/jdk17/bin/java \
  --java21 /path/to/jdk21/bin/java \
  --matrix-output /path/to/results --only-web

python3 sdk/compatibility/run_classpath_checks.py \
  --java17 /path/to/jdk17/bin/java \
  --java21 /path/to/jdk21/bin/java \
  --matrix-output /path/to/results --only-without-loadbalancer
```

第一個指令會建置一次 SDK、依序建立 AP 的版本組合並執行網路驗證；測試使用自己建立的 Echo 規則，結束後刪除規則並停止測試 Server。第二個指令從 AP classpath 實際移除 Feign 或 Spring Retry，驗證選用依賴的邊界。第三個指令驗證 Boot 3.5／4.1 的 servlet AP，經由真實 HTTP request、Feign、Echo fixture／原下游至 JSON response 的路徑。第四個指令移除 LoadBalancer，驗證固定 URL 的 Feign 呼叫。結果以 JSON 與日誌保留在指定目錄，失敗時回傳非零狀態。
