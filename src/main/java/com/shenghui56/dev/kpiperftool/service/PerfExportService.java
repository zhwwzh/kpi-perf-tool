package com.shenghui56.dev.kpiperftool.service;

import com.shenghui56.dev.kpiperftool.framework.excel.EasyExcelStreamExporter;
import com.shenghui56.dev.kpiperftool.framework.excel.SheetNameResolver;
import com.shenghui56.dev.kpiperftool.model.bo.SqlExecContextBO;
import com.shenghui56.dev.kpiperftool.model.bo.SqlExecResultBO;
import com.shenghui56.dev.kpiperftool.model.bo.SqlTaskBO;
import com.shenghui56.dev.kpiperftool.framework.sql.SqlTaskParser;
import com.shenghui56.dev.kpiperftool.framework.config.PerfToolProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 导出执行入口：
 * 1) 读取 SQL 文件（支持 classpath:/ 和相对路径）
 * 2) 解析为 SqlTaskBO 列表（name/sql/index）
 * 3) sheetName = SQL 文件中的 name（做清洗 + 31 限制 + 去重）
 * 4) 调用 EasyExcel 流式导出：多 sheet + summary
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PerfExportService {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private final PerfToolProperties properties;
    private final ResourceLoader resourceLoader;
    private final EasyExcelStreamExporter exporter;

    /**
     * 应用启动后直接执行（你当前工程就是 main 里触发）
     */
    public void runOnce() throws Exception {
        long runStartMs = System.currentTimeMillis();

        log.info("[PerfConfig] inputFile={}", properties.getInputFile());
        log.info("[PerfConfig] outputDir={}", properties.getOutputDir());
        log.info("[PerfConfig] allowWrite={}", properties.isAllowWrite());
        log.info("[PerfConfig] fetchSize={}, writeBatchSize={}, timeoutSec={}",
                properties.getFetchSize(),
                properties.getWriteBatchSize(),
                properties.getQueryTimeoutSeconds());

        // 1) 读取 SQL 文件内容
        String sqlText = loadSqlText(properties.getInputFile());

        // 2) 解析任务（name/sql/index）
        List<SqlTaskBO> tasks = SqlTaskParser.parse(sqlText);
        log.info("[PerfExport] parsed tasks={}", tasks.size());

        // 3) sheetName = name（清洗/截断/去重，遵循 Excel 31 限制）
        int maxLen = properties.getSheetNameMaxLen() <= 0 ? 31 : properties.getSheetNameMaxLen();
        SheetNameResolver.resolve(tasks, maxLen);
        log.info("[PerfExport] sheet names resolved. maxLen={}", maxLen);

        // 4) 构造上下文（流式参数）
        SqlExecContextBO ctx = SqlExecContextBO.builder()
                .allowWrite(properties.isAllowWrite())
                .fetchSize(properties.getFetchSize())
                .writeBatchSize(properties.getWriteBatchSize())
                .queryTimeoutSeconds(properties.getQueryTimeoutSeconds())
                .build();

        // 5) 输出文件路径
        Path outputFile = buildOutputFilePath();
        log.info("[PerfExport] outputFile={}", outputFile.toAbsolutePath());

        // 6) 导出（多 sheet + summary）
        List<SqlExecResultBO> results = exporter.exportAllAndReturn(outputFile, tasks, ctx);

        // 7) 汇总日志：ASCII 总览表 + 单行收尾
        long elapsedMs = System.currentTimeMillis() - runStartMs;
        long success = results.stream().filter(SqlExecResultBO::isSuccess).count();
        long fail = results.size() - success;
        log.info("\n{}", renderSummaryTable(results, outputFile, elapsedMs, success, fail));
        log.info("[PerfExport] Export done. outputFile={}, total={}, success={}, fail={}",
                outputFile.toAbsolutePath(), results.size(), success, fail);
    }

    // ===== ASCII 总览表 =====

    private static final int COL_NUM = 4;
    private static final int COL_SHEET = 32;
    private static final int COL_STATUS = 8;
    private static final int COL_ROWS = 9;
    private static final int COL_COST = 10;
    private static final int COL_ERROR = 40;

    private static String renderSummaryTable(List<SqlExecResultBO> results,
                                             Path outputFile,
                                             long elapsedMs,
                                             long success,
                                             long fail) {
        String sep = "=".repeat(COL_NUM + COL_SHEET + COL_STATUS + COL_ROWS + COL_COST + COL_ERROR + 13);
        StringBuilder sb = new StringBuilder();
        sb.append(sep).append('\n');
        sb.append("                                            Export Summary\n");
        sb.append(sep).append('\n');
        sb.append("| ").append(padOrTrunc("#", COL_NUM))
          .append(" | ").append(padOrTrunc("Sheet", COL_SHEET))
          .append(" | ").append(padOrTrunc("Status", COL_STATUS))
          .append(" | ").append(padOrTrunc("Rows", COL_ROWS))
          .append(" | ").append(padOrTrunc("Cost(ms)", COL_COST))
          .append(" | ").append(padOrTrunc("Error", COL_ERROR))
          .append(" |\n");
        sb.append('|').append("-".repeat(COL_NUM + 2))
          .append('|').append("-".repeat(COL_SHEET + 2))
          .append('|').append("-".repeat(COL_STATUS + 2))
          .append('|').append("-".repeat(COL_ROWS + 2))
          .append('|').append("-".repeat(COL_COST + 2))
          .append('|').append("-".repeat(COL_ERROR + 2))
          .append("|\n");
        for (SqlExecResultBO r : results) {
            sb.append("| ").append(padOrTrunc(String.valueOf(r.getIndex()), COL_NUM))
              .append(" | ").append(padOrTrunc(r.getSheetName(), COL_SHEET))
              .append(" | ").append(padOrTrunc(r.isSuccess() ? "SUCCESS" : "FAIL", COL_STATUS))
              .append(" | ").append(padOrTrunc(String.valueOf(r.getRowCount()), COL_ROWS))
              .append(" | ").append(padOrTrunc(String.valueOf(r.getCostMillis()), COL_COST))
              .append(" | ").append(padOrTrunc(r.getErrorMessage() == null ? "" : r.getErrorMessage(), COL_ERROR))
              .append(" |\n");
        }
        sb.append(sep).append('\n');
        sb.append("Output:  ").append(outputFile.toAbsolutePath()).append('\n');
        sb.append("Totals:  ").append(results.size())
          .append("  |  Success: ").append(success)
          .append("  |  Fail: ").append(fail)
          .append("  |  Elapsed: ").append(elapsedMs).append("ms\n");
        sb.append(sep);
        return sb.toString();
    }

    /** 按视觉宽度填充（CJK 字符算 2 列），超长用 "..." 截断 */
    private static String padOrTrunc(String s, int width) {
        if (s == null) s = "";
        int vw = visualWidth(s);
        if (vw <= width) {
            return s + " ".repeat(width - vw);
        }
        StringBuilder sb = new StringBuilder();
        int sofar = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            int cw = isWide(c) ? 2 : 1;
            if (sofar + cw > width - 3) break;
            sb.append(c);
            sofar += cw;
        }
        sb.append("...");
        int curW = visualWidth(sb.toString());
        if (curW < width) sb.append(" ".repeat(width - curW));
        return sb.toString();
    }

    private static int visualWidth(String s) {
        int w = 0;
        for (int i = 0; i < s.length(); i++) {
            w += isWide(s.charAt(i)) ? 2 : 1;
        }
        return w;
    }

    private static boolean isWide(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF)   // CJK Unified Ideographs
            || (c >= 0x3000 && c <= 0x303F)   // CJK Symbols and Punctuation
            || (c >= 0xFF00 && c <= 0xFFEF);  // Halfwidth/Fullwidth Forms
    }

    /**
     * 读取 SQL 文件：
     * - classpath:/data/sql/perf.sql
     * - classpath:data/sql/perf.sql
     * - 相对路径（相对于 user.dir）
     * - 绝对路径
     */
    private String loadSqlText(String input) throws Exception {
        if (!StringUtils.hasText(input)) {
            throw new IllegalArgumentException("perf.inputFile is blank");
        }

        String raw = input.trim();

        // 1) classpath
        if (raw.startsWith("classpath:")) {
            String location = raw; // Spring ResourceLoader 直接支持
            Resource res = resourceLoader.getResource(location);
            if (!res.exists()) {
                throw new IllegalArgumentException("Input file not found on classpath: " + raw);
            }
            try (InputStream in = res.getInputStream()) {
                byte[] bytes = in.readAllBytes();
                String text = new String(bytes, StandardCharsets.UTF_8);
                log.info("[PerfExport] loaded sql from classpath. location={}, bytes={}", raw, bytes.length);
                return text;
            }
        }

        // 2) filesystem：相对路径 / 绝对路径
        Path p = Paths.get(raw);
        if (!p.isAbsolute()) {
            p = Paths.get(System.getProperty("user.dir")).resolve(raw).normalize();
        }

        if (!Files.exists(p)) {
            throw new IllegalArgumentException("Input file not found: " + p.toAbsolutePath());
        }

        byte[] bytes = Files.readAllBytes(p);
        String text = new String(bytes, StandardCharsets.UTF_8);
        log.info("[PerfExport] loaded sql from file. path={}, bytes={}", p.toAbsolutePath(), bytes.length);
        return text;
    }

    private Path buildOutputFilePath() throws Exception {
        String outDirRaw = properties.getOutputDir();
        if (!StringUtils.hasText(outDirRaw)) {
            throw new IllegalArgumentException("perf.outputDir is blank");
        }

        Path outDir = Paths.get(outDirRaw.trim());
        if (!outDir.isAbsolute()) {
            outDir = Paths.get(System.getProperty("user.dir")).resolve(outDir).normalize();
        }
        Files.createDirectories(outDir);

        String prefix = StringUtils.hasText(properties.getOutputFilePrefix())
                ? properties.getOutputFilePrefix().trim()
                : "perf_kpi";

        String filename = prefix + "_" + TS.format(LocalDateTime.now()) + ".xlsx";
        return outDir.resolve(filename);
    }
}
