---
target: HTTP Mock response-mode surfaces
total_score: 22
max_score: 40
na_heuristics: 
p0_count: 0
p1_count: 3
timestamp: 2026-08-09T05-10-49Z
slug: main-resources-static-components-ruleeditmodal-js
---
## Design Health Score

此畫面是 Operate 型企業工具，十項 heuristic 均適用。

| # | Heuristic | Score | Key Issue |
|---|---|---:|---|
| 1 | Visibility of System Status | 3/4 | 模式、空狀態與選取狀態可見，但清單載入與破壞性切換沒有狀態提示。 |
| 2 | Match System / Real World | 3/4 | Mock、SSE、回應 ID 符合領域語言；「一般」與共享資源仍不夠清楚。 |
| 3 | User Control and Freedom | 1/4 | 切到現有回應會清掉新回應草稿，沒有復原或保留。 |
| 4 | Consistency and Standards | 3/4 | 樣式與 Echo 一致，但搜尋式選擇器不符合 combobox/listbox 慣例。 |
| 5 | Error Prevention | 1/4 | 草稿遺失與共用回應修改都缺少充分防護。 |
| 6 | Recognition Rather Than Recall | 3/4 | ID、描述、大小、使用數與預覽降低記憶負擔；關閉狀態缺少下拉提示。 |
| 7 | Flexibility and Efficiency | 2/4 | 搜尋有效率，但無法以鍵盤走訪或選取回應。 |
| 8 | Aesthetic and Minimalist Design | 3/4 | 已乾淨、克制且具產品脈絡；已選回應的層級仍太弱。 |
| 9 | Error Recovery | 1/4 | 切換後的內容遺失沒有復原路徑；空內容預覽還可能卡在載入中。 |
| 10 | Help and Documentation | 2/4 | 有區塊提示，但選擇方式與共享影響缺少情境說明。 |
| **Total** | | **22/40** | **Acceptable — significant fixes needed** |

## Design Specificity Verdict

畫面已具 Echo 的產品特徵，不是可任意套用到其他 SaaS 的 AI 後台模板。HTTP/JMS、Mock 回應、SSE 篩選、回應 ID、使用規則數、JSON/XML/Text 範本與即時內容預覽，讓操作明確屬於 Mock Server。視覺語言仍偏 Bootstrap 企業工具，但沒有漸層、glow、單邊有色邊框或卡片農場。後續不應靠增加裝飾解決問題，而要強化選擇、所有權與狀態轉換。

Deterministic scan 對 `RuleEditModal.js` 回傳 `[]`、0 findings。它沒有偵測出互動語意與資料保留問題，因此不能等同於畫面可核准；人工設計審查與來源證據補出了鍵盤、焦點、草稿與空內容狀態問題。

兩個審查代理的 Browser runtime 都回傳 `No browser is available`，因此沒有注入 overlay，也沒有 `[Human]` 分頁。視覺證據使用本回合前已產生並實際操作驗證的六張 1440×1000 淺／深色截圖；技術端另以原始碼、HTTP 200 與 token 對比計算佐證。

## Overall Impression

視覺方向合格，最大問題已不是美感，而是介面傳達的安全感高於實際行為：看起來像可自由切換的兩個模式，實際卻會清掉未儲存內容；看起來像可搜尋的選擇器，實際只有滑鼠能完成選擇。

## What’s Working

- 「建立新回應／使用現有」兩個模式可立即比較，淺深色 active state 都清楚，而且沒有依賴顏色作為唯一訊號。
- 資訊順序合理：模式 → 選擇器／範本 → 回應內容；新回應才顯示 JSON/XML/Text 範本，符合 progressive disclosure。
- 已選回應同時顯示 ID、描述、類型、大小與預覽，能減少使用者在規則與回應管理頁之間來回確認。

## Cognitive Load

8 項中有 2 項失敗，屬 moderate load：

- Visual hierarchy：選取後搜尋框仍比已選回應更醒目；使用者的注意力沒有從「尋找」轉到「確認」。
- Minimal choices：下拉一次展示大量同質選項，雖有搜尋，但缺少結果數、最近使用或清楚的選擇提示。

其餘 single focus、chunking、grouping、one thing at a time、working memory 與 progressive disclosure 通過。

## Emotional Journey

「建立新回應」的起點明確，範本與編輯器讓使用者快速進入工作；切到「使用現有」時，搜尋欄沒有明說它也是選擇器，形成第一個遲疑點。選取後的 ID、大小與內容預覽是整段流程最有信心的時刻。但最深的落差發生在探索另一模式時：內容會無聲遺失；編輯既有回應又可能影響其他規則。介面看起來越安全，實際風險反而越容易被忽略。

## Priority Issues

### [P1] 模式切換會無聲刪除新回應草稿

