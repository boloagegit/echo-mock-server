# SDK 截圖與即時呼叫證據

此工具啟動真正的 Echo Server、Boot 3.5.16／4.1.1 的獨立 servlet AP，以及本機原下游對照服務。使用 Chromium 擷取 AP 實測頁與 Echo 原生 UI，不改寫 UI 資料，也不攔截或偽造網路回應。

## 證明方式

`ProofApplication.java` 只編譯一次，四個 AP 共用該 bytecode、普通 SDK JAR 與 `proof-application.yml`。兩個 Feign context 都保持指向同一個原下游 URL，只有 `routed` 被 SDK 選中。Controller 呼叫 Feign bean，沒有直接呼叫 Echo `/mock` 的程式碼。

| AP | 選定的 routed | 未選定的 unselected |
|---|---|---|
| Boot 3.5.16，SDK 啟用 | 真正 Echo 回應 ECHO | 原下游回應 ORIGINAL |
| Boot 3.5.16，SDK 停用 | 原下游回應 ORIGINAL | 原下游回應 ORIGINAL |
| Boot 4.1.1，SDK 啟用 | 真正 Echo 回應 ECHO | 原下游回應 ORIGINAL |
| Boot 4.1.1，SDK 停用 | 原下游回應 ORIGINAL | 原下游回應 ORIGINAL |

停用對照組只增加 `--echo.client.enabled=false`，不更改 Feign interface、下游網址或其他接入程式。AP 的 runtime JSON 從實際載入的 SDK class 來源計算 JAR SHA-256，並從實際 resource 計算 YAML SHA-256；不是前端預填值。

每次請求有獨立 Request ID，放在 HTTP request body。Echo 的測試規則要求正確的原始 host、Boot 版本表頭與 AP Authorization，並將收到的本文原樣回傳到 response body。本機原下游也將收到的本文原樣回傳。瀏覽器檢查 Request ID 往返一致；工具再核對 Echo 原生請求紀錄與原下游收件紀錄，確認 2 筆選定請求只到 Echo，其他 6 筆只到原下游。

`proof.html` 是清楚標示的實測工具頁。`echo-*.png` 則是 Echo 既有模擬規則、請求紀錄與本文 drawer 的真實瀏覽器截圖。兩類畫面分開保留。

## 重現

先建置 SDK 與 Echo，或提供現有 JAR；再從 repository 根目錄準備兩個 AP 的 framework classpath（SDK 路徑須為絕對路徑）：

```sh
./gradlew -p sdk/compatibility \
  -PbootVersion=3.5.16 -PcloudVersion=2025.0.3 \
  -PsdkJar=/absolute/echo-spring-boot-starter-0.1.0-SNAPSHOT.jar prepareProbe

./gradlew -p sdk/compatibility \
  -PbootVersion=4.1.1 -PcloudVersion=2025.1.3 \
  -PsdkJar=/absolute/echo-spring-boot-starter-0.1.0-SNAPSHOT.jar prepareProbe

python3 sdk/compatibility/evidence/capture_evidence.py \
  --java-home /path/to/jdk17 \
  --echo-server-jar /path/to/echo-server.jar \
  --sdk-jar /absolute/echo-spring-boot-starter-0.1.0-SNAPSHOT.jar \
  --output /path/to/evidence
```

需要 Python Playwright 與 `/usr/bin/chromium`。所有服務只監聽 loopback，Echo 使用獨立暫存資料目錄與本機測試帳密；結束時移除自己的規則並停止自己的服務。`--hold` 可供人工檢視，在輸出目錄建立 `STOP` 檔即可停止；擷取失敗時可修正工具後建立 `RETRY`，失敗原因會另外保留。

## 打包後的下載驗收

先完成上方兩個 AP classpath 的準備，再從 repository 根目錄執行一般 Echo 建置及驗收：

```sh
./gradlew bootJar
python3 sdk/compatibility/evidence/verify_packaged_download.py \
  --java-home /path/to/jdk17 \
  --echo-server-jar /path/to/echo-server.jar \
  --output /path/to/download-evidence
```

此工具將單顆 Echo JAR 複製到獨立目錄，以真實登入畫面登入，從左下角帳號選單 →「SDK 中心」實際按下 JAR／ZIP 下載按鈕，核對瀏覽器存檔與內含 metadata 的檔名、大小及 SHA-256。另驗證匿名下載為 401、Maven 說明、設定範例更新及手機版面，並保留原生頁面截圖。

接著把「瀏覽器實際下載的 JAR」交給上方 AP 工具，再跑 Boot 3.5.16／4.1.1 的啟用、停用與未選定對照，逐筆核對 Echo 與原後端收件紀錄。結果位於 output 的 packaged-download-results.json，以及 downloaded-sdk-live/。所有程序皆會清理；下載檔與證據保留供核對。

只有調整下載入口或頁面時，可加上 --downloads-only，驗證帳號選單、收合側欄、手機版與實際下載，略過已通過且 SDK 內容未變的 AP 相容性探針。

## 證據檔

- `screenshots/`：四個 AP 啟用／停用畫面，以及 Echo 原生 UI。
- `proof-results.json`：實際 framework 版本、SDK／設定／AP source hash、瀏覽器回應與逐筆核對結果。
- `*-browser.json` 與 `ap-browser-network.har`：瀏覽器實際取得的 API 回應與網路紀錄。
- `echo-rule-*.json`、`echo-log-summary.json`、`echo-log-details.json`：真正 Echo 的規則 ID、匹配結果、request body 與 response body。
- `original-backend-requests.jsonl`：本機原下游實際收到的請求。
- `lifecycle.json`：服務停止時間及 owned process 清理結果。

這組 screenshot AP 與先前完整相容性矩陣使用不同的測試 YAML；每一組內的 Boot 3／4 都使用相同檔案。完整矩陣的 16 個版本組合仍由上層 `run_matrix.py` 驗證。SDK 本身沒有因擷取截圖而修改。
