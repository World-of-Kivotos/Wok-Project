$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

$repoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$sourceRoot = Join-Path $repoRoot 'tools\assets\gunsmith'
$textureRoot = Join-Path $repoRoot 'src\main\resources\assets\miningdim\textures\item'
$modelRoot = Join-Path $repoRoot 'src\main\resources\assets\miningdim\models\item'

$sources = [ordered]@{
    'stock' = @{
        Path = Join-Path $sourceRoot 'shotgun_stock_source.png'
        Hash = 'B2C5111B9C89617D02CB845DB28302B46A60A3E60CAE9E153502DE9BD09D516B'
        RemoveCheckerboard = $false
    }
    'barrel' = @{
        Path = Join-Path $sourceRoot 'shotgun_barrel_source.png'
        Hash = '2E46217F13A15428A1A8389D101929A7B75E94A26D30E1288BDE92AE459A9AD0'
        RemoveCheckerboard = $false
    }
    'bolt' = @{
        Path = Join-Path $sourceRoot 'shotgun_bolt_imagegen_source.png'
        Hash = '4CB4D6B8A9CC63FD13221D09D6D1F07A6E2DDE8090107EB6AD74BFFF01A911B0'
        RemoveCheckerboard = $true
    }
    'handguard' = @{
        Path = Join-Path $sourceRoot 'shotgun_handguard_source.png'
        Hash = 'D16198634AD881A49B2A419D8EB338CD6B7D69E0C304FA653A987B6A9E72B453'
        RemoveCheckerboard = $false
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
        throw 'Shotgun component source contains no visible pixels'
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
        throw "Missing shotgun component source: $sourcePath"
    }
    if ((Get-FileHash -Algorithm SHA256 -LiteralPath $sourcePath).Hash -ne $part.Value.Hash) {
        throw "Shotgun component source hash mismatch: $sourcePath"
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

        $assetName = 'gunsmith_part_shotgun_' + $part.Key + '_' + $quality.Key
        $texturePath = Join-Path $textureRoot ($assetName + '.png')
        $final.Save($texturePath, [System.Drawing.Imaging.ImageFormat]::Png)
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

'Generated shotgun gunsmith component quality icons and leaf models.'
