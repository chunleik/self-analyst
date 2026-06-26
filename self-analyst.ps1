$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$Jar = Join-Path $ScriptDir "self-analyst-app\target\self-analyst-app-1.0.0.jar"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$jvmArgs = @(
    "-Dfile.encoding=UTF-8",
    "-Dsun.stdout.encoding=UTF-8",
    "-Dsun.stderr.encoding=UTF-8",
    "-jar", $Jar
)
& java $jvmArgs $args
