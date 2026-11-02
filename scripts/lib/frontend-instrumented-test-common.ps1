#!/usr/bin/env pwsh
<#
Shared helpers for frontend instrumented test runners (E2E, NFR and regression).
Dot-source after setting $Root and $FrontendDir.

Expected layout:
  frontend\app\src\androidTest\...\{e2e,nfr,ui,integration}\
  frontend\app\build.gradle[.kts]
#>

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not $Root)        { throw 'ROOT must be set' }
if (-not $FrontendDir) { throw 'FRONTEND_DIR must be set' }

function Info($msg) { Write-Host "==> $msg" -ForegroundColor Blue }
function Warn($msg) { Write-Host "WARN: $msg" -ForegroundColor Yellow }
function Die($msg)  { Write-Host "ERROR: $msg" -ForegroundColor Red; exit 1 }

function Configure-AndroidHome {
    if ($env:ANDROID_HOME -and (Test-Path $env:ANDROID_HOME)) {
        # already set, use it
    }
    else {
        $localProperties = Join-Path $FrontendDir 'local.properties'
        $sdkLine = $null
        if (Test-Path $localProperties) {
            $sdkLine = Get-Content $localProperties | Where-Object { $_ -match '^\s*sdk\.dir\s*=' } | Select-Object -First 1
        }
        $sdkDir = $null
        if ($sdkLine) {
            $sdkDir = ($sdkLine -replace '^\s*sdk\.dir\s*=\s*', '').Trim(' ', '"')
            $sdkDir = $sdkDir -replace '\\:', ':'
            $sdkDir = $sdkDir -replace '\\\\', '\'
        }
        if ($sdkDir -and (Test-Path $sdkDir)) {
            $env:ANDROID_HOME = $sdkDir
        }
    }
    if ($env:ANDROID_HOME) {
        $env:Path = "$($env:ANDROID_HOME)\platform-tools;$($env:Path)"
    }
}

function Pick-GradleBuildFile($dir) {
    $kts = Join-Path $dir 'build.gradle.kts'
    $groovy = Join-Path $dir 'build.gradle'
    if (Test-Path $kts) { return $kts }
    if (Test-Path $groovy) { return $groovy }
    Die "Cannot find build.gradle.kts or build.gradle in $dir."
}

function Resolve-TestRoot {
    $script:TestRoot = Join-Path $FrontendDir 'app\src\androidTest'
    if (-not (Test-Path $script:TestRoot)) { Die "Cannot find $($script:TestRoot)." }
    $script:GradleTask = ':app:connectedDebugAndroidTest'
    $script:AppBuild = Pick-GradleBuildFile (Join-Path $FrontendDir 'app')
}

function Get-ApplicationId {
    $line = Get-Content $script:AppBuild | Where-Object { $_ -match '\sapplicationId\s*=' } | Select-Object -First 1
    if ($line -and ($line -match '=\s*["'']([^"'']+)["'']')) {
        return $Matches[1]
    }
    return $null
}

function Get-KotlinTestClass($file) {
    $package = $null
    $className = $null
    foreach ($line in Get-Content $file) {
        if (-not $package -and $line -match '^\s*package\s+(\S+)') {
            $package = $Matches[1]
        }
        if (-not $className -and $line -match '^\s*(class|object)\s+([A-Za-z0-9_]+)') {
            $className = $Matches[2]
        }
    }
    if (-not $package -or -not $className) { Die "Could not resolve test class from $file" }
    return "$package.$className"
}

function Discover-TestFiles($subdir) {
    $script:TestFiles = @(
        Get-ChildItem -Path $script:TestRoot -Recurse -File -Filter '*.kt' -ErrorAction SilentlyContinue |
            Where-Object { $_.FullName -match "[\\/]$([regex]::Escape($subdir))[\\/]" -and $_.Name -like '*Test.kt' } |
            Sort-Object FullName |
            ForEach-Object { $_.FullName }
    )
}

function Ensure-Backend {
    $startScript = Join-Path $Root 'scripts\run-backend.ps1'
    if (-not (Test-Path $startScript)) {
        Info "No scripts\run-backend.ps1; skipping backend startup."
        return
    }
    Info "Starting backend (scripts\run-backend.ps1) ..."
    & $startScript
    if ($LASTEXITCODE -ne 0) { Die "Backend startup failed." }
}

function Wait-ForAppSignIn {
    Warn "Sign in inside the app on the emulator (not just emulator Settings > Accounts)."
    Warn "  Use a disposable Ledger test account required by the selected scenario."
    Warn "  When the main app screen is visible, press Enter here to run tests ..."
    [void](Read-Host)
}

function Run-InstrumentedTests {
    param(
        [string]$suite,
        [switch]$ListOnly,
        [switch]$SkipSetup,
        [switch]$RequireSignIn,
        [string[]]$GradleArguments = @()
    )
    $subdir = $null
    $label = $null
    switch ($suite) {
        'e2e' { $subdir = 'e2e'; $label = 'E2E' }
        'nfr' { $subdir = 'nfr'; $label = 'NFR' }
        'ui' { $subdir = 'ui'; $label = 'UI regression' }
        'integration' { $subdir = 'integration'; $label = 'integration' }
        default { Die "Unknown suite '$suite' (expected e2e, nfr, ui or integration)." }
    }

    Resolve-TestRoot
    Discover-TestFiles $subdir

    if ($script:TestFiles.Count -eq 0) {
        if ($ListOnly) {
            Warn "No frontend ${label} tests exist yet; this is not a passing suite."
            return
        }
        Die "No frontend ${label} tests in app\src\androidTest\...\${subdir}\. See documentation/Testing.md."
    }

    $classes = @($script:TestFiles | ForEach-Object { Get-KotlinTestClass $_ })
    if ($ListOnly) { $classes | ForEach-Object { Write-Output $_ }; return }
    $classArg = $classes -join ','

    $appId = Get-ApplicationId
    if (-not $appId) { Die "Could not resolve applicationId from $($script:AppBuild)." }

    if (-not $SkipSetup) {
        Ensure-Backend
        $frontendScript = Join-Path $Root 'scripts\run-frontend.ps1'
        & $frontendScript
        if ($LASTEXITCODE -ne 0) { Die "Frontend startup failed." }
    }

    Configure-AndroidHome
    $adb = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
    if (-not (Test-Path $adb)) {
        $adbCmd = Get-Command adb -ErrorAction SilentlyContinue
        if ($adbCmd) { $adb = $adbCmd.Source }
        else { Die "adb not found." }
    }

    if ($RequireSignIn) { Wait-ForAppSignIn }
    & $adb shell am force-stop $appId 2>$null | Out-Null

    Info "Running frontend ${label} tests (${classArg}) ..."
    Push-Location $FrontendDir
    try {
        & .\gradlew.bat $script:GradleTask "-Pandroid.testInstrumentationRunnerArguments.class=$classArg" @GradleArguments
        if ($LASTEXITCODE -ne 0) { Die "Frontend ${label} tests failed." }
    }
    finally {
        Pop-Location
    }
    Info "Frontend ${label} tests finished."
}