- **Location:** `useRuleForm.js:451–457`、`RuleEditModal.js:543–548`。
- **Why it matters:** 使用者只是查看現有回應，就可能失去花時間撰寫的 JSON，並且 status/delay 也被重設；這是會導致支援案件的信任問題。
- **Fix:** 為 new/existing 維持兩份暫存狀態；切換只隱藏、不清除。只在儲存後或使用者明確確認「捨棄草稿並切換」時清除。兩個按鈕必須走同一個對稱的 transition function。
- **Suggested command:** `$impeccable harden`

### [P1] 現有回應選擇器是滑鼠專用

- **Location:** `RuleEditModal.js:559–588`。
- **Why it matters:** 結果是不可聚焦的 `<div @click>`；鍵盤與螢幕閱讀器使用者無法走訪或選取，Escape 還會直接關閉整個 modal。
- **Fix:** 保留相同外觀與操作邏輯，改為 ARIA combobox/listbox pattern：可見 label、`aria-expanded`、`aria-controls`、`aria-activedescendant`、`role=option`、`aria-selected`；Arrow keys 移動、Enter 選取、Escape 先關閉清單。
- **Suggested command:** `$impeccable audit`

### [P1] Modal 缺少完整焦點管理

- **Location:** `RuleEditModal.js:194–203`、`app.js:202–210`。
- **Why it matters:** 雖有 `role=dialog`、`aria-modal` 和 Escape 關閉，但沒有開啟時移入焦點、focus trap、背景 inert 與關閉後回到觸發按鈕。鍵盤使用者可能 tab 到背景並失去位置。
- **Fix:** 以既有 ConfirmModal 的焦點模式統一實作；不要改視覺或按鈕順序。
- **Suggested command:** `$impeccable audit`

### [P2] 選擇器的可發現性與選取後層級不足

- **Location:** 現有回應清單與已選回應畫面；`RuleEditModal.js:559–610`。
- **Why it matters:** 搜尋 icon 只暗示過濾，不暗示點擊會展開 inventory；選取後搜尋框仍強調，已選回應反而像次要 metadata。
- **Fix:** 加上「選擇現有回應」可見 label，placeholder 改為「搜尋 ID 或描述」，增加 chevron 與「14 個可用回應／N 個結果」。選取後加入「目前選用」，以描述為主行、ID/類型/大小/使用數為次行，搜尋控制降為「更換回應」。不要增加新卡片。
- **Suggested command:** `$impeccable clarify` + `$impeccable layout`

### [P2] 共用回應與空內容狀態容易誤判

- **Location:** `RuleEditModal.js:591–610, 691–713`、`useRuleForm.js:743–757`。
- **Why it matters:** 共用影響通常到按下編輯後才清楚；合法的空字串回應因 `v-if="form.responseId && previewResponseBody"` 被當成永遠載入中。
- **Fix:** 在選取卡持續顯示使用規則數；多人共用時將動作命名為「編輯共用回應」並在首次修改前確認。預覽載入狀態應以 explicit loading boolean 判斷，而不是 body truthiness；空 body 顯示明確空內容狀態。
- **Suggested command:** `$impeccable harden`

## Persona Red Flags

- **Alex（Power User）:** 搜尋很快，但不能鍵盤選取；Escape 直接關 modal；比較兩種模式會損失草稿，阻礙快速操作。
- **Sam（Accessibility-dependent）:** 搜尋框沒有 label/combobox state，結果沒有 option 語意，modal 焦點不封閉；24–28 px icon controls 也增加操作困難。
- **Jordan（First-timer）:** 不知道搜尋框會展開選單；「一般」意義模糊；「編輯回應」沒有告知可能影響其他規則。

## Minor Observations

- Light/dark 主要文字對比通過 token 計算：dark muted/card 6.81:1、light muted/card 5.39:1、white/primary 6.29:1；不需重做色票。
- 搜尋 focus ring 推算約 1.7:1，低於 3:1 的焦點指示對比期待，應強化既有 focus token，而不是新增 glow。
- All/SSE 使用 11 px 文字且高度約 21 px；selected-card icon buttons 約 28 px。維持企業工具密度即可，但建議可視控制至少 32–36 px、hit area 40 px。
- 已選 check icon 應保留固定欄寬，避免有無選取時描述起點跳動。
- 沒有 card-farm、gradient、glow 或單邊有色邊框問題；不要為了改善層級再加更多卡片。

## Questions to Consider

- 如果兩個模式像分頁一樣保留各自草稿，使用者是否就能安全比較而不用增加學習成本？
- 「編輯回應」是否應明確呈現為修改獨立的共用資源，而不是看起來像規則局部內容？
- 選取後，搜尋是否應退成「更換回應」，讓已選回應成為右側的主要物件？
