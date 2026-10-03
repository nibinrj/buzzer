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
    [ValidateSet('help', 'up', 'obs', 'down', 'nuke', 'build', 'test', 'run', 'health', 'logs', 'keys', 'images',
        'kind-up', 'kind-obs', 'kind-down', 'bench')]
    [string] $Task = 'help',

    [string] $Svc,

    # run only: log JSON lines (ECS), as in a container, instead of readable text.
    [switch] $Json,

    # bench only: a regular expression; runs only the benchmarks whose name matches (JMH's own filter).
    [string] $Only
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
  obs      Start Prometheus, Grafana and Jaeger (profile obs) and open their pages
  down     Stop the local stack (data volumes are kept)
  nuke     Stop the stack AND delete its data volumes (asks first)
  build    .\mvnw.cmd verify (all modules, with tests)
  test     .\mvnw.cmd test
  run      Run one service with .env loaded, JVM in UTC: .\tasks.ps1 run -Svc identity-service
           Readable log lines; add -Json for the JSON lines a container writes
  health   GET /actuator/health on the management ports 9080-9084
  logs     Follow stack logs: .\tasks.ps1 logs [-Svc postgres]
  keys     Generate the RS256 JWT key pair into .secrets\ (never overwrites)
  images   Package the jars (no tests) and build buzzer/<svc>:<git sha> (+ :kind) for all five (amd64, local only)
  kind-up    Create the kind cluster "buzzer" (if missing) and deploy everything: Secrets from .env and .secrets\,
             Postgres/Redis/Redpanda, Traefik, the five services, Prometheus + Grafana. Run `images` first.
             App at http://127.0.0.1:8000
  kind-obs   Open the cluster's Grafana (127.0.0.1:3001) and Prometheus (127.0.0.1:9091) until Ctrl+C
  kind-down  Delete the kind cluster "buzzer" and ALL its data (compose's data is not touched)
  bench    Build benchmarks\target\benchmarks.jar and run every JMH benchmark (~4 min; needs Docker for Redis).
           One group only: .\tasks.ps1 bench -Only EventReader. JSON results: benchmarks\target\jmh-result.json
'@
}

# kind/kubectl settings. Every kubectl call names the context, so a task can never act on another cluster
# (kubectl's "current context" may point anywhere).
$kindCluster = 'buzzer'
$kubeContext = "kind-$kindCluster"
# The Gateway API CRDs, at the version Traefik v3.7.13 is built against (its go.mod: sigs.k8s.io/gateway-api v1.6.1).
$gatewayApiVersion = 'v1.6.1'
$gatewayApiCrds = "https://github.com/kubernetes-sigs/gateway-api/releases/download/$gatewayApiVersion/standard-install.yaml"

# Creates or updates one Secret from .env values, without the values touching the disk or a command line:
# the Secret is built here as JSON (valid YAML) and piped to kubectl over stdin.
# --server-side: client-side apply would copy the whole object, values included, into the
# kubectl.kubernetes.io/last-applied-configuration annotation, readable by anyone who can read the Secret's
# metadata. Server-side apply records only which fields it owns, not their values.
# A Secret whose keys are file names and whose values are file contents (identity-service's PEM keys), mounted into
# a pod as files. Like Set-KindSecret: built in memory and sent to kubectl on stdin, never written to disk.
function Set-KindFileSecret([string] $Name, [hashtable] $Files) {
    $data = [ordered]@{}
    foreach ($key in $Files.Keys | Sort-Object) {
        if (-not (Test-Path -LiteralPath $Files[$key])) { Stop-Task "Secret ${Name}: $($Files[$key]) not found. Run .\tasks.ps1 keys first." }
        $data[$key] = [Convert]::ToBase64String([IO.File]::ReadAllBytes($Files[$key]))
    }
    $secret = [ordered]@{
        apiVersion = 'v1'
        kind       = 'Secret'
        type       = 'Opaque'
        metadata   = [ordered]@{
            name      = $Name
            namespace = 'buzzer'
            labels    = @{ 'app.kubernetes.io/part-of' = 'buzzer'; 'app.kubernetes.io/managed-by' = 'tasks.ps1' }
        }
        data       = $data
    }
    $secret | ConvertTo-Json -Depth 5 |
        kubectl --context $kubeContext apply --server-side --field-manager=tasks-ps1 -f - | Out-Host
    if ($LASTEXITCODE -ne 0) { Stop-Task "kubectl apply failed for Secret $Name" }
}

