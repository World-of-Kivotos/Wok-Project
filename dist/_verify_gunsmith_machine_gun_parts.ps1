$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$itemDir = Join-Path $root 'src/main/resources/assets/miningdim/textures/item'
$modelDir = Join-Path $root 'src/main/resources/assets/miningdim/models/item'
$qualities = @('common', 'improved', 'milspec', 'precision', 'legendary')
$parts = @('handguard', 'bolt', 'barrel', 'stock', 'bipod')
$expected = @{}
$checks = [Collections.Generic.List[string]]::new()

foreach ($part in $parts) {
    foreach ($quality in $qualities) {
        $name = "gunsmith_part_machine_gun_${part}_${quality}"
        $expected[$name] = $true
        $pngPath = Join-Path $itemDir "$name.png"
        $modelPath = Join-Path $modelDir "$name.json"
        if (-not (Test-Path -LiteralPath $pngPath)) { throw "Missing texture: $pngPath" }
        if (-not (Test-Path -LiteralPath $modelPath)) { throw "Missing model: $modelPath" }

        $image = [Drawing.Bitmap]::new($pngPath)
        $hasTransparent = $false
        $hasVisible = $false
        for ($y = 0; $y -lt $image.Height; $y++) {
            for ($x = 0; $x -lt $image.Width; $x++) {
                $alpha = $image.GetPixel($x, $y).A
                if ($alpha -eq 0) { $hasTransparent = $true }
                if ($alpha -gt 0) { $hasVisible = $true }
            }
        }
        if ($image.Width -ne 64 -or $image.Height -ne 64) { throw "Wrong image size: $pngPath ($($image.Width)x$($image.Height))" }
        if (-not $hasTransparent) { throw "No transparent pixels: $pngPath" }
        if (-not $hasVisible) { throw "Empty texture: $pngPath" }
        $image.Dispose()

        $model = Get-Content -LiteralPath $modelPath -Raw -Encoding UTF8 | ConvertFrom-Json
        $textureRef = [string]$model.textures.layer0
        $refParts = $textureRef.Split(':', 2)
        $textureFile = Join-Path $root ('src/main/resources/assets/{0}/textures/{1}.png' -f $refParts[0], $refParts[1].Replace('/', '\'))
        if (-not (Test-Path -LiteralPath $textureFile)) { throw "Missing model texture reference: $modelPath -> $textureRef" }
    }
}

$textureFiles = @(Get-ChildItem -LiteralPath $itemDir -Filter 'gunsmith_part_machine_gun_*.png' -File)
$modelFiles = @(Get-ChildItem -LiteralPath $modelDir -Filter 'gunsmith_part_machine_gun_*.json' -File)
if ($textureFiles.Count -ne 25) { throw "Expected 25 machine-gun textures, found $($textureFiles.Count)" }
if ($modelFiles.Count -ne 25) { throw "Expected 25 machine-gun models, found $($modelFiles.Count)" }

$dispatchPath = Join-Path $modelDir 'gunsmith_part.json'
$dispatch = Get-Content -LiteralPath $dispatchPath -Raw -Encoding UTF8 | ConvertFrom-Json
$allCmd = @($dispatch.overrides | ForEach-Object { [int]$_.predicate.custom_model_data })
$duplicates = @($allCmd | Group-Object | Where-Object Count -gt 1)
if ($duplicates.Count -gt 0) { throw "Duplicate CMD values: $($duplicates.Name -join ', ')" }

$required = @{
    611 = 'miningdim:item/gunsmith_part_machine_gun_barrel_common'
    612 = 'miningdim:item/gunsmith_part_machine_gun_barrel_improved'
    613 = 'miningdim:item/gunsmith_part_machine_gun_barrel_milspec'
    614 = 'miningdim:item/gunsmith_part_machine_gun_barrel_precision'
    615 = 'miningdim:item/gunsmith_part_machine_gun_barrel_legendary'
    621 = 'miningdim:item/gunsmith_part_machine_gun_bolt_common'
    622 = 'miningdim:item/gunsmith_part_machine_gun_bolt_improved'
    623 = 'miningdim:item/gunsmith_part_machine_gun_bolt_milspec'
    624 = 'miningdim:item/gunsmith_part_machine_gun_bolt_precision'
    625 = 'miningdim:item/gunsmith_part_machine_gun_bolt_legendary'
    631 = 'miningdim:item/gunsmith_part_machine_gun_handguard_common'
    632 = 'miningdim:item/gunsmith_part_machine_gun_handguard_improved'
    633 = 'miningdim:item/gunsmith_part_machine_gun_handguard_milspec'
    634 = 'miningdim:item/gunsmith_part_machine_gun_handguard_precision'
    635 = 'miningdim:item/gunsmith_part_machine_gun_handguard_legendary'
    651 = 'miningdim:item/gunsmith_part_machine_gun_stock_common'
    652 = 'miningdim:item/gunsmith_part_machine_gun_stock_improved'
    653 = 'miningdim:item/gunsmith_part_machine_gun_stock_milspec'
    654 = 'miningdim:item/gunsmith_part_machine_gun_stock_precision'
    655 = 'miningdim:item/gunsmith_part_machine_gun_stock_legendary'
    701 = 'miningdim:item/gunsmith_part_machine_gun_bipod_common'
    702 = 'miningdim:item/gunsmith_part_machine_gun_bipod_improved'
    703 = 'miningdim:item/gunsmith_part_machine_gun_bipod_milspec'
    704 = 'miningdim:item/gunsmith_part_machine_gun_bipod_precision'
    705 = 'miningdim:item/gunsmith_part_machine_gun_bipod_legendary'
}
$byCmd = @{}
foreach ($override in $dispatch.overrides) { $byCmd[[int]$override.predicate.custom_model_data] = [string]$override.model }
foreach ($cmd in $required.Keys) {
    if (-not $byCmd.ContainsKey([int]$cmd)) { throw "Missing required CMD: $cmd" }
    if ($byCmd[[int]$cmd] -ne $required[$cmd]) { throw "Wrong model for CMD ${cmd}: $($byCmd[[int]$cmd])" }
}

Write-Output "PASS: 25 PNG textures are 64x64, non-empty, and transparent."
Write-Output "PASS: 25 item models parse and reference existing textures."
Write-Output "PASS: gunsmith_part.json parses with $($allCmd.Count) unique CMD values."
Write-Output "PASS: CMD mappings 611-615, 621-625, 631-635, 651-655, and 701-705 match."
