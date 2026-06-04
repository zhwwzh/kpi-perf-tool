package com.shenghui56.dev.kpiperftool.framework.sql;

import java.util.ArrayList;
import java.util.List;

public final class SqlSplitter {

    private SqlSplitter() {
    }

    /**
     * 将包含多条 SQL 的文本切分为单条 SQL 列表。
     * 支持处理：单引号/双引号/反引号、-- 注释、/* *\/ 注释、以及 SQL 内部的分号。
     */
    public static List<String> split(String content) {
        List<String> result = new ArrayList<>();
        if (content == null || content.isBlank()) {
            return result;
        }

        StringBuilder sb = new StringBuilder();
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        boolean inBacktick = false;

        boolean inLineComment = false;
        boolean inBlockComment = false;

        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            char next = (i + 1 < content.length()) ? content.charAt(i + 1) : '\0';

            // 结束行注释（保留注释字符，仅用状态判定分号是否生效）
            if (inLineComment) {
                sb.append(c);
                if (c == '\n') {
                    inLineComment = false;
                }
                continue;
            }

            // 结束块注释（保留注释字符）
            if (inBlockComment) {
                sb.append(c);
                if (c == '*' && next == '/') {
                    sb.append(next);
                    inBlockComment = false;
                    i++;
                }
                continue;
            }

            // 进入注释（必须在非引号中，保留 -- 与 /* 起始两字符）
            if (!inSingleQuote && !inDoubleQuote && !inBacktick) {
                if (c == '-' && next == '-') {
                    inLineComment = true;
                    sb.append(c).append(next);
                    i++;
                    continue;
                }
                if (c == '/' && next == '*') {
                    inBlockComment = true;
                    sb.append(c).append(next);
                    i++;
                    continue;
                }
            }

            // 引号状态切换（处理转义：这里按常见 MySQL/ANSI 简化，不把 \\' 等做极端兼容）
            if (c == '\'' && !inDoubleQuote && !inBacktick) {
                inSingleQuote = !inSingleQuote;
                sb.append(c);
                continue;
            }
            if (c == '"' && !inSingleQuote && !inBacktick) {
                inDoubleQuote = !inDoubleQuote;
                sb.append(c);
                continue;
            }
            if (c == '`' && !inSingleQuote && !inDoubleQuote) {
                inBacktick = !inBacktick;
                sb.append(c);
                continue;
            }

            // SQL 结束符：仅当不在引号中
            if (c == ';' && !inSingleQuote && !inDoubleQuote && !inBacktick) {
                String sql = sb.toString().trim();
                if (!sql.isBlank()) {
                    result.add(sql);
                }
                sb.setLength(0);
                continue;
            }

            sb.append(c);
        }

        // 最后一段
        String tail = sb.toString().trim();
        if (!tail.isBlank()) {
            result.add(tail);
        }
        return result;
    }
}
