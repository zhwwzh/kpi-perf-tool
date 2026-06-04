package com.shenghui56.dev.kpiperftool.framework.excel;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.ExcelWriter;
import com.alibaba.excel.write.metadata.WriteSheet;
import com.alibaba.excel.write.metadata.WriteTable;
import com.shenghui56.dev.kpiperftool.framework.sql.SqlSafetyChecker;
import com.shenghui56.dev.kpiperftool.model.bo.SqlExecContextBO;
import com.shenghui56.dev.kpiperftool.model.bo.SqlExecResultBO;
import com.shenghui56.dev.kpiperftool.model.bo.SqlTaskBO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;

@Slf4j
@Component
@RequiredArgsConstructor
public class EasyExcelStreamExporter {

    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // Excel 单元格字符上限为 32767，留 767 余量
    private static final int EXCEL_CELL_MAX = 32000;

    private final JdbcTemplate jdbcTemplate;

    public List<SqlExecResultBO> exportAllAndReturn(Path outputFile,
            List<SqlTaskBO> tasks,
            SqlExecContextBO ctx) throws Exception {
        Files.createDirectories(outputFile.getParent());

        log.info(
                "[exportAllAndReturn] start. outputFile={}, tasks={}, ctx(fetchSize={}, writeBatchSize={}, timeoutSec={}, allowWrite={})",
                outputFile.toAbsolutePath(), tasks == null ? 0 : tasks.size(),
                ctx.getFetchSize(), ctx.getWriteBatchSize(), ctx.getQueryTimeoutSeconds(), ctx.isAllowWrite());

        List<SqlExecResultBO> results = new ArrayList<>(tasks.size());

        long writerStart = System.currentTimeMillis();
        try (ExcelWriter writer = EasyExcel.write(outputFile.toFile()).build()) {
            log.info("[exportAllAndReturn] ExcelWriter opened. cost={}ms", System.currentTimeMillis() - writerStart);

            for (SqlTaskBO task : tasks) {
                results.add(exportOne(writer, task, ctx));
            }

            log.info("[exportAllAndReturn] data sheets done. results={}. build summary...", results.size());
            List<List<Object>> summary = buildSummaryDataWithHead(results);

            log.info("[exportAllAndReturn] summary built. rows={}. write summary...", summary.size());
            writeSummary(writer, summary);
            log.info("[exportAllAndReturn] summary written.");
        } catch (Exception e) {
            log.error("[exportAllAndReturn] writer lifecycle failed. outputFile={}", outputFile.toAbsolutePath(), e);
            throw e;
        }

        log.info("[exportAllAndReturn] Excel generated (data + summary): {}", outputFile.toAbsolutePath());
        postCheckFile(outputFile);
        return results;
    }

    private List<List<Object>> buildSummaryDataWithHead(List<SqlExecResultBO> results) {
        List<List<Object>> data = new ArrayList<>(results.size() + 1);
        data.add(List.of("index", "sheet", "success", "rowCount", "costMillis", "error"));
        for (SqlExecResultBO r : results) {
            data.add(List.of(
                    r.getIndex(),
                    r.getSheetName(),
                    r.isSuccess(),
                    r.getRowCount(),
                    r.getCostMillis(),
                    r.getErrorMessage() == null ? "" : r.getErrorMessage()));
        }
        return data;
    }

