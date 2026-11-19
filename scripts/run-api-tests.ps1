#Requires -Version 7.4
param(
    [ValidateRange(1, 10)]
    [int] $Runs = 1
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$project = 'smp-practice-5-' + [guid]::NewGuid().ToString('N').Substring(0, 12)
$network = "${project}_smp-network"
$runner = "$project-runner"
$results = Join-Path $root "services/api-tests/target/runs/$project"
$stackRequested = $false
$runnerStarted = $false
$suitePrepared = $false
$previousGhcrUser = $env:GHCR_USER
$env:GHCR_USER = $project
$compose = @('compose', '--project-name', $project, '--env-file', '.env.example', '--profile', 'authorization-faults',
    '-f', 'docker-compose.yaml', '-f', 'services/api-tests/compose.yaml')
$services = @('gateway', 'card-management', 'switch', 'authorization', 'bin-lookup',
    'merchant-acquirer', 'terminal-simulator', 'transaction-logger')

function Invoke-Docker {
    param([string[]] $DockerArguments)
    docker @DockerArguments
    if ($LASTEXITCODE -ne 0) {
        throw "Docker command exited with code $LASTEXITCODE."
    }
}

function Get-ApiSuiteArguments {
    param([string] $Phase, [string] $Tags)
    $arguments = @('exec', $runner, 'mvn', '-B', '-ntp', '-f', 'services/pom.xml', '-Papi-tests', '-pl', 'api-tests')
    $goal = 'test'
    if ($suitePrepared) {
        $arguments += '-o'
        $goal = 'surefire:test'
    }
    $arguments + @(
        '-DfailIfNoTests=true', '-Dapi.test.reportsDir=/results/surefire-reports', "-Dapi.test.phase=run-$run/$Phase",
        "-Dapi.test.brokerSignals=/results/run-$run/broker", "-Dgroups=$Tags", $goal)
}

function Invoke-ApiSuite {
    param([string] $Phase, [string] $Tags = $Phase)
    Write-Host "API test phase: $Phase"
    Invoke-Docker (Get-ApiSuiteArguments $Phase $Tags)
}

function Invoke-BrokerOutageSuite {
    Write-Host 'API test phase: broker-outage'
    $signals = Join-Path $results "run-$run/broker"
    $arguments = Get-ApiSuiteArguments 'broker-outage' 'broker-outage'
    Invoke-Docker ($compose + @('stop', '--timeout', '3', 'rabbitmq'))
    $job = Start-ThreadJob -ArgumentList (,$arguments) -ScriptBlock {
        param([string[]] $DockerArguments)
        docker @DockerArguments 2>&1
        if ($LASTEXITCODE -ne 0) {
            throw "Broker outage test exited with code $LASTEXITCODE."
        }
    }
    try {
        $timer = [System.Diagnostics.Stopwatch]::StartNew()
        while (-not (Test-Path -LiteralPath (Join-Path $signals 'restart-requested'))) {
            if ($job.State -in @('Completed', 'Failed', 'Stopped')) {
                Receive-Job $job
                throw 'Broker outage test ended before requesting restoration.'
            }
            if ($timer.Elapsed.TotalSeconds -ge 240) {
                throw 'Broker outage test did not request restoration within 240 seconds.'
            }
            Start-Sleep -Seconds 1
        }
        Invoke-Docker ($compose + @('up', '-d', '--no-build', 'rabbitmq'))
        Wait-HealthyServices @('rabbitmq')
        New-Item -ItemType File -Path (Join-Path $signals 'broker-healthy') | Out-Null
        Receive-Job $job -Wait
        if ($job.State -ne 'Completed') {
            throw 'Broker outage test failed.'
        }
    }
    finally {
        if ($job.State -notin @('Completed', 'Failed', 'Stopped')) {
            Stop-Job $job
        }
        Remove-Job $job
    }
}

function Wait-HealthyServices {
    param([string[]] $TargetServices, [int] $TimeoutSeconds = 480)
    $timer = [System.Diagnostics.Stopwatch]::StartNew()
    do {
        $rows = docker @compose ps --all --format json @TargetServices
        if ($LASTEXITCODE -ne 0) {
            throw 'Could not read Docker Compose service status.'
        }
        $healthy = @($rows | ConvertFrom-Json | Where-Object { $_.State -eq 'running' -and $_.Health -eq 'healthy' })
        if ($healthy.Count -eq $TargetServices.Count) {
            return
        }
        Start-Sleep -Seconds 5
    } while ($timer.Elapsed.TotalSeconds -lt $TimeoutSeconds)
    throw "Timed out waiting for Docker Compose services: $($TargetServices -join ', ')."
}

Push-Location $root
try {
    Write-Host "Docker Compose project: $project"
    Invoke-Docker ($compose + @('build') + $services)
    New-Item -ItemType Directory -Path $results -Force | Out-Null
    Invoke-Docker @('run', '-d', '--init', '--name', $runner,
        '--env', 'JAVA_TOOL_OPTIONS=-XX:ActiveProcessorCount=2 -XX:TieredStopAtLevel=1 -Xmx512m',
        '--mount', "type=bind,source=$results,target=/results",
        '--mount', 'type=volume,source=smp-practice-5-maven,target=/root/.m2',
        '--workdir', '/workspace', 'maven:3.9.11-eclipse-temurin-21', 'sleep', 'infinity')
    $runnerStarted = $true
    Invoke-Docker @('exec', $runner, 'mkdir', '-p', '/workspace/services/api-tests')
    Invoke-Docker @('cp', 'services/pom.xml', "${runner}:/workspace/services/pom.xml")
    Invoke-Docker @('cp', 'services/api-tests/pom.xml', "${runner}:/workspace/services/api-tests/pom.xml")
    Invoke-Docker @('cp', 'services/api-tests/src', "${runner}:/workspace/services/api-tests/src")
    for ($run = 1; $run -le $Runs; $run++) {
        $runnerAttached = $false
        $runTimer = [System.Diagnostics.Stopwatch]::StartNew()
        try {
            Write-Host "API test run $run of $Runs"
            $stackRequested = $true
            # Allow slow first starts to become healthy before Compose checks service dependencies.
            Invoke-Docker ($compose + @('up', '-d', '--no-build', 'postgres', 'rabbitmq'))
            Wait-HealthyServices @('postgres', 'rabbitmq')
            Invoke-Docker ($compose + @('up', '-d', '--no-build') + $services)
            Wait-HealthyServices $services
            Invoke-Docker @('network', 'connect', $network, $runner)
            $runnerAttached = $true
            $faultTags = 'broker-outage | authorization-down | authorization-faults | switch-down | card-down'
            $faultTags += ' | downstream-down | terminal-errors | switch-dns | postgres-down | openapi-faults'
            $faultTags += ' | switch-missing-pan'
            Invoke-ApiSuite 'normal' "!($faultTags)"
            $suitePrepared = $true
            Invoke-BrokerOutageSuite
            Invoke-Docker ($compose + @('stop', '--timeout', '3', 'authorization'))
            Invoke-ApiSuite 'authorization-down'
            Invoke-Docker ($compose + @('up', '-d', '--no-build', 'card-management-faults'))
            $previousCardMgmtUrl = $env:PRACTICE5_CARD_MGMT_URL
            $previousCapacity = $env:TRANSACTIONS_RATE_LIMIT_CAPACITY
            $previousRefill = $env:TRANSACTIONS_RATE_LIMIT_REFILL_PER_SECOND
            $previousCircuitBreakerThreshold = $env:PRACTICE5_CIRCUIT_BREAKER_THRESHOLD
            $previousSwitchUrl = $env:PRACTICE5_SWITCH_URL
            $previousCardsUrl = $env:PRACTICE5_GATEWAY_CARD_URL
            $previousMerchantUrl = $env:PRACTICE5_MERCHANT_GATEWAY_URL
            $previousTerminalUrl = $env:PRACTICE5_TERMINAL_CARD_MGMT_URL
            try {
                $env:TRANSACTIONS_RATE_LIMIT_CAPACITY = '1'
                $env:TRANSACTIONS_RATE_LIMIT_REFILL_PER_SECOND = '0'
                # Each failure test must reach its own upstream while the fault stack is active.
                $env:PRACTICE5_CIRCUIT_BREAKER_THRESHOLD = '30'
                $env:PRACTICE5_SWITCH_URL = 'http://switch:65534'
                $env:PRACTICE5_GATEWAY_CARD_URL = 'http://card-management:65534'
                $env:PRACTICE5_CARD_MGMT_URL = 'http://card-management-faults:8080'
                $env:PRACTICE5_MERCHANT_GATEWAY_URL = 'http://card-management-faults:8081'
                $env:PRACTICE5_TERMINAL_CARD_MGMT_URL = 'http://card-management-faults:8081'
                $faultServices = @('authorization', 'gateway', 'merchant-acquirer', 'terminal-simulator')
                Invoke-Docker ($compose + @('up', '-d', '--no-deps', '--no-build', '--force-recreate') + $faultServices)
                Wait-HealthyServices $faultServices
            }
            finally {
                $env:TRANSACTIONS_RATE_LIMIT_CAPACITY = $previousCapacity
                $env:TRANSACTIONS_RATE_LIMIT_REFILL_PER_SECOND = $previousRefill
                $env:PRACTICE5_CIRCUIT_BREAKER_THRESHOLD = $previousCircuitBreakerThreshold
                $env:PRACTICE5_SWITCH_URL = $previousSwitchUrl
                $env:PRACTICE5_GATEWAY_CARD_URL = $previousCardsUrl
                $env:PRACTICE5_CARD_MGMT_URL = $previousCardMgmtUrl
                $env:PRACTICE5_MERCHANT_GATEWAY_URL = $previousMerchantUrl
                $env:PRACTICE5_TERMINAL_CARD_MGMT_URL = $previousTerminalUrl
            }
            Invoke-ApiSuite 'downstream-down' 'authorization-faults | switch-down | card-down | downstream-down'
            try {
                $env:PRACTICE5_TERMINAL_CARD_MGMT_URL = 'http://card-management-faults:8081/terminal-errors'
                $env:PRACTICE5_SWITCH_URL = 'http://switch-unavailable.invalid:8080'
                $env:PRACTICE5_CIRCUIT_BREAKER_THRESHOLD = '30'
                Invoke-Docker ($compose + @('up', '-d', '--no-deps', '--no-build', '--force-recreate', 'terminal-simulator', 'gateway'))
                Wait-HealthyServices @('terminal-simulator', 'gateway')
            }
            finally {
                $env:PRACTICE5_TERMINAL_CARD_MGMT_URL = $previousTerminalUrl
                $env:PRACTICE5_SWITCH_URL = $previousSwitchUrl
                $env:PRACTICE5_CIRCUIT_BREAKER_THRESHOLD = $previousCircuitBreakerThreshold
            }
            # Terminal's fixture and the failed Switch lookup do not use PostgreSQL.
            Invoke-Docker ($compose + @('stop', '--timeout', '3', 'postgres'))
            Invoke-ApiSuite 'postgres-down' 'terminal-errors | switch-dns | postgres-down'
            $documentationUpstreams = @('PRACTICE5_SWITCH_URL', 'PRACTICE5_GATEWAY_CARD_URL',
                'PRACTICE5_LOGGER_URL', 'PRACTICE5_TERMINAL_URL', 'PRACTICE5_MERCHANT_URL')
            $previousUpstreams = @{}
            try {
                foreach ($name in $documentationUpstreams) {
                    $previousUpstreams[$name] = [Environment]::GetEnvironmentVariable($name)
                    [Environment]::SetEnvironmentVariable($name, 'http://card-management-faults:8081')
                }
                Invoke-Docker ($compose + @('up', '-d', '--no-deps', '--no-build', '--force-recreate', 'gateway'))
                Wait-HealthyServices @('gateway')
            }
            finally {
                foreach ($name in $previousUpstreams.Keys) {
                    [Environment]::SetEnvironmentVariable($name, $previousUpstreams[$name])
                }
            }
            Invoke-ApiSuite 'openapi-faults'
            # Switch publishes the PAN-less decline. Logger requeues it, so run this test just before teardown.
            Invoke-ApiSuite 'switch-missing-pan'
        }
        catch {
            docker @compose ps --all
            docker @compose logs --tail 25
            throw
        }
        finally {
            if ($runnerAttached) {
                Invoke-Docker @('network', 'disconnect', $network, $runner)
            }
            if ($stackRequested) {
                Invoke-Docker ($compose + @('down', '--volumes', '--timeout', '3'))
                $stackRequested = $false
            }
        }
        Write-Host "API test run $run of $Runs completed in $([math]::Round($runTimer.Elapsed.TotalSeconds)) seconds"
    }
}
finally {
    try {
        if ($runnerStarted) {
            Invoke-Docker @('rm', '-f', $runner)
        }
    }
    finally {
        try {
            if ($stackRequested) {
                Invoke-Docker ($compose + @('down', '--volumes', '--timeout', '3'))
            }
        }
        finally {
            $env:GHCR_USER = $previousGhcrUser
            Pop-Location
        }
    }
}
