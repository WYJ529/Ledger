#!/usr/bin/env pwsh
# Supplemental tests; these are not the course end-to-end acceptance suite.
param(
    [ValidateSet('ui', 'integration')][string]$Suite = 'ui',
    [switch]$ListOnly,
    [switch]$SkipSetup,
    [switch]$RequireSignIn,
    [string[]]$GradleArguments = @()
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$Root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$FrontendDir = if ($env:FRONTEND_DIR) { $env:FRONTEND_DIR } else { Join-Path $Root 'frontend' }
$env:FRONTEND_DIR = $FrontendDir
. (Join-Path $Root 'scripts/lib/frontend-instrumented-test-common.ps1')
Run-InstrumentedTests $Suite -ListOnly:$ListOnly -SkipSetup:$SkipSetup -RequireSignIn:$RequireSignIn -GradleArguments $GradleArguments