    private SqlExecResultBO exportOne(ExcelWriter writer, SqlTaskBO task, SqlExecContextBO ctx) {
        String sql = task.getSql();
        String sheetName = task.getSheetName();

        long start = System.currentTimeMillis();
        log.info("[exportOne] start. index={}, sheetName={}", task.getIndex(), sheetName);

        WriteSheet writeSheet = EasyExcel.writerSheet(sheetName).build();
        WriteTable metaTable = EasyExcel.writerTable(0).needHead(false).build();

        // ① 先写完整 SQL meta（无论成功失败，sheet 都先有 SQL 内容；超长 SQL 截断避免触达 Excel 单元格上限）
        List<List<String>> metaRows = new ArrayList<>();
        metaRows.add(List.of(safeTruncate(sql, EXCEL_CELL_MAX)));
        metaRows.add(List.of(""));
        writer.write(metaRows, writeSheet, metaTable);

        try {
            // ② 安全校验
            SqlSafetyChecker.checkOrThrow(sql, ctx.isAllowWrite());

            // ③ 写数据
            int rowCount = streamQueryAndWrite(writer, writeSheet, sql, ctx);

            long cost = System.currentTimeMillis() - start;
            return SqlExecResultBO.builder()
                    .index(task.getIndex())
                    .sheetName(sheetName)
                    .success(true)
                    .rowCount(rowCount)
                    .costMillis(cost)
                    .errorMessage("")
                    .build();

        } catch (Exception e) {
            long cost = System.currentTimeMillis() - start;

            // 提取根因消息，避免被 Spring 包装层（如 BadSqlGrammarException）淹没
            String rootMsg = rootCauseMessage(e);

            // 控制台留痕：完整堆栈含 Caused by 链，方便排障
            log.error("[exportOne] SQL failed. index={}, sheet={}, rootCause={}",
                    task.getIndex(), sheetName, rootMsg, e);

            // 失败仅追加 ERROR 行（SQL 已由 metaRows 写入，避免重复）
            try {
                writer.write(
                        List.of(List.of("ERROR: " + safeTruncate(rootMsg, 500))),
                        writeSheet, metaTable);
            } catch (Exception writeErr) {
                log.warn("[exportOne] write error row failed. sheet={}, originalError={}",
                        sheetName, e.toString(), writeErr);
            }

            return SqlExecResultBO.builder()
                    .index(task.getIndex())
                    .sheetName(sheetName)
                    .success(false)
                    .rowCount(0)
                    .costMillis(cost)
                    .errorMessage(safeTruncate(rootMsg, 200))
                    .build();
        }
    }

