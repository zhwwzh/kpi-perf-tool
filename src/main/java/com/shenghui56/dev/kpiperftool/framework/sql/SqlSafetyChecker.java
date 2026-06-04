package com.shenghui56.dev.kpiperftool.framework.sql;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public final class SqlSafetyChecker {

    private SqlSafetyChecker() {
    }

    /**
     * 允许的只读语句起始关键词
     */
    private static final Set<String> READONLY_PREFIX = Set.of(
            "select", "with", "show", "describe", "desc", "explain");

    /**
     * 语句级写入/DDL 关键字（注意：replace 必须是 replace into，不能拦 replace(...) 函数）
     * - 这里全部按“语句级”来拦截，不做 contains 级别的粗暴扫描，避免函数名误杀
     */
    private static final Pattern WRITE_STMT_PREFIX = Pattern.compile(
            "^\\s*(?:/\\*.*?\\*/\\s*)*(?:(?:--|#).*?\\R\\s*)*" + // 允许前置注释
                    "(?i)(" +
                    "insert\\b" +
                    "|update\\b" +
                    "|delete\\b" +
                    "|merge\\b" +
                    "|replace\\s+into\\b" + // 关键：只拦 REPLACE INTO，不拦 REPLACE(
                    "|create\\b" +
                    "|alter\\b" +
                    "|drop\\b" +
                    "|truncate\\b" +
                    "|rename\\b" +
                    "|grant\\b" +
                    "|revoke\\b" +
                    ")",
            Pattern.DOTALL);

    public static void checkOrThrow(String sql, boolean allowWrite) {
        String raw = sql == null ? "" : sql;
        String normalized = normalizeForPrefix(raw);

        // 0) 空 SQL 一律拒绝（无论 allowWrite 与否）
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("SQL is empty.");
        }

        if (allowWrite) {
            return;
        }

        // 1) 必须以只读前缀开头（跳过前置注释）
        String prefix = firstKeywordSkippingComments(raw);
        if (prefix == null || prefix.isBlank()) {
            throw new IllegalArgumentException("Only read-only SQL is allowed. SQL=" + brief(raw));
        }
        String lowerPrefix = prefix.toLowerCase(Locale.ROOT);
        if (!READONLY_PREFIX.contains(lowerPrefix)) {
            throw new IllegalArgumentException(
                    "Only read-only SQL is allowed. firstKeyword=" + lowerPrefix + ", SQL=" + brief(raw));
        }

        // 2) 拦截“语句级写入/DDL”（只看语句起始处，避免 REPLACE(...) 函数误杀）
        if (WRITE_STMT_PREFIX.matcher(raw).find()) {
            throw new IllegalArgumentException("Write statement detected (not allowed). SQL=" + brief(raw));
        }
    }

    /**
     * 用于 prefix 判定的 normalize：只做空白压缩，不做 contains 扫描
     */
    private static String normalizeForPrefix(String sql) {
        return sql == null ? ""
                : sql
                        .replaceAll("[\\t\\n\\r]+", " ")
                        .replaceAll("\\s+", " ")
                        .trim();
    }

    /**
     * 取“第一个有效关键字”，跳过：
     * - /* ... *\/ 块注释
     * - -- ... 行注释
     * - # ... 行注释
     */
    private static String firstKeywordSkippingComments(String sql) {
        if (sql == null)
            return null;
        int i = 0;
        int n = sql.length();

        while (i < n) {
            // skip whitespace
            while (i < n && Character.isWhitespace(sql.charAt(i)))
                i++;
            if (i >= n)
                break;

            // skip block comment /* ... */
            if (i + 1 < n && sql.charAt(i) == '/' && sql.charAt(i + 1) == '*') {
                int end = sql.indexOf("*/", i + 2);
                if (end < 0)
                    return null; // 注释未闭合，视为非法
                i = end + 2;
                continue;
            }

            // skip line comment -- ... \n
            if (i + 1 < n && sql.charAt(i) == '-' && sql.charAt(i + 1) == '-') {
                int end = findLineEnd(sql, i + 2);
                i = end;
                continue;
            }

            // skip line comment # ... \n
            if (sql.charAt(i) == '#') {
                int end = findLineEnd(sql, i + 1);
                i = end;
                continue;
            }

            // now parse first keyword token
            int start = i;
            while (i < n) {
                char ch = sql.charAt(i);
                if (Character.isLetterOrDigit(ch) || ch == '_') {
                    i++;
                } else {
                    break;
                }
            }
            if (i > start) {
                return sql.substring(start, i);
            }

            // 如果遇到其他符号，向后继续找
            i++;
        }
        return null;
    }

    private static int findLineEnd(String s, int from) {
        int n = s.length();
        for (int i = from; i < n; i++) {
            char c = s.charAt(i);
            if (c == '\n' || c == '\r') {
                return i;
            }
        }
        return n;
    }

    private static String brief(String sql) {
        if (sql == null)
            return "";
        String s = sql.trim().replaceAll("\\s+", " ");
        return s.length() <= 200 ? s : s.substring(0, 200) + "...";
    }
}
