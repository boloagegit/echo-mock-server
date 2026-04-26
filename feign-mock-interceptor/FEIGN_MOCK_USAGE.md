# Feign Mock Interceptor 使用說明

將 FeignClient 請求自動轉發到 Echo Mock Server，無需修改現有程式碼。

## 安裝

將 `FeignMockConfig.java` 複製到你的專案，調整 package 名稱。

## 設定

在 `application.yml` 加入：

```yaml
mock:
  enabled: true                          # 開關 (預設 false)
  server:
    url: http://localhost:8080           # Mock Server 位址
```

## 使用方式

### 啟用 Mock

```yaml
# application-dev.yml
mock:
  enabled: true
```

### 停用 Mock (預設)

```yaml
mock:
  enabled: false
```

或直接不設定，預設就是停用。

### 環境變數覆蓋

```bash
MOCK_ENABLED=true MOCK_SERVER_URL=http://mock-server:8080 java -jar app.jar
```

## 運作原理

```
FeignClient 請求
       ↓
攔截器檢查 mock.enabled
       ↓
┌──────┴──────┐
↓             ↓
true         false
↓             ↓
轉發到       正常請求
Mock Server  原始 API
```

啟用時，攔截器會：
1. 擷取原始 API host 存入 `X-Original-Host` header
2. 保留 base path（如 `/gateway`）並與 Mock Server 組合
3. 將請求轉發到 Mock Server 的 `/mock/**` 端點

### 範例

原始 FeignClient URL: `https://api.example.com/gateway`
請求路徑: `/api/users`

轉換後:
- `X-Original-Host: api.example.com`
- 請求 URL: `http://localhost:8080/mock/gateway/api/users`

Echo 規則 matchKey 應設為: `/gateway/api/users`

## 注意事項

- Mock Server 需先建立對應的 Mock 規則
- 規則的 matchKey 需包含 base path（如 `/gateway/api/users`）
- 此攔截器會影響所有 FeignClient，如需針對特定 Client，請自行調整 `@ConditionalOnProperty` 條件
