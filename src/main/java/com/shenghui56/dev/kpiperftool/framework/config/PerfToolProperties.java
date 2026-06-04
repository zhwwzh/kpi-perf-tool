package com.shenghui56.dev.kpiperftool.framework.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "perf")
public class PerfToolProperties {

    /**
     * 输入 SQL 文件路径（本地目录）
     * 示例：/data/sql/perf.sql
     */
    private String inputFile;

    /**
     * 输出目录（本地目录）
     * 示例：/data/output
     */
    private String outputDir;

    /**
     * 输出文件名前缀
     * 示例：perf_kpi
     */
    private String outputFilePrefix = "perf_kpi";

    /**
     * 是否允许写操作（默认 false）
     * - false：只允许只读 SQL（SELECT/SHOW/DESCRIBE/EXPLAIN/WITH）
     * - true：允许写（强烈建议仅在隔离环境使用）
     */
    private boolean allowWrite = false;

    /**
     * Excel sheet 名最大长度（Excel 限制最大 31）
     * 注意：同时还需过滤非法字符：:/\?*[]
     */
    private int sheetNameMaxLen = 31;

    // ===== 流式与性能参数 =====

    /**
     * JDBC fetchSize：每次从服务端抓取的行数（用于流式读取 ResultSet）
     *
     * MySQL 建议：
     * - JDBC URL 增加 useCursorFetch=true
     * - 对大结果集建议适当调大/调小（例如 500~5000）
     */
    private int fetchSize = 1000;

    /**
     * EasyExcel 每次写入的行数（批量 flush）
     * - 值越大：写入调用次数少，但每批内存占用更高
     * - 值越小：内存占用更稳，但写入次数增多
     */
    private int writeBatchSize = 1000;

    /**
     * 单条 SQL 超时时间（秒）
     * 通过 PreparedStatement#setQueryTimeout 生效（依赖驱动实现）
     */
    private int queryTimeoutSeconds = 300;
}
