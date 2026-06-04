@echo off
setlocal
rem 切换到项目根目录（scripts/ 的父目录），作为 user.dir / logs / output 的基准
cd /d "%~dp0\.."

rem 自动定位 jar（兼容版本号变化）
set "JAR="
for %%f in (kpi-perf-tool-*.jar) do set "JAR=%%f"
if "%JAR%"=="" (
    echo Error: kpi-perf-tool-*.jar not found in %CD%
    exit /b 2
)

java -jar "%JAR%" %*
exit /b %errorlevel%
