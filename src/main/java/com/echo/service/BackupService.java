package com.echo.service;

import java.util.List;

/**
 * 資料庫備份服務介面
 * <p>
 * 由 SqliteBackupService 實作；外部資料庫的備份由 DBA 負責。
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
