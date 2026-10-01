#Requires -Version 7.0
<#
.SYNOPSIS
    GET /actuator/health on every Buzzer service, print a table, exit 1 unless all are UP.
#>
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# The management ports (management.server.port): actuator is not served on the services' own ports.
$services = [ordered]@{
    'gateway'          = 9080
    'identity-service' = 9081
    'quiz-service'     = 9082
    'session-service'  = 9083
    'scoring-service'  = 9084
}

$results = foreach ($entry in $services.GetEnumerator()) {
    $url = "http://localhost:$($entry.Value)/actuator/health"
    $status = try {
        # Actuator answers 503 when DOWN; -SkipHttpErrorCheck returns that body instead of throwing.
        $response = Invoke-RestMethod -Uri $url -TimeoutSec 3 -SkipHttpErrorCheck
        $property = if ($response) { $response.PSObject.Properties['status'] } else { $null }
        if ($property) { [string] $property.Value } else { 'UNKNOWN' }
    }
    catch {
        # Connection refused or 3 s timeout: nothing answered on that port.
        'UNREACHABLE'
    }
    [pscustomobject]@{ Service = $entry.Key; Port = $entry.Value; Status = $status }
}

$results | Format-Table -AutoSize

if ($results | Where-Object Status -ne 'UP') { exit 1 }
exit 0
