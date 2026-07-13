$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$itemDir = Join-Path $root 'src/main/resources/assets/miningdim/textures/item'
$modelDir = Join-Path $root 'src/main/resources/assets/miningdim/models/item'
$outDir = $PSScriptRoot

$qualities = [ordered]@{
    common = [Drawing.Color]::FromArgb(255, 233, 238, 247)
    improved = [Drawing.Color]::FromArgb(255, 71, 227, 124)
    milspec = [Drawing.Color]::FromArgb(255, 86, 168, 255)
    precision = [Drawing.Color]::FromArgb(255, 197, 108, 255)
    legendary = [Drawing.Color]::FromArgb(255, 255, 55, 72)
}

$parts = [ordered]@{
    handguard = @{ source = 'C:\Users\ADMINI~1\AppData\Local\Temp\codex-clipboard-5f97041c-b9e9-4ba7-8bdd-159e481ec58f.png'; maxW = 60; maxH = 34 }
    bolt = @{ source = 'C:\Users\ADMINI~1\AppData\Local\Temp\codex-clipboard-b0659081-1aa8-41f6-acbe-7dfd695cf0af.png'; maxW = 58; maxH = 50 }
    barrel = @{ source = 'C:\Users\ADMINI~1\AppData\Local\Temp\codex-clipboard-8f213eaa-b6de-4021-b1bd-31fa8ad57f5d.png'; maxW = 60; maxH = 34 }
    stock = @{ source = 'C:\Users\ADMINI~1\AppData\Local\Temp\codex-clipboard-5ca680eb-f8a6-4e07-ba05-5485117de821.png'; maxW = 52; maxH = 54 }
    bipod = @{ source = 'C:\Users\ADMINI~1\AppData\Local\Temp\codex-clipboard-39c9a141-12c0-486d-ab92-3eea8d62cd7a.png'; maxW = 60; maxH = 34 }
}

function Test-SourceBackground([Drawing.Color] $color) {
    return $color.A -eq 0 -or ([Math]::Max($color.R, [Math]::Max($color.G, $color.B)) -le 10)
}

