#Requires -Version 7.0
<#
.SYNOPSIS
    Prepares a game for docs/chaos-scoring.md: a throwaway host account and a published 8-question quiz.
.DESCRIPTION
    Goes through the gateway, like the test client. Needs the gateway, identity-service and quiz-service running.
    Registers a fresh host with a random password on every run, so no credential is stored anywhere and reruns
    never collide. The password is put on the clipboard (never printed): paste it into the test client's host
    login. Prints the host's email and the quiz id.
.EXAMPLE
    .\tools\http\chaos-game.ps1
#>
param(
    [string] $BaseUrl = 'http://localhost:8080'
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

# 1. A brand-new host. The password never reaches the screen: it goes to the clipboard at the end.
$email = "chaos-$([guid]::NewGuid().ToString('N').Substring(0, 8))@test.dev"
$password = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(18))
$credentials = @{ email = $email; password = $password }
Invoke-Json Post "$BaseUrl/api/auth/register" $credentials | Out-Null
$login = Invoke-Json Post "$BaseUrl/api/auth/login" $credentials
$auth = @{ Authorization = "Bearer $($login.accessToken)" }

# 2. The quiz: 8 questions, 4 options each, the correct one in a different place each time, 30 s to answer
#    (time to click through several browser tabs). Every change returns the quiz with its new version,
#    which the next change must send back (optimistic locking).
$quiz = Invoke-Json Post "$BaseUrl/api/quizzes" @{ title = "Chaos $(Get-Date -Format 'HH:mm:ss')" } $auth
$questions = @(
    @{ text = 'Capital of France?';    options = @('Paris', 'Lyon', 'Nice', 'Lille');           correct = 0 },
    @{ text = 'Capital of Japan?';     options = @('Osaka', 'Tokyo', 'Kyoto', 'Nagoya');        correct = 1 },
    @{ text = 'Capital of Italy?';     options = @('Milan', 'Turin', 'Rome', 'Naples');         correct = 2 },
    @{ text = 'Capital of Canada?';    options = @('Toronto', 'Montreal', 'Vancouver', 'Ottawa'); correct = 3 },
    @{ text = 'Capital of Australia?'; options = @('Canberra', 'Sydney', 'Perth', 'Melbourne'); correct = 0 },
    @{ text = 'Capital of Brazil?';    options = @('Rio', 'Brasilia', 'Recife', 'Salvador');    correct = 1 },
    @{ text = 'Capital of Kenya?';     options = @('Mombasa', 'Kisumu', 'Nairobi', 'Nakuru');   correct = 2 },
    @{ text = 'Capital of Norway?';    options = @('Bergen', 'Stavanger', 'Tromso', 'Oslo');    correct = 3 }
)
foreach ($question in $questions) {
    $options = for ($i = 0; $i -lt $question.options.Count; $i++) {
        @{ text = $question.options[$i]; correct = ($i -eq $question.correct) }
    }
    $body = @{ text = $question.text; timeLimitSeconds = 30; version = $quiz.version; options = @($options) }
    $quiz = Invoke-Json Post "$BaseUrl/api/quizzes/$($quiz.id)/questions" $body $auth
}
$quiz = Invoke-Json Post "$BaseUrl/api/quizzes/$($quiz.id)/publish" @{ version = $quiz.version } $auth

Set-Clipboard -Value $password
Write-Host ''
Write-Host "Host email : $email"
Write-Host 'Password   : on your clipboard (paste it into the test client; it is not shown here)'
Write-Host "Quiz id    : $($quiz.id)  ($($questions.Count) questions, status $($quiz.status))" -ForegroundColor Green
