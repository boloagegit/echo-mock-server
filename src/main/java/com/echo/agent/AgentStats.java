package com.echo.agent;

import lombok.Builder;
import lombok.Getter;

/**
 * Agent 統計資訊，包含佇列大小、已處理數、已丟棄數。
 */
@Getter
@Builder
public class AgentStats {

    private final int queueSize;
    private final long processedCount;
    private final long droppedCount;
}
