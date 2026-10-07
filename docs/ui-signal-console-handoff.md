# Signal Console UI 改版：交接說明

給接手的人（或 Cowork）快速進入狀況用。專案規範以 `AGENTS.md` 為準，設計語言見 `DESIGN.md`。

## 目前狀態

- 分支 `ui/signal-console`，從 `main` 分出，已 push 到 `origin`，還沒有 PR。每個 commit 訊息都寫了改了什麼、為什麼，以及測試怎麼調整。
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

1. **快取版本**：改到哪個靜態檔，就要把 `index.html` 裡它的 `?v=` 調升。目前 `console.css` 是 `20261007.17`、`style.css` 是 `20261007.15`，同一天再加後綴。i18n 的版本在 `composables/useI18n.js`。部分測試會固定版本號（如 `LoginAccessibilityResourceTest`、`ListRefinementResourceTest`），要一起更新。
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
- **對齊檢查**：量測文字本身的位置，不要量按鈕外框。例如可排序表頭要比 `button span` 的 left 與下方儲存格文字的 left；數字欄比圖示的 right 與數值的 right。
- **headless 截圖時要注意**：
  - 編輯器有未儲存變更時，離開頁面會跳出「離開網站？」，要自動接受。
  - 分頁卡住時，先開新分頁再關掉舊的。

## 對齊微調（2026-10-07）

只動 `console.css` 最後的「Alignment pass」與「Phone widths」兩段，測試在 `AlignmentPassResourceTest`。

- 側欄 logo 列與頁首同一條中線（y=40）；logo、分區標題、選單圖示、服務卡、頭像共用 22px 左緣。啟動外框（boot shell）同步調整，重新整理不會跳。
- 搜尋框吃掉工具列剩餘寬度（`DESIGN.md` 的原則）。規則頁篩選太多，搜尋獨佔一行，篩選與檢視切換在下一行。
- 可排序表頭的文字與下方資料同一條邊；數字欄（Priority、Size、Duration）靠右。表頭字重統一，只有目前排序的欄位加粗。
- 分頁按鈕與每頁筆數一樣高；空狀態置中；設定頁「詳細資料」連結對齊該列第一行。
- 編輯器左右兩欄到分隔線一樣寬；抽屜的 ⋯ 與關閉按鈕對齊。
- 手機寬：編輯器標題列改成格線排列，編輯器滿版；設定總覽改為上下排列；窄列表縮小固定欄寬，讓 endpoint 看得到。

## 待辦

- [x] **JMS「回覆佇列」**：這個輸入框從來沒有存檔過；後端一律回覆到請求的 JMSReplyTo（TextMessage，CorrelationID 帶入請求的 MessageID），請求沒帶就不回覆。
  - 已依使用者決定，改成唯讀說明，後端不動。說明視窗的文字也一併更正。
  - 若日後真的需要「請求沒帶回覆地址時，回到固定佇列」：只在沒有 JMSReplyTo 時才使用規則設定的佇列，這樣不影響現有情境。這需要改 Entity，要先經使用者同意。
- [ ] **最終截圖**：每個畫面在深色與淺色主題、SIT 與 UAT accent、1440 寬與手機寬各一張，交給使用者。
- [ ] **HTTP／JMS 切換在窄寬度會位移**（既有問題，非這次造成）：視窗寬度約 1180px 以下時，請求欄變窄，HTTP 的方法按鈕列會換行，切到 JMS 後下面的列往上移約 80px。1440 寬時正常。Windows 筆電用 125%／150% 縮放時，實際寬度常落在這個範圍。
- [ ] **建議，尚未改**：編輯器左欄的欄位標籤在上方、右欄（Status Code、Response Mode）在左側，可以統一；「1 rules」單複數文案。
- [ ] 使用者同意後才開 PR 到 `main`。版本與 tag 依 `AGENTS.md`（`vYYYY.MM.DD`）。
