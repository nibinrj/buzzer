#Requires -Version 7.0
<#
.SYNOPSIS
    Plays load-test games with tools/load/game.js, one per player count, and saves each summary.
.DESCRIPTION
    One game per player count, one after the other: players can only join in the lobby, so a game can't grow
    once it has started. Each game saves, in tools/load/results/ (gitignored):
        <yyyyMMdd-HHmmss>-<N>p.txt    the summary k6 prints
        <yyyyMMdd-HHmmss>-<N>p.json   the same numbers as JSON (k6 --summary-export)

    Locally: the host stack (.\tasks.ps1 up, obs, and the five services with run), the gateway on :8080.

    The gateway limits requests per client IP, and every k6 virtual user shares this machine's IP. Start the gateway
    for a load run with the limits raised (real players come from many IPs):
        $env:GATEWAY_RATE_LIMIT_PER_IP_REPLENISH_RATE = '1000'; $env:GATEWAY_RATE_LIMIT_PER_IP_BURST_CAPACITY = '2000'
        $env:GATEWAY_RATE_LIMIT_PER_USER_REPLENISH_RATE = '1000'; $env:GATEWAY_RATE_LIMIT_PER_USER_BURST_CAPACITY = '2000'
        .\tasks.ps1 run -Svc gateway
    (If .env sets the same names, .env wins.) Without that, joins crawl at ~2 per second and the summary's
    rate_limited counter shows the 429s.
.EXAMPLE
    .\tools\load\run-game.ps1                                   # 50, then 100, then 200 players, 127.0.0.1:8080
.EXAMPLE
    .\tools\load\run-game.ps1 -Players 5 -Settings @{ QUESTIONS = '3' }   # smoke test
.EXAMPLE
    .\tools\load\run-game.ps1 -Players 500 -BaseUrl https://<alb-dns-name>   # against AWS
#>
param(
    [int[]] $Players = @(50, 100, 200),

    # 127.0.0.1, not localhost: localhost tries ::1 first, and every connection would wait for that to fail.
    [string] $BaseUrl = 'http://127.0.0.1:8080',

    # More game.js settings, e.g. @{ QUESTIONS = '5'; P_CORRECT = '0.5'; ACK_P95_MS = '100' } (see its top).
    [hashtable] $Settings = @{},

    # Seconds between games, so one game's tail (Kafka, outbox) doesn't overlap the next one's joins.
    [int] $PauseSeconds = 15
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Get-Command k6 -CommandType Application -ErrorAction SilentlyContinue)) {
    Write-Host 'k6 not found on PATH. Install it: winget install k6 --source winget (then open a new terminal).' -ForegroundColor Red
    exit 1
}

$resultsDir = Join-Path $PSScriptRoot 'results'
New-Item -ItemType Directory -Force -Path $resultsDir | Out-Null
$script = Join-Path $PSScriptRoot 'game.js'

for ($i = 0; $i -lt $Players.Count; $i++) {
    $count = $Players[$i]
    if ($i -gt 0) {
        Start-Sleep -Seconds $PauseSeconds
    }
    $name = "$(Get-Date -Format 'yyyyMMdd-HHmmss')-${count}p"
    $k6Args = @('run',
        '--summary-export', (Join-Path $resultsDir "$name.json"),
        '--summary-trend-stats', 'avg,min,med,p(90),p(95),p(99),max',
        '-e', "BASE_URL=$BaseUrl",
        '-e', "PLAYERS=$count")
    foreach ($key in $Settings.Keys) {
        $k6Args += @('-e', "$key=$($Settings[$key])")
    }
    $k6Args += $script

    Write-Host "=== $count players against $BaseUrl ===" -ForegroundColor Cyan
    # The throwaway host's password: new for every game, never printed, and passed as an environment variable (k6
    # reads those into __ENV) rather than with -e, so it isn't on a command line. game.js keeps it out of its
    # setup data, because k6 writes that into the summary JSON.
    $env:HOST_PASSWORD = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(18))
    try {
        & k6 @k6Args | Tee-Object -FilePath (Join-Path $resultsDir "$name.txt")
    }
    finally {
        Remove-Item Env:HOST_PASSWORD -ErrorAction SilentlyContinue
    }

    # 99: a threshold was crossed. The game still ran to the end and its summary is saved, so go on to the next.
    # Anything else non-zero: the run itself broke (setup aborted, script error); later games would break the same way.
    if ($LASTEXITCODE -eq 99) {
        Write-Host "$count players: thresholds crossed (see $name.txt)" -ForegroundColor Yellow
    }
    elseif ($LASTEXITCODE -ne 0) {
        Write-Host "$count players: k6 failed with exit code $LASTEXITCODE (see $name.txt)" -ForegroundColor Red
        exit $LASTEXITCODE
    }
    else {
        Write-Host "$count players: all thresholds met" -ForegroundColor Green
    }
}
Write-Host "Summaries in $resultsDir" -ForegroundColor Green
