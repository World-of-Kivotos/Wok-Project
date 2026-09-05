param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$SourcePath,

    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$JarPath,

    [string]$OutputPath = (Join-Path $PSScriptRoot '..\..\src\main\resources\data\miningdim\fishing\journal\tide_1_6_5.json')
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$expectedSourceSha256 = '6A115C09DD48A72CA67CF57ED992DB3E760C3EE02BBD5EE7B3142E22DB3CFDDC'
$expectedJarSha256 = 'C5BB95A5B3B9F49A0838D4B72FCA964C791FE97284A2EE37E749882D61B7A424'

if (-not (Test-Path -LiteralPath $SourcePath -PathType Leaf)) { throw "Source file not found: $SourcePath" }
if (-not (Test-Path -LiteralPath $JarPath -PathType Leaf)) { throw "JAR file not found: $JarPath" }

$sourceHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $SourcePath).Hash
$jarHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $JarPath).Hash
if ($sourceHash -ne $expectedSourceSha256) { throw "Unexpected source SHA256: $sourceHash" }
if ($jarHash -ne $expectedJarSha256) { throw "Unexpected JAR SHA256: $jarHash" }

Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [System.IO.Compression.ZipFile]::OpenRead((Resolve-Path -LiteralPath $JarPath))
try {
    $modsEntry = $archive.GetEntry('META-INF/mods.toml')
    if ($null -eq $modsEntry) { throw 'JAR has no META-INF/mods.toml' }
    $reader = [System.IO.StreamReader]::new($modsEntry.Open())
    try { $modsToml = $reader.ReadToEnd() } finally { $reader.Dispose() }
    if ($modsToml -notmatch '(?m)^\s*modId\s*=\s*"tide"\s*(?:#.*)?$') { throw 'JAR modId is not tide' }
    if ($modsToml -notmatch '(?m)^\s*version\s*=\s*"1\.6\.5"\s*(?:#.*)?$') { throw 'JAR version is not 1.6.5' }

    $langEntry = $archive.GetEntry('assets/tide/lang/en_us.json')
    if ($null -eq $langEntry) { throw 'JAR has no English language file' }
    $reader = [System.IO.StreamReader]::new($langEntry.Open())
    try { $lang = $reader.ReadToEnd() | ConvertFrom-Json } finally { $reader.Dispose() }

    $source = Get-Content -Raw -LiteralPath $SourcePath
    $pattern = 'addProfile\("(?<item>tide:[^"]+)",\s*"(?<description>[^"]+)",\s*"(?<category>[^"]+)",\s*"(?<habitat>[^"]+)",\s*"(?<conditions>[^"]+)"\)'
    $matches = [regex]::Matches($source, $pattern)
    if ($matches.Count -eq 0) { throw 'No tide addProfile entries found' }

    $entries = [System.Collections.Generic.List[object]]::new()
    $seen = [System.Collections.Generic.HashSet[string]]::new()
    foreach ($match in $matches) {
        $item = $match.Groups['item'].Value
        $path = $item.Substring(5)
        if (-not $seen.Add($item)) { throw "Duplicate item: $item" }

        $keys = @(
            $match.Groups['description'].Value,
            "profile.info.location.$($match.Groups['habitat'].Value)",
            "profile.info.climate.$($match.Groups['conditions'].Value)"
        )
        foreach ($key in $keys) {
            if ($null -eq $lang.PSObject.Properties[$key]) { throw "Missing English translation key: $key" }
        }
        if ($null -eq $archive.GetEntry("assets/tide/models/item/$path.json")) {
            throw "Missing item model: assets/tide/models/item/$path.json"
        }

        $entries.Add([ordered]@{
            item = $item
            category = $match.Groups['category'].Value
            description = $match.Groups['description'].Value
            habitat = $keys[1]
            conditions = $keys[2]
        })
    }

    $parent = Split-Path -Parent $OutputPath
    if (-not (Test-Path -LiteralPath $parent)) { New-Item -ItemType Directory -Path $parent -Force | Out-Null }
    $document = [ordered]@{ required_mod = 'tide'; required_version = '1.6.5'; entries = $entries }
    $document | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $OutputPath -Encoding utf8
    Write-Output "Generated $($entries.Count) Tide entries at $OutputPath"
} finally {
    $archive.Dispose()
}
