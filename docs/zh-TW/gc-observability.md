# GC 紀錄與記憶體觀察測試

管理員可在 **系統管理 → 資源監控 → 記憶體與 GC** 查看每次 GC 的結果與回收後
Heap 趨勢。關閉頁面時也會繼續記錄；開啟或更新頁面只讀取資料，不會觸發 GC。
沒有強制 GC 按鈕，也不會掃描所有物件、產生 heap dump 或在每筆請求上增加監控工作。

每筆包含收集器、收集器內部 ID、動作、原因、完成時間、耗時、回收前後 Heap、
淨回收量與各記憶體區用量。Heap 加總排除 non-heap；資料不完整時顯示無法取得，
不會當成零。併發回收期間記憶體仍可能增加，因此淨回收量可以是負值。

**GC 後仍有存活物件，不代表洩漏。** 正在處理的請求、規則、快取與 broker 都會保留
正常使用的物件。Young GC 不會回收整個 Heap；要在暖機後、相近負載下比較相同收集器。
併發 GC 耗時也不等於服務停頓時間。圖表不能單獨診斷洩漏或保證不會 OOM。

## 記錄方式與容量

使用 JDK 的 GC 通知，支援的 JVM 包含 Windows／Linux 上的 HotSpot／Temurin。
通知處理只計算一筆小紀錄並放進有上限的記憶體緩衝區，不寫磁碟、資料庫或網路。
開始記錄的時間是監控服務訂閱時，不是 JVM 剛啟動時；若需要更早的啟動紀錄或永久
保留，請另外啟用 JVM GC log。

```yaml
echo:
  monitoring:
    enabled: true
    jvm-enabled: true
    gc-enabled: true
    gc-history-capacity: 512  # 16～4096 筆；變更後需重啟
    gc-history-max-age-seconds: 86400  # 60～604800 秒；預設 24 小時
```

前三個開關任何一個關閉，就不訂閱 GC 通知。紀錄只屬於目前程序，重啟後清空；
滿額或過期就移除最舊一筆，先到的限制先執行。保存期限依記錄後的經過時間計算，
不受系統校時影響。每分鐘執行 housekeeping，讀取或收到新事件時也會清理；沒有新 GC、
沒人開畫面仍會清理。過期資料最多等到下一次清理才釋放參照，但不會再回傳給讀取者。
GC 頻繁時可能很快就超過 512 筆，因此 24 小時是最長保存時間，不保證有整天的紀錄。
這些紀錄不寫入資料庫或 application log。畫面顯示最新 512 筆，可依收集器篩選，
每頁 25 筆，並列出開始記錄時間、累計筆數、容量移除數、過期移除數、通知失敗數與未支援的收集器。
匯出的是目前顯示的範圍，並非永久歷史。更新失敗會標示舊資料，權限失效則清除畫面資料。
服務停止時會移除訂閱。

`GET /api/admin/resources/gc` 僅限 ADMIN，回應設為 `Cache-Control: no-store`。
沒有 POST 操作。不帶 `after` 時回傳最新 `limit` 筆（預設／最大 512）。
`?after=123&limit=200` 則從仍保留、序號大於 123 的最舊一筆開始，依通知接收順序回傳。
用 `nextAfter` 繼續讀到 `hasMore=false`。序號需搭配 `processStartedAt`，重啟時重設游標；
不同收集器的內部 ID 可能重複。`missedEvents` 代表尚未讀取就被覆蓋或過期的筆數，
`notificationFailures` 則是無法解析的通知。需要長期歷史時，請把資料收集到 Echo 以外。
JVM 通知屬於盡力傳遞，不能當作完整稽核紀錄。

若另外啟用 JVM GC 檔案，也要獨立設定輪替，例如
`-Xlog:gc*:file=logs/gc.log:time,uptime,level,tags:filecount=5,filesize=10M`。
請先建立 log 目錄；這與管理畫面的保留規則是分開的。

## Heap 之外也要觀察

既有快照包含 non-heap、direct／mapped buffer、執行緒、HTTP／JMS 處理中工作與日誌佇列。
這次再補上 HTTP 加速的處理中請求、緩衝保留量、等待工作與容量上限。保留量是資源預算，
不是精確的物件大小。即使 Heap 穩定，原生記憶體、執行緒 stack、SQLite、程序／容器限制
仍可能造成失敗。可比較的基線持續升高時，需要進一步調查物件引用或原生記憶體來源。

記憶體頁面也提供「記憶體累積來源對照」：比較同一程序的本次／前次手動資源快照，
包括規則、本文與模板快取、HTTP／JMS 緩衝預留量、日誌佇列、等待工作、Direct／Non-heap
與執行緒。瀏覽器最多保存兩份快照，離開元件或權限失效即清除，服務重啟則重設比較。
資源快照與 GC 不是同時取樣；一起增加只能縮小調查範圍，不能直接斷定是哪個物件沒釋放。

