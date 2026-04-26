package com.echo.service;

import com.echo.entity.FaultType;
import com.echo.entity.HttpRule;
import com.echo.entity.JmsRule;
import com.echo.entity.Response;
import com.echo.repository.ResponseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExcelImportService {

    private final ResponseRepository responseRepository;

    private static final String[] RULE_HEADERS = {
        "protocol", "targetHost", "matchKey", "method", "status", "delayMs", "maxDelayMs",
        "bodyCondition", "queryCondition", "headerCondition", "responseBody", "description", "enabled",
        "faultType", "scenarioName", "requiredScenarioState", "newScenarioState"
    };

    public byte[] generateTemplate() {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Rules");

            // Header style
            CellStyle headerStyle = workbook.createCellStyle();
            Font font = workbook.createFont();
            font.setBold(true);
            headerStyle.setFont(font);
            headerStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            // Header row
            Row header = sheet.createRow(0);
            for (int i = 0; i < RULE_HEADERS.length; i++) {
                Cell cell = header.createCell(i);
                cell.setCellValue(RULE_HEADERS[i]);
                cell.setCellStyle(headerStyle);
            }

            // Example HTTP rule
            Row httpRow = sheet.createRow(1);
            httpRow.createCell(0).setCellValue("HTTP");           // protocol
            httpRow.createCell(1).setCellValue("api.example.com"); // targetHost
            httpRow.createCell(2).setCellValue("/api/users");      // matchKey
            httpRow.createCell(3).setCellValue("GET");             // method
            httpRow.createCell(4).setCellValue(200);               // status
            httpRow.createCell(5).setCellValue(0);                 // delayMs
            httpRow.createCell(6).setCellValue("");                // maxDelayMs
            httpRow.createCell(7).setCellValue("userId=123");      // bodyCondition
            httpRow.createCell(8).setCellValue("status=active");   // queryCondition
            httpRow.createCell(9).setCellValue("Content-Type*=json"); // headerCondition
            httpRow.createCell(10).setCellValue("{\"name\":\"test\",\"id\":1}"); // responseBody
            httpRow.createCell(11).setCellValue("用戶查詢範例");    // description
            httpRow.createCell(12).setCellValue("true");           // enabled
            httpRow.createCell(13).setCellValue("NONE");           // faultType
            httpRow.createCell(14).setCellValue("");               // scenarioName
            httpRow.createCell(15).setCellValue("");               // requiredScenarioState
            httpRow.createCell(16).setCellValue("");               // newScenarioState

            // Example JMS rule
            Row jmsRow = sheet.createRow(2);
            jmsRow.createCell(0).setCellValue("JMS");              // protocol
            jmsRow.createCell(1).setCellValue("");                 // targetHost
            jmsRow.createCell(2).setCellValue("*");                // matchKey
            jmsRow.createCell(3).setCellValue("");                 // method
            jmsRow.createCell(4).setCellValue(0);                  // status
            jmsRow.createCell(5).setCellValue(50);                 // delayMs
            jmsRow.createCell(6).setCellValue("");                 // maxDelayMs
            jmsRow.createCell(7).setCellValue("//OrderType=VIP");  // bodyCondition
            jmsRow.createCell(8).setCellValue("");                 // queryCondition
            jmsRow.createCell(9).setCellValue("");                 // headerCondition
            jmsRow.createCell(10).setCellValue("<response><status>OK</status></response>"); // responseBody
            jmsRow.createCell(11).setCellValue("JMS 訂單回應範例"); // description
            jmsRow.createCell(12).setCellValue("true");            // enabled
            jmsRow.createCell(13).setCellValue("NONE");            // faultType
            jmsRow.createCell(14).setCellValue("");                // scenarioName
            jmsRow.createCell(15).setCellValue("");                // requiredScenarioState
            jmsRow.createCell(16).setCellValue("");                // newScenarioState

            // Auto-size columns
            for (int i = 0; i < RULE_HEADERS.length; i++) {
                sheet.autoSizeColumn(i);
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate template", e);
        }
    }

    public List<Object> parseExcel(MultipartFile file) throws Exception {
        List<Object> rules = new ArrayList<>();

        try (InputStream is = file.getInputStream(); Workbook workbook = WorkbookFactory.create(is)) {
            Sheet sheet = workbook.getSheetAt(0);
            Map<String, Integer> headerMap = parseHeader(sheet.getRow(0));

            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null || isEmptyRow(row)) {
                    continue;
                }

                String protocol = getCellString(row, headerMap.get("protocol")).toUpperCase(Locale.ROOT);
                if (protocol.isEmpty()) {
                    continue;
                }

                if ("HTTP".equals(protocol)) {
                    rules.add(parseHttpRule(row, headerMap));
                } else if ("JMS".equals(protocol)) {
                    rules.add(parseJmsRule(row, headerMap));
                }
            }
        }

        return rules;
    }

    private Map<String, Integer> parseHeader(Row row) {
        Map<String, Integer> map = new HashMap<>();
        if (row == null) {
            return map;
        }
        for (int i = 0; i < row.getLastCellNum(); i++) {
            Cell cell = row.getCell(i);
            if (cell != null) {
                map.put(cell.getStringCellValue().trim().toLowerCase(Locale.ROOT), i);
            }
        }
        return map;
    }

    private boolean isEmptyRow(Row row) {
        for (int i = 0; i < row.getLastCellNum(); i++) {
            Cell cell = row.getCell(i);
            if (cell != null && cell.getCellType() != CellType.BLANK) {
                String value = getCellString(row, i);
                if (!value.isEmpty()) {
                    return false;
                }
            }
        }
        return true;
    }

    private HttpRule parseHttpRule(Row row, Map<String, Integer> headerMap) {
        HttpRule rule = new HttpRule();
        rule.setTargetHost(getCellString(row, headerMap.get("targethost")));
        rule.setMatchKey(getCellString(row, headerMap.get("matchkey")));
        rule.setMethod(getCellString(row, headerMap.get("method"), "GET"));
        rule.setHttpStatus(getCellInt(row, headerMap.get("status"), 200));
        rule.setDelayMs(getCellLong(row, headerMap.get("delayms"), 0L));
        long httpMaxDelay = getCellLong(row, headerMap.get("maxdelayms"), 0L);
        if (httpMaxDelay > 0) {
            rule.setMaxDelayMs(httpMaxDelay);
        }
        rule.setBodyCondition(getCellString(row, headerMap.get("bodycondition")));
        rule.setQueryCondition(getCellString(row, headerMap.get("querycondition")));
        rule.setHeaderCondition(getCellString(row, headerMap.get("headercondition")));
        String desc = getCellString(row, headerMap.get("description"));
        rule.setDescription(desc.isEmpty() ? "[Excel匯入]" : "[Excel匯入] " + desc);
        rule.setEnabled(getCellBoolean(row, headerMap.get("enabled"), true));
        
        // Parse faultType
        String ft = getCellString(row, headerMap.get("faulttype"));
        if (!ft.isEmpty()) {
            try {
                rule.setFaultType(FaultType.valueOf(ft.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                log.warn("Invalid faultType '{}' in Excel row, using NONE", ft);
                rule.setFaultType(FaultType.NONE);
            }
        }

        // Parse scenario fields
        String scenarioName = getCellString(row, headerMap.get("scenarioname"));
        if (!scenarioName.isEmpty()) {
            rule.setScenarioName(scenarioName);
        }
        String requiredState = getCellString(row, headerMap.get("requiredscenariostate"));
        if (!requiredState.isEmpty()) {
            rule.setRequiredScenarioState(requiredState);
        }
        String newState = getCellString(row, headerMap.get("newscenariostate"));
        if (!newState.isEmpty()) {
            rule.setNewScenarioState(newState);
        }

        // 建立 Response
        String responseBody = getCellString(row, headerMap.get("responsebody"));
        if (!responseBody.isEmpty()) {
            Response response = responseRepository.save(Response.builder()
                    .description("[Excel匯入] " + rule.getMethod() + " " + rule.getMatchKey())
                    .body(responseBody)
                    .build());
            rule.setResponseId(response.getId());
        }
        return rule;
    }

    private JmsRule parseJmsRule(Row row, Map<String, Integer> headerMap) {
        JmsRule rule = new JmsRule();
        rule.setQueueName(getCellString(row, headerMap.get("matchkey"), "*"));
        rule.setDelayMs(getCellLong(row, headerMap.get("delayms"), 0L));
        long jmsMaxDelay = getCellLong(row, headerMap.get("maxdelayms"), 0L);
        if (jmsMaxDelay > 0) {
            rule.setMaxDelayMs(jmsMaxDelay);
        }
        rule.setBodyCondition(getCellString(row, headerMap.get("bodycondition")));
        String desc = getCellString(row, headerMap.get("description"));
        rule.setDescription(desc.isEmpty() ? "[Excel匯入]" : "[Excel匯入] " + desc);
        rule.setEnabled(getCellBoolean(row, headerMap.get("enabled"), true));
        
        // Parse faultType
        String jmsFt = getCellString(row, headerMap.get("faulttype"));
        if (!jmsFt.isEmpty()) {
            try {
                rule.setFaultType(FaultType.valueOf(jmsFt.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                log.warn("Invalid faultType '{}' in Excel row, using NONE", jmsFt);
                rule.setFaultType(FaultType.NONE);
            }
        }

        // Parse scenario fields
        String jmsScenarioName = getCellString(row, headerMap.get("scenarioname"));
        if (!jmsScenarioName.isEmpty()) {
            rule.setScenarioName(jmsScenarioName);
        }
        String jmsRequiredState = getCellString(row, headerMap.get("requiredscenariostate"));
        if (!jmsRequiredState.isEmpty()) {
            rule.setRequiredScenarioState(jmsRequiredState);
        }
        String jmsNewState = getCellString(row, headerMap.get("newscenariostate"));
        if (!jmsNewState.isEmpty()) {
            rule.setNewScenarioState(jmsNewState);
        }

        // 建立 Response
        String responseBody = getCellString(row, headerMap.get("responsebody"));
        if (!responseBody.isEmpty()) {
            Response response = responseRepository.save(Response.builder()
                    .description("[Excel匯入] JMS " + rule.getQueueName())
                    .body(responseBody)
                    .build());
            rule.setResponseId(response.getId());
        }
        return rule;
    }

    private String getCellString(Row row, Integer colIndex) {
        return getCellString(row, colIndex, "");
    }

    private String getCellString(Row row, Integer colIndex, String defaultValue) {
        if (colIndex == null) {
            return defaultValue;
        }
        Cell cell = row.getCell(colIndex);
        if (cell == null) {
            return defaultValue;
        }
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue().trim();
            case NUMERIC -> String.valueOf((long) cell.getNumericCellValue());
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            default -> defaultValue;
        };
    }

    private int getCellInt(Row row, Integer colIndex, int defaultValue) {
        if (colIndex == null) {
            return defaultValue;
        }
        Cell cell = row.getCell(colIndex);
        if (cell == null) {
            return defaultValue;
        }
        return switch (cell.getCellType()) {
            case NUMERIC -> (int) cell.getNumericCellValue();
            case STRING -> {
                try {
                    yield Integer.parseInt(cell.getStringCellValue().trim());
                } catch (NumberFormatException e) {
                    yield defaultValue;
                }
            }
            default -> defaultValue;
        };
    }

    private long getCellLong(Row row, Integer colIndex, long defaultValue) {
        if (colIndex == null) {
            return defaultValue;
        }
        Cell cell = row.getCell(colIndex);
        if (cell == null) {
            return defaultValue;
        }
        return switch (cell.getCellType()) {
            case NUMERIC -> (long) cell.getNumericCellValue();
            case STRING -> {
                try {
                    yield Long.parseLong(cell.getStringCellValue().trim());
                } catch (NumberFormatException e) {
                    yield defaultValue;
                }
            }
            default -> defaultValue;
        };
    }

    private boolean getCellBoolean(Row row, Integer colIndex, boolean defaultValue) {
        if (colIndex == null) {
            return defaultValue;
        }
        Cell cell = row.getCell(colIndex);
        if (cell == null) {
            return defaultValue;
        }
        return switch (cell.getCellType()) {
            case BOOLEAN -> cell.getBooleanCellValue();
            case STRING -> {
                String v = cell.getStringCellValue().trim().toLowerCase(Locale.ROOT);
                yield "true".equals(v) || "1".equals(v) || "yes".equals(v);
            }
            case NUMERIC -> cell.getNumericCellValue() != 0;
            default -> defaultValue;
        };
    }
}
