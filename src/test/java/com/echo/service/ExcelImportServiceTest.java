package com.echo.service;

import com.echo.entity.HttpRule;
import com.echo.entity.JmsRule;
import com.echo.entity.Response;
import com.echo.repository.ResponseRepository;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class ExcelImportServiceTest {

    @Mock
    private ResponseRepository responseRepository;

    private ExcelImportService service;

    @BeforeEach
    void setUp() {
        service = new ExcelImportService(responseRepository);
        AtomicLong responseIdCounter = new AtomicLong(1);
        when(responseRepository.save(any(Response.class))).thenAnswer(inv -> {
            Response r = inv.getArgument(0);
            return Response.builder().id(responseIdCounter.getAndIncrement()).description(r.getDescription()).body(r.getBody()).build();
        });
    }

    @Test
    void generateTemplate_shouldCreateValidExcel() throws Exception {
        byte[] template = service.generateTemplate();

        assertNotNull(template);
        assertTrue(template.length > 0);

        // Verify Excel content
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(template))) {
            Sheet sheet = workbook.getSheetAt(0);
            assertEquals("Rules", sheet.getSheetName());

            // Verify header row
            Row header = sheet.getRow(0);
            assertEquals("protocol", header.getCell(0).getStringCellValue());
            assertEquals("targetHost", header.getCell(1).getStringCellValue());
            assertEquals("matchKey", header.getCell(2).getStringCellValue());

            // Verify example HTTP row
            Row httpRow = sheet.getRow(1);
            assertEquals("HTTP", httpRow.getCell(0).getStringCellValue());
            assertEquals("api.example.com", httpRow.getCell(1).getStringCellValue());

            // Verify example JMS row
            Row jmsRow = sheet.getRow(2);
            assertEquals("JMS", jmsRow.getCell(0).getStringCellValue());
        }
    }

    @Test
    void parseExcel_shouldParseHttpRule() throws Exception {
        byte[] excelData = createTestExcel("HTTP", "api.test.com", "/api/test", "POST", 201, 100,
                "userId=1", "status=active", "{\"result\":\"ok\"}", "Test HTTP Rule", true);

        MockMultipartFile file = new MockMultipartFile("file", "test.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", excelData);

        List<Object> rules = service.parseExcel(file);

        assertEquals(1, rules.size());
        assertTrue(rules.get(0) instanceof HttpRule);

        HttpRule rule = (HttpRule) rules.get(0);
        assertEquals("api.test.com", rule.getTargetHost());
        assertEquals("/api/test", rule.getMatchKey());
        assertEquals("POST", rule.getMethod());
        assertEquals(201, rule.getHttpStatus());
        assertEquals(100L, rule.getDelayMs());
        assertEquals("userId=1", rule.getBodyCondition());
        assertEquals("status=active", rule.getQueryCondition());
        assertNotNull(rule.getResponseId()); // Response created via responseId
        assertEquals("[Excel匯入] Test HTTP Rule", rule.getDescription());
        assertTrue(rule.getEnabled());
    }

    @Test
    void parseExcel_shouldParseJmsRule() throws Exception {
        byte[] excelData = createTestExcel("JMS", "", "*", "", 0, 50,
                "//OrderType=VIP", "", "<response>OK</response>", "Test JMS Rule", true);

        MockMultipartFile file = new MockMultipartFile("file", "test.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", excelData);

        List<Object> rules = service.parseExcel(file);

        assertEquals(1, rules.size());
        assertTrue(rules.get(0) instanceof JmsRule);

        JmsRule rule = (JmsRule) rules.get(0);
        assertEquals("*", rule.getQueueName());
        assertEquals(50L, rule.getDelayMs());
        assertEquals("//OrderType=VIP", rule.getBodyCondition());
        assertNotNull(rule.getResponseId()); // Response created via responseId
        assertEquals("[Excel匯入] Test JMS Rule", rule.getDescription());
        assertTrue(rule.getEnabled());
    }

    @Test
    void parseExcel_shouldSkipEmptyRows() throws Exception {
        byte[] excelData = createExcelWithEmptyRow();

        MockMultipartFile file = new MockMultipartFile("file", "test.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", excelData);

        List<Object> rules = service.parseExcel(file);

        assertEquals(1, rules.size());
    }

    @Test
    void parseExcel_shouldHandleDefaultValues() throws Exception {
        // Create Excel with minimal data
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Rules");

            // Header
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("protocol");
            header.createCell(2).setCellValue("matchKey");

            // Data row with only required fields
            Row dataRow = sheet.createRow(1);
            dataRow.createCell(0).setCellValue("HTTP");
            dataRow.createCell(2).setCellValue("/api/minimal");

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);

            MockMultipartFile file = new MockMultipartFile("file", "test.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", out.toByteArray());

            List<Object> rules = service.parseExcel(file);

            assertEquals(1, rules.size());
            HttpRule rule = (HttpRule) rules.get(0);
            assertEquals("/api/minimal", rule.getMatchKey());
            assertEquals("GET", rule.getMethod()); // default
            assertEquals(200, rule.getHttpStatus()); // default
            assertEquals(0L, rule.getDelayMs()); // default
            assertTrue(rule.getEnabled()); // default
        }
    }

    @Test
    void parseExcel_shouldHandleBooleanValues() throws Exception {
        // Test various boolean representations
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Rules");

            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("protocol");
            header.createCell(2).setCellValue("matchKey");
            header.createCell(10).setCellValue("enabled");

            // Row with "false" string
            Row row1 = sheet.createRow(1);
            row1.createCell(0).setCellValue("HTTP");
            row1.createCell(2).setCellValue("/api/test1");
            row1.createCell(10).setCellValue("false");

            // Row with "1" string
            Row row2 = sheet.createRow(2);
            row2.createCell(0).setCellValue("HTTP");
            row2.createCell(2).setCellValue("/api/test2");
            row2.createCell(10).setCellValue("1");

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);

            MockMultipartFile file = new MockMultipartFile("file", "test.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", out.toByteArray());

            List<Object> rules = service.parseExcel(file);

            assertEquals(2, rules.size());
            assertFalse(((HttpRule) rules.get(0)).getEnabled());
            assertTrue(((HttpRule) rules.get(1)).getEnabled());
        }
    }

    private byte[] createTestExcel(String protocol, String targetHost, String matchKey, String method,
                                   int status, int delayMs, String bodyCondition, String queryCondition,
                                   String responseBody, String description, boolean enabled) throws Exception {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Rules");

            // Header
            Row header = sheet.createRow(0);
            String[] headers = {"protocol", "targetHost", "matchKey", "method", "status", "delayMs",
                    "bodyCondition", "queryCondition", "headerCondition", "responseBody", "description", "enabled"};
            for (int i = 0; i < headers.length; i++) {
                header.createCell(i).setCellValue(headers[i]);
            }

            // Data row
            Row dataRow = sheet.createRow(1);
            dataRow.createCell(0).setCellValue(protocol);
            dataRow.createCell(1).setCellValue(targetHost);
            dataRow.createCell(2).setCellValue(matchKey);
            dataRow.createCell(3).setCellValue(method);
            dataRow.createCell(4).setCellValue(status);
            dataRow.createCell(5).setCellValue(delayMs);
            dataRow.createCell(6).setCellValue(bodyCondition);
            dataRow.createCell(7).setCellValue(queryCondition);
            dataRow.createCell(8).setCellValue(""); // headerCondition
            dataRow.createCell(9).setCellValue(responseBody);
            dataRow.createCell(10).setCellValue(description);
            dataRow.createCell(11).setCellValue(String.valueOf(enabled));

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            return out.toByteArray();
        }
    }

    private byte[] createExcelWithEmptyRow() throws Exception {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Rules");

            // Header
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("protocol");
            header.createCell(2).setCellValue("matchKey");

            // Empty row
            sheet.createRow(1);

            // Data row
            Row dataRow = sheet.createRow(2);
            dataRow.createCell(0).setCellValue("HTTP");
            dataRow.createCell(2).setCellValue("/api/test");

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            return out.toByteArray();
        }
    }
}
