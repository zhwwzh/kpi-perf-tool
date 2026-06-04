package com.shenghui56.dev.kpiperftool.framework.excel;

import com.shenghui56.dev.kpiperftool.model.bo.SqlTaskBO;
import lombok.experimental.UtilityClass;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@UtilityClass
public class SheetNameResolver {

    // Excel sheet name 禁止字符: : \ / ? * [ ]
    private static final String INVALID_CHARS_REGEX = "[:\\\\/?*\\[\\]]";

    public static void resolve(List<SqlTaskBO> tasks, int maxLen) {
        Map<String, Integer> counter = new HashMap<>();
        for (SqlTaskBO t : tasks) {
            String base = buildBaseName(t);
            base = sanitize(base, maxLen);

            // 去重：同名加 (2) (3) ...
            String finalName = uniqueName(base, counter, maxLen);
            t.setSheetName(finalName);
        }
    }

    private static String buildBaseName(SqlTaskBO t) {
        String name = t.getName();
        if (name == null || name.isBlank()) {
            return "SQL_" + t.getIndex();
        }
        return name.trim();
    }

    private static String sanitize(String name, int maxLen) {
        String s = name.replaceAll(INVALID_CHARS_REGEX, "_");
        s = s.replaceAll("\\s+", " ").trim();
        // Excel 不允许 sheet name 以单引号开头或结尾
        s = s.replaceAll("^'+", "").replaceAll("'+$", "");
        if (s.isEmpty())
            s = "SQL";
        if (s.length() > maxLen)
            s = s.substring(0, maxLen);
        return s;
    }

    private static String uniqueName(String base, Map<String, Integer> counter, int maxLen) {
        // 循环直到候选名不与已分配名字撞车（含与用户手写的 "xxx (2)" 撞车的情况）
        String candidate = base;
        while (true) {
            String key = candidate.toLowerCase(Locale.ROOT);
            Integer existed = counter.get(key);
            if (existed == null) {
                counter.put(key, 1);
                return candidate;
            }
            int n = existed + 1;
            counter.put(key, n);
            String suffix = " (" + n + ")";
            int keep = Math.max(0, maxLen - suffix.length());
            candidate = (base.length() > keep ? base.substring(0, keep) : base) + suffix;
        }
    }
}
