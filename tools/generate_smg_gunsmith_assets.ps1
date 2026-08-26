$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

$repoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$sourceRoot = Join-Path $repoRoot 'tools\assets\gunsmith'
$textureRoot = Join-Path $repoRoot 'src\main\resources\assets\miningdim\textures\item'
$modelRoot = Join-Path $repoRoot 'src\main\resources\assets\miningdim\models\item'

$sources = [ordered]@{
    'barrel' = @{
        Path = Join-Path $sourceRoot 'smg_barrel_full_redraw_source.png'
        Hash = '2F0B1647148BA2FD16223B352F48777FC1EDE726ECA97A7FBF7865A422F9DA1D'
        RemoveCheckerboard = $true
    }
    'stock' = @{
        Path = Join-Path $sourceRoot 'smg_stock_full_redraw_source.png'
        Hash = 'CF9750B2F0D7F3300CDDECA82BB41FAAA8E55532A8C7C68725C5BE72223C7EC5'
        RemoveCheckerboard = $true
    }
    'receiver' = @{
        Path = Join-Path $sourceRoot 'smg_receiver_full_redraw_source.png'
        Hash = 'A79A92BA33EF263905B210C309DE874E91E578B599A8D3AADD4F5C36AF78C0EA'
        RemoveCheckerboard = $true
    }
    'handguard' = @{
        Path = Join-Path $sourceRoot 'smg_handguard_full_redraw_source.png'
        Hash = '25D59D41AE8194469FEEE968F6E43D327B750A8BDF0DBA2A79ED5298C4DD0434'
        RemoveCheckerboard = $true
    }
    'grip' = @{
        Path = Join-Path $sourceRoot 'smg_grip_full_redraw_source.png'
        Hash = '6AF2328B948C406F9E7D9A9D763444070920DBDF153AACECD4BF3D5B3DA356BF'
        RemoveCheckerboard = $true
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

function Remove-LightCheckerboard([System.Drawing.Bitmap]$source) {
    $result = New-Canvas $source.Width $source.Height
    for ($y = 0; $y -lt $source.Height; $y++) {
        for ($x = 0; $x -lt $source.Width; $x++) {
            $pixel = $source.GetPixel($x, $y)
            $maximum = [Math]::Max($pixel.R, [Math]::Max($pixel.G, $pixel.B))
            $minimum = [Math]::Min($pixel.R, [Math]::Min($pixel.G, $pixel.B))
            $neutral = ($maximum - $minimum) -le 18
            $brightness = ($pixel.R + $pixel.G + $pixel.B) / 3.0
            if ($neutral -and $brightness -ge 225.0) {
                $result.SetPixel($x, $y, [System.Drawing.Color]::Transparent)
            } elseif ($neutral -and $brightness -gt 200.0) {
                $alpha = [Math]::Round((225.0 - $brightness) / 25.0 * 255.0)
                $result.SetPixel($x, $y, [System.Drawing.Color]::FromArgb(
                        [int]$alpha, $pixel.R, $pixel.G, $pixel.B))
            } else {
                $result.SetPixel($x, $y, [System.Drawing.Color]::FromArgb(
                        $pixel.A, $pixel.R, $pixel.G, $pixel.B))
            }
        }
    }
    return $result
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
        throw 'SMG component source contains no visible pixels'
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
        throw "Missing SMG component source: $sourcePath"
    }
    if ((Get-FileHash -Algorithm SHA256 -LiteralPath $sourcePath).Hash -ne $part.Value.Hash) {
        throw "SMG component source hash mismatch: $sourcePath"
    }

    $raw = [System.Drawing.Bitmap]::new($sourcePath)
    if ($part.Value.RemoveCheckerboard) {
        $prepared = Remove-LightCheckerboard $raw
        $raw.Dispose()
    } else {
        $prepared = $raw
    }
    $base = New-NormalizedSource $prepared
    $prepared.Dispose()

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

        $assetName = 'gunsmith_part_smg_' + $part.Key + '_' + $quality.Key
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

'Generated SMG gunsmith component quality icons and leaf models.'
