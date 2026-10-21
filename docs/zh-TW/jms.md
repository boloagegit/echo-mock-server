# JMS

[← 文件目錄](README.md)

Echo 如何接收 JMS 請求、回覆，以及轉發到真正的 broker。

Echo 可作為 JMS Proxy，在開發環境攔截 JMS 訊息：

```
應用服務 ──JMS──▶ Echo (Artemis)  ──JMS──▶ ESB (TIBCO/Artemis)
                 Queue: ECHO.REQUEST      Queue: TARGET.REQUEST
                 有匹配規則 → Mock Response
                 無匹配規則 → 轉發到 Target ESB
```

管理員可在「系統設定 → 轉發連線 → JMS」建立多組 Artemis/TIBCO 連線、測試連線並指定一組資料庫預設值。有效且啟用的 `application.yml` 轉發設定優先；未設定時才使用資料庫預設連線。這是設定優先順序，不是連線失敗後自動切換的後備機制。未匹配規則的訊息，以及選擇「預設 JMS 連線」的規則，會使用此**轉發端**預設；指定特定連線的規則則使用該連線。Echo 自己接收訊息的 Embedded Artemis 不受影響。密碼以 AES-GCM 加密保存且不會由 API 回傳；正式環境請固定設定 `ECHO_JMS_CREDENTIAL_KEY`，更換此金鑰前必須先重新輸入所有已儲存密碼。

系統設定分為「總覽、資源監控、轉發連線、資料與備份、服務設定」。僅進入頁面與手動重新整理／重試時更新，不定時輪詢。監控明細區分目前使用量、啟動後累計與設定上限；判讀健康度或資源未釋放前，請先參閱[資源快照的意義與限制](../resource-monitoring.md)。

[交易診斷](../transaction-diagnostics.md)提供有容量限制的異常摘要與請求紀錄關聯 ID。
設定方式、留存限制與證據判讀集中於該文件，正常請求不新增診斷檔案日誌。

Artemis 大訊息讀取已採用官方批次複製修正，見[升級驗證](artemis-large-message-validation-2026-10-18.md)。先保留 client 預設的編碼後 100 KiB 門檻。提高發送端 URL 的 `minLargeMessageSize`（例如 `524288`）可減少大訊息檔案 I/O，但更多訊息本文會留在記憶體，尤其小 heap 必須先依本文大小與併發量實測。套用這次升級不需要更改門檻；這是**發送端**設定，不是 Echo 的 `application.yml`。

## 轉送資源重用

回覆送出失敗時，現在會把錯誤交回來源 listener 的交易管理者，避免原始請求被提交卻沒有成功回覆。沒有 `JMSReplyTo`、刻意注入 `CONNECTION_RESET` 的不回覆行為維持原樣；錯誤回覆送出失敗也會 rollback，不會因送出失敗再嘗試第二份回覆。

重送可能重複執行 mock／Scenario、寫入紀錄及下游副作用；send 返回不代表已 commit，跨 broker 也不保證 exactly-once。永久刪除的回覆目標應由來源 broker 設定有限重試與 DLQ。內建 Artemis 預設保留失敗訊息並無限退避重送；外部 broker 使用自己的政策，Echo 不會默默丟棄永久回覆失敗。上述語義已用 Artemis 與 JMS 替身驗證，TIBCO EMS 仍需獨立驗收。

轉送預設為每個目標保留最多 8 個 idle Session（`echo.jms.forward.session-cache-size`；設為 `0` 停用重用）。每筆請求仍使用自己的暫存回覆 Queue 與 Consumer，避免遲到或重複回覆成為另一筆請求的答案。來源交易與重送行為不變。量測與資源生命週期檢查見 [JMS 效能與正確性](../jms-performance-and-correctness.md)。

## 從共用 broker 接收 JMS（多實例）

預設每台 Echo 用自己內建的 Artemis 收 JMS，發送端必須指定某一台。要開多台分擔量與備援時，讓所有實例監聽既有 broker（例如公司 ESB）上的同一個 queue：broker 會把每則訊息只交給其中一台，回覆送到訊息的 `JMSReplyTo`，也在同一個 broker 上。

```
應用程式 ──JMS──▶ ESB 上的 queue ECHO.REQUEST ──▶ Echo A
                                              └─▶ Echo B
```

```yaml
echo:
  jms:
    enabled: true
    queue: ECHO.REQUEST            # 各實例共用的 broker queue
    source:
      type: tibco                  # embedded（預設）| artemis | tibco
      server-url: tcp://esb-server:7222
      username: echo               # ECHO_JMS_SOURCE_USERNAME
      password: ${ECHO_JMS_SOURCE_PASSWORD}
      reconnect-interval-ms: 5000  # broker 連不上時的重試間隔
```

- 不啟動內建 Artemis，也不開啟 `echo.jms.port`。
- broker 沒開時 Echo 照樣啟動並持續重連；**系統設定 → 服務設定**會顯示 JMS 是否正在接收。
- 連線恢復會沿用來源 provider factory；應用程式最終關閉時，先回收快取資源，再關閉實作 `AutoCloseable` 的 provider factory。
- 訊息在交易內處理：某台處理到一半停止或當機，broker 會把訊息交給另一台。若當機剛好發生在回覆送出後、提交前，發送端可能收到兩次回覆（至少一次傳遞）。
- 重送延遲、次數上限與 dead-letter 由 broker 自己的設定決定；`echo.jms.redelivery-*`、`max-delivery-attempts`、paging 與磁碟設定只適用於內建 broker。
- TIBCO EMS 需要 EMS 10.3 以上的 client（`tibjms.jar`，`jakarta.jms`）放在 `libs/`，與轉送功能相同。
