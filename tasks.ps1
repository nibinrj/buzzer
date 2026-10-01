#Requires -Version 7.0
<#
.SYNOPSIS
    Buzzer developer task runner (the Windows replacement for a Makefile).
.EXAMPLE
    .\tasks.ps1                              # help
    .\tasks.ps1 up
    .\tasks.ps1 run -Svc identity-service
    .\tasks.ps1 run -Svc identity-service -Json
    .\tasks.ps1 logs -Svc postgres
#>
[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [ValidateSet('help', 'up', 'down', 'nuke', 'build', 'test', 'run', 'health', 'logs', 'keys')]
    [string] $Task = 'help',

    [string] $Svc,

    # run only: log JSON lines (ECS), as in a container, instead of readable text.
    [switch] $Json
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Stop-Task([string] $Message) {
    Write-Host $Message -ForegroundColor Red
    exit 1
}

# Runs an external program and stops the script with the program's exit code if it fails.
# PowerShell does not stop on a failing native command by itself, so every call goes through here.
function Invoke-Native {
    param(
        [Parameter(Mandatory)] [string] $FilePath,
        [string[]] $Arguments = @()
    )
    & $FilePath @Arguments
    if ($LASTEXITCODE -ne 0) {
        Write-Host "Failed (exit code $LASTEXITCODE): $FilePath $($Arguments -join ' ')" -ForegroundColor Red
        exit $LASTEXITCODE
    }
}

# openssl from PATH, else the copy bundled with Git for Windows in <git root>\usr\bin.
# The git root is found from git.exe's location, so no install path is hardcoded.
function Find-OpenSsl {
    $onPath = Get-Command openssl -CommandType Application -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if ($onPath) { return $onPath.Source }

    $git = Get-Command git -CommandType Application -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if ($git) {
        # git.exe sits in <git root>\cmd, \bin or \mingw64\bin: walk up a few levels.
        $dir = Split-Path $git.Source -Parent
        for ($i = 0; $i -lt 3 -and $dir; $i++) {
            $candidate = Join-Path $dir 'usr' 'bin' 'openssl.exe'
            if (Test-Path $candidate) { return $candidate }
            $dir = Split-Path $dir -Parent
        }
    }
    return $null
}

# Reads KEY=VALUE lines from a .env file (blank lines and # comments skipped, optional quotes
# around the value stripped). Never prints values: .env holds passwords.
function Read-DotEnv([string] $Path) {
    $vars = [ordered]@{}
    $lineNumber = 0
    foreach ($line in Get-Content -LiteralPath $Path) {
        $lineNumber++
        $trimmed = $line.Trim()
        if (-not $trimmed -or $trimmed.StartsWith('#')) { continue }
        $eq = $trimmed.IndexOf('=')
        if ($eq -lt 1) { Stop-Task ".env line ${lineNumber}: expected KEY=VALUE" }
        $value = $trimmed.Substring($eq + 1).Trim()
        if ($value.Length -ge 2 -and ($value[0] -eq '"' -or $value[0] -eq "'") -and $value[-1] -eq $value[0]) {
            $value = $value.Substring(1, $value.Length - 2)
        }
        $vars[$trimmed.Substring(0, $eq).Trim()] = $value
    }
    return $vars
}

function Show-Help {
    Write-Host @'
Usage: .\tasks.ps1 <task> [-Svc <name>]

  help     Show this list
  up       Start postgres, redis, redpanda and wait until healthy
  down     Stop the local stack (data volumes are kept)
  nuke     Stop the stack AND delete its data volumes (asks first)
  build    .\mvnw.cmd verify (all modules, with tests)
  test     .\mvnw.cmd test
  run      Run one service with .env loaded, JVM in UTC: .\tasks.ps1 run -Svc identity-service
           Readable log lines; add -Json for the JSON lines a container writes
  health   GET /actuator/health on the management ports 9080-9084
  logs     Follow stack logs: .\tasks.ps1 logs [-Svc postgres]
  keys     Generate the RS256 JWT key pair into .secrets\ (never overwrites)
'@
}

$mvnw = Join-Path $PSScriptRoot 'mvnw.cmd'

# Every task runs from the repo root (mvnw, docker compose and .secrets use relative paths).
# Push/Pop keeps the caller's current directory unchanged afterwards.
Push-Location $PSScriptRoot
try {
    switch ($Task) {
        'help' { Show-Help }

        'up' { Invoke-Native docker @('compose', 'up', '-d', '--wait') }

        'down' { Invoke-Native docker @('compose', 'down') }

        'nuke' {
            Write-Host 'This deletes ALL local data: the postgres, redis and redpanda volumes.' -ForegroundColor Yellow
            $answer = Read-Host "Type 'yes' to continue"
            if ($answer -cne 'yes') { Stop-Task 'Aborted. Nothing was deleted.' }
            Invoke-Native docker @('compose', 'down', '-v')
        }

        'build' { Invoke-Native $mvnw @('verify') }

        'test' { Invoke-Native $mvnw @('test') }

        'run' {
            if (-not $Svc) { Stop-Task 'run needs -Svc <name>, e.g. .\tasks.ps1 run -Svc identity-service' }
            if (-not (Test-Path (Join-Path $PSScriptRoot 'services' $Svc 'pom.xml'))) {
                $known = (Get-ChildItem (Join-Path $PSScriptRoot 'services') -Directory).Name -join ', '
                Stop-Task "Unknown service '$Svc'. Known: $known"
            }
            $dotEnvPath = Join-Path $PSScriptRoot '.env'
            if (-not (Test-Path -LiteralPath $dotEnvPath)) {
                Stop-Task 'No .env found. Create it first: Copy-Item .env.example .env (then set real passwords).'
            }
            $dotEnv = Read-DotEnv $dotEnvPath

            # A single "-pl <svc> -am spring-boot:run" would also run spring-boot:run on the
            # parent pom and shared-events, which have no main class and fail. So: first install
            # the service's upstream modules into ~/.m2, then run only the service.
            Invoke-Native $mvnw @('-q', '-pl', "services/$Svc", '-am', 'install', '-DskipTests')

            # Spring does not read .env, so its values become environment variables of this process
            # (inherited by Maven and the app) only while the service runs. The finally block restores
            # the previous values, so passwords don't linger in your PowerShell session afterwards.
            $previous = @{}
            try {
                foreach ($name in $dotEnv.Keys) {
                    $existing = Get-Item -LiteralPath "Env:$name" -ErrorAction SilentlyContinue
                    $previous[$name] = if ($existing) { $existing.Value } else { $null }
                    Set-Item -LiteralPath "Env:$name" -Value $dotEnv[$name]
                }
                # The app runs in its own JVM; UTC because postgres:16 rejects the Windows JDK's
                # default zone name "Asia/Calcutta" (same reason as surefire's argLine in the root pom).
                # Services log JSON by default (what a container writes); the "human" profile switches a
                # terminal back to readable lines. -Json skips it.
                $runArgs = @('-pl', "services/$Svc", 'spring-boot:run',
                    '-Dspring-boot.run.jvmArguments=-Duser.timezone=UTC')
                if (-not $Json) { $runArgs += '-Dspring-boot.run.profiles=human' }
                Invoke-Native $mvnw $runArgs
            }
            finally {
                foreach ($name in $previous.Keys) {
                    # Variables that didn't exist before are deleted, not set to an empty string.
                    if ($null -eq $previous[$name]) {
                        Remove-Item -LiteralPath "Env:$name" -ErrorAction SilentlyContinue
                    }
                    else {
                        Set-Item -LiteralPath "Env:$name" -Value $previous[$name]
                    }
                }
            }
        }

        'health' {
            & (Join-Path $PSScriptRoot 'tools' 'health.ps1')
            exit $LASTEXITCODE
        }

        'logs' {
            $logArgs = @('compose', 'logs', '-f', '--tail=100')
            if ($Svc) { $logArgs += $Svc }
            Invoke-Native docker $logArgs
        }

        'keys' {
            $openssl = Find-OpenSsl
            if (-not $openssl) {
                Stop-Task 'openssl not found on PATH or in Git for Windows (usr\bin). Install Git for Windows or add openssl to PATH.'
            }

            $privateKey = '.secrets/jwt-private.pem'
            $publicKey = '.secrets/jwt-public.pem'
            $existing = @($privateKey, $publicKey) | Where-Object { Test-Path $_ }
            if ($existing) {
                Stop-Task "Refusing to overwrite existing key file(s): $($existing -join ', '). Delete them yourself if you really want new keys."
            }

            New-Item -ItemType Directory -Force -Path '.secrets' | Out-Null
            # genpkey writes PKCS#8 ("BEGIN PRIVATE KEY"); -pubout writes X.509 SubjectPublicKeyInfo ("BEGIN PUBLIC KEY").
            Invoke-Native $openssl @('genpkey', '-algorithm', 'RSA', '-pkeyopt', 'rsa_keygen_bits:2048', '-out', $privateKey)
            Invoke-Native $openssl @('pkey', '-in', $privateKey, '-pubout', '-out', $publicKey)
            Write-Host "Created $privateKey and $publicKey (using $openssl)" -ForegroundColor Green
        }
    }
}
finally {
    Pop-Location
}
