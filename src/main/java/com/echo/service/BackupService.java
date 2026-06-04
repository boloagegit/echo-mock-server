package com.echo.service;

import java.util.List;

/**
 * 資料庫備份服務介面
 * <p>
 * 由 H2BackupService 和 SqliteBackupService 分別實作。
 */
public interface BackupService {

    String backup(String trigger);

    List<? extends BackupFileInfo> listBackups();

    String getBackupPath();

    int getRetentionDays();

    /** 備份檔案資訊 */
    interface BackupFileInfo {
        String name();
        long size();
        java.time.LocalDateTime time();
    }
}
