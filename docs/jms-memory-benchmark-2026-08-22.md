# JMS XML 記憶體修正壓測（2026-08-22）

## 目的

驗證 Echo 在大型 JMS XML 與 30 路併發下不會因 heap 用盡而失去連線，並比較修正前後的吞吐與延遲。

## 方法

- 同一台 Apple M5 / Darwin arm64，同一 OpenJDK 22.0.2 runtime。
- 修正前使用乾淨 `HEAD` jar；修正後使用同一建置與壓測程式。
- JVM 固定 `-Xms512m -Xmx512m -XX:+UseG1GC`。
- 每個情境都啟動全新 Echo process 與臨時資料庫，不讀寫專案 `mockdb` 檔案。
- Artemis Core TCP request/reply，訊息為 non-persistent，暖機 3 秒、測量 10 秒。
- 規則條件：`//ServiceName=PerfService;//Marker=TARGET`。
- 1 MB / 30 路另做暖機 5 秒、測量 60 秒的 soak test。

## 結果

| XML body / 併發 | 修正前 RPS | 修正後 RPS | 修正前 p95 | 修正後 p95 | 修正前 Full GC | 修正後 Full GC | 結果 |
|---|---:|---:|---:|---:|---:|---:|---|
| 1 KB / 10 | 2,680.30 | 2,794.85 | 4.383 ms | 3.805 ms | 0 | 0 | 穩定 |
| 1 KB / 30 | 6,985.35 | 7,315.01 | 7.233 ms | 4.506 ms | 0 | 0 | 穩定 |
| 100 KB / 30 | 1,004.69 | 3,511.10 | 52.578 ms | 14.431 ms | 2 | 0 | 穩定，發送端使用 512 KB large-message 門檻 |
| 1 MB / 10 | 76.34 | 70.05 | 154.158 ms | 160.445 ms | 58 | 0 | 穩定，無錯誤 |
| 1 MB / 30 | 0 | 83.52 | 無成功樣本 | 420.924 ms | 153 | 6 | 修正前連線崩潰；修正後 0 錯誤 |

100 KB 情境的修正後數字使用發送端 URL：

```text
tcp://echo-host:61616?minLargeMessageSize=524288
```

不加這個發送端參數時，安全版的 100 KB / 30 路為 893.19 RPS、p95 44.129 ms、0 錯誤、0 Full GC。原因是 Core client 預設會將約 100 KB 以上的 encoded body 改走 large-message 檔案路徑。512 KB 門檻讓約 256 KB 以內的文字 XML 保持走一般訊息，更大的訊息仍落盤保護 heap。

60 秒 soak test 處理 5,409 筆 1 MB XML（90.13 RPS），0 錯誤，p95 348.494 ms，測試結束時 process 仍存活。

## 判讀

- 原版的 1 MB / 30 路並非單純變慢，而是 broker/client 連線已無法完成任何請求。
- XML 一般條件改為 StAX 單趟掃描後，不再為每則訊息建立完整 DOM；複雜 XPath 才建立一次 DOM 並共用。
- Embedded Artemis 必須保持 persistence，否則 large message 在進入 Echo listener 前就可能用 heap 組裝到 OOM。
- listener 記憶體額度、consumer window 與 broker paging 分別限制解析中、預取中與佇列中的訊息，三者不能互相取代。
