[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string] $ManifestPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $ManifestPath -PathType Leaf)) {
    throw "Bench manifest not found: $ManifestPath"
}

$manifest = Get-Content -LiteralPath $ManifestPath -Raw | ConvertFrom-Json
$required = @(
    'schemaVersion',
    'runtimeVariant',
    'serverArtifact',
    'pluginArtifact',
    'extensionSet',
    'bots',
    'worlds',
    'warmupSeconds',
    'measurementSeconds',
    'minSamples',
    'protocol'
)

foreach ($property in $required) {
    if ($null -eq $manifest.PSObject.Properties[$property]) {
        throw "Missing manifest property: $property"
    }
}

if ([int]$manifest.schemaVersion -ne 1) {
    throw "Unsupported bench manifest schemaVersion: $($manifest.schemaVersion)"
}
if ([string]$manifest.runtimeVariant -ne 'btccore') {
    throw "Non-comparable runtimeVariant: $($manifest.runtimeVariant); expected btccore"
}
if ([int]$manifest.bots -ne 50 -or [int]$manifest.worlds -ne 50) {
    throw "The reference load requires exactly 50 bots and 50 worlds"
}
if ([int]$manifest.warmupSeconds -lt 60) {
    throw "warmupSeconds must be at least 60"
}
if ([int]$manifest.measurementSeconds -lt 90) {
    throw "measurementSeconds must be at least 90"
}
if ([int]$manifest.minSamples -lt 30) {
    throw "minSamples must be at least 30"
}
if ([string]$manifest.protocol -ne "A/B/A'") {
    throw "protocol must be A/B/A'"
}
if ($manifest.extensionSet -isnot [System.Collections.IEnumerable] -or @($manifest.extensionSet).Count -eq 0) {
    throw "extensionSet must contain at least one deployed extension"
}

foreach ($artifactProperty in @('serverArtifact', 'pluginArtifact')) {
    $artifact = $manifest.$artifactProperty
    foreach ($property in @('path', 'sha256')) {
        if ($null -eq $artifact.PSObject.Properties[$property] -or
            [string]::IsNullOrWhiteSpace([string]$artifact.$property)) {
            throw "$artifactProperty.$property is required"
        }
    }
    if ([string]$artifact.sha256 -notmatch '^[A-Fa-f0-9]{64}$') {
        throw "$artifactProperty.sha256 must be a SHA-256 hex digest"
    }
}

[pscustomobject]@{
    valid = $true
    schemaVersion = [int]$manifest.schemaVersion
    runtimeVariant = [string]$manifest.runtimeVariant
    bots = [int]$manifest.bots
    worlds = [int]$manifest.worlds
    warmupSeconds = [int]$manifest.warmupSeconds
    measurementSeconds = [int]$manifest.measurementSeconds
    minSamples = [int]$manifest.minSamples
    protocol = [string]$manifest.protocol
    extensionCount = @($manifest.extensionSet).Count
} | ConvertTo-Json -Compress
