# Police IoT Platform: Local Startup Script
# Usage: .\run_all.ps1

param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$SpringProfile = "dev"
)

if (-not $JavaHome) {
    throw "JAVA_HOME is not set. Please point it to a Java 17+ installation."
}

$repoRoot = Split-Path -Parent $MyInvocation.MyCommand.Path

$env:JAVA_HOME = $JavaHome
$env:SPRING_PROFILES_ACTIVE = $SpringProfile

Write-Host "--- Starting Police IoT Platform ---" -ForegroundColor Cyan
Write-Host "Repo Root: $repoRoot" -ForegroundColor Yellow
Write-Host "Java Home: $env:JAVA_HOME" -ForegroundColor Yellow
Write-Host "Profile:   $env:SPRING_PROFILES_ACTIVE" -ForegroundColor Yellow

function Start-Service {
    param(
        [string]$ServiceName,
        [string]$ProjectName
    )

    Write-Host "Launching $ServiceName..." -ForegroundColor Green

    $command = @"
Set-Location -Path '$repoRoot'
`$env:JAVA_HOME = '$env:JAVA_HOME'
`$env:SPRING_PROFILES_ACTIVE = '$env:SPRING_PROFILES_ACTIVE'
./gradlew :$ProjectName:bootRun
"@

    Start-Process powershell -ArgumentList "-NoExit", "-Command", $command
}

Start-Service -ServiceName "Device Service (Port 8081)" -ProjectName "device-service"
Start-Sleep -Seconds 5

Start-Service -ServiceName "Command Service (Port 8082)" -ProjectName "command-service"
Start-Sleep -Seconds 5

Start-Service -ServiceName "Event Service (Port 8083)" -ProjectName "event-service"
Start-Sleep -Seconds 5

Start-Service -ServiceName "Sim Service (Port 8084)" -ProjectName "sim-service"

Write-Host "--- All Services Launched ---" -ForegroundColor Cyan
Write-Host "Please wait for services to start up completely."