function New-Cutout([string] $path) {
    $src = [Drawing.Bitmap]::new($path)
    $width = $src.Width
    $height = $src.Height
    $visited = New-Object 'bool[,]' $width, $height
    $stack = [Collections.Generic.Stack[Drawing.Point]]::new()

    for ($x = 0; $x -lt $width; $x++) {
        $stack.Push([Drawing.Point]::new($x, 0))
        $stack.Push([Drawing.Point]::new($x, $height - 1))
    }
    for ($y = 0; $y -lt $height; $y++) {
        $stack.Push([Drawing.Point]::new(0, $y))
        $stack.Push([Drawing.Point]::new($width - 1, $y))
    }

    while ($stack.Count -gt 0) {
        $point = $stack.Pop()
        $x = $point.X
        $y = $point.Y
        if ($x -lt 0 -or $x -ge $width -or $y -lt 0 -or $y -ge $height -or $visited[$x, $y]) {
            continue
        }
        $visited[$x, $y] = $true
        if (-not (Test-SourceBackground $src.GetPixel($x, $y))) {
            continue
        }
        $stack.Push([Drawing.Point]::new($x + 1, $y))
        $stack.Push([Drawing.Point]::new($x - 1, $y))
        $stack.Push([Drawing.Point]::new($x, $y + 1))
        $stack.Push([Drawing.Point]::new($x, $y - 1))
    }

    $cutout = [Drawing.Bitmap]::new($width, $height, [Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $minX = $width
    $minY = $height
    $maxX = -1
    $maxY = -1
    for ($y = 0; $y -lt $height; $y++) {
        for ($x = 0; $x -lt $width; $x++) {
            $pixel = $src.GetPixel($x, $y)
            if ($visited[$x, $y] -and (Test-SourceBackground $pixel)) {
                $cutout.SetPixel($x, $y, [Drawing.Color]::Transparent)
            } else {
                $cutout.SetPixel($x, $y, $pixel)
                if ($pixel.A -gt 0) {
                    $minX = [Math]::Min($minX, $x)
                    $minY = [Math]::Min($minY, $y)
                    $maxX = [Math]::Max($maxX, $x)
                    $maxY = [Math]::Max($maxY, $y)
                }
            }
        }
    }
    $src.Dispose()
    if ($maxX -lt 0) {
        $cutout.Dispose()
        throw "Source contains no visible pixels: $path"
    }
    $crop = $cutout.Clone([Drawing.Rectangle]::new($minX, $minY, $maxX - $minX + 1, $maxY - $minY + 1), [Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $cutout.Dispose()
    return $crop
}

function New-LowResIcon([hashtable] $config) {
    $cropped = New-Cutout $config.source
    $scale = [Math]::Min($config.maxW / [double]$cropped.Width, $config.maxH / [double]$cropped.Height)
    $scaledW = [Math]::Max(1, [Math]::Round($cropped.Width * $scale))
    $scaledH = [Math]::Max(1, [Math]::Round($cropped.Height * $scale))
    $icon = [Drawing.Bitmap]::new(64, 64, [Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $graphics = [Drawing.Graphics]::FromImage($icon)
    $graphics.Clear([Drawing.Color]::Transparent)
    $graphics.CompositingMode = [Drawing.Drawing2D.CompositingMode]::SourceCopy
    $graphics.InterpolationMode = [Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $graphics.PixelOffsetMode = [Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $graphics.DrawImage($cropped, [Drawing.Rectangle]::new([Math]::Floor((64 - $scaledW) / 2), [Math]::Floor((64 - $scaledH) / 2), $scaledW, $scaledH))
    $graphics.Dispose()
    $cropped.Dispose()
    return $icon
}

function New-DilatedMask([Drawing.Bitmap] $base, [int] $radius) {
    $mask = New-Object 'bool[,]' 64, 64
    $alpha = New-Object 'bool[,]' 64, 64
    for ($y = 0; $y -lt 64; $y++) {
        for ($x = 0; $x -lt 64; $x++) {
            $alpha[$x, $y] = $base.GetPixel($x, $y).A -gt 16
        }
    }
    $limit = ($radius * $radius) + $radius
    for ($y = 0; $y -lt 64; $y++) {
        for ($x = 0; $x -lt 64; $x++) {
            if (-not $alpha[$x, $y]) { continue }
            for ($dy = -$radius; $dy -le $radius; $dy++) {
                for ($dx = -$radius; $dx -le $radius; $dx++) {
                    if (($dx * $dx) + ($dy * $dy) -le $limit) {
                        $xx = $x + $dx
                        $yy = $y + $dy
                        if ($xx -ge 0 -and $xx -lt 64 -and $yy -ge 0 -and $yy -lt 64) {
                            $mask[$xx, $yy] = $true
                        }
                    }
                }
            }
        }
    }
    Write-Output -NoEnumerate $mask
}

function New-OutlinedIcon([Drawing.Bitmap] $base, [Drawing.Color] $qualityColor) {
    $outer = New-DilatedMask $base 2
    $separator = New-DilatedMask $base 1
    $result = [Drawing.Bitmap]::new(64, 64, [Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $void = [Drawing.Color]::FromArgb(255, 7, 9, 11)
    for ($y = 0; $y -lt 64; $y++) {
        for ($x = 0; $x -lt 64; $x++) {
            if ($outer[$x, $y]) { $result.SetPixel($x, $y, $qualityColor) }
            if ($separator[$x, $y]) { $result.SetPixel($x, $y, $void) }
            $pixel = $base.GetPixel($x, $y)
            if ($pixel.A -gt 0) { $result.SetPixel($x, $y, $pixel) }
        }
    }
    return $result
}

function Save-Model([string] $name) {
    $json = @{
        parent = 'minecraft:item/generated'
        textures = @{ layer0 = "miningdim:item/$name" }
    } | ConvertTo-Json -Depth 4
    [IO.File]::WriteAllText((Join-Path $modelDir "$name.json"), "$json`n", [Text.UTF8Encoding]::new($false))
}

function Draw-Label([Drawing.Graphics] $graphics, [string] $text, [int] $x, [int] $y) {
    $font = [Drawing.Font]::new('Arial', 7)
    $brush = [Drawing.SolidBrush]::new([Drawing.Color]::FromArgb(255, 220, 226, 235))
    $graphics.DrawString($text, $font, $brush, $x, $y)
    $brush.Dispose()
    $font.Dispose()
}

New-Item -ItemType Directory -Force -Path $itemDir, $modelDir, $outDir | Out-Null
$generated = @{}
$baseIcons = @{}
foreach ($part in $parts.Keys) {
    Copy-Item -LiteralPath $parts[$part].source -Destination (Join-Path $outDir "gunsmith-machine-gun-$part-source.png") -Force
    $baseIcons[$part] = New-LowResIcon $parts[$part]
}

foreach ($part in $parts.Keys) {
    foreach ($quality in $qualities.Keys) {
        $name = "gunsmith_part_machine_gun_${part}_${quality}"
        $icon = New-OutlinedIcon $baseIcons[$part] $qualities[$quality]
        $icon.Save((Join-Path $itemDir "$name.png"), [Drawing.Imaging.ImageFormat]::Png)
        $icon.Dispose()
        Save-Model $name
        $generated["$part/$quality"] = Join-Path $itemDir "$name.png"
    }
    $baseIcons[$part].Dispose()
}

$cellW = 112
$cellH = 98
$preview = [Drawing.Bitmap]::new($cellW * $parts.Count, $cellH * $qualities.Count, [Drawing.Imaging.PixelFormat]::Format32bppArgb)
$graphics = [Drawing.Graphics]::FromImage($preview)
$graphics.Clear([Drawing.Color]::FromArgb(255, 21, 25, 32))
$partsArray = @($parts.Keys)
$qualitiesArray = @($qualities.Keys)
for ($row = 0; $row -lt $qualitiesArray.Count; $row++) {
    for ($column = 0; $column -lt $partsArray.Count; $column++) {
        $part = $partsArray[$column]
        $quality = $qualitiesArray[$row]
        $x = $column * $cellW
        $y = $row * $cellH
        $graphics.DrawRectangle([Drawing.Pens]::DarkSlateGray, $x + 4, $y + 4, $cellW - 9, $cellH - 9)
        $icon = [Drawing.Bitmap]::new($generated["$part/$quality"])
        $graphics.DrawImage($icon, $x + 24, $y + 7, 64, 64)
        $icon.Dispose()
        Draw-Label $graphics "$part / $quality" ($x + 7) ($y + 75)
    }
}
$graphics.Dispose()
$preview.Save((Join-Path $outDir 'gunsmith-machine-gun-parts-preview.png'), [Drawing.Imaging.ImageFormat]::Png)
$preview.Dispose()
Write-Output "Generated $($parts.Count * $qualities.Count) machine-gun icons and models."
