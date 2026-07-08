[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8

Set-ExecutionPolicy RemoteSigned -Scope Process -Force

# Fix: bypass unstable mvnw wrapper, use cached Maven directly
# mvnw shell script uses Java hashCode but cache uses SHA-256 → mismatch → download fails
$MavenCache = "$env:USERPROFILE\.m2\wrapper\dists\apache-maven-3.9.15\0226a00282e400185496f3b60ec5a3f029cbdc6893912937d4876d57695224e1"

if (-not (Test-Path $MavenCache)) {
    Write-Host "[ERROR] Maven cache not found at: $MavenCache" -ForegroundColor Red
    Write-Host "[INFO] Falling back to mvnw.cmd..." -ForegroundColor Yellow
    & .\mvnw.cmd spring-boot:run
} else {
    Write-Host "[INFO] Using cached Maven from wrapper" -ForegroundColor Green
    $env:MAVEN_HOME = $MavenCache
    # Set JAVA_HOME if not already valid
    if (-not (Test-Path "$env:JAVA_HOME\bin\java.exe")) {
        $javaCmd = (Get-Command java -ErrorAction SilentlyContinue).Source
        if ($javaCmd) {
            $env:JAVA_HOME = Split-Path (Split-Path $javaCmd)
            Write-Host "[INFO] Auto-detected JAVA_HOME: $env:JAVA_HOME" -ForegroundColor Green
        } else {
            Write-Host "[WARN] JAVA_HOME not set and 'java' not found in PATH" -ForegroundColor Yellow
        }
    }
    & "$MavenCache\bin\mvn.cmd" spring-boot:run
}

pause