function Set-KindSecret([string] $Name, [hashtable] $DotEnv, [string[]] $Keys, [string] $Namespace = 'buzzer') {
    $missing = @($Keys | Where-Object { -not $DotEnv.ContainsKey($_) -or -not $DotEnv[$_] })
    if ($missing) { Stop-Task "Secret ${Name}: .env is missing $($missing -join ', ')" }
    $data = [ordered]@{}
    foreach ($key in $Keys) {
        # A Secret's data values are base64: an encoding for arbitrary bytes, NOT encryption (K.1 §4).
        $data[$key] = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($DotEnv[$key]))
    }
    $secret = [ordered]@{
        apiVersion = 'v1'
        kind       = 'Secret'
        type       = 'Opaque'
        metadata   = [ordered]@{
            name      = $Name
            namespace = $Namespace
            labels    = @{ 'app.kubernetes.io/part-of' = 'buzzer'; 'app.kubernetes.io/managed-by' = 'tasks.ps1' }
        }
        data       = $data
    }
    $secret | ConvertTo-Json -Depth 5 |
        kubectl --context $kubeContext apply --server-side --field-manager=tasks-ps1 -f - | Out-Host
    if ($LASTEXITCODE -ne 0) { Stop-Task "kubectl apply failed for Secret $Name" }
}

$mvnw = Join-Path $PSScriptRoot 'mvnw.cmd'

