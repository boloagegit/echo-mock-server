package com.echo.protocol.http;

/**
 * HTTP 規則路徑（matchKey）的比對規則，請求比對、規則快照的路徑索引與規則順序檢查都用這一份：
 * <ul>
 *   <li>{@code *}：所有路徑</li>
 *   <li>{@code /x/*}：以 {@code /x/} 開頭的路徑</li>
 *   <li>其他：路徑完全相同</li>
 * </ul>
 */
public final class HttpPathPattern {

    private HttpPathPattern() {
    }

    /** 規則路徑是否符合請求路徑。 */
    public static boolean matches(String pattern, String path) {
        if ("*".equals(pattern)) {
            return true;
        }
        if (pattern == null || path == null) {
            return false;
        }
        if (pattern.equals(path)) {
            return true;
        }
        return isPrefix(pattern) && path.startsWith(prefixOf(pattern));
    }

    /** 只符合與自己完全相同的請求路徑（不是 {@code *}，也不是 {@code /x/*}）。 */
    public static boolean isExact(String pattern) {
        return pattern != null && !"*".equals(pattern) && !isPrefix(pattern);
    }

    /** {@code broader} 符合的請求路徑是否包含 {@code narrower} 符合的所有請求路徑。 */
    public static boolean covers(String broader, String narrower) {
        if (broader == null || narrower == null) {
            return false;
        }
        if ("*".equals(broader)) {
            return true;
        }
        if ("*".equals(narrower)) {
            return false;
        }
        if (isPrefix(broader)) {
            String narrowerPrefix = isPrefix(narrower) ? prefixOf(narrower) : narrower;
            return narrowerPrefix.startsWith(prefixOf(broader));
        }
        return broader.equals(narrower);
    }

    private static boolean isPrefix(String pattern) {
        return pattern.endsWith("/*");
    }

    /** {@code /x/*} → {@code /x/} */
    private static String prefixOf(String pattern) {
        return pattern.substring(0, pattern.length() - 1);
    }
}
