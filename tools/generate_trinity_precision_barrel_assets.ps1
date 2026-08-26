$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

$repoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$assetRoot = Join-Path $repoRoot 'tools\assets\gunsmith'
$textureRoot = Join-Path $repoRoot 'src\main\resources\assets\miningdim\textures'
$itemRoot = Join-Path $textureRoot 'item'
$factionRoot = Join-Path $textureRoot 'gui\gunsmith\factions'
[System.IO.Directory]::CreateDirectory($assetRoot) | Out-Null
[System.IO.Directory]::CreateDirectory($factionRoot) | Out-Null

$sourceMap = @{
    'red_winter' = @{
        Temp = 'C:\Users\ADMINI~1\AppData\Local\Temp\codex-clipboard-d8012195-14f7-406e-b3a9-2192e691e970.png'
        Asset = Join-Path $assetRoot 'red_winter_faction_logo_source.png'
        Hash = 'A7A3ADBFED1FFE27CB82DEFFFB1ACEDD75997B3CB59954BC28221F4282408ACC'
    }
    'gehenna' = @{
        Temp = 'C:\Users\ADMINI~1\AppData\Local\Temp\codex-clipboard-7666a052-fa18-483d-8b1d-862676385062.png'
        Asset = Join-Path $assetRoot 'gehenna_faction_logo_source.png'
        Hash = '9DC9B2A2A220E23BDAEFACAF9550AF1C3C9993FA32DE104912F5B5B9F84F3E40'
    }
}

foreach ($entry in $sourceMap.GetEnumerator()) {
    $data = $entry.Value
    if (!(Test-Path -LiteralPath $data.Asset -PathType Leaf)) {
        if (!(Test-Path -LiteralPath $data.Temp -PathType Leaf)) {
            throw "Missing supplied faction logo source: $($entry.Key)"
        }
        [System.IO.File]::Copy($data.Temp, $data.Asset, $false)
    }
    $actual = (Get-FileHash -Algorithm SHA256 -LiteralPath $data.Asset).Hash
    if ($actual -ne $data.Hash) {
        throw "Supplied faction logo hash mismatch: $($entry.Key)"
    }
}

$barrelSource = Join-Path $assetRoot 'trinity_precision_graduated_barrel_source.png'
$trinitySource = Join-Path $assetRoot 'trinity_faction_logo_source.png'
$blueSource = Join-Path $itemRoot 'gunsmith_faction_blue_heavy_industries.png'
foreach ($required in @($barrelSource, $trinitySource, $blueSource)) {
    if (!(Test-Path -LiteralPath $required -PathType Leaf)) {
        throw "Missing required supplied asset: $required"
    }
}
if ((Get-FileHash -Algorithm SHA256 -LiteralPath $trinitySource).Hash -ne
        'EACDD61AE84680400079D83ABCB381C9565FA6D40DDBFD3817A57DAB57933927') {
    throw 'Supplied Trinity logo hash mismatch'
}

