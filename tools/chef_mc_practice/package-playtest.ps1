param(
    [Parameter(Mandatory = $true)][string]$BaselineJar,
    [Parameter(Mandatory = $true)][string]$BuiltJar,
    [Parameter(Mandatory = $true)][string]$OutputJar,
    [string]$Version = '1.0.35-chef-playtest'
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$baselinePath = [IO.Path]::GetFullPath($BaselineJar)
$builtPath = [IO.Path]::GetFullPath($BuiltJar)
$outputPath = [IO.Path]::GetFullPath($OutputJar)
if ($outputPath -eq $baselinePath -or $outputPath -eq $builtPath -or (Test-Path -LiteralPath $outputPath)) {
    throw 'Output must be a new file, separate from both inputs.'
}
if ($Version -notmatch '^[a-zA-Z0-9._-]+$') { throw 'Invalid manifest version.' }
$prefixes = @(
    'com/miningdim/job/chef/zhangshao/',
    'assets/miningdim/zhangshao/',
    'data/miningdim/zhangshao/',
    'META-INF/licenses/zhangshao/'
)
function Is-PracticeEntry([string]$name) {
    foreach ($prefix in $prefixes) {
        if ($name.StartsWith($prefix, [StringComparison]::Ordinal)) { return $true }
    }
    return $false
}
function Copy-ArchiveEntry($sourceEntry, $destination) {
    $created = $destination.CreateEntry($sourceEntry.FullName, [IO.Compression.CompressionLevel]::Optimal)
    $created.LastWriteTime = $sourceEntry.LastWriteTime
    $inputStream = $sourceEntry.Open()
    $outputStream = $created.Open()
    try { $inputStream.CopyTo($outputStream) }
    finally { $outputStream.Dispose(); $inputStream.Dispose() }
}
function Entry-Hash($entry) {
    $stream = $entry.Open()
    $hasher = [Security.Cryptography.SHA256]::Create()
    try { return [Convert]::ToHexString($hasher.ComputeHash($stream)) }
    finally { $hasher.Dispose(); $stream.Dispose() }
}
$baselineHash = (Get-FileHash -LiteralPath $baselinePath -Algorithm SHA256).Hash
$builtHash = (Get-FileHash -LiteralPath $builtPath -Algorithm SHA256).Hash
$baseline = [IO.Compression.ZipFile]::OpenRead($baselinePath)
$built = [IO.Compression.ZipFile]::OpenRead($builtPath)
try {
    $metadataReader = [IO.StreamReader]::new($baseline.GetEntry('META-INF/mods.toml').Open())
    try { $metadata = $metadataReader.ReadToEnd() } finally { $metadataReader.Dispose() }
    if ($metadata -notmatch '(?m)^modId\s*=\s*"miningdim"\s*$') { throw 'Baseline is not WOK miningdim.' }
    $additions = @($built.Entries | Where-Object { Is-PracticeEntry $_.FullName })
    if (-not ($additions.FullName -contains 'com/miningdim/job/chef/zhangshao/client/ZhangshaoPracticeScreen.class')) {
        throw 'Built JAR does not contain the practice screen.'
    }
    foreach ($entry in $additions) {
        if ($null -ne $baseline.GetEntry($entry.FullName)) { throw ('Baseline already contains practice content: ' + $entry.FullName) }
    }
    $parent = Split-Path -Parent $outputPath
    New-Item -ItemType Directory -Path $parent -Force | Out-Null
    $output = [IO.Compression.ZipFile]::Open($outputPath, [IO.Compression.ZipArchiveMode]::Create)
    try {
        foreach ($entry in $baseline.Entries) {
            if ($entry.FullName -eq 'META-INF/MANIFEST.MF') {
                $reader = [IO.StreamReader]::new($entry.Open())
                try { $manifest = $reader.ReadToEnd() } finally { $reader.Dispose() }
                if ($manifest -notmatch '(?m)^Implementation-Version:') { throw 'Missing baseline version.' }
                $manifest = [regex]::Replace($manifest, '(?m)^Implementation-Version:[^\r\n]*', "Implementation-Version: $Version")
                $manifestEntry = $output.CreateEntry('META-INF/MANIFEST.MF')
                $writer = [IO.StreamWriter]::new($manifestEntry.Open(), [Text.UTF8Encoding]::new($false))
                try { $writer.Write($manifest) } finally { $writer.Dispose() }
            } else {
                Copy-ArchiveEntry $entry $output
            }
        }
        foreach ($entry in $additions) { Copy-ArchiveEntry $entry $output }
    } finally { $output.Dispose() }
    $check = [IO.Compression.ZipFile]::OpenRead($outputPath)
    try {
        foreach ($entry in $baseline.Entries) {
            if ($entry.FullName -eq 'META-INF/MANIFEST.MF') { continue }
            $copy = $check.GetEntry($entry.FullName)
            if ($null -eq $copy -or (Entry-Hash $entry) -ne (Entry-Hash $copy)) {
                throw ('Baseline content changed: ' + $entry.FullName)
            }
        }
        foreach ($entry in $additions) {
            if ((Entry-Hash $entry) -ne (Entry-Hash $check.GetEntry($entry.FullName))) {
                throw ('Practice entry differs: ' + $entry.FullName)
            }
        }
        if ($check.Entries.Count -ne $baseline.Entries.Count + $additions.Count) { throw 'Unexpected archive entry count.' }
    } finally { $check.Dispose() }
    [ordered]@{
        version = $Version
        baseline = $baselinePath
        baselineSha256 = $baselineHash
        built = $builtPath
        builtSha256 = $builtHash
        output = $outputPath
        outputSha256 = (Get-FileHash -LiteralPath $outputPath -Algorithm SHA256).Hash
        preservedEntries = $baseline.Entries.Count - 1
        addedEntries = $additions.FullName
    } | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath ($outputPath + '.verification.json') -Encoding UTF8
    Write-Output ("Preserved {0} baseline entries; added {1} practice entries." -f ($baseline.Entries.Count - 1), $additions.Count)
    Write-Output $outputPath
} finally { $built.Dispose(); $baseline.Dispose() }
