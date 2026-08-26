$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

$repoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$sourceRoot = Join-Path $repoRoot 'tools\assets\gunsmith'
$textureRoot = Join-Path $repoRoot 'src\main\resources\assets\miningdim\textures\item'
$modelRoot = Join-Path $repoRoot 'src\main\resources\assets\miningdim\models\item'
$barrelPath = Join-Path $sourceRoot 'sniper_barrel_full_redraw_source.png'
$trinityPath = Join-Path $sourceRoot 'trinity_faction_logo_source.png'

$expectedHashes = @{
    $barrelPath = '66F88079164B8C3E6898926CFC0514D62B2201497AC1B3C24BEF9EC92D66C1DE'
    $trinityPath = 'EACDD61AE84680400079D83ABCB381C9565FA6D40DDBFD3817A57DAB57933927'
}
foreach ($entry in $expectedHashes.GetEnumerator()) {
    if (!(Test-Path -LiteralPath $entry.Key -PathType Leaf)) {
        throw "Missing supplied redraw source: $($entry.Key)"
    }
    if ((Get-FileHash -Algorithm SHA256 -LiteralPath $entry.Key).Hash -ne $entry.Value) {
        throw "Supplied redraw source hash mismatch: $($entry.Key)"
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

function Get-AlphaBounds([System.Drawing.Bitmap]$bitmap) {
    $minX = $bitmap.Width
    $minY = $bitmap.Height
    $maxX = -1
    $maxY = -1
    for ($y = 0; $y -lt $bitmap.Height; $y++) {
        for ($x = 0; $x -lt $bitmap.Width; $x++) {
            if ($bitmap.GetPixel($x, $y).A -gt 8) {
                $minX = [Math]::Min($minX, $x)
                $minY = [Math]::Min($minY, $y)
                $maxX = [Math]::Max($maxX, $x)
                $maxY = [Math]::Max($maxY, $y)
            }
        }
    }
    if ($maxX -lt $minX -or $maxY -lt $minY) {
        throw 'Redraw source contains no visible pixels'
    }
    return [System.Drawing.Rectangle]::new(
        $minX, $minY, $maxX - $minX + 1, $maxY - $minY + 1)
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

$barrel = [System.Drawing.Bitmap]::new($barrelPath)
$barrelBounds = Get-AlphaBounds $barrel
$trinityRaw = [System.Drawing.Bitmap]::new($trinityPath)
$trinity = New-Recolored $trinityRaw ([System.Drawing.Color]::FromArgb(255, 245, 241, 227))
$trinityBounds = Get-AlphaBounds $trinity

foreach ($quality in $qualityColors.GetEnumerator()) {
    $canvas = New-Canvas 512 512
    $graphics = [System.Drawing.Graphics]::FromImage($canvas)
    Set-HighQuality $graphics
    $barrelRect = [System.Drawing.Rectangle]::new(16, 172, 480, 160)
    $outline = New-Recolored $barrel $quality.Value
    foreach ($offset in @(
            @(-14, 0), @(14, 0), @(0, -14), @(0, 14),
            @(-10, -10), @(10, -10), @(-10, 10), @(10, 10))) {
        $target = [System.Drawing.Rectangle]::new(
            $barrelRect.X + $offset[0], $barrelRect.Y + $offset[1],
            $barrelRect.Width, $barrelRect.Height)
        $graphics.DrawImage($outline, $target, $barrelBounds.X, $barrelBounds.Y,
            $barrelBounds.Width, $barrelBounds.Height, [System.Drawing.GraphicsUnit]::Pixel)
    }
    $graphics.DrawImage($barrel, $barrelRect, $barrelBounds.X, $barrelBounds.Y,
        $barrelBounds.Width, $barrelBounds.Height, [System.Drawing.GraphicsUnit]::Pixel)
    $outline.Dispose()

    $shadowBrush = [System.Drawing.SolidBrush]::new([System.Drawing.Color]::FromArgb(190, 5, 10, 18))
    $graphics.FillEllipse($shadowBrush, 374, 344, 130, 130)
    $shadowBrush.Dispose()
    $logoRect = [System.Drawing.Rectangle]::new(384, 354, 110, 110)
    $graphics.DrawImage($trinity, $logoRect, $trinityBounds.X, $trinityBounds.Y,
        $trinityBounds.Width, $trinityBounds.Height, [System.Drawing.GraphicsUnit]::Pixel)
    $graphics.Dispose()

    $final = New-Canvas 64 64
    $finalGraphics = [System.Drawing.Graphics]::FromImage($final)
    Set-HighQuality $finalGraphics
    $finalGraphics.DrawImage($canvas, [System.Drawing.Rectangle]::new(0, 0, 64, 64),
        0, 0, 512, 512, [System.Drawing.GraphicsUnit]::Pixel)
    $finalGraphics.Dispose()
    Clear-LowAlpha $final

    $assetName = 'gunsmith_part_sniper_barrel_trinity_precision_graduated_' + $quality.Key
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

$trinity.Dispose()
$trinityRaw.Dispose()
$barrel.Dispose()

'Generated Trinity precision-graduated sniper barrel icons in five quality tiers.'
