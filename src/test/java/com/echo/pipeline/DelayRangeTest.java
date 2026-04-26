package com.echo.pipeline;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 延遲範圍計算測試
 * <p>
 * 測試 AbstractMockPipeline.calculateDelay 的各種情境：
 * 固定延遲、隨機範圍、邊界條件。
 */
class DelayRangeTest {

    @Test
    void fixedDelay_whenMaxDelayIsNull() {
        long result = AbstractMockPipeline.calculateDelay(100, null);
        assertEquals(100, result);
    }

    @Test
    void fixedDelay_whenMaxDelayIsZero() {
        long result = AbstractMockPipeline.calculateDelay(100, 0L);
        assertEquals(100, result);
    }

    @Test
    void fixedDelay_whenMaxDelayLessThanMin() {
        long result = AbstractMockPipeline.calculateDelay(500, 200L);
        assertEquals(500, result);
    }

    @Test
    void fixedDelay_whenMaxDelayEqualsMin() {
        long result = AbstractMockPipeline.calculateDelay(100, 100L);
        assertEquals(100, result);
    }

    @Test
    void rangeDelay_shouldReturnValueBetweenMinAndMax() {
        long min = 100;
        long max = 500;
        for (int i = 0; i < 50; i++) {
            long result = AbstractMockPipeline.calculateDelay(min, max);
            assertTrue(result >= min, "Delay " + result + " should be >= " + min);
            assertTrue(result <= max, "Delay " + result + " should be <= " + max);
        }
    }

    @Test
    void rangeDelay_shouldShowVariation() {
        long min = 100;
        long max = 1000;
        long first = AbstractMockPipeline.calculateDelay(min, max);
        boolean hasVariation = false;
        for (int i = 0; i < 100; i++) {
            long result = AbstractMockPipeline.calculateDelay(min, max);
            if (result != first) {
                hasVariation = true;
                break;
            }
        }
        assertTrue(hasVariation, "Random delay should show variation over multiple calls");
    }

    @Test
    void fixedDelay_whenMinIsZeroAndMaxIsNull() {
        long result = AbstractMockPipeline.calculateDelay(0, null);
        assertEquals(0, result);
    }

    @Test
    void rangeDelay_whenMinIsZero() {
        long max = 100;
        for (int i = 0; i < 50; i++) {
            long result = AbstractMockPipeline.calculateDelay(0, max);
            assertTrue(result >= 0, "Delay should be >= 0");
            assertTrue(result <= max, "Delay should be <= " + max);
        }
    }
}
