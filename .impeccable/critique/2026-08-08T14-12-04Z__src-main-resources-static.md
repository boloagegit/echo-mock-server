---
target: 目前 Echo Mock Server 管理介面
total_score: 32
max_score: 40
na_heuristics: 
p0_count: 0
p1_count: 2
timestamp: 2026-08-08T14-12-04Z
slug: src-main-resources-static
---
Method: dual-agent (A: /root/ui_design_review · B: /root/ui_evidence_review)

## Design Health Score

| # | Heuristic | Score | Key Issue |
|---|-----------|-------|-----------|
| 1 | Visibility of System Status | 3 | Spinner、toggle、top loader 與 toast 齊全，但成功結果多為瞬時回饋。 |
| 2 | Match System / Real World | 3 | 規則、回應、轉送符合管理者語言；SSE、來源主機匹配、預設連線仍缺少就地說明。 |
| 3 | User Control and Freedom | 3 | Modal 有取消、關閉及全域 Esc；尚無草稿復原或 undo。 |
| 4 | Consistency and Standards | 4 | 深淺主題、表格、badge、卡片、主按鈕與 spacing token 高度一致。 |
| 5 | Error Prevention | 3 | required、formErrors、刪除確認與保護狀態完善；規則 modal 的同屏分支仍容易誤選。 |
| 6 | Recognition Rather Than Recall | 3 | 側欄文字、filter chips 與狀態標示良好；行點擊預覽、雙擊編輯與多個 icon action 需記憶。 |
| 7 | Flexibility and Efficiency | 4 | `/` 搜尋、`n` 新增、方向鍵分頁、`[` 收合側欄、批次操作與複製支援專家流程。 |
| 8 | Aesthetic and Minimalist Design | 3 | 桌面掃讀與雙欄 modal 乾淨；窄螢幕壓縮後失去部分重要狀態。 |
| 9 | Error Recovery | 3 | error toast、inline form errors 與 confirm 已存在；失敗訊息的可行動程度仍需 live 驗證。 |
| 10 | Help and Documentation | 3 | 說明、導覽與 Priority Help 可見；高風險決策點的 contextual help 不足。 |
| **Total** | | **32/40** | **Good** |

## Design Specificity Verdict

**LLM assessment**：中高。Echo 的「規則 → 可共用回應」模型、HTTP/JMS、Mock/轉送、條件、SSE 與保留策略已具體反映於列表、modal 和設定頁，因此不是換文案就能套給別的 CRUD 後台。弱點是外殼仍屬熟悉的 sidebar + data table + violet CTA；產品辨識度主要來自資料模型與文案，而非更有辨識度的任務編排。

**Deterministic scan**：掃描 `src/main/resources/static` 得到 2 項，全部位於 `login.html`：

- warning：`gradient-text`，`login.html:39`
- advisory：`codex-grid-background`，`login.html:19`

主工作區 Rules、Responses、Settings 沒有 detector 命中。登入頁的 grid 可以解釋為 mock/network 技術意象，因此不是必然缺陷；但 gradient heading 加固定雙軸 grid 正是容易產生 AI 模板感的組合，且與使用者明確要求避免 AI 感一致，不能完全視為 false positive。

**Visual overlays**：未建立。兩個 Terra 子代理的 Browser runtime 都回傳 `No browser is available`，因此沒有可靠的 `[Human]` tab 或 detector overlay。設計審查改用 repo 內桌面／手機、深／淺色成果截圖；證據審查使用 DOM/CSS、HTTP 200 與程式語意作 fallback，沒有把靜態檢查說成 live E2E。

## Overall Impression

這一版已是穩健、清楚、可投入企業內部使用的 Operate 介面，並且比先前的單色邊框與大面積灰階卡片成熟很多。最大機會不在再換一套皮，而是建立更明確的任務軸：讓新增規則先回答「攔截什麼」，再回答「回什麼」，並在手機上用摘要重述桌面資訊，而不是直接隱藏。

## What's Working

1. **共用視覺系統成熟**：dark/light 的 muted、surface、separator、violet primary 與 spacing token 一致，頁面不再像不同模組拼接。
2. **資料模型真正可見**：endpoint、method、condition、SSE、protected、retention、response usage 都是 mock-server 任務需要的訊息，不是裝飾性 dashboard 指標。
3. **專家效率有實質支援**：批次選取、複製、inline toggle、preview、keyboard shortcuts 與密集表格都符合高頻管理工作。

## Priority Issues

### [P1] 新增規則 modal 缺少明確任務軸

**Why it matters**：首屏同時要求決定 protocol、啟用／保護／SSE、Mock/轉送、method、path、host、response mode 與 format；左右兩欄視覺權重相等，新手容易在理解回應生命週期前就做錯選擇。

**Fix**：保留既有雙欄、欄位順序與所有功能，但加入「1. 定義匹配」「2. 設定回應」兩個清晰節點；右欄提供隨「建立新回應／使用現有」切換的結果說明。將只在特定模式使用的 SSE、轉送與格式設定放入各自區塊的 progressive disclosure。

**Suggested command**：`$impeccable distill`

### [P1] 窄螢幕列表隱藏了判斷安全性所需的狀態

