$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

$repoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$sourceRoot = Join-Path $repoRoot 'tools\assets\gunsmith'
$textureRoot = Join-Path $repoRoot 'src\main\resources\assets\miningdim\textures\item'
$modelRoot = Join-Path $repoRoot 'src\main\resources\assets\miningdim\models\item'

$sources = [ordered]@{
    'ar' = @{
        Path = Join-Path $sourceRoot 'gunsmith_blueprint_ar_source.png'
        Hash = '6FBC5AA64A6F59A07B8AFA8683C64829A37D1BE08C774D0CA9572C48E2310BFA'
        RemoveCheckerboard = $true
    }
    'ak' = @{
        Path = Join-Path $sourceRoot 'gunsmith_blueprint_ak_source.png'
        Hash = '2C7D6A431F69E5827473011BAB8AF230965215A87A9E7D67BE790816EA8B137F'
        RemoveCheckerboard = $true
    }
    'pistol' = @{
        Path = Join-Path $sourceRoot 'gunsmith_blueprint_pistol_source.png'
        Hash = '26A0C4297D2F70ADE19E3DCD09058C33188293EC96375B4120E26087CBC25D6D'
        RemoveCheckerboard = $false
    }
    'sniper' = @{
        Path = Join-Path $sourceRoot 'gunsmith_blueprint_sniper_source.png'
        Hash = '85AA084EC67A77D905F6E161A5D384A9D16683107625F772CC09B3803E9DC6C6'
        RemoveCheckerboard = $true
    }
    'smg' = @{
        Path = Join-Path $sourceRoot 'gunsmith_blueprint_smg_source.png'
        Hash = '115B20003C9B3AC475A9074DE8FDFCC914AE2799616E952FF7E3BD46DD3BE9FA'
        RemoveCheckerboard = $true
    }
    'shotgun' = @{
        Path = Join-Path $sourceRoot 'gunsmith_blueprint_shotgun_source.png'
        Hash = 'D0B12EA3607890C9B79A0DC74FC63E0DCDB60541D6A02E730A636D7F14B052B5'
        RemoveCheckerboard = $true
    }
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

function Remove-EdgeCheckerboard([System.Drawing.Bitmap]$source) {
    $result = New-Canvas $source.Width $source.Height
    $graphics = [System.Drawing.Graphics]::FromImage($result)
    $graphics.CompositingMode = [System.Drawing.Drawing2D.CompositingMode]::SourceCopy
    $graphics.DrawImageUnscaled($source, 0, 0)
    $graphics.Dispose()

    $rectangle = [System.Drawing.Rectangle]::new(0, 0, $result.Width, $result.Height)
    $bitmapData = $result.LockBits(
        $rectangle,
        [System.Drawing.Imaging.ImageLockMode]::ReadWrite,
        [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $byteCount = [Math]::Abs($bitmapData.Stride) * $bitmapData.Height
    $pixels = [byte[]]::new($byteCount)
    [System.Runtime.InteropServices.Marshal]::Copy($bitmapData.Scan0, $pixels, 0, $byteCount)
    $result.UnlockBits($bitmapData)

    $pixelCount = $result.Width * $result.Height
    $background = [byte[]]::new($pixelCount)
    for ($y = 0; $y -lt $result.Height; $y++) {
        $rowOffset = $y * $bitmapData.Stride
        $rowIndex = $y * $result.Width
        for ($x = 0; $x -lt $result.Width; $x++) {
            $offset = $rowOffset + $x * 4
            $blue = $pixels[$offset]
            $green = $pixels[$offset + 1]
            $red = $pixels[$offset + 2]
            $maximum = [Math]::Max($red, [Math]::Max($green, $blue))
            $minimum = [Math]::Min($red, [Math]::Min($green, $blue))
            if (($maximum - $minimum) -le 18 -and ($red + $green + $blue) -ge 630) {
                $background[$rowIndex + $x] = 1
            }
        }
    }

    $queue = [int[]]::new($pixelCount)
    $head = 0
    $tail = 0
    for ($x = 0; $x -lt $source.Width; $x++) {
        foreach ($index in @($x, (($source.Height - 1) * $source.Width + $x))) {
            if ($background[$index] -eq 1) {
                $background[$index] = 2
                $queue[$tail++] = $index
            }
        }
    }
    for ($y = 1; $y -lt $source.Height - 1; $y++) {
        foreach ($index in @(($y * $source.Width), ($y * $source.Width + $source.Width - 1))) {
            if ($background[$index] -eq 1) {
                $background[$index] = 2
                $queue[$tail++] = $index
            }
        }
    }

    while ($head -lt $tail) {
        $index = $queue[$head++]
        $x = $index % $source.Width
        $y = [int][Math]::Floor($index / $source.Width)
        $pixels[$y * $bitmapData.Stride + $x * 4 + 3] = 0
        if ($x -gt 0) {
            $neighbor = $index - 1
            if ($background[$neighbor] -eq 1) {
                $background[$neighbor] = 2
                $queue[$tail++] = $neighbor
            }
        }
        if ($x + 1 -lt $source.Width) {
            $neighbor = $index + 1
            if ($background[$neighbor] -eq 1) {
                $background[$neighbor] = 2
                $queue[$tail++] = $neighbor
            }
        }
        if ($y -gt 0) {
            $neighbor = $index - $source.Width
            if ($background[$neighbor] -eq 1) {
                $background[$neighbor] = 2
                $queue[$tail++] = $neighbor
            }
        }
        if ($y + 1 -lt $source.Height) {
            $neighbor = $index + $source.Width
            if ($background[$neighbor] -eq 1) {
                $background[$neighbor] = 2
                $queue[$tail++] = $neighbor
            }
        }
    }

    $bitmapData = $result.LockBits(
        $rectangle,
        [System.Drawing.Imaging.ImageLockMode]::WriteOnly,
        [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    [System.Runtime.InteropServices.Marshal]::Copy($pixels, 0, $bitmapData.Scan0, $byteCount)
    $result.UnlockBits($bitmapData)
    return $result
}

function Get-AlphaBounds([System.Drawing.Bitmap]$bitmap, [int]$threshold) {
    $minX = $bitmap.Width
    $minY = $bitmap.Height
    $maxX = -1
    $maxY = -1
    $rectangle = [System.Drawing.Rectangle]::new(0, 0, $bitmap.Width, $bitmap.Height)
    $bitmapData = $bitmap.LockBits(
        $rectangle,
        [System.Drawing.Imaging.ImageLockMode]::ReadOnly,
        [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $byteCount = [Math]::Abs($bitmapData.Stride) * $bitmapData.Height
    $pixels = [byte[]]::new($byteCount)
    [System.Runtime.InteropServices.Marshal]::Copy($bitmapData.Scan0, $pixels, 0, $byteCount)
    $bitmap.UnlockBits($bitmapData)
    for ($y = 0; $y -lt $bitmap.Height; $y++) {
        for ($x = 0; $x -lt $bitmap.Width; $x++) {
            if ($pixels[$y * $bitmapData.Stride + $x * 4 + 3] -gt $threshold) {
                $minX = [Math]::Min($minX, $x)
                $minY = [Math]::Min($minY, $y)
                $maxX = [Math]::Max($maxX, $x)
                $maxY = [Math]::Max($maxY, $y)
            }
        }
    }
    if ($maxX -lt $minX -or $maxY -lt $minY) {
        throw 'Gunsmith blueprint source contains no visible pixels'
    }
    return [System.Drawing.Rectangle]::new(
        $minX, $minY, $maxX - $minX + 1, $maxY - $minY + 1)
}

function New-NormalizedSource([System.Drawing.Bitmap]$source) {
    $bounds = Get-AlphaBounds $source 8
    $scale = [Math]::Min(232.0 / $bounds.Width, 232.0 / $bounds.Height)
    $width = [Math]::Max(1, [Math]::Round($bounds.Width * $scale))
    $height = [Math]::Max(1, [Math]::Round($bounds.Height * $scale))
    $x = [Math]::Round((256 - $width) / 2.0)
    $y = [Math]::Round((256 - $height) / 2.0)
    $result = New-Canvas 256 256
    $graphics = [System.Drawing.Graphics]::FromImage($result)
    Set-HighQuality $graphics
    $graphics.DrawImage($source,
        [System.Drawing.Rectangle]::new($x, $y, $width, $height),
        $bounds.X, $bounds.Y, $bounds.Width, $bounds.Height,
        [System.Drawing.GraphicsUnit]::Pixel)
    $graphics.Dispose()
    return $result
}

function Clear-LowAlpha([System.Drawing.Bitmap]$bitmap) {
    for ($y = 0; $y -lt $bitmap.Height; $y++) {
        for ($x = 0; $x -lt $bitmap.Width; $x++) {
            $pixel = $bitmap.GetPixel($x, $y)
            if ($pixel.A -le 24) {
                $bitmap.SetPixel($x, $y, [System.Drawing.Color]::Transparent)
            } elseif ($pixel.A -ge 192) {
                $bitmap.SetPixel($x, $y,
                    [System.Drawing.Color]::FromArgb(255, $pixel.R, $pixel.G, $pixel.B))
            }
        }
    }
}

foreach ($entry in $sources.GetEnumerator()) {
    $sourcePath = $entry.Value.Path
    if (!(Test-Path -LiteralPath $sourcePath -PathType Leaf)) {
        throw "Missing gunsmith blueprint source: $sourcePath"
    }
    if ((Get-FileHash -Algorithm SHA256 -LiteralPath $sourcePath).Hash -ne $entry.Value.Hash) {
        throw "Gunsmith blueprint source hash mismatch: $sourcePath"
    }

    $raw = [System.Drawing.Bitmap]::new($sourcePath)
    if ($entry.Value.RemoveCheckerboard) {
        $prepared = Remove-EdgeCheckerboard $raw
        $raw.Dispose()
    } else {
        $prepared = $raw
    }
    $normalized = New-NormalizedSource $prepared
    $prepared.Dispose()

    $final = $normalized
    Clear-LowAlpha $final

    $assetName = 'gunsmith_blueprint_' + $entry.Key
    $final.Save((Join-Path $textureRoot ($assetName + '.png')),
        [System.Drawing.Imaging.ImageFormat]::Png)
    $final.Dispose()

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

$arBlueprintPath = Join-Path $textureRoot 'gunsmith_blueprint_ar.png'
$marksman = [System.Drawing.Bitmap]::new($arBlueprintPath)
$marksmanGraphics = [System.Drawing.Graphics]::FromImage($marksman)
$marksmanGraphics.CompositingMode = [System.Drawing.Drawing2D.CompositingMode]::SourceOver
$marksmanGraphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::NearestNeighbor
$marksmanGraphics.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::Half
$marksmanGraphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::None
$silhouette = [System.Drawing.SolidBrush]::new([System.Drawing.Color]::FromArgb(255, 250, 250, 250))
try {
    # Keep the established blueprint card but turn the AR silhouette into a visibly scoped,
    # long-barrel precision rifle. Integer-aligned shapes stay crisp at Minecraft item scale.
    $marksmanGraphics.FillRectangle($silhouette, 113, 83, 47, 9)
    $marksmanGraphics.FillRectangle($silhouette, 105, 86, 8, 5)
    $marksmanGraphics.FillRectangle($silhouette, 160, 81, 12, 13)
    $marksmanGraphics.FillRectangle($silhouette, 124, 92, 6, 7)
    $marksmanGraphics.FillRectangle($silhouette, 151, 92, 6, 7)
    $marksmanGraphics.FillRectangle($silhouette, 213, 109, 14, 6)
    $marksmanGraphics.FillRectangle($silhouette, 226, 107, 5, 10)
    $marksmanGraphics.FillPolygon($silhouette, [System.Drawing.Point[]]@(
        [System.Drawing.Point]::new(171, 116),
        [System.Drawing.Point]::new(177, 116),
        [System.Drawing.Point]::new(169, 149),
        [System.Drawing.Point]::new(163, 149)))
    $marksmanGraphics.FillPolygon($silhouette, [System.Drawing.Point[]]@(
        [System.Drawing.Point]::new(181, 116),
        [System.Drawing.Point]::new(187, 116),
        [System.Drawing.Point]::new(195, 149),
        [System.Drawing.Point]::new(189, 149)))
} finally {
    $silhouette.Dispose()
    $marksmanGraphics.Dispose()
}
$marksman.Save((Join-Path $textureRoot 'gunsmith_blueprint_marksman.png'),
    [System.Drawing.Imaging.ImageFormat]::Png)
$marksman.Dispose()

$marksmanModel = @"
{
  "parent": "minecraft:item/generated",
  "textures": {
    "layer0": "miningdim:item/gunsmith_blueprint_marksman"
  }
}
"@
[System.IO.File]::WriteAllText(
    (Join-Path $modelRoot 'gunsmith_blueprint_marksman.json'),
    $marksmanModel + [Environment]::NewLine,
    [System.Text.UTF8Encoding]::new($false))

'Generated seven 256x256 gunsmith blueprint icons and leaf models.'
