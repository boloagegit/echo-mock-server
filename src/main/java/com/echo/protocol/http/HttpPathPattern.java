package com.echo.protocol.http;

/**
 * The comparison rules of HTTP rule path (matchKey), request comparison, path index of rule snapshot and rule sequence check all use this copy:
 * <ul>
 *   <li>{@code *}: all paths</li>
 *   <li>{@code /x/*}: Path  starting with {@code /x/}</li>
 *   <li>Others: The path is exactly the same as</li>
 * </ul>
 */
public final class HttpPathPattern {

    private HttpPathPattern() {
    }

    /** Whether the rule path matches the request path. */
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

    /** Only match the request path that is exactly the same as itself (not {@code *}, not {@code /x/*}). */
    public static boolean isExact(String pattern) {
        return pattern != null && !"*".equals(pattern) && !isPrefix(pattern);
    }

    /** Whether the request path matched by {@code broader} contains all the request paths matched by {@code narrower}. */
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
