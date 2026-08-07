param(
    [string]$EnvFile = ".env"
)

$ErrorActionPreference = "Stop"

function Set-EnvironmentFromDotEnv {
    param([string]$Path)

    if (-not (Test-Path -LiteralPath $Path)) {
        return
    }

    Get-Content -LiteralPath $Path | ForEach-Object {
        $line = $_.Trim()

        if ($line.Length -eq 0 -or $line.StartsWith("#")) {
            return
        }

        $separatorIndex = $line.IndexOf("=")
        if ($separatorIndex -lt 1) {
            return
        }

        $name = $line.Substring(0, $separatorIndex).Trim()
        $value = $line.Substring($separatorIndex + 1).Trim()

        if (($value.StartsWith('"') -and $value.EndsWith('"')) -or
            ($value.StartsWith("'") -and $value.EndsWith("'"))) {
            $value = $value.Substring(1, $value.Length - 2)
        }

        Set-Item -Path "Env:$name" -Value $value
    }
}

Set-EnvironmentFromDotEnv -Path $EnvFile

$requiredVariables = @(
    "TELEGRAM_BOT_USERNAME",
    "TELEGRAM_BOT_TOKEN",
    "SPRING_DATASOURCE_PASSWORD",
    "POSTGRES_SUPERUSER_PASSWORD"
)

$missingVariables = $requiredVariables | Where-Object {
    [string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($_))
}

if ($missingVariables.Count -gt 0) {
    throw "Missing required environment variables: $($missingVariables -join ', ')"
}

$listenerProcessIds = @(Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue |
    Select-Object -ExpandProperty OwningProcess -Unique)

if ($listenerProcessIds.Count -gt 1) {
    throw "Port 8080 has multiple listeners; refusing to stop any process."
}

if ($listenerProcessIds.Count -eq 1) {
    $listenerProcess = Get-CimInstance Win32_Process -Filter "ProcessId = $($listenerProcessIds[0])"
    $parentProcess = if ($listenerProcess) {
        Get-CimInstance Win32_Process -Filter "ProcessId = $($listenerProcess.ParentProcessId)"
    }
    $isProjectMavenBot = $listenerProcess.Name -eq "java.exe" -and
        $listenerProcess.CommandLine -like "*com.example.dailyreportbot.DailyReportTelegramBotApplication*" -and
        $parentProcess.Name -eq "java.exe" -and
        $parentProcess.CommandLine -like "*org.codehaus.plexus.classworlds.launcher.Launcher*" -and
        $parentProcess.CommandLine -like "*spring-boot:run*" -and
        $parentProcess.CommandLine -like "*$PSScriptRoot*"

    if (-not $isProjectMavenBot) {
        throw "Port 8080 is in use by a process that was not verified as this project's Maven bot; nothing was stopped."
    }

    Write-Host "Stopping the verified Maven bot for this project..."
    Stop-Process -Id $listenerProcess.ProcessId
    Wait-Process -Id $listenerProcess.ProcessId -Timeout 15 -ErrorAction SilentlyContinue
}

if (Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue) {
    throw "The verified Maven bot did not release port 8080; Docker Compose was not started."
}

Write-Host "Building and starting the Docker Compose stack..."
Push-Location $PSScriptRoot
try {
    docker compose up --build -d
    if ($LASTEXITCODE -ne 0) {
        throw "Docker Compose could not start the stack."
    }
} finally {
    Pop-Location
}