# Every task runs from the repo root (mvnw, docker compose and .secrets use relative paths).
# Push/Pop keeps the caller's current directory unchanged afterwards.
Push-Location $PSScriptRoot
try {
    switch ($Task) {
        'help' { Show-Help }

        'up' { Invoke-Native docker @('compose', 'up', '-d', '--wait') }

        'obs' {
            # Profile "obs" in docker-compose.yml. Grafana needs GRAFANA_ADMIN_PASSWORD in .env; the services send
            # traces to Jaeger once MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT is set there too.
            Invoke-Native docker @('compose', '--profile', 'obs', 'up', '-d', '--wait')
            Write-Host 'Grafana     http://localhost:3000 (admin / GRAFANA_ADMIN_PASSWORD)'
            Write-Host 'Prometheus  http://localhost:9090/targets'
            Write-Host 'Jaeger      http://localhost:16686'
            foreach ($url in 'http://localhost:3000', 'http://localhost:9090/targets', 'http://localhost:16686') {
                Start-Process $url
            }
        }

        # --profile obs: also stops the observability containers, if they run.
        'down' { Invoke-Native docker @('compose', '--profile', 'obs', 'down') }

        'nuke' {
            Write-Host 'This deletes ALL local data: the postgres, redis, redpanda and prometheus volumes.' -ForegroundColor Yellow
            $answer = Read-Host "Type 'yes' to continue"
            if ($answer -cne 'yes') { Stop-Task 'Aborted. Nothing was deleted.' }
            Invoke-Native docker @('compose', '--profile', 'obs', 'down', '-v')
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
            $logArgs = @('compose', '--profile', 'obs', 'logs', '-f', '--tail=100')
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

        'images' {
            # Jars first, built on the host like CI will. No tests here: that's what build is for.
            Invoke-Native $mvnw @('-q', '-DskipTests', 'package')

            # Tag = the commit the jars came from, never "latest" (kind would try to pull "latest" from Docker Hub).
            # "-dirty" marks images built from uncommitted changes, so a tag never claims a commit it doesn't match.
            $revision = & git rev-parse --short HEAD
            if ($LASTEXITCODE -ne 0) { Stop-Task 'git rev-parse failed: images are tagged with the commit id.' }
            $tag = if (& git status --porcelain) { "$revision-dirty" } else { $revision }

            # amd64 only: kind runs amd64 here. The arm64 build for ECS needs a registry push (Phase 6.5).
            foreach ($service in (Get-ChildItem 'services' -Directory).Name) {
                Invoke-Native docker @('build', '--platform', 'linux/amd64',
                    '--build-arg', "SERVICE=$service", '--build-arg', "REVISION=$tag",
                    '--tag', "buzzer/${service}:$tag", '.')
            }
            # Also tagged "kind": the tag overlays/kind deploys (a moving tag; the image's revision label keeps the
            # commit). kind-up loads whatever carries it and restarts the services.
            foreach ($service in (Get-ChildItem 'services' -Directory).Name) {
                Invoke-Native docker @('tag', "buzzer/${service}:$tag", "buzzer/${service}:kind")
            }
            Write-Host "Built buzzer/<service>:$tag (+ :kind) for: $((Get-ChildItem 'services' -Directory).Name -join ', ')" -ForegroundColor Green
        }

        'kind-up' {
            $dotEnvPath = Join-Path $PSScriptRoot '.env'
            if (-not (Test-Path -LiteralPath $dotEnvPath)) {
                Stop-Task 'No .env found. Create it first: Copy-Item .env.example .env (then set real passwords).'
            }
            $dotEnv = Read-DotEnv $dotEnvPath

            # Re-running is safe: an existing cluster is reused and every apply below is idempotent.
            $clusters = & kind get clusters 2>$null
            if ($LASTEXITCODE -ne 0) { Stop-Task 'kind not found or failed. Install kind and check Docker Desktop is running.' }
            if ($clusters -notcontains $kindCluster) {
                Invoke-Native kind @('create', 'cluster', '--config', 'infra/k8s/kind-config.yaml')
            }
            else {
                Write-Host "kind cluster '$kindCluster' already exists, reusing it."
            }

            # Order matters: namespaces, then the Secrets that live in them, then the pods that read the Secrets.
            Invoke-Native kubectl @('--context', $kubeContext, 'apply', '-f', 'infra/k8s/base/namespaces.yaml')
            # One Secret per concern (K.1 D4): a pod only gets the values it needs.
            Set-KindSecret 'postgres-credentials' $dotEnv @('POSTGRES_USER', 'POSTGRES_PASSWORD',
                'IDENTITY_DB_PASSWORD', 'QUIZ_DB_PASSWORD', 'SESSION_DB_PASSWORD', 'SCORING_DB_PASSWORD')
            Set-KindSecret 'redis-credentials' $dotEnv @('REDIS_PASSWORD')
            # identity-service's signing keys: the files .\tasks.ps1 keys created, mounted into its pod as files.
            Set-KindFileSecret 'identity-jwt' @{
                'jwt-private.pem' = (Join-Path $PSScriptRoot '.secrets' 'jwt-private.pem')
                'jwt-public.pem'  = (Join-Path $PSScriptRoot '.secrets' 'jwt-public.pem')
            }
            # Grafana's admin password, the same .env value compose's Grafana uses.
            Set-KindSecret 'grafana-admin' $dotEnv @('GRAFANA_ADMIN_PASSWORD') 'observability'

            # The Gateway API kinds (GatewayClass, Gateway, HTTPRoute) must exist before anything uses them.
            # Downloaded only when missing or at another version (the CRDs carry their bundle version), so a re-run
            # doesn't need the internet. Server-side apply: these CRDs are too large for the annotation client-side
            # apply keeps.
            $installed = & kubectl --context $kubeContext get crd gateways.gateway.networking.k8s.io `
                -o 'jsonpath={.metadata.annotations.gateway\.networking\.k8s\.io/bundle-version}' 2>$null
            if ($installed -ne $gatewayApiVersion) {
                Invoke-Native kubectl @('--context', $kubeContext, 'apply', '--server-side', '-f', $gatewayApiCrds)
            }
            Invoke-Native kubectl @('--context', $kubeContext, 'wait', '--for=condition=Established',
                'crd/gatewayclasses.gateway.networking.k8s.io', 'crd/gateways.gateway.networking.k8s.io',
                'crd/httproutes.gateway.networking.k8s.io', '--timeout=60s')

            # Images come from this PC's Docker, not from a download inside the node: `kind load` copies each one
            # into the node's containerd. Docker Desktop already has them (compose, .\tasks.ps1 images), and the node
            # pulling a second copy over its own network path is slower and can fail on its own. The list is read
            # from the rendered manifests, so a new image in a manifest is picked up without editing this task.
            $rendered = & kubectl kustomize 'infra/k8s/overlays/kind'
            if ($LASTEXITCODE -ne 0) { Stop-Task 'kubectl kustomize failed: fix the manifests first.' }
            $images = $rendered | Select-String -Pattern '^\s+image:\s*(\S+)' |
                ForEach-Object { $_.Matches[0].Groups[1].Value } | Sort-Object -Unique
            foreach ($image in $images) {
                & docker image inspect $image *> $null
                if ($LASTEXITCODE -ne 0) {
                    # Our own images exist only on this PC: there is nothing to pull.
                    if ($image -like 'buzzer/*') { Stop-Task "$image not found. Build the images first: .\tasks.ps1 images" }
                    Invoke-Native docker @('pull', $image)
                }
                Invoke-Native kind @('load', 'docker-image', $image, '--name', $kindCluster)
            }

            # Were the services already deployed? Then they need a restart below to pick up the images just loaded.
            $existing = & kubectl --context $kubeContext -n buzzer get deployment --selector 'app.kubernetes.io/component=service' -o name 2>$null
            Invoke-Native kubectl @('--context', $kubeContext, 'apply', '-k', 'infra/k8s/overlays/kind')

            # rollout status waits for the pods to exist AND be ready (kubectl wait fails if the StatefulSet
            # controller hasn't created the pod yet). Postgres's first start runs initdb + the init SQL.
            foreach ($statefulSet in 'postgres', 'redis', 'redpanda') {
                Invoke-Native kubectl @('--context', $kubeContext, '-n', 'buzzer', 'rollout', 'status',
                    "statefulset/$statefulSet", '--timeout=300s')
            }

            # Already-running services are restarted: the "kind" tag may now point at a newer image (just loaded),
            # and a pod only picks it up when it starts. NOT on the first deploy: those pods are brand new, and
            # restarting them while they start would briefly reserve memory for both generations (the scheduler
            # counts a terminating pod until it's gone). On a fresh cluster a service may start before Postgres, fail
            # and be restarted by Kubernetes after a back-off; the wait below allows for that.
            if ($existing) {
                Invoke-Native kubectl @('--context', $kubeContext, '-n', 'buzzer', 'rollout', 'restart', 'deployment',
                    '--selector', 'app.kubernetes.io/component=service')
            }
            $deployments = 'traefik', 'identity-service', 'quiz-service', 'session-service', 'scoring-service', 'gateway'
            foreach ($deployment in $deployments) {
                # Up to 10 min: seven JVMs start at once on one machine.
                Invoke-Native kubectl @('--context', $kubeContext, '-n', 'buzzer', 'rollout', 'status',
                    "deployment/$deployment", '--timeout=600s')
            }
            foreach ($deployment in 'prometheus', 'grafana') {
                Invoke-Native kubectl @('--context', $kubeContext, '-n', 'observability', 'rollout', 'status',
                    "deployment/$deployment", '--timeout=300s')
            }
            Invoke-Native kubectl @('--context', $kubeContext, 'get', 'pods', '-n', 'buzzer')
            Invoke-Native kubectl @('--context', $kubeContext, 'get', 'pods', '-n', 'observability')
            Write-Host 'Dashboards: .\tasks.ps1 kind-obs'
            # 127.0.0.1, not localhost: the port is published on IPv4 only, and localhost tries ::1 first (kind-config.yaml).
            Write-Host "Cluster '$kindCluster' is up (context $kubeContext): http://127.0.0.1:8000 (test client Base URL)" -ForegroundColor Green
        }

        'kind-obs' {
            # The cluster's Prometheus and Grafana are deliberately not routed by Traefik: that would make them
            # public. kubectl port-forward tunnels through the API server to their Services, on this PC only, for as
            # long as this task runs. Ports 9091/3001, not compose's 9090/3000, so the two stacks can't be mixed up.
            $forwards = @(
                @{ Name = 'prometheus'; Port = 9091; Target = 9090 },
                @{ Name = 'grafana'; Port = 3001; Target = 3000 })
            $jobs = foreach ($forward in $forwards) {
                Start-Job -Name "kind-obs-$($forward.Name)" -ArgumentList $kubeContext, $forward.Name, $forward.Port, $forward.Target -ScriptBlock {
                    param($context, $name, $port, $target)
                    kubectl --context $context -n observability port-forward "service/$name" "${port}:$target" --address 127.0.0.1
                }
            }
            try {
                Start-Sleep -Seconds 3
                Write-Host 'Grafana     http://127.0.0.1:3001 (admin / GRAFANA_ADMIN_PASSWORD)'
                Write-Host 'Prometheus  http://127.0.0.1:9091/targets'
                foreach ($url in 'http://127.0.0.1:3001', 'http://127.0.0.1:9091/targets') { Start-Process $url }
                Write-Host 'Forwarding until Ctrl+C.'
                # A forward is tied to the one pod it picked: it ends when that pod is replaced. Say so instead of
                # leaving a dead tunnel behind.
                Wait-Job -Job $jobs -Any | Out-Null
                $jobs | Receive-Job
                Write-Host 'A port-forward stopped (its pod was replaced?). Run .\tasks.ps1 kind-obs again.' -ForegroundColor Yellow
            }
            finally {
                $jobs | Stop-Job -PassThru | Remove-Job -Force
            }
        }

        # Deleting the cluster deletes its PVCs too: in-cluster data is dev scaffolding (K.1 §9).
        'kind-down' { Invoke-Native kind @('delete', 'cluster', '--name', $kindCluster) }

        'bench' {
            # -am builds the services' plain jars first (the classes under test). No tests: that's what build is for.
            Invoke-Native $mvnw @('-q', '-pl', 'benchmarks', '-am', 'package', '-DskipTests')

            # The JDK mvnw used (JAVA_HOME), not whichever java is first on PATH. JMH starts each fork with this JVM too.
            $java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin' 'java' } else { 'java' }
            $resultFile = Join-Path 'benchmarks' 'target' 'jmh-result.json'
            $benchArgs = @('-jar', (Join-Path 'benchmarks' 'target' 'benchmarks.jar'), '-rf', 'json', '-rff', $resultFile)
            if ($Only) { $benchArgs += $Only }
            Invoke-Native $java $benchArgs
            Write-Host "Saved $resultFile (the table above is the same data). How to read it: docs\benchmarks.md" -ForegroundColor Green
        }
    }
}
finally {
    Pop-Location
}
