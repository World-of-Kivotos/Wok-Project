$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

$repoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$source = Join-Path $repoRoot 'tools\assets\gunsmith\ar_three_round_burst_bolt_source.png'
$itemRoot = Join-Path $repoRoot 'src\main\resources\assets\miningdim\textures\item'

if (!(Test-Path -LiteralPath $source -PathType Leaf)) {
    throw "Missing AR three-round-burst bolt source: $source"
}
if ((Get-FileHash -Algorithm SHA256 -LiteralPath $source).Hash -ne
        'D9A6CAA404CD0DB6C230B73F38B7EB7D204FA8AC160F02E84BE80F8F1DDA2C79') {
    throw 'AR three-round-burst bolt source hash mismatch'
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
        throw 'AR three-round-burst bolt source contains no visible pixels'
    }
    return [System.Drawing.Rectangle]::new(
        $minX, $minY, $maxX - $minX + 1, $maxY - $minY + 1)
}

function New-Recolored([System.Drawing.Bitmap]$sourceBitmap, [System.Drawing.Color]$color) {
    $result = New-Canvas $sourceBitmap.Width $sourceBitmap.Height
    for ($y = 0; $y -lt $sourceBitmap.Height; $y++) {
        for ($x = 0; $x -lt $sourceBitmap.Width; $x++) {
            $pixel = $sourceBitmap.GetPixel($x, $y)
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

$raw = [System.Drawing.Bitmap]::FromFile($source)
$bounds = Get-AlphaBounds $raw 8
$base = New-Canvas 512 512
$baseGraphics = [System.Drawing.Graphics]::FromImage($base)
Set-HighQuality $baseGraphics
$receiverRect = [System.Drawing.Rectangle]::new(20, 123, 472, 266)
$baseGraphics.DrawImage($raw, $receiverRect,
    $bounds.X, $bounds.Y, $bounds.Width, $bounds.Height,
    [System.Drawing.GraphicsUnit]::Pixel)
$baseGraphics.Dispose()
$raw.Dispose()

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

    $output = Join-Path $itemRoot (
        'gunsmith_part_ar_bolt_three_round_burst_' + $quality.Key + '.png')
    $final.Save($output, [System.Drawing.Imaging.ImageFormat]::Png)
    $final.Dispose()
    $canvas.Dispose()
}

$base.Dispose()
'Generated AR three-round-burst bolt quality icons.'
