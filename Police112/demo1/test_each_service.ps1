# Police IoT Platform: Test Each Service Individually
# Usage: .\test_each_service.ps1

param(
    [string]$JavaHome = $env:JAVA_HOME
)

if (-not $JavaHome) {
    throw "JAVA_HOME is not set. Please point it to a Java 17+ installation."
}

$repoRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$env:JAVA_HOME = $JavaHome

$services = @(
    "common",
    "device-service",
    "command-service",
    "event-service",
    "sim-service"
)

Set-Location -Path $repoRoot

foreach ($service in $services) {
    Write-Host "--- Running tests for $service ---" -ForegroundColor Cyan
    ./gradlew ":$service:test"

    if ($LASTEXITCODE -ne 0) {
        throw "Tests failed for $service"
    }

    Write-Host "--- $service tests passed ---" -ForegroundColor Green
    Write-Host ""
}

Write-Host "All individual service test runs completed successfully." -ForegroundColor Green