function New-Canvas([int]$width, [int]$height) {
    return [System.Drawing.Bitmap]::new(
        $width, $height, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
}

function Get-AlphaBounds([System.Drawing.Bitmap]$bitmap) {
    $minX = $bitmap.Width
    $minY = $bitmap.Height
    $maxX = -1
    $maxY = -1
    for ($y = 0; $y -lt $bitmap.Height; $y++) {
        for ($x = 0; $x -lt $bitmap.Width; $x++) {
            if ($bitmap.GetPixel($x, $y).A -gt 0) {
                $minX = [Math]::Min($minX, $x)
                $minY = [Math]::Min($minY, $y)
                $maxX = [Math]::Max($maxX, $x)
                $maxY = [Math]::Max($maxY, $y)
            }
        }
    }
    if ($maxX -lt $minX -or $maxY -lt $minY) {
        throw 'Image contains no visible pixels'
    }
    return [System.Drawing.Rectangle]::new($minX, $minY, $maxX - $minX + 1, $maxY - $minY + 1)
}

function New-Recolored([System.Drawing.Bitmap]$source, [System.Drawing.Color]$color) {
    $result = New-Canvas $source.Width $source.Height
    for ($y = 0; $y -lt $source.Height; $y++) {
        for ($x = 0; $x -lt $source.Width; $x++) {
            $pixel = $source.GetPixel($x, $y)
            if ($pixel.A -gt 0) {
                $result.SetPixel($x, $y,
                    [System.Drawing.Color]::FromArgb($pixel.A, $color.R, $color.G, $color.B))
            }
        }
    }
    return $result
}

function Set-HighQuality([System.Drawing.Graphics]$graphics) {
    $graphics.CompositingMode = [System.Drawing.Drawing2D.CompositingMode]::SourceOver
    $graphics.CompositingQuality = [System.Drawing.Drawing2D.CompositingQuality]::HighQuality
    $graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $graphics.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
}

function Save-Scaled([System.Drawing.Bitmap]$source, [string]$path, [int]$width, [int]$height) {
    $output = New-Canvas $width $height
    $graphics = [System.Drawing.Graphics]::FromImage($output)
    Set-HighQuality $graphics
    $graphics.DrawImage($source, [System.Drawing.Rectangle]::new(0, 0, $width, $height),
        0, 0, $source.Width, $source.Height, [System.Drawing.GraphicsUnit]::Pixel)
    $graphics.Dispose()
    $output.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
    $output.Dispose()
}

# Tooltip logos keep the exact supplied silhouette. Black line art is recolored only for contrast.
$red = [System.Drawing.Bitmap]::FromFile($sourceMap['red_winter'].Asset)
Save-Scaled $red (Join-Path $factionRoot 'red_winter.png') 160 160
$red.Dispose()

$gehennaRaw = [System.Drawing.Bitmap]::FromFile($sourceMap['gehenna'].Asset)
$gehenna = New-Recolored $gehennaRaw ([System.Drawing.Color]::FromArgb(255, 246, 215, 114))
Save-Scaled $gehenna (Join-Path $factionRoot 'gehenna.png') 160 160
$gehenna.Dispose()
$gehennaRaw.Dispose()

$trinityRaw = [System.Drawing.Bitmap]::FromFile($trinitySource)
$trinity = New-Recolored $trinityRaw ([System.Drawing.Color]::FromArgb(255, 245, 241, 227))
Save-Scaled $trinity (Join-Path $factionRoot 'trinity.png') 160 160

$blue = [System.Drawing.Bitmap]::FromFile($blueSource)
Save-Scaled $blue (Join-Path $factionRoot 'blue_heavy_industries.png') 160 160
$blue.Dispose()

$barrel = [System.Drawing.Bitmap]::FromFile($barrelSource)
$barrelBounds = Get-AlphaBounds $barrel
$trinityBounds = Get-AlphaBounds $trinity
$qualityColors = [ordered]@{
    'common' = [System.Drawing.Color]::FromArgb(255, 233, 238, 247)
    'improved' = [System.Drawing.Color]::FromArgb(255, 71, 227, 124)
    'milspec' = [System.Drawing.Color]::FromArgb(255, 86, 168, 255)
    'precision' = [System.Drawing.Color]::FromArgb(255, 197, 108, 255)
    'legendary' = [System.Drawing.Color]::FromArgb(255, 255, 79, 94)
}

foreach ($quality in $qualityColors.GetEnumerator()) {
    $canvas = New-Canvas 512 512
    $graphics = [System.Drawing.Graphics]::FromImage($canvas)
    Set-HighQuality $graphics

    $barrelRect = [System.Drawing.Rectangle]::new(18, 174, 476, 160)
    $outlineSource = New-Recolored $barrel $quality.Value
    foreach ($offset in @(
            @(-14, 0), @(14, 0), @(0, -14), @(0, 14),
            @(-10, -10), @(10, -10), @(-10, 10), @(10, 10))) {
        $target = [System.Drawing.Rectangle]::new(
            $barrelRect.X + $offset[0], $barrelRect.Y + $offset[1],
            $barrelRect.Width, $barrelRect.Height)
        $graphics.DrawImage($outlineSource, $target, $barrelBounds.X, $barrelBounds.Y,
            $barrelBounds.Width, $barrelBounds.Height, [System.Drawing.GraphicsUnit]::Pixel)
    }
    $graphics.DrawImage($barrel, $barrelRect, $barrelBounds.X, $barrelBounds.Y,
        $barrelBounds.Width, $barrelBounds.Height, [System.Drawing.GraphicsUnit]::Pixel)
    $outlineSource.Dispose()

    # Preserve the supplied Trinity geometry and use it as the bottom-right faction mark.
    $shadowBrush = [System.Drawing.SolidBrush]::new([System.Drawing.Color]::FromArgb(184, 5, 10, 18))
    $graphics.FillEllipse($shadowBrush, 354, 338, 150, 150)
    $shadowBrush.Dispose()
    $logoRect = [System.Drawing.Rectangle]::new(365, 349, 128, 128)
    $graphics.DrawImage($trinity, $logoRect, $trinityBounds.X, $trinityBounds.Y,
        $trinityBounds.Width, $trinityBounds.Height, [System.Drawing.GraphicsUnit]::Pixel)
    $graphics.Dispose()

    $final = New-Canvas 64 64
    $finalGraphics = [System.Drawing.Graphics]::FromImage($final)
    Set-HighQuality $finalGraphics
    $finalGraphics.DrawImage($canvas, [System.Drawing.Rectangle]::new(0, 0, 64, 64),
        0, 0, 512, 512, [System.Drawing.GraphicsUnit]::Pixel)
    $finalGraphics.Dispose()
    $output = Join-Path $itemRoot (
        'gunsmith_part_ar_barrel_trinity_precision_graduated_' + $quality.Key + '.png')
    $final.Save($output, [System.Drawing.Imaging.ImageFormat]::Png)
    $final.Dispose()
    $canvas.Dispose()
}

$trinity.Dispose()
$trinityRaw.Dispose()
$barrel.Dispose()

'Generated Trinity barrel icons and exact-source faction tooltip logos.'
