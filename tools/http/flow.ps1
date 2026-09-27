#Requires -Version 7.0
<#
.SYNOPSIS
    Phase 1 end to end: register -> login -> create quiz -> add questions -> publish -> internal snapshot.
.DESCRIPTION
    Needs identity-service (8081) and quiz-service (8082) running, for example in two terminals:
        .\tasks.ps1 run -Svc identity-service
        .\tasks.ps1 run -Svc quiz-service
    Registers a fresh host with a random password on every run, so no credential is stored here and
    reruns never collide. Any non-2xx response stops the script and shows the ProblemDetail body.
.EXAMPLE
    .\tools\http\flow.ps1
#>
param(
    [string] $IdentityUrl = 'http://localhost:8081',
    [string] $QuizUrl = 'http://localhost:8082'
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Invoke-Json {
    param(
        [string] $Method,
        [string] $Uri,
        [object] $Body,
        [hashtable] $Headers = @{}
    )
    # -Depth 5: ConvertTo-Json flattens nested objects (the options) below depth 2 by default.
    Invoke-RestMethod -Method $Method -Uri $Uri -Headers $Headers -ContentType 'application/json' `
        -Body ($Body | ConvertTo-Json -Depth 5)
}

# 1. Register and log in a brand-new host.
$email = "flow-$([guid]::NewGuid().ToString('N').Substring(0, 8))@test.dev"
$password = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(18))
$credentials = @{ email = $email; password = $password }

Invoke-Json Post "$IdentityUrl/api/auth/register" $credentials | Out-Null
$login = Invoke-Json Post "$IdentityUrl/api/auth/login" $credentials
$auth = @{ Authorization = "Bearer $($login.accessToken)" }
Write-Host "Registered and logged in as $email" -ForegroundColor Green

# 2. Create a quiz. Every change returns the whole quiz, so $quiz always holds the latest version,
#    which the next change must send back (optimistic locking).
$quiz = Invoke-Json Post "$QuizUrl/api/quizzes" @{ title = "Capitals $(Get-Date -Format HHmmss)" } $auth
Write-Host "Created quiz $($quiz.id) (version $($quiz.version))"

# 3. Add two questions.
$questions = @(
    @{ text = 'Capital of France?'; right = 'Paris'; wrong = 'Lyon' },
    @{ text = 'Capital of Japan?'; right = 'Tokyo'; wrong = 'Osaka' }
)
foreach ($question in $questions) {
    $body = @{
        text             = $question.text
        timeLimitSeconds = 20
        version          = $quiz.version
        options          = @(
            @{ text = $question.right; correct = $true },
            @{ text = $question.wrong; correct = $false }
        )
    }
    $quiz = Invoke-Json Post "$QuizUrl/api/quizzes/$($quiz.id)/questions" $body $auth
    Write-Host "Added '$($question.text)' (version $($quiz.version))"
}

# 4. Publish.
$quiz = Invoke-Json Post "$QuizUrl/api/quizzes/$($quiz.id)/publish" @{ version = $quiz.version } $auth
Write-Host "Published: status $($quiz.status), version $($quiz.version)" -ForegroundColor Green

# 5. Fetch the snapshot the way session-service will: internal endpoint, no token.
$snapshot = Invoke-RestMethod -Uri "$QuizUrl/internal/quizzes/$($quiz.id)/snapshot"
if ($snapshot.questions.Count -ne $questions.Count) {
    throw "Snapshot has $($snapshot.questions.Count) questions, expected $($questions.Count)"
}
Write-Host "Snapshot OK: $($snapshot.questions.Count) questions, correct answers included" -ForegroundColor Green
$snapshot | ConvertTo-Json -Depth 5
