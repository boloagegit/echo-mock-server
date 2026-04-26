package com.echo.config;

import com.echo.entity.HttpRule;
import com.echo.entity.JmsRule;
import com.echo.entity.Response;
import com.echo.repository.HttpRuleRepository;
import com.echo.repository.JmsRuleRepository;
import com.echo.repository.ResponseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 開發環境測試資料初始化
 */
@Component
@Profile("dev")
@RequiredArgsConstructor
@Slf4j
public class DataInitializer implements CommandLineRunner {

    private final ResponseRepository responseRepository;
    private final HttpRuleRepository httpRuleRepository;
    private final JmsRuleRepository jmsRuleRepository;

    @Override
    public void run(String... args) {
        if (httpRuleRepository.count() > 0) {
            log.info("Data already exists, skipping initialization");
            return;
        }

        log.info("Initializing test data...");

        // ========== Responses ==========
        Response usersResp = responseRepository.save(Response.builder()
                .description("用戶列表")
                .body("[{\"id\":1,\"name\":\"Alice\"},{\"id\":2,\"name\":\"Bob\"}]").build());

        Response userCreatedResp = responseRepository.save(Response.builder()
                .description("用戶建立成功")
                .body("{\"id\":99,\"created\":true}").build());

        Response orderPendingResp = responseRepository.save(Response.builder()
                .description("訂單-待處理")
                .body("{\"type\":\"pending\"}").build());

        Response orderAllResp = responseRepository.save(Response.builder()
                .description("訂單-全部")
                .body("{\"type\":\"all\"}").build());

        Response healthResp = responseRepository.save(Response.builder()
                .description("健康檢查")
                .body("{\"status\":\"UP\"}").build());

        Response delayResp = responseRepository.save(Response.builder()
                .description("延遲測試")
                .body("{\"delayed\":true}").build());

        Response echoResp = responseRepository.save(Response.builder()
                .description("模板測試 - 回傳請求資訊")
                .body("{\"path\":\"{{request.path}}\",\"method\":\"{{request.method}}\",\"userId\":\"{{request.query.userId}}\",\"timestamp\":\"{{now format='yyyy-MM-dd HH:mm:ss'}}\",\"traceId\":\"{{randomValue type='UUID'}}\"}").build());

        Response templateFullResp = responseRepository.save(Response.builder()
                .description("模板測試 - 完整功能")
                .body("{\"path\":\"{{request.path}}\",\"method\":\"{{request.method}}\",\"segment0\":\"{{request.pathSegments.[0]}}\",\"segment1\":\"{{request.pathSegments.[1]}}\",\"query\":\"{{request.query.q}}\",\"auth\":\"{{request.headers.Authorization}}\",\"body\":{{{request.body}}},\"time\":\"{{now}}\",\"uuid\":\"{{randomValue type='UUID'}}\",\"code\":\"{{randomValue length=8 type='ALPHANUMERIC'}}\"}").build());

        Response templateV2Resp = responseRepository.save(Response.builder()
                .description("Phase 2 模板測試 - 條件與迴圈")
                .body("{\"isGet\":{{#if (eq request.method 'GET')}}true{{else}}false{{/if}},\"hasDebug\":{{#if request.query.debug}}true{{else}}false{{/if}},\"items\":[{{#each (split request.query.ids ',')}}\"{{this}}\"{{#unless @last}},{{/unless}}{{/each}}],\"count\":\"{{size (split request.query.ids ',')}}\"}").build());

        Response jsonPathResp = responseRepository.save(Response.builder()
                .description("Phase 2b 模板測試 - JSONPath")
                .body("{\"userName\":\"{{jsonPath request.body '$.user.name'}}\",\"firstItem\":\"{{jsonPath request.body '$.items[0].id'}}\",\"itemCount\":\"{{size (jsonPath request.body '$.items')}}\"}").build());

        Response notFoundResp = responseRepository.save(Response.builder()
                .description("預設回應 - 404")
                .body("{\"error\":\"not found\"}").build());

        Response orderOkResp = responseRepository.save(Response.builder()
                .description("訂單成功")
                .body("{\"result\":\"ORDER_OK\"}").build());

        Response paymentOkResp = responseRepository.save(Response.builder()
                .description("付款成功")
                .body("{\"result\":\"PAYMENT_OK\"}").build());

        Response defaultResp = responseRepository.save(Response.builder()
                .description("JMS 預設回應")
                .body("{\"result\":\"DEFAULT\"}").build());

        // ========== HTTP Rules ==========
        httpRuleRepository.save(HttpRule.builder()
                .targetHost("default").matchKey("/api/users").method("GET")
                .description("用戶列表").priority(10)
                .responseId(usersResp.getId()).httpStatus(200).delayMs(0L).build());

        httpRuleRepository.save(HttpRule.builder()
                .targetHost("default").matchKey("/api/users").method("POST")
                .bodyCondition("name*=").description("建立用戶(name欄位存在)").priority(10)
                .responseId(userCreatedResp.getId()).httpStatus(201).delayMs(0L).build());

        httpRuleRepository.save(HttpRule.builder()
                .targetHost("default").matchKey("/api/orders").method("GET")
                .queryCondition("status=pending").description("訂單-待處理").priority(10)
                .responseId(orderPendingResp.getId()).httpStatus(200).delayMs(0L).build());

        httpRuleRepository.save(HttpRule.builder()
                .targetHost("default").matchKey("/api/orders").method("GET")
                .description("訂單-全部").priority(1)
                .responseId(orderAllResp.getId()).httpStatus(200).delayMs(0L).build());

        httpRuleRepository.save(HttpRule.builder()
                .targetHost("default").matchKey("/health").method("GET")
                .description("健康檢查").priority(10)
                .responseId(healthResp.getId()).httpStatus(200).delayMs(0L).build());

        httpRuleRepository.save(HttpRule.builder()
                .targetHost("default").matchKey("/api/delay").method("GET")
                .description("延遲測試 500ms").priority(10)
                .responseId(delayResp.getId()).httpStatus(200).delayMs(500L).build());

        httpRuleRepository.save(HttpRule.builder()
                .targetHost("default").matchKey("/api/echo").method("POST")
                .description("模板測試 - 回傳請求資訊").priority(10)
                .responseId(echoResp.getId()).httpStatus(200).delayMs(0L).build());

        httpRuleRepository.save(HttpRule.builder()
                .targetHost("default").matchKey("/api/template/full").method("POST")
                .description("模板測試 - 完整功能").priority(10)
                .responseId(templateFullResp.getId()).httpStatus(200).delayMs(0L).build());

        httpRuleRepository.save(HttpRule.builder()
                .targetHost("default").matchKey("/api/template/v2").method("GET")
                .description("Phase 2 模板測試 - 條件與迴圈").priority(10)
                .responseId(templateV2Resp.getId()).httpStatus(200).delayMs(0L).build());

        httpRuleRepository.save(HttpRule.builder()
                .targetHost("default").matchKey("/api/template/jsonpath").method("POST")
                .description("Phase 2b 模板測試 - JSONPath").priority(10)
                .responseId(jsonPathResp.getId()).httpStatus(200).delayMs(0L).build());

        httpRuleRepository.save(HttpRule.builder()
                .targetHost("default").matchKey("*").method("GET")
                .description("預設回應(萬用)").priority(0)
                .responseId(notFoundResp.getId()).httpStatus(404).delayMs(0L).build());

        // ========== JMS Rules ==========
        jmsRuleRepository.save(JmsRule.builder()
                .queueName("*").bodyCondition("type=ORDER")
                .description("訂單處理").priority(10)
                .responseId(orderOkResp.getId()).delayMs(0L).build());

        jmsRuleRepository.save(JmsRule.builder()
                .queueName("*").bodyCondition("type=PAYMENT")
                .description("付款處理").priority(10)
                .responseId(paymentOkResp.getId()).delayMs(0L).build());

        jmsRuleRepository.save(JmsRule.builder()
                .queueName("*").description("預設回應")
                .priority(0).responseId(defaultResp.getId()).delayMs(0L).build());

        // ========== Scenario 範例：訂單流程 ==========
        Response scenarioOrderPendingResp = responseRepository.save(Response.builder()
                .description("訂單-待付款")
                .body("{\"orderId\":\"ORD-001\",\"status\":\"pending\"}").build());

        Response scenarioPayResp = responseRepository.save(Response.builder()
                .description("付款成功")
                .body("{\"orderId\":\"ORD-001\",\"result\":\"paid\"}").build());

        Response scenarioOrderPaidResp = responseRepository.save(Response.builder()
                .description("訂單-已付款")
                .body("{\"orderId\":\"ORD-001\",\"status\":\"paid\"}").build());

        httpRuleRepository.save(HttpRule.builder()
                .targetHost("default").matchKey("/api/scenario/order").method("GET")
                .description("Scenario: 查詢訂單(待付款)").priority(10)
                .responseId(scenarioOrderPendingResp.getId()).httpStatus(200).delayMs(0L)
                .scenarioName("order-flow").requiredScenarioState("Started").newScenarioState("Started")
                .build());

        httpRuleRepository.save(HttpRule.builder()
                .targetHost("default").matchKey("/api/scenario/order/pay").method("POST")
                .description("Scenario: 付款").priority(10)
                .responseId(scenarioPayResp.getId()).httpStatus(200).delayMs(0L)
                .scenarioName("order-flow").requiredScenarioState("Started").newScenarioState("Paid")
                .build());

        httpRuleRepository.save(HttpRule.builder()
                .targetHost("default").matchKey("/api/scenario/order").method("GET")
                .description("Scenario: 查詢訂單(已付款)").priority(10)
                .responseId(scenarioOrderPaidResp.getId()).httpStatus(200).delayMs(0L)
                .scenarioName("order-flow").requiredScenarioState("Paid").newScenarioState("Paid")
                .build());

        log.info("Test data initialized: {} HTTP rules, {} JMS rules, {} responses",
                httpRuleRepository.count(), jmsRuleRepository.count(), responseRepository.count());
    }
}
