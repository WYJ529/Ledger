#!/usr/bin/env pwsh
<#
Run frontend E2E tests (app/src/androidTest/.../e2e/*.kt).

Manual sign-in is optional: use -RequireSignIn (PowerShell) or --require-sign-in (Bash).
Usage: .\scripts\run-frontend-e2e-tests.ps1

NOTE ON EXECUTION POLICY:
  Windows blocks script execution by default. If running this script fails
  with a message about execution policies, either:
    - run once: Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass
    - or launch with: pwsh -ExecutionPolicy Bypass -File .\scripts\run-frontend-e2e-tests.ps1
#>

param(
    [switch]$ListOnly,
    [switch]$SkipSetup,
    [switch]$RequireSignIn,
    [string[]]$GradleArguments = @()
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$Root = (Resolve-Path (Join-Path (Split-Path -Parent $MyInvocation.MyCommand.Path) '..')).Path
$FrontendDir = if ($env:FRONTEND_DIR) { $env:FRONTEND_DIR } else { Join-Path $Root 'frontend' }
$env:FRONTEND_DIR = $FrontendDir

. (Join-Path $Root 'scripts\lib\frontend-instrumented-test-common.ps1')
Run-InstrumentedTests e2e -ListOnly:$ListOnly -SkipSetup:$SkipSetup -RequireSignIn:$RequireSignIn -GradleArguments $GradleArguments
