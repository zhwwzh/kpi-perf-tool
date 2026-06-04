package com.shenghui56.dev.kpiperftool.framework.sql;

import com.shenghui56.dev.kpiperftool.model.bo.SqlTaskBO;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQL 任务解析器（无状态）
 *
 * 职责：
 * 1) 从完整 SQL 文本中拆分多段 SQL
 * 2) 解析 name 元信息（-- name: xxx / /* name: xxx *\/）
 * 3) 从 SQL 正文中移除 name 注释，得到“纯 SQL”
 *
 * 不负责：
 * - sheetName 生成（由 SheetNameResolver 统一处理）
 */
public final class SqlTaskParser {

    /** 支持：-- name: xxx（整行） */
    private static final Pattern LINE_NAME = Pattern.compile("(?im)^\\s*--\\s*name\\s*:\\s*(.+?)\\s*$");

    /** 支持：/* name: xxx *\/ */
    private static final Pattern BLOCK_NAME = Pattern.compile("(?is)/\\*\\s*name\\s*:\\s*(.+?)\\s*\\*/");

    private SqlTaskParser() {
        // utility class
    }

    /**
     * 主入口：从完整 SQL 文件文本解析
     */
    public static List<SqlTaskBO> parse(String sqlText) {
        if (sqlText == null || sqlText.isBlank()) {
            return List.of();
        }

        // 使用状态机切分（识别引号/注释内的分号），SqlSplitter 已保留注释文本以便提取 name
        List<String> segments = SqlSplitter.split(sqlText);
        List<SqlTaskBO> tasks = new ArrayList<>();

        int index = 1;
        for (String rawSql : segments) {
            if (rawSql.isBlank()) {
                continue;
            }

            String name = extractName(rawSql);
            String cleanSql = cleanSqlBody(rawSql);

            tasks.add(SqlTaskBO.builder()
                    .index(index++)
                    .name(name)
                    .sql(cleanSql)
                    .build());
        }
        return tasks;
    }

    /**
     * 从 SQL 中提取 name 元信息
     */
    private static String extractName(String sql) {
        if (sql == null || sql.isBlank()) {
            return null;
        }

        // 1) 行注释优先
        Matcher m1 = LINE_NAME.matcher(sql);
        if (m1.find()) {
            return cleanName(m1.group(1));
        }

        // 2) 块注释兜底
        Matcher m2 = BLOCK_NAME.matcher(sql);
        if (m2.find()) {
            return cleanName(m2.group(1));
        }

        return null;
    }

    /**
     * 清理 SQL 正文，得到可直接发给 DB 的语句：
     * 1) 剥离首部所有连续的注释/空白（含 --xxx / ---xxx / # xxx / /* ... *\/），
     *    以兼容用户用非标准前缀注释作为段落标题的情况（如 "--- 用例通过率"）。
     *    MariaDB/MySQL 严格要求 "--" 后接空白才算注释，"---xxx" 会被视为语法错误，
     *    这一步避免把这类字符串发给 DB。
     * 2) 删除 SQL 中任意位置的 -- name: xxx 行和 /* name: xxx *\/ 块（保留原行为）
     */
    private static String cleanSqlBody(String sql) {
        if (sql == null || sql.isBlank()) {
            return sql;
        }

        String s = stripLeadingComments(sql);
        s = s.replaceAll("(?im)^\\s*--\\s*name\\s*:\\s*.+?$", "");
        s = s.replaceAll("(?is)/\\*\\s*name\\s*:\\s*.+?\\*/", "");
        return s.trim();
    }

    /**
     * 从字符串开头跳过所有连续的注释/空白，返回剩余部分。
     * 支持：空白、-- 行注释（任意 - 数量开头）、# 行注释、/* ... *\/ 块注释。
     * 块注释未闭合时原样保留（让后续语法报错暴露）。
     */
    private static String stripLeadingComments(String sql) {
        int i = 0;
        int n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                int end = sql.indexOf("*/", i + 2);
                if (end < 0) {
                    return sql.substring(i);
                }
                i = end + 2;
                continue;
            }
            if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                int end = sql.indexOf('\n', i);
                if (end < 0) {
                    return "";
                }
                i = end + 1;
                continue;
            }
            if (c == '#') {
                int end = sql.indexOf('\n', i);
                if (end < 0) {
                    return "";
                }
                i = end + 1;
                continue;
            }
            return sql.substring(i);
        }
        return "";
    }

    private static String cleanName(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