## 可重複的觀察測試

工具自行啟動隔離的 Echo、資料庫、下游 broker 與負載程序，不會連到既有部署或清除它的規則。
Windows 使用完整 JDK 17（含 `jcmd`）及 Python 3，不需要 Docker；正式驗收仍在 Linux 執行。

```powershell
.\gradlew.bat bootJar memoryObservationFixtureJar
if ($LASTEXITCODE -ne 0) { throw 'Build failed' }
$echoJars = @(Get-ChildItem build/libs/echo-server-*.jar -File)
if ($echoJars.Count -ne 1) { throw 'Expected exactly one Echo JAR' }
python scripts/test-memory-observability.py --jar ($echoJars[0].FullName) --output build/observations/run-1
```

```bash
./gradlew bootJar memoryObservationFixtureJar
python3 scripts/test-memory-observability.py \
  --jar build/libs/echo-server-0.0.0-SNAPSHOT.jar \
  --output build/observations/run-1
# 發行版本請替換成實際的 JAR 名稱。
```

可用 `--java` 指定完整 JDK 的 `bin/java` 或 `bin/java.exe`。輸出目錄必須尚未存在。
測試專用 Echo 將 GC 保存期限縮短為 60 秒，每兩秒讀取一次；必須觀察到過期清理，
且不能有尚未讀取就遺失的事件，否則不通過。
若要測試反覆重連，可用 `--warmup-cycles 6 --warmup-seconds 15`，讓 broker 延後建立的
網路執行緒在基準點之前也經歷重連；實際暖機長度須依部署的 pool 大小調整。
所有暖機結果都會保存。若數量仍增加，應看執行緒清單與 NMT 類別，不應直接放寬門檻。
預設 Heap 384 MiB、暖機 60 秒，再跑 10 輪「負載 60 秒 → 自然閒置 15 秒」，
HTTP／JMS 合計每秒 100 筆，資料大小 16 KiB。`--cycles 60 --seconds 300` 可延長到約五小時；
長時間驗收應使用接近正式部署的 Linux 規格與實際流量。

每輪核對 HTTP 回應、JMS 內容與 correlation ID，並確認請求、緩衝、寫入工作歸零，
下游沒有殘留暫時 Queue。全程保存自然 GC 紀錄。**只有在停止負載、自然等待且確認工作
清空後，才由測試工具觸發 GC**，用來比較相同條件下仍被保留的 Heap；負載期間不強制回收。
測試 JVM 會開啟 Native Memory Tracking，不能把這輪數字當成正式吞吐基準。

產物包含 `summary.json`、GC／資源 NDJSON 時序、JVM GC log、各輪原生記憶體摘要、
執行緒清單與程序 log。判定比較前兩個／後兩個觀察點的中位數及線性趨勢；預設允許增加量：
Heap 24 MiB、direct 8 MiB、non-heap 16 MiB、NMT committed／RSS 128 MiB、4 個執行緒、
8 個 Linux 檔案描述符。Heap／原生記憶體允許量可透過參數設定。這些是回歸測試門檻，
不是通用安全上限；快取與連線池正常保留是預期行為，低於門檻的小量洩漏仍需要更長的測試。
RSS 與檔案描述符只在 Linux 採集，NMT 則兩個平台都能使用。

若要進一步找出內容種類，可加上 `--class-histograms`，在閒置觀察點收集各 Java 類別的
物件數與本身大小，輸出 `*-classes.txt`，並在 `classGrowthSuspects` 列出相較基準增加最多的
30 個類別。這只會對測試工具自行建立的 Echo 執行 `jcmd GC.class_histogram -all`，
檢查期間會暫停 JVM；`-all` 不另要求 GC，但包含不可達物件及觀察點之後的新配置。
看到 `byte[]` 或 `String` 增加是線索，不代表它一定洩漏，也不能直接知道由哪個模組持有。
要找到引用鏈，需在可控環境取得 heap dump，再用 Eclipse MAT 等工具離線分析；
正式服務的管理 API 不會自行觸發這些高成本操作。測試輸出是有限次執行的證據，
請用 CI artifact 保存期限或測試環境的清理工作移除舊 run 目錄。

回應錯誤、GC 資料不完整、服務重啟、未讀資料被覆蓋、資源未釋放，或不足四個觀察點，
都不能算通過。`no-growth-detected-in-window` 只代表這段測試沒有超出宣告的門檻，
不代表永遠沒有洩漏。分析器另有刻意逐輪增加的模擬資料測試，確認它確實會拒絕持續增長。
