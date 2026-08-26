$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

$repoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$sourceRoot = Join-Path $repoRoot 'tools\assets\gunsmith'
$textureRoot = Join-Path $repoRoot 'src\main\resources\assets\miningdim\textures\item'
$modelRoot = Join-Path $repoRoot 'src\main\resources\assets\miningdim\models\item'

$sources = [ordered]@{
    'receiver' = @{
        Path = Join-Path $sourceRoot 'sniper_receiver_full_redraw_source.png'
        Hash = '9F5AAEBABE3F63683B12F01FAF333B1C5C908FDE3F9600BF76456338BCD59612'
    }
    'barrel' = @{
        Path = Join-Path $sourceRoot 'sniper_barrel_full_redraw_source.png'
        Hash = '66F88079164B8C3E6898926CFC0514D62B2201497AC1B3C24BEF9EC92D66C1DE'
    }
    'handguard' = @{
        Path = Join-Path $sourceRoot 'sniper_handguard_full_redraw_source.png'
        Hash = '66109CC22D8F3230F817702F2A9FF43049F92E51688E2E3BCD4AFEAAF5FAA4EF'
    }
    'stock' = @{
        Path = Join-Path $sourceRoot 'sniper_stock_full_redraw_source.png'
        Hash = '4AF68D657C6D5721ED1C3426CEAE203CAC00F81E5277EB05BFA047071C312628'
    }
    'firing_pin' = @{
        Path = Join-Path $sourceRoot 'sniper_firing_pin_full_redraw_source.png'
        Hash = 'EDBF5C7FB62FAFC37158DE734A9CB76BF7E14D6CBC4D9CB3D9C0729E2333ECE3'
    }
}

$qualityColors = [ordered]@{
    'common' = [System.Drawing.Color]::FromArgb(255, 233, 238, 247)
    'improved' = [System.Drawing.Color]::FromArgb(255, 71, 227, 124)
    'milspec' = [System.Drawing.Color]::FromArgb(255, 86, 168, 255)
    'precision' = [System.Drawing.Color]::FromArgb(255, 197, 108, 255)
    'legendary' = [System.Drawing.Color]::FromArgb(255, 255, 79, 94)
}

