param(
    [string]$EnvFile = ".env",
    [switch]$SkipEnvCheck
)

$ErrorActionPreference = "Stop"

function Set-EnvironmentFromDotEnv {
    param([string]$Path)

    if (-not (Test-Path -LiteralPath $Path)) {
        Write-Host "No $Path file found. Using existing environment variables and application defaults."
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

if (-not $SkipEnvCheck) {
    $requiredVariables = @(
        "TELEGRAM_BOT_USERNAME",
        "TELEGRAM_BOT_TOKEN",
        "SPRING_DATASOURCE_USERNAME",
        "SPRING_DATASOURCE_PASSWORD"
    )

    $missingVariables = $requiredVariables | Where-Object {
        [string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($_))
    }

    if ($missingVariables.Count -gt 0) {
        Write-Host "Missing required environment variables:" -ForegroundColor Red
        $missingVariables | ForEach-Object { Write-Host " - $_" -ForegroundColor Red }
        Write-Host ""
        Write-Host "Create .env from .env.example, fill your local values, then run .\run-dev.ps1 again."
        exit 1
    }
}

if ([string]::IsNullOrWhiteSpace($env:SPRING_DATASOURCE_URL)) {
    $env:SPRING_DATASOURCE_URL = "jdbc:postgresql://localhost:5432/daily_report_bot"
}

Write-Host "Config loaded:"
Write-Host " - TELEGRAM_BOT_USERNAME=$env:TELEGRAM_BOT_USERNAME"
Write-Host " - TELEGRAM_BOT_TOKEN=<hidden>"
Write-Host " - SPRING_DATASOURCE_URL=$env:SPRING_DATASOURCE_URL"
Write-Host " - SPRING_DATASOURCE_USERNAME=$env:SPRING_DATASOURCE_USERNAME"

Write-Host "Starting Daily Report Telegram Bot..."
Write-Host "Press Ctrl+C to stop."
mvn spring-boot:run
