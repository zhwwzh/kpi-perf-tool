package com.shenghui56.dev.kpiperftool.cli;

import com.shenghui56.dev.kpiperftool.KPIPerfToolApplication;
import com.shenghui56.dev.kpiperftool.framework.config.PerfToolProperties;
import com.shenghui56.dev.kpiperftool.service.PerfExportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * CLI 入口 Runner
 *
 * 运行策略：
 * - 如果未显式传 perf.inputFile / perf.outputDir，则默认以 jar 所在目录为根：
 * inputFile = {jarDir}/perf.sql
 * outputDir = {jarDir}/output
 *
 * Exit Code：
 * 0 - success
 * 1 - execution failed
 * 2 - invalid arguments
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PerfCliRunner implements ApplicationRunner {

    private final PerfExportService perfExportService;
    private final PerfToolProperties properties;
    private final ConfigurableApplicationContext applicationContext;

    @Override
    public void run(ApplicationArguments args) {
        int code = doRun(args);
        // 走 SpringApplication.exit 触发 context 优雅关闭（@PreDestroy / DataSource close 等）
        int exitCode = SpringApplication.exit(applicationContext, () -> code);
        System.exit(exitCode);
    }

    private int doRun(ApplicationArguments args) {
        try {
            // 1) --help
            if (args.containsOption("help")) {
                printHelp();
                return 0;
            }

            // 2) 路径规范化：以 jarDir 为锚点（空值填默认 + 相对路径展开为绝对）
            resolvePathsAgainstJarDir(properties);

            // 3) 参数校验
            validate(properties);

            // 4) 打印最终生效配置（排障必备）
            logEffectiveConfig(properties);

            // 5) 执行
            log.info("[CLI] start perf export...");
            perfExportService.runOnce();
            log.info("[CLI] perf export finished successfully.");
            return 0;
        } catch (IllegalArgumentException e) {
            log.error("[CLI] invalid arguments: {}", e.getMessage());
            return 2;
        } catch (Exception e) {
            log.error("[CLI] execution failed", e);
            return 1;
        }
    }

    // ======================= defaults =======================

    /**
     * 以 jarDir 为锚点处理路径：
     * - 空值：填默认（inputFile -> {jarDir}/perf.sql；outputDir -> {jarDir}/output）
     * - 相对路径：按 jarDir 展开为绝对路径（避免与 user.dir 的工作目录差异）
     * - classpath: 前缀或绝对路径：原样保留
     */
    private void resolvePathsAgainstJarDir(PerfToolProperties p) {
        Path jarDir = resolveJarDir();
        log.info("[CLI] jarDir resolved: {}", jarDir.toAbsolutePath());

        String input = p.getInputFile();
        if (isBlank(input)) {
            Path defaultSql = jarDir.resolve("perf.sql");
            p.setInputFile(defaultSql.toString());
            log.info("[CLI] perf.inputFile not set. use default: {}", defaultSql.toAbsolutePath());
        } else if (!input.trim().startsWith("classpath:")) {
            Path candidate = Paths.get(input.trim());
            if (!candidate.isAbsolute()) {
                Path resolved = jarDir.resolve(candidate).normalize();
                p.setInputFile(resolved.toString());
                log.info("[CLI] perf.inputFile is relative. resolve against jarDir: {} -> {}",
                        input, resolved.toAbsolutePath());
            }
        }

        String out = p.getOutputDir();
        if (isBlank(out)) {
            Path defaultOut = jarDir.resolve("output");
            p.setOutputDir(defaultOut.toString());
            log.info("[CLI] perf.outputDir not set. use default: {}", defaultOut.toAbsolutePath());
        } else if (out.trim().startsWith("classpath:")) {
            // classpath 是 jar 内只读资源，不能写入
            throw new IllegalArgumentException(
                    "perf.outputDir does not support 'classpath:' prefix. Use a filesystem path. got=" + out);
        } else {
            Path candidate = Paths.get(out.trim());
            if (!candidate.isAbsolute()) {
                Path resolved = jarDir.resolve(candidate).normalize();
                p.setOutputDir(resolved.toString());
                log.info("[CLI] perf.outputDir is relative. resolve against jarDir: {} -> {}",
                        out, resolved.toAbsolutePath());
            }
        }
    }

    /**
     * 获取 jar 所在目录：
     * - fat jar 运行：返回 jar 文件所在目录
     * - 异常：回退到 user.dir
     */
    private Path resolveJarDir() {
        try {
            URI uri = KPIPerfToolApplication.class
                    .getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI();

            Path p = Paths.get(uri).normalize();

            // fat jar: .../kpiperftool-1.0.0.jar
            if (Files.isRegularFile(p) && p.toString().toLowerCase().endsWith(".jar")) {
                return p.getParent();
            }

            // IDE: .../target/classes
            // 返回其父目录（可选）：一般是 .../target
            // 这里为了“以运行产物为锚”，我们尽量取父级
            Path parent = p.getParent();
            return parent != null ? parent : p;
        } catch (Exception e) {
            Path fallback = Paths.get(System.getProperty("user.dir"));
            log.warn("[CLI] resolveJarDir failed. fallback to user.dir={}", fallback.toAbsolutePath(), e);
            return fallback;
        }
    }

    // ======================= validation =======================

    private void validate(PerfToolProperties p) {
        if (isBlank(p.getInputFile())) {
            throw new IllegalArgumentException("perf.inputFile is required");
        }
        if (isBlank(p.getOutputDir())) {
            throw new IllegalArgumentException("perf.outputDir is required");
        }
        if (p.getFetchSize() <= 0) {
            throw new IllegalArgumentException("perf.fetchSize must be > 0");
        }
        if (p.getWriteBatchSize() <= 0) {
            throw new IllegalArgumentException("perf.writeBatchSize must be > 0");
        }
        if (p.getQueryTimeoutSeconds() <= 0) {
            throw new IllegalArgumentException("perf.queryTimeoutSeconds must be > 0");
        }
        if (p.getSheetNameMaxLen() <= 0 || p.getSheetNameMaxLen() > 31) {
            // 你也可以允许 >31 然后再截断，但这里建议强校验，避免运维误配
            throw new IllegalArgumentException("perf.sheetNameMaxLen must be in (1..31)");
        }
    }

    // ======================= logging/help =======================

    private void logEffectiveConfig(PerfToolProperties p) {
        log.info("========== KPI Perf Tool (Effective Config) ==========");
        log.info("inputFile            = {}", p.getInputFile());
        log.info("outputDir            = {}", p.getOutputDir());
        log.info("outputFilePrefix     = {}", p.getOutputFilePrefix());
        log.info("allowWrite           = {}", p.isAllowWrite());
        log.info("sheetNameMaxLen      = {}", p.getSheetNameMaxLen());
        log.info("fetchSize            = {}", p.getFetchSize());
        log.info("writeBatchSize       = {}", p.getWriteBatchSize());
        log.info("queryTimeoutSeconds  = {}", p.getQueryTimeoutSeconds());
        log.info("======================================================");
    }

    private void printHelp() {
        System.out.println("""
                ========================================================
                KPI Perf Tool - CLI Usage
                ========================================================

                Usage:
                  java [JVM options] -jar kpi-perf-tool-<version>.jar [--perf.X=Y ...]

                Defaults (if not provided):
                  perf.inputFile = {jarDir}/perf.sql
                  perf.outputDir = {jarDir}/output

                Configuration sources (priority high -> low):
                  1. CLI arg:   --perf.X=Y          (recommended; PowerShell-friendly)
                  2. JVM prop:  -Dperf.X=Y          (PowerShell: wrap with single quotes)
                  3. Env var:   PERF_X              (upper-case + underscore)
                  4. application.yaml
                  5. Built-in default (jarDir-based)

                Options (all support both --perf.X=Y and -Dperf.X=Y):
                  perf.inputFile = PATH
                      SQL file path. Supports:
                        - classpath:xxx           (read from jar resources)
                        - absolute path           (D:/foo/bar.sql or /foo/bar.sql)
                        - relative path           (resolved against jarDir, NOT user.dir)

                  perf.outputDir = DIR
                      Output directory (filesystem path; 'classpath:' not allowed)

                  perf.outputFilePrefix = STR
                      Output filename prefix (default: perf_kpi)

                  perf.allowWrite = true|false
                      Allow write SQL (default: false; never enable in production)

                  perf.sheetNameMaxLen = NUM
                      Max Excel sheet name length, 1..31 (default: 31)

                  perf.fetchSize = NUM
                      JDBC fetch size (default: 1000)

                  perf.writeBatchSize = NUM
                      Excel write batch size (default: 1000)

                  perf.queryTimeoutSeconds = NUM
                      SQL execution timeout in seconds (default: 300)

                CLI Options:
                  --help
                      Show this help message and exit

                Examples:
                  # Defaults: put perf.sql next to the jar, then:
                  java -jar kpi-perf-tool-1.0.0.jar

                  # Override via --perf.X=Y (recommended; works in PowerShell)
                  java -jar kpi-perf-tool-1.0.0.jar \\
                       --perf.inputFile=D:/data/sql/perf.sql \\
                       --perf.outputDir=D:/data/output

                  # Override via -D (PowerShell: quote each one as '-Dperf.x=y')
                  java -Dperf.inputFile=/data/sql/perf.sql \\
                       -Dperf.outputDir=/data/output \\
                       -jar kpi-perf-tool-1.0.0.jar

                  # Env var (set before launch)
                  PERF_INPUT_FILE=/data/sql/perf.sql \\
                  PERF_OUTPUT_DIR=/data/output \\
                  java -jar kpi-perf-tool-1.0.0.jar

                Exit Codes:
                  0 - success
                  1 - execution failed
                  2 - invalid arguments
                ========================================================
                """);
    }

    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