**Why it matters**：390px 截圖只留下 ID、endpoint 與四個 icon action；protocol、condition、enabled、priority 與建立時間消失。30px/28px icon button 與 36px mobile header action 也低於 44px touch guideline。Rules/Responses 的 clickable `<tr>` 沒有 `tabindex` 或 row-level keyboard handler，鍵盤使用者只能逐一尋找內部按鈕。

**Fix**：endpoint 下保留 2–3 個摘要 badge（protocol、enabled、priority 或 condition count）；手機版將四個 action 收到具文字標籤的「操作」觸發器，但動作內容與流程不變。將 mobile touch target 提高至至少 40–44px，並讓 row preview 有可聚焦的明確控制。

**Suggested command**：`$impeccable adapt`

### [P2] Rules 篩選帶的並列決策過多

**Why it matters**：HTTP/JMS、enabled、protected、list/group 與 search 同時可見，單一決策點超過 4 個選項；mobile 換行後群組關係更弱。Filter chips 只提供事後回饋，沒有降低首次篩選負擔。

**Fix**：第一層只保留 HTTP/JMS 與 search；enabled/protected 放進具狀態摘要的「更多篩選」，持續沿用目前 chips 與 clear all。這是漸進揭露，不變更篩選邏輯。

**Suggested command**：`$impeccable layout`

### [P2] Settings 把動作與長篇相容性說明放在相同層級

**Why it matters**：新增連線 CTA、empty state、fallback 行為、TLS／application.yml 相容提示同時可見，使用者需先讀完多段文字才知道下一步。

**Fix**：每個 HTTP/JMS 區塊第一行只呈現一句狀態摘要與單一 CTA；fallback、安全開關與相容性資訊完整保留在「為何／注意事項」展開區。延續 HTTP 紫、JMS 綠的現有語意。

**Suggested command**：`$impeccable clarify`

### [P2] 登入頁仍保留兩個 AI 模板感特徵

**Why it matters**：gradient-clipped heading 與固定雙軸 grid background 並用，與主工作區目前較克制、企業工具式的視覺語言不一致，也是自動掃描唯一明確命中的區域。

**Fix**：保留技術／network 氣質，但改成單色品牌標題；背景只保留一種低對比訊號（例如稀疏節點或單軸導引），不要同時使用 grid 與漸層字。

**Suggested command**：`$impeccable quieter`

## Cognitive Load

8 項 checklist 中失敗 3 項，屬 moderate：

- **One thing at a time**：新增規則 modal 同屏處理匹配、HTTP 動作、狀態與回應生命週期。
- **Minimal choices**：method 本身已有 5 個選項；整個 modal 首屏可見互動選擇達 17+。
- **Working memory**：需把左側 path/host/method 意圖帶到右側，並記住「新建／使用既有」對後續的影響。

Rules desktop filter 約有 9 個可並列考慮項；手機換行後仍未形成常用／進階層級。

## Emotional Journey

列表開場提供「我知道自己在哪、可快速掃描」的掌控感；count、primary CTA 與一致 badge 建立信任。進入新增規則後，兩個同等重量面板造成最大的焦慮谷底：使用者必須同時理解匹配規則與回應生命週期。儲存後若只有瞬時 toast，缺少「已安全儲存、可以測試」的持久收尾訊號。Settings 的 HTTP 紫／JMS 綠分區能穩定辨識，但長篇提示再次拉高閱讀負擔。

## Persona Red Flags

**Alex（Power User）**：keyboard shortcuts、批次與複製是亮點；但每列 preview/history/copy/edit 四個同型 icon 仍增加快速 targeting 成本，雙擊編輯又只靠一次性 hint 發現。

**Sam（Keyboard/Screen Reader）**：modal 已有 `role="dialog"`、`aria-modal`，全域 `Escape` 也由 `app.js` 處理；但 sortable `th` 與 clickable row 缺少 button/tab semantics，row preview 對 keyboard-only 使用者不可直接啟動。焦點 trap 與 live-region 仍需真正 browser audit。

**Jordan（First-Timer）**：Mock 回應、轉發下游、使用現有、來源主機匹配、SSE 都是正確術語，但缺少「何時選這個」的就地翻譯。Help 存在，卻離真正決策點太遠。

**Casey（Mobile）**：篩選區占用第一屏高度，關鍵 row 狀態被隱藏；頂部與列尾 icon target 偏小，不適合單手快速確認與操作。

## Minor Observations

- Response 管理比 Rules 更安定：「回應可被多規則共用」與 usage badge 降低刪除猜測。
- Response modal 的 description → content type → format → body 閱讀順序自然。
- datasource URL 在窄卡中截斷時，應保留 title 或 copy affordance。
- Sidebar 收合後符合專家需求，但 density/theme/language 需可靠 tooltip 與 accessible name。
- 子代理的 source-only evidence 曾懷疑 Rule modal 缺少 Esc；綜合 `app.js` 全域 handler 後判定為局部檔案造成的 false alarm，不列為缺陷。

## Questions to Consider

- 如果新增規則只先要求回答「攔截哪個請求」，再回答「回什麼」，能否提高第一次成功率而不犧牲專家效率？
- 手機列表真正需要的是縮小版完整表格，還是「目前是否安全／啟用」的操作摘要？
- Settings 應先服務「建立／測試連線」，還是先解釋 application.yml fallback？