function New-Canvas([int]$width, [int]$height) {
    return [System.Drawing.Bitmap]::new(
        $width, $height, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
}

function Set-HighQuality([System.Drawing.Graphics]$graphics) {
    $graphics.CompositingMode = [System.Drawing.Drawing2D.CompositingMode]::SourceOver
    $graphics.CompositingQuality = [System.Drawing.Drawing2D.CompositingQuality]::HighQuality
    $graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $graphics.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
}

function Get-AlphaBounds([System.Drawing.Bitmap]$bitmap, [int]$threshold) {
    $minX = $bitmap.Width
    $minY = $bitmap.Height
    $maxX = -1
    $maxY = -1
    for ($y = 0; $y -lt $bitmap.Height; $y++) {
        for ($x = 0; $x -lt $bitmap.Width; $x++) {
            if ($bitmap.GetPixel($x, $y).A -gt $threshold) {
                $minX = [Math]::Min($minX, $x)
                $minY = [Math]::Min($minY, $y)
                $maxX = [Math]::Max($maxX, $x)
                $maxY = [Math]::Max($maxY, $y)
            }
        }
    }
    if ($maxX -lt $minX -or $maxY -lt $minY) {
        throw 'Sniper component source contains no visible pixels'
    }
    return [System.Drawing.Rectangle]::new(
        $minX, $minY, $maxX - $minX + 1, $maxY - $minY + 1)
}

function New-NormalizedSource([System.Drawing.Bitmap]$source) {
    $bounds = Get-AlphaBounds $source 8
    $scale = [Math]::Min(460.0 / $bounds.Width, 350.0 / $bounds.Height)
    $width = [Math]::Max(1, [Math]::Round($bounds.Width * $scale))
    $height = [Math]::Max(1, [Math]::Round($bounds.Height * $scale))
    $x = [Math]::Round((512 - $width) / 2.0)
    $y = [Math]::Round((512 - $height) / 2.0)
    $result = New-Canvas 512 512
    $graphics = [System.Drawing.Graphics]::FromImage($result)
    Set-HighQuality $graphics
    $graphics.DrawImage($source,
        [System.Drawing.Rectangle]::new($x, $y, $width, $height),
        $bounds.X, $bounds.Y, $bounds.Width, $bounds.Height,
        [System.Drawing.GraphicsUnit]::Pixel)
    $graphics.Dispose()
    return $result
}

function New-Recolored([System.Drawing.Bitmap]$source, [System.Drawing.Color]$color) {
    $result = New-Canvas $source.Width $source.Height
    for ($y = 0; $y -lt $source.Height; $y++) {
        for ($x = 0; $x -lt $source.Width; $x++) {
            $pixel = $source.GetPixel($x, $y)
            if ($pixel.A -gt 8) {
                $result.SetPixel($x, $y,
                    [System.Drawing.Color]::FromArgb($pixel.A, $color.R, $color.G, $color.B))
            }
        }
    }
    return $result
}

function Clear-LowAlpha([System.Drawing.Bitmap]$bitmap) {
    for ($y = 0; $y -lt $bitmap.Height; $y++) {
        for ($x = 0; $x -lt $bitmap.Width; $x++) {
            $pixel = $bitmap.GetPixel($x, $y)
            if ($pixel.A -le 12) {
                $bitmap.SetPixel($x, $y, [System.Drawing.Color]::Transparent)
            } elseif ($pixel.A -ge 224) {
                $bitmap.SetPixel($x, $y,
                    [System.Drawing.Color]::FromArgb(255, $pixel.R, $pixel.G, $pixel.B))
            }
        }
    }
}

foreach ($part in $sources.GetEnumerator()) {
    $sourcePath = $part.Value.Path
    if (!(Test-Path -LiteralPath $sourcePath -PathType Leaf)) {
        throw "Missing sniper component source: $sourcePath"
    }
    if ((Get-FileHash -Algorithm SHA256 -LiteralPath $sourcePath).Hash -ne $part.Value.Hash) {
        throw "Sniper component source hash mismatch: $sourcePath"
    }

    $raw = [System.Drawing.Bitmap]::new($sourcePath)
    $base = New-NormalizedSource $raw
    $raw.Dispose()

    foreach ($quality in $qualityColors.GetEnumerator()) {
        $canvas = New-Canvas 512 512
        $graphics = [System.Drawing.Graphics]::FromImage($canvas)
        Set-HighQuality $graphics
        $outline = New-Recolored $base $quality.Value
        foreach ($offset in @(
                @(-14, 0), @(14, 0), @(0, -14), @(0, 14),
                @(-10, -10), @(10, -10), @(-10, 10), @(10, 10))) {
            $graphics.DrawImage($outline, $offset[0], $offset[1])
        }
        $graphics.DrawImage($base, 0, 0)
        $outline.Dispose()
        $graphics.Dispose()

        $final = New-Canvas 64 64
        $finalGraphics = [System.Drawing.Graphics]::FromImage($final)
        Set-HighQuality $finalGraphics
        $finalGraphics.DrawImage($canvas, [System.Drawing.Rectangle]::new(0, 0, 64, 64),
            0, 0, 512, 512, [System.Drawing.GraphicsUnit]::Pixel)
        $finalGraphics.Dispose()
        Clear-LowAlpha $final

        $assetName = 'gunsmith_part_sniper_' + $part.Key + '_' + $quality.Key
        $final.Save((Join-Path $textureRoot ($assetName + '.png')),
            [System.Drawing.Imaging.ImageFormat]::Png)
        $final.Dispose()
        $canvas.Dispose()

        $model = @"
{
  "parent": "minecraft:item/generated",
  "textures": {
    "layer0": "miningdim:item/$assetName"
  }
}
"@
        [System.IO.File]::WriteAllText(
            (Join-Path $modelRoot ($assetName + '.json')),
            $model + [Environment]::NewLine,
            [System.Text.UTF8Encoding]::new($false))
    }
    $base.Dispose()
}

'Generated five sniper gunsmith components in five quality tiers.'
