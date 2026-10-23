# SDK 中心與發行檔登錄

SDK 中心由 Echo JAR 內含的 catalog 驅動，支援多個 SDK、各自的推薦版本與保留版本、任意下載格式，以及每個版本自己的接入說明。入口位於左下角帳號選單；選擇 SDK 後才進入版本與下載頁。

目前正式登錄的 SDK 是 spring-openfeign。新增種類或版本不需修改 Controller 或頁面。SDK 與 Echo 的版號各自管理。

## 建置與新增來源

一般建置仍是：

    ./gradlew bootJar

原始碼模組可公開名為 echoSdkRelease 的 Gradle outgoing configuration，發佈一個包含 release.json 與下載檔的目錄；由根專案的 echoSdkReleases configuration 引用。現有範例見 sdk/build.gradle：

    dependencies {
        echoSdkReleases project(path: ':sdk', configuration: 'echoSdkRelease')
    }

已建好的其他語言 SDK 或保留版本可放入 sdk/releases/，或指定額外目錄：

    ./gradlew bootJar -PsdkCatalogDir=/absolute/path/to/sdk-releases

目錄內可有任意層分類，但每個 release.json 與它的所有 artifact 必須在同一個目錄。額外目錄與原始碼模組會合併，並非覆蓋模組產物。沒有下載時編譯、外部下載 URL、執行時掃描工作目錄或資料庫登錄。

bundleSdk 會驗證所有描述及檔案，再產生 catalog.json、實際大小、SHA-256 與版本化下載 URL，並將產物放入 Echo 的 BOOT-INF/classes/echo-sdk/<sdkId>/<version>/。SDK 不會加入 Echo 的 BOOT-INF/lib 或被當成 Server 的 runtime dependency。

## release.json

以下只是格式範例，example-client 並非已實作 SDK：

```json
{
  "schemaVersion": 1,
  "sdk": {
    "id": "example-client",
    "name": "Example SDK",
    "language": "Example",
    "description": {"en": "Example integration", "zh-TW": "接入範例"}
  },
  "version": "1.0.0",
  "channel": "stable",
  "recommended": true,
  "releaseDate": "2026-10-22",
  "compatibility": ["Runtime requirements"],
  "artifacts": [{
    "id": "package",
    "label": {"en": "Package", "zh-TW": "安裝包"},
    "description": {"en": "Includes installation instructions", "zh-TW": "含安裝說明"},
    "fileName": "example-client-1.0.0.tar.gz",
    "mediaType": "application/gzip"
  }],
  "guide": {
    "sections": [{
      "id": "configure",
      "title": {"en": "Configuration", "zh-TW": "設定"},
      "paragraphs": [{"en": "Set the endpoint.", "zh-TW": "設定連線位址。"}],
      "inputs": [{
        "id": "endpoint",
        "type": "url",
        "label": "Echo URL",
        "hint": {"en": "Use an address reachable from the application", "zh-TW": "填入應用可連線的位址"},
        "defaultValue": "{{echoMockUrl}}"
      }],
      "snippets": [{
        "label": "JSON",
        "language": "json",
        "code": "{\"endpoint\": {{json:endpoint}}}"
      }]
    }]
  }
}
```

- SDK 與 artifact id 為小寫字母開頭，後續可用小寫字母、數字、連字號，最長 64 字元。版本允許英數字、點、底線、加號及連字號，最長 128 字元。
- fileName 必須是同一目錄中的普通檔名；不允許路徑或指向其他目錄的符號連結。mediaType 為不含參數的 MIME type。
- 同一 SDK/version 只能登錄一次，artifact id 與檔名在該版本中不可重複。
- 每個 SDK 必須恰有一個 recommended=true。channel 可為 stable、preview 或 deprecated；已棄用版本不能是推薦版本。
- releaseDate 為 ISO 日期，代表該下載版本的發行／封裝日期。推薦版本置前，其餘依日期排序；不根據不同語言的版本字串猜測推薦版本。
- SDK 共用的名稱、語言與描述採推薦版本的資料，讓舊版描述不會蓋掉新版資訊。
- 保留舊版需提供該版原始產物與描述，並設為 recommended=false。更新 SDK 原始碼不會憑空重建已不存在的歷史版本，也不會將新版內容放到舊版 URL。
- 顯示文字可以是字串或語系對照表；頁面優先使用目前語系，再回退至英文或第一個可用語系。

## 接入說明