    private static String rootCauseMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String msg = cur.getMessage();
        return msg != null ? msg : cur.toString();
    }

    /**
     * 真流式：ResultSet 一行一行读，分批写入 Excel。
     */
    private int streamQueryAndWrite(ExcelWriter writer,
            WriteSheet writeSheet,
            String sql,
            SqlExecContextBO ctx) throws Exception {

        long start = System.currentTimeMillis();
        log.debug("[streamQueryAndWrite] start. fetchSize={}, batchSize={}, timeoutSec={}",
                ctx.getFetchSize(), ctx.getWriteBatchSize(), ctx.getQueryTimeoutSeconds());

        return jdbcTemplate.execute((Connection conn) -> {
            long connStart = System.currentTimeMillis();
            log.debug("[streamQueryAndWrite] got connection. cost={}ms, autoCommit={}",
                    (System.currentTimeMillis() - connStart), safeGetAutoCommit(conn));

            try (PreparedStatement ps = conn.prepareStatement(
                    sql,
                    ResultSet.TYPE_FORWARD_ONLY,
                    ResultSet.CONCUR_READ_ONLY)) {

                ps.setQueryTimeout(ctx.getQueryTimeoutSeconds());
                ps.setFetchDirection(ResultSet.FETCH_FORWARD);
                ps.setFetchSize(ctx.getFetchSize());

                log.debug("[streamQueryAndWrite] statement prepared. timeoutSec={}, fetchSize={}",
                        ctx.getQueryTimeoutSeconds(), ctx.getFetchSize());

                long execStart = System.currentTimeMillis();
                try (ResultSet rs = ps.executeQuery()) {
                    log.debug("[streamQueryAndWrite] query executed. cost={}ms",
                            System.currentTimeMillis() - execStart);

                    ResultSetMetaData md = rs.getMetaData();
                    int colCount = md.getColumnCount();
                    log.debug("[streamQueryAndWrite] result meta. colCount={}", colCount);

                    // 动态表头
                    List<List<String>> head = new ArrayList<>(colCount);
                    for (int c = 1; c <= colCount; c++) {
                        String label = md.getColumnLabel(c);
                        head.add(List.of(label == null ? ("COL_" + c) : label));
                    }
                    log.debug("[streamQueryAndWrite] head built. headCols={}", head.size());

                    WriteTable dataTable = EasyExcel.writerTable(1).head(head).build();

                    List<List<Object>> batch = new ArrayList<>(ctx.getWriteBatchSize());
                    int total = 0;
                    int flushTimes = 0;

                    long loopStart = System.currentTimeMillis();
                    while (rs.next()) {
                        List<Object> row = new ArrayList<>(colCount);
                        for (int c = 1; c <= colCount; c++) {
                            Object raw;
                            try {
                                raw = rs.getObject(c);
                            } catch (Exception ex) {
                                log.error("[streamQueryAndWrite] rs.getObject failed. colIndex={}, colLabel={}, sql={}",
                                        c, safeColLabel(md, c), safeTruncate(sql, 240), ex);
                                throw ex;
                            }

                            Object v;
                            try {
                                v = normalizeCellValue(raw);
                            } catch (Exception ex) {
                                log.error(
                                        "[streamQueryAndWrite] normalizeCellValue failed. colIndex={}, colLabel={}, rawClass={}, rawValuePreview={}, sql={}",
                                        c, safeColLabel(md, c),
                                        raw == null ? "null" : raw.getClass().getName(),
                                        raw == null ? "null" : safeTruncate(String.valueOf(raw), 120),
                                        safeTruncate(sql, 240),
                                        ex);
                                throw ex;
                            }

                            row.add(v);
                        }
                        batch.add(row);
                        total++;

                        // 长查询进度提示（DEBUG 级），每 1 万行一次
                        if (total % 10000 == 0) {
                            log.debug("[streamQueryAndWrite] progress. rowsSoFar={}, elapsed={}ms",
                                    total, System.currentTimeMillis() - loopStart);
                        }

                        if (batch.size() >= ctx.getWriteBatchSize()) {
                            long flushStart = System.currentTimeMillis();
                            writer.write(batch, writeSheet, dataTable);
                            flushTimes++;
                            log.debug(
                                    "[streamQueryAndWrite] batch flushed. flushTimes={}, batchSize={}, cost={}ms, total={}",
                                    flushTimes, batch.size(), System.currentTimeMillis() - flushStart, total);
                            batch.clear();
                        }
                    }

                    if (!batch.isEmpty()) {
                        long flushStart = System.currentTimeMillis();
                        writer.write(batch, writeSheet, dataTable);
                        flushTimes++;
                        log.debug(
                                "[streamQueryAndWrite] last batch flushed. flushTimes={}, batchSize={}, cost={}ms, total={}",
                                flushTimes, batch.size(), System.currentTimeMillis() - flushStart, total);
                        batch.clear();
                    }

                    log.info(
                            "[streamQueryAndWrite] done. totalRows={}, flushTimes={}, readLoopCost={}ms, totalCost={}ms",
                            total, flushTimes,
                            (System.currentTimeMillis() - loopStart),
                            (System.currentTimeMillis() - start));

                    return total;
                }
            }
        });
    }

    /**
     * 关键修复点：
     * EasyExcel 在“无模型 + List<List<Object>>”写入时，对部分 JDBC 类型缺少默认 Converter。
     * 这里把日期时间类统一转成 String，保证跨版本稳定。
     */
    private Object normalizeCellValue(Object v) {
        if (v == null) {
            return null;
        }

        // JDBC 时间类型
        if (v instanceof java.sql.Date d) {
            return d.toLocalDate().toString(); // yyyy-MM-dd
        }
        if (v instanceof java.sql.Time t) {
            return t.toLocalTime().toString(); // HH:mm:ss
        }
        if (v instanceof java.sql.Timestamp ts) {
            // yyyy-MM-dd HH:mm:ss.SSS
            return TS_FMT.format(ts.toLocalDateTime());
        }

        // 兜底：util.Date（极少数驱动/列类型会返回）
        if (v instanceof java.util.Date ud) {
            // yyyy-MM-dd HH:mm:ss
            return DT_FMT.format(ud.toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDateTime());
        }

        // byte[] 等二进制：避免写入后变成不可读对象（可按需改成 Base64/Hex）
        if (v instanceof byte[] bytes) {
            return "0x" + toHex(bytes, 64); // 只截断前 64 bytes 的 hex
        }

        // 其他类型保持原样（String/Number/BigDecimal/Boolean 等）
        return v;
    }

    private String toHex(byte[] bytes, int maxBytes) {
        int len = Math.min(bytes.length, Math.max(0, maxBytes));
        StringBuilder sb = new StringBuilder(len * 2);
        for (int i = 0; i < len; i++) {
            int b = bytes[i] & 0xFF;
            char hi = Character.forDigit((b >>> 4) & 0x0F, 16);
            char lo = Character.forDigit(b & 0x0F, 16);
            sb.append(hi).append(lo);
        }
        if (bytes.length > len) {
            sb.append("...(len=").append(bytes.length).append(")");
        }
        return sb.toString();
    }

    private String safeColLabel(ResultSetMetaData md, int idx) {
        try {
            String s = md.getColumnLabel(idx);
            return s == null ? ("COL_" + idx) : s;
        } catch (Exception e) {
            return "COL_" + idx;
        }
    }

    private void writeSummary(ExcelWriter writer, List<List<Object>> summaryDataWithHead) {
        int rows = summaryDataWithHead == null ? 0 : summaryDataWithHead.size();
        log.info("[writeSummary] start. rows={}", rows);

        WriteSheet sheet = EasyExcel.writerSheet("summary").build();
        WriteTable table = EasyExcel.writerTable(0).needHead(false).build();

        long start = System.currentTimeMillis();
        writer.write(summaryDataWithHead, sheet, table);
        log.info("[writeSummary] done. cost={}ms", System.currentTimeMillis() - start);
    }

    private void postCheckFile(Path outputFile) {
        try {
            if (!Files.exists(outputFile)) {
                log.error("[postCheckFile] file not exists: {}", outputFile.toAbsolutePath());
                return;
            }
            long size = Files.size(outputFile);
            log.info("[postCheckFile] file exists. path={}, size={} bytes", outputFile.toAbsolutePath(), size);

            // xlsx 本质是 zip，校验 zip 结构是否可读 + 关键 entry 是否存在
            try (ZipFile zf = new ZipFile(outputFile.toFile())) {
                boolean hasContentTypes = zf.getEntry("[Content_Types].xml") != null;
                boolean hasRels = zf.getEntry("_rels/.rels") != null;
                boolean hasWorkbook = zf.getEntry("xl/workbook.xml") != null;

                log.info("[postCheckFile] zip open OK. entriesCount={}, hasContentTypes={}, hasRels={}, hasWorkbook={}",
                        zf.size(), hasContentTypes, hasRels, hasWorkbook);

                if (!hasContentTypes || !hasRels || !hasWorkbook) {
                    log.error("[postCheckFile] invalid xlsx structure detected! path={}", outputFile.toAbsolutePath());
                }
            }
        } catch (Exception e) {
            log.error("[postCheckFile] check failed. path={}", outputFile.toAbsolutePath(), e);
        }
    }

    private boolean safeGetAutoCommit(Connection conn) {
        try {
            return conn.getAutoCommit();
        } catch (Exception e) {
            return false;
        }
    }

    private String safeTruncate(String s, int max) {
        if (s == null)
            return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
