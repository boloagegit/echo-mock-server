package com.echo.service;

import java.util.List;

/**
 * Database backup service interface
 * <p>
 * Implemented by SqliteBackupService; DBA is responsible for the backup of external databases.
 */
public interface BackupService {

    String backup(String trigger);

    List<? extends BackupFileInfo> listBackups();

    String getBackupPath();

    int getRetentionDays();

    /** Backup file information */
    interface BackupFileInfo {
        String name();
        long size();
        java.time.LocalDateTime time();
    }
}
