# Signal Console UI 改版：交接說明

給接手的人（或 Cowork）快速進入狀況用。專案規範以 `AGENTS.md` 為準，設計語言見 `DESIGN.md`。

## 目前狀態

- 分支 `ui/signal-console`，從 `main` 分出，尚未 push、沒有 PR。每個 commit 訊息都寫了改了什麼、為什麼，以及測試怎麼調整。
- 範圍只在前端（`src/main/resources/static/`）與前端測試。唯一的後端變更是 `config/WebMvcConfig.java` 的靜態檔快取：`Cache-Control: no-cache` + Last-Modified，每次驗證、沒變回 304；`index.html` 維持 no-store。這項已經使用者同意（「快取但每次驗證」）。
- 最後一次全套測試：`./gradlew t spotbugsMain` 0 failures，`node --test src/test/js/*.cjs` 全過。

## 使用者的要求（一直有效）

- 給公司 SIT／UAT 用的專業工具，主要在 Windows 上使用。強調所有畫面一致、操作順暢、不閃爍。
- 動畫可以有，但不能影響效能，也不能看起來像閃一下。
- 能自繪的圖示就自繪（`components/UiIcon.js`）。
- 「不要動操作邏輯，排版可以微調」：改版不改變行為。發現行為面的問題時，另外提出，不要順手改。
- 完成時要附上每個畫面的截圖（見「待辦」）。
- 依部署環境切換主色：SIT 用 teal、UAT 用 blue。設定 `echo.ui.accent`（環境變數 `ECHO_UI_ACCENT`），由 `theme.css` 的 `data-accent` 套用。

## 結構速覽

- **CSS 分三層**：`theme.css`（tokens）→ `style.css`（舊樣式，已清掉沒用到的規則）→ `console.css`（新的 Signal Console 層，後載入，同權重時以它為準）。
- **共用元件**（在 `app.js` 註冊，`index.html` 以 `<script>` 載入）：
  - `ui-detail-drawer`
    - 點外部收合：`KEEP_OPEN` 白名單內的元素例外。
    - 可加寬：`localStorage echo.drawerWide`。
    - 標題超過兩行時收合，可展開。
  - `ui-detail-section`
    - 可收合，捲動時標題固定。
    - 收合狀態記在 `localStorage echo.drawerSections`。
  - `ui-code-viewer`
    - 用在所有長內容：規則、回應、請求記錄的 body，以及修訂記錄的長欄位。
    - CodeMirror 唯讀，只繪製看得到的行。
    - 提供全文搜尋（最多標示 2000 筆，計數涵蓋全部）、換行、JSON／XML 格式化（2 MB 以內）、複製。
  - `ui-toggle`
    - 是 `role="switch"` 的 button，外觀由 `aria-checked` 決定。
    - 不要改回原生 checkbox：取消預設動作的 checkbox 會在事件結束後還原外觀，結果是資料改了、畫面沒變。
  - `heldDetailMixin`（`utils.js`）：切換詳情時，保留前一筆直到下一筆準備好，避免閃爍。
- **重新整理不閃**的作法：
  - 啟動期間加 `is-booting` class，App 準備好才顯示。
  - `ui-snapshot.js` 在 pagehide 時存下畫面，重新整理時先畫出來。
  - 字型 preload。
  - 頁面切換由 router 保持舊頁（`PAGE_HOLD_MS`）。
- **規則編輯器**（方案 B）：
  - 版型：左邊請求、右邊回應，測試列在兩欄下方。
  - HTTP／JMS 共用相同欄位位置，切換協定時只換欄位，其他區塊不移動。標題下的摘要列一直存在。
  - 只有切進來的欄位淡入（`rule-protocol-swap`）。

## 改動時的規則（踩過的坑）

1. **快取版本**：改到哪個靜態檔，就要把 `index.html` 裡它的 `?v=` 調升。目前是 `20261007.15`，同一天再加後綴。i18n 的版本在 `composables/useI18n.js`。部分測試會固定版本號（如 `LoginAccessibilityResourceTest`、`ListRefinementResourceTest`），要一起更新。
2. **前端測試政策**：
   - 元件重做後，原本寫死字串的 assertion 要改寫成行為／a11y 測試。
   - 不能刪測試，也不能跳過失敗的測試。
   - commit 訊息要列出改了哪些測試。
3. **commit 前**：先確認 0 failures。曾經發生過指令沒在失敗時停下、把失敗的測試 commit 進去的情況。
4. **不要做的事**：
   - 不引入 CDN（一律用 WebJars）。
   - 不直接改 `mockdb.*`。
   - 不 commit 密碼或金鑰。
5. **Commit 結尾**：`Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`。

## 驗證方式

```bash
./gradlew dev
```

開發模式，http://localhost:8080，會自動載入測試資料。

```bash
./gradlew test --tests 'com.echo.frontend.*'
```

只跑前端資源測試，比較快。

```bash
node --test src/test/js/*.cjs
```

前端邏輯測試。

```bash
./gradlew t spotbugsMain
```

完整測試加 SpotBugs，合併前必跑。

- **閃爍檢查**：用 Chrome DevTools Protocol 逐格截圖。重點看重新整理後的第一格，要和最終畫面一樣。
- **版面位移檢查**：量測各區塊的 `getBoundingClientRect()`，例如 HTTP／JMS 切換前後各列的 top 要相同。
- **headless 截圖時要注意**：
  - 編輯器有未儲存變更時，離開頁面會跳出「離開網站？」，要自動接受。
  - 分頁卡住時，先開新分頁再關掉舊的。

## 待辦

- [ ] **JMS「回覆佇列」欄位沒有存檔**。這是既有的問題，不是這次改版造成的。
  - `form.replyQueue` 只用在草稿偵測。後端一律回覆到請求的 JMSReplyTo（TextMessage，CorrelationID 帶入請求的 MessageID）。
  - 要請使用者決定：存成規則欄位（需要改 Entity，先確認），或拿掉欄位改成說明文字。
  - 已另開背景任務。
- [ ] **最終截圖**：每個畫面在深色與淺色主題、SIT 與 UAT accent、1440 寬與手機寬各一張，交給使用者。
- [ ] 使用者同意後才 push、開 PR 到 `main`。版本與 tag 依 `AGENTS.md`（`vYYYY.MM.DD`）。
