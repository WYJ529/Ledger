#!/usr/bin/env pwsh
<#
Run backend NFR tests in backend\tests\nfr\

Usage:
  .\scripts\run-backend-nfr-tests.ps1

NOTE ON EXECUTION POLICY:
  Windows blocks script execution by default. If running this script fails
  with a message about execution policies, either:
    - run once: Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass
    - or launch with: pwsh -ExecutionPolicy Bypass -File .\scripts\run-backend-nfr-tests.ps1
#>

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$Root = (Resolve-Path (Join-Path (Split-Path -Parent $MyInvocation.MyCommand.Path) '..')).Path
$BackendDir = Join-Path $Root 'backend'
$NfrDir = Join-Path $BackendDir 'tests\nfr'

function Info($msg) { Write-Host "==> $msg" -ForegroundColor Blue }
function Warn($msg) { Write-Host "WARN: $msg" -ForegroundColor Yellow }
function Die($msg)  { Write-Host "ERROR: $msg" -ForegroundColor Red; exit 1 }

function HasTestFiles($dir, $pattern) {
    if (-not (Test-Path $dir)) { return $false }
    $match = Get-ChildItem -Path $dir -Filter $pattern -Recurse -File -ErrorAction SilentlyContinue |
        Select-Object -First 1
    return [bool]$match
}

# ---------------------------------------------------------------------------
# Discover backend NFR tests
# ---------------------------------------------------------------------------

if ((Test-Path $NfrDir) -and (HasTestFiles $NfrDir '*.test.ts')) {
    # proceed
}
elseif (Test-Path $NfrDir) {
    Die "No backend NFR tests exist yet in backend/tests/nfr/. See documentation/Testing.md; this is not a passing suite."
}
else {
    Die "Missing backend/tests/nfr/. See documentation/Testing.md."
}

if (-not (Get-Command node -ErrorAction SilentlyContinue)) { Die "Node.js not found." }
if (-not (Get-Command npm -ErrorAction SilentlyContinue))  { Die "npm not found." }

if (-not (Test-Path (Join-Path $BackendDir 'node_modules'))) {
    Info "Installing backend dependencies ..."
    Push-Location $BackendDir
    try {
        npm ci --include=dev
        if ($LASTEXITCODE -ne 0) { Die "npm ci failed." }
    }
    finally {
        Pop-Location
    }
}

Info "Running backend NFR tests (tests/nfr/) ..."
Push-Location $BackendDir
try {
    npm test -- tests/nfr/ --runInBand
    if ($LASTEXITCODE -ne 0) { Die "Backend NFR tests failed." }
}
finally {
    Pop-Location
}

Write-Host ""
Info "Backend NFR tests finished."
