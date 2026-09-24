param(
    [string]$ModelPath = (Join-Path $PSScriptRoot 'fish_quality_model.json'),
    [string]$OutputDir = (Join-Path $PSScriptRoot '..\..\src\main\resources\data\miningdim\tags\items\fish_quality'),
    # Compare only, never write: exits 1 when any tag file differs from what the model produces.
    [switch]$Check
)

# Generates the six fish quality tags (data/miningdim/tags/items/fish_quality/<tier>.json) from fish_quality_model.json.
#
# tier = probability points + condition points, capped at the last tier:
#   probability points: p_bite >= probability_bands[0] scores 0, each lower band adds 1, below the last threshold scores max;
#   condition points: sum of the points of every condition listed on the fish.
# vanilla and miningdim items are written as required entries; any other namespace (Tide and other optional mods) is
# written as {"id": ..., "required": false} so the tag still loads when that mod is absent.
# Output is UTF-8 without BOM with LF line endings; rerunning on the same model is byte-for-byte stable.

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$utf8 = New-Object System.Text.UTF8Encoding($false)
$model = [System.IO.File]::ReadAllText((Resolve-Path -LiteralPath $ModelPath), $utf8) | ConvertFrom-Json

$bands = @($model.probability_bands | ForEach-Object { [double]$_ })
$tiers = @($model.tiers | ForEach-Object { [string]$_ })
if ($tiers.Count -ne $bands.Count + 1) { throw "tiers must have exactly one more entry than probability_bands" }
for ($i = 1; $i -lt $bands.Count; $i++) {
    if ($bands[$i] -ge $bands[$i - 1]) { throw "probability_bands must be strictly descending" }
}

$points = @{}
foreach ($property in $model.conditions.PSObject.Properties) {
    $value = [int]$property.Value.points
    if ($value -lt 0) { throw "Condition $($property.Name) has negative points" }
    $points[$property.Name] = $value
}

$groups = [ordered]@{}
foreach ($tier in $tiers) { $groups[$tier] = New-Object System.Collections.Generic.List[string] }
$seen = New-Object System.Collections.Generic.HashSet[string]
foreach ($fish in $model.fish) {
    $item = [string]$fish.item
    if ($item -notmatch '^[a-z0-9_.-]+:[a-z0-9_./-]+$') { throw "Invalid item id: $item" }
    if (-not $seen.Add($item)) { throw "Duplicate fish in model: $item" }
    $probability = [double]$fish.p_bite
    if ($probability -le 0 -or $probability -gt 1) { throw "p_bite must be in (0, 1] for $item" }
    $band = $bands.Count
    for ($i = 0; $i -lt $bands.Count; $i++) {
        if ($probability -ge $bands[$i]) { $band = $i; break }
    }
    $conditionPoints = 0
    foreach ($condition in @($fish.conditions)) {
        if (-not $points.ContainsKey([string]$condition)) { throw "Unknown condition '$condition' on $item" }
        $conditionPoints += $points[[string]$condition]
    }
    $tier = [Math]::Min($tiers.Count - 1, $band + $conditionPoints)
    $groups[$tiers[$tier]].Add($item)
}

# Resolve through the PowerShell provider: [IO.Path]::GetFullPath would anchor a relative path to the .NET process
# directory, which differs from the PowerShell location after Set-Location.
$resolvedOutput = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($OutputDir)
if (-not $Check -and -not (Test-Path -LiteralPath $resolvedOutput)) {
    New-Item -ItemType Directory -Path $resolvedOutput -Force | Out-Null
}

$mismatch = $false
foreach ($tier in $tiers) {
    $items = @($groups[$tier] | Sort-Object)
    $lines = New-Object System.Collections.Generic.List[string]
    $lines.Add('{')
    $lines.Add('  "replace": false,')
    if ($items.Count -eq 0) {
        $lines.Add('  "values": []')
    } else {
        $lines.Add('  "values": [')
        for ($i = 0; $i -lt $items.Count; $i++) {
            $item = $items[$i]
            $separator = if ($i -lt $items.Count - 1) { ',' } else { '' }
            $namespace = $item.Substring(0, $item.IndexOf(':'))
            if ($namespace -eq 'minecraft' -or $namespace -eq 'miningdim') {
                $lines.Add("    ""$item""$separator")
            } else {
                $lines.Add("    { ""id"": ""$item"", ""required"": false }$separator")
            }
        }
        $lines.Add('  ]')
    }
    $lines.Add('}')
    $content = ($lines -join "`n") + "`n"
    $target = Join-Path $resolvedOutput "$tier.json"
    if ($Check) {
        $existing = if (Test-Path -LiteralPath $target) { [System.IO.File]::ReadAllText($target, $utf8) } else { '' }
        if ($existing -ne $content) {
            Write-Output "OUT OF DATE: $target"
            $mismatch = $true
        }
    } else {
        [System.IO.File]::WriteAllText($target, $content, $utf8)
    }
    Write-Output ("{0,-10} {1,3}" -f $tier, $items.Count)
}

if ($Check -and $mismatch) {
    exit 1
}
