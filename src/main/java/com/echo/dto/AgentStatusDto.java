package com.echo.dto;

import lombok.Builder;
import lombok.Getter;

/**
 * Agent 狀態資料傳輸物件，用於 Admin API 回傳。
 */
@Getter
@Builder
public class AgentStatusDto {

    private final String name;
    private final String description;
    private final String status;
    private final int queueSize;
    private final long processedCount;
    private final long droppedCount;
}
