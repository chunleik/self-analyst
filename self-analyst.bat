@echo off
chcp 65001 >nul 2>&1
set "SCRIPT_DIR=%~dp0"
set "JAR=%SCRIPT_DIR%self-analyst-app\target\self-analyst-app-1.0.0.jar"
java -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 -jar "%JAR%" %*