guide.sections 是有順序的章節，頁面依序自動編號，title 不需自帶「1.」等序號。每個章節有 id、title，可提供 paragraphs、inputs、snippets，或用 options 提供 Gradle／Maven、npm／pnpm 等不同安裝方式。每個 option 有 id、label、paragraphs 與 snippets，並可選填 artifact，指向同一版本中該安裝方式需要的 artifact id；建置時會驗證該 id 存在。頁面只依資料渲染，不判斷特定 SDK 名稱、語言或 artifact 格式。

輸入欄位支援 text、url。defaultValue 的 {{echoMockUrl}} 會使用目前瀏覽器開啟的 Echo mock URL，使用者可修改為 AP 可連線的網址。snippet 的 {{fieldId}} 直接替換文字，{{json:fieldId}} 使用 JSON 字串編碼，可安全放入 JSON 或 YAML。所有參照都必須是該版本已宣告的輸入欄位。說明與程式碼使用文字插值顯示，不執行內容或插入原始 HTML。

## 頁面資訊架構

- 清單：每個 SDK 一列，並排名稱與用途、語言、推薦版本的適用環境、推薦版本與通道；多於一個 SDK 時才顯示搜尋。整列與「開始接入」按鈕都會進入該 SDK 的推薦版本。
- 詳細頁頂端：SDK 名稱、用途與版本選單，下方列出該版本的適用環境，先回答「能不能用」。非推薦、預覽或已棄用版本會顯示提示；非推薦版本提供一鍵改用推薦版本。
- 下載面板：版本、發布日期與一個主要下載。主要下載是目前所選安裝方式的 option.artifact；沒有對應時使用 artifacts 的第一個檔案。其他檔案、用途、檔名、大小與 SHA-256 收在同一面板，不需另讀文件判斷。桌面版固定在右側，手機版排在步驟之前。
- 接入步驟：guide.sections 以單欄編號步驟呈現。安裝方式切換會同步更新說明、需要的檔案與程式碼；設定欄位修改後，程式碼範例即時更新並可複製。

## API 與網址

所有 API 沿用登入權限，一般登入使用者即可讀取，匿名為 401。

- GET /api/admin/sdk：schemaVersion=1 的完整目錄，含 sdks、recommendedVersion、releases、artifacts 與 guide。
- GET /api/admin/sdk/{sdkId}/releases/{version}/artifacts/{artifactId}：精確版本的下載檔，附 Content-Disposition、Content-Length 與對應 MIME type。
- 不存在的 SDK、版本或檔案回 404，不改發其他 SDK 或推薦版本。

頁面網址支援 /#/sdk?sdk=spring-openfeign&version=0.1.0-SNAPSHOT。切換版本會同步網址；重新整理、上一頁及下一頁會保留選擇。指定不存在版本時顯示找不到，不悄悄切回推薦版本。

執行時會驗證 catalog 的識別碼、版本、資源大小及下載 URL，再建立唯讀查詢表。下載參數只用來查表，不能變成任意 classpath 或檔案系統路徑。

## 驗證

SdkCatalogServiceTest 驗證多 SDK、多版本、舊版內容、未知座標、路徑限制、無效登錄及空目錄。SdkDownloadIntegrationTest 經真實安全鏈驗證一般使用者下載、匿名 401、未知檔案 404、JAR／ZIP 與 catalog 的內容及校驗碼。

sdk/compatibility/catalog-fixtures 包含明確標示 TEST FIXTURE 的第二種類型及兩個版本，只用來驗收 catalog；一般 Echo 不會內含它們。

    ./gradlew bootJar -PsdkCatalogDir=sdk/compatibility/catalog-fixtures
    python3 sdk/compatibility/evidence/verify_packaged_download.py \
      --java-home /path/to/jdk17 --echo-server-jar /path/to/fixture-echo.jar \
      --output /path/to/fixture-evidence --downloads-only --expect-fixtures

驗收後重新執行一般 bootJar，恢復正式產物。verify_catalog_build.py 會檢查缺檔、版本重複、缺少推薦版本及不存在的說明欄位皆使建置失敗，並在 finally 恢復一般建置、確認測試資料與檔案都未留在正式 JAR。

verify_packaged_download.py 從單顆 Echo JAR 啟動，使用真實瀏覽器登入、進入 SDK 中心、逐一選擇 SDK／版本、重新整理與前後導覽、下載所有宣告檔案並核對大小及 SHA-256。可另外把下載的 OpenFeign JAR 接入 Boot 3／4 AP 完成實際請求驗證。
