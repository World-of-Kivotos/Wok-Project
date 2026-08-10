Add-Type -AssemblyName System.Drawing

$root = if ([string]::IsNullOrEmpty($PSScriptRoot)) {
    (Get-Location).Path
} else {
    Split-Path -Parent $PSScriptRoot
}
$backgroundPath = Join-Path $root 'src\main\resources\assets\miningdim\textures\gui\container\gunsmith_press.png'
$iconPath = Join-Path $root 'src\main\resources\assets\miningdim\textures\item\gunsmith_part_ar_core_gehenna_high_speed_gas_legendary.png'
$outputPath = Join-Path $root 'dist\gunsmith-gehenna-high-speed-gas-manufacturing-ui-preview.png'
$output3xPath = Join-Path $root 'dist\gunsmith-gehenna-high-speed-gas-manufacturing-ui-preview-3x.png'

$c = @{
    PanelBorder = [Drawing.Color]::FromArgb(255, 66, 80, 93)
    Panel = [Drawing.Color]::FromArgb(255, 17, 24, 32)
    PanelInner = [Drawing.Color]::FromArgb(255, 8, 13, 18)
    Text = [Drawing.Color]::FromArgb(255, 233, 237, 247)
    Muted = [Drawing.Color]::FromArgb(255, 142, 156, 170)
    Cyan = [Drawing.Color]::FromArgb(255, 53, 210, 164)
    CyanBright = [Drawing.Color]::FromArgb(255, 98, 230, 200)
    Amber = [Drawing.Color]::FromArgb(255, 240, 181, 42)
    Red = [Drawing.Color]::FromArgb(255, 255, 107, 104)
    Quality = [Drawing.Color]::FromArgb(255, 255, 79, 94)
    Disabled = [Drawing.Color]::FromArgb(255, 69, 77, 90)
}

function New-UiFont([float]$size) {
    return [Drawing.Font]::new('Microsoft YaHei UI', $size, [Drawing.FontStyle]::Regular,
        [Drawing.GraphicsUnit]::Pixel)
}

function Fill-Rect($graphics, $color, [int]$x, [int]$y, [int]$width, [int]$height) {
    $brush = [Drawing.SolidBrush]::new($color)
    try {
        $graphics.FillRectangle($brush, $x, $y, $width, $height)
    } finally {
        $brush.Dispose()
    }
}

function Draw-Text($graphics, [string]$text, [int]$x, [int]$y, $color, [float]$size) {
    $font = New-UiFont $size
    $brush = [Drawing.SolidBrush]::new($color)
    try {
        $graphics.DrawString($text, $font, $brush, [float]$x, [float]$y)
    } finally {
        $brush.Dispose()
        $font.Dispose()
    }
}

function Draw-Fit($graphics, [string]$text, [int]$x, [int]$y, [int]$maxWidth,
                  $color, [int]$preferred, [int]$minimum) {
    for ($size = $preferred; $size -ge $minimum; $size--) {
        $font = New-UiFont $size
        try {
            if ($graphics.MeasureString($text, $font).Width -le $maxWidth) {
                Draw-Text $graphics $text $x $y $color $size
                return
            }
        } finally {
            $font.Dispose()
        }
    }
    Draw-Text $graphics $text $x $y $color $minimum
}

function Draw-Centered($graphics, [string]$text, [int]$x, [int]$y, [int]$width, [int]$height,
                       $color, [float]$size) {
    $font = New-UiFont $size
    $brush = [Drawing.SolidBrush]::new($color)
    $format = [Drawing.StringFormat]::new()
    try {
        $format.Alignment = [Drawing.StringAlignment]::Center
        $format.LineAlignment = [Drawing.StringAlignment]::Center
        $graphics.DrawString($text, $font, $brush,
            [Drawing.RectangleF]::new($x, $y, $width, $height), $format)
    } finally {
        $format.Dispose()
        $brush.Dispose()
        $font.Dispose()
    }
}

function Draw-Panel($graphics, [int]$x, [int]$y, [int]$width, [int]$height, $border, $fill) {
    Fill-Rect $graphics $border $x $y $width $height
    Fill-Rect $graphics $fill ($x + 1) ($y + 1) ($width - 2) ($height - 2)
}

function Draw-Slot($graphics, [int]$x, [int]$y, $border, $itemColor) {
    Fill-Rect $graphics $border ($x - 2) ($y - 2) 22 22
    Fill-Rect $graphics $c.PanelInner ($x - 1) ($y - 1) 20 20
    if ($null -ne $itemColor) {
        Fill-Rect $graphics $itemColor ($x + 3) ($y + 3) 12 12
        Fill-Rect $graphics ([Drawing.Color]::FromArgb(100, 255, 255, 255)) ($x + 4) ($y + 4) 10 1
    }
}

$background = [Drawing.Image]::FromFile($backgroundPath)
$canvas = [Drawing.Bitmap]::new(360, 240, [Drawing.Imaging.PixelFormat]::Format32bppArgb)
$graphics = [Drawing.Graphics]::FromImage($canvas)
try {
    $graphics.InterpolationMode = [Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $graphics.PixelOffsetMode = [Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $graphics.TextRenderingHint = [Drawing.Text.TextRenderingHint]::AntiAliasGridFit
    $graphics.DrawImage($background, [Drawing.Rectangle]::new(0, 0, 360, 240))

    Draw-Fit $graphics '机械冲压机' 98 17 180 $c.Text 15 12
    Draw-Fit $graphics '枪匠组件制造线' 99 39 170 $c.Muted 8 7

    Fill-Rect $graphics ([Drawing.Color]::FromArgb(255, 40, 48, 62)) 29 39 18 18
    Fill-Rect $graphics ([Drawing.Color]::FromArgb(255, 208, 166, 131)) 32 42 12 9
    Fill-Rect $graphics ([Drawing.Color]::FromArgb(255, 68, 84, 116)) 34 50 8 6
    Draw-Fit $graphics 'LV 10' 52 41 25 $c.Text 7 6
    Fill-Rect $graphics ([Drawing.Color]::FromArgb(255, 26, 32, 41)) 29 65 46 2
    Fill-Rect $graphics $c.Cyan 29 65 43 2

    Draw-Panel $graphics 27 74 52 14 $c.Cyan $c.Panel
    Draw-Centered $graphics '<' 27 74 12 14 $c.Text 7
    Draw-Centered $graphics 'AR' 39 74 28 14 ([Drawing.Color]::FromArgb(255, 191, 251, 239)) 7
    Draw-Centered $graphics '>' 67 74 12 14 $c.Text 7

    $parts = @('导气', '枪管', '枪机', '护木', '握把', '枪托')
    for ($index = 0; $index -lt $parts.Count; $index++) {
        $y = 92 + $index * 18
        $selected = $index -eq 0
        $popupOwner = $index -eq 0
        $border = if ($popupOwner) { $c.Cyan } elseif ($selected) {
            $c.Amber
        } else {
            [Drawing.Color]::FromArgb(255, 51, 56, 68)
        }
        $fill = if ($popupOwner) {
            [Drawing.Color]::FromArgb(255, 22, 60, 56)
        } elseif ($selected) {
            [Drawing.Color]::FromArgb(255, 58, 49, 40)
        } else {
            [Drawing.Color]::FromArgb(255, 35, 38, 49)
        }
        $textColor = if ($popupOwner) {
            [Drawing.Color]::FromArgb(255, 200, 255, 243)
        } elseif ($selected) {
            [Drawing.Color]::FromArgb(255, 243, 215, 162)
        } else {
            [Drawing.Color]::FromArgb(255, 209, 216, 228)
        }
        Draw-Panel $graphics 27 $y 52 14 $border $fill
        Draw-Centered $graphics $parts[$index] 27 $y 43 14 $textColor 7
        if ($popupOwner) {
            Draw-Centered $graphics '<' 69 $y 10 14 $c.CyanBright 7
        }
    }

    Draw-Panel $graphics 25 198 57 32 ([Drawing.Color]::FromArgb(255, 51, 56, 68)) `
        ([Drawing.Color]::FromArgb(255, 32, 35, 46))
    Draw-Centered $graphics '当前组件' 27 200 53 10 $c.Muted 5
    Draw-Centered $graphics '格赫娜高速导气' 27 212 53 12 ([Drawing.Color]::FromArgb(255, 200, 255, 243)) 5

    Draw-Panel $graphics 94 58 188 63 $c.PanelBorder $c.Panel
    Fill-Rect $graphics $c.Quality 95 59 3 61
    Fill-Rect $graphics ([Drawing.Color]::FromArgb(255, 38, 49, 59)) 101 65 46 46
    Fill-Rect $graphics $c.PanelInner 103 67 42 42
    $icon = [Drawing.Image]::FromFile($iconPath)
    try {
        $oldMode = $graphics.InterpolationMode
        $graphics.InterpolationMode = [Drawing.Drawing2D.InterpolationMode]::NearestNeighbor
        $graphics.PixelOffsetMode = [Drawing.Drawing2D.PixelOffsetMode]::Half
        $graphics.DrawImage($icon, [Drawing.Rectangle]::new(104, 68, 40, 40))
        $graphics.InterpolationMode = $oldMode
        $graphics.PixelOffsetMode = [Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    } finally {
        $icon.Dispose()
    }
    Draw-Fit $graphics '格赫娜高速导气' 153 63 123 $c.Quality 9 6
    Draw-Fit $graphics 'AR / 导气组件' 153 78 123 $c.Muted 7 6
    Draw-Fit $graphics '半/全自动射速 +18.5%~+25.0%' 153 88 123 $c.CyanBright 6 5
    Draw-Fit $graphics '上跳后坐力 +222.2%~+300.0%' 153 98 123 $c.Red 6 5
    Draw-Fit $graphics '散布 +30.0%' 222 108 54 $c.Red 6 5

    $qualities = @(
        @('普通', [Drawing.Color]::FromArgb(255, 233, 238, 247)),
        @('改良', [Drawing.Color]::FromArgb(255, 71, 227, 124)),
        @('军规', [Drawing.Color]::FromArgb(255, 86, 168, 255)),
        @('精密', [Drawing.Color]::FromArgb(255, 197, 108, 255)),
        @('传奇', $c.Quality)
    )
    for ($index = 0; $index -lt $qualities.Count; $index++) {
        $x = 103 + $index * 36
        $selected = $index -eq 4
        $fill = if ($selected) {
            [Drawing.Color]::FromArgb(255, 37, 42, 52)
        } else {
            [Drawing.Color]::FromArgb(255, 34, 38, 49)
        }
        $textColor = if ($selected) { $qualities[$index][1] } else {
            [Drawing.Color]::FromArgb(255, 184, 192, 206)
        }
        Draw-Panel $graphics $x 124 32 13 $qualities[$index][1] $fill
        Draw-Centered $graphics $qualities[$index][0] $x 124 32 13 $textColor 6
    }

    Draw-Panel $graphics 287 58 63 84 $c.PanelBorder $c.Panel
    Draw-Centered $graphics '材料 / 成品' 287 60 63 10 $c.Text 6
    Draw-Slot $graphics 294 84 $c.Cyan ([Drawing.Color]::FromArgb(255, 145, 150, 157))
    Draw-Slot $graphics 320 84 $c.Cyan ([Drawing.Color]::FromArgb(255, 184, 107, 66))
    Draw-Slot $graphics 294 110 $c.Cyan ([Drawing.Color]::FromArgb(255, 87, 161, 72))
    Draw-Slot $graphics 320 110 $c.Disabled $null
    Draw-Centered $graphics '40/40' 292 71 22 10 $c.CyanBright 6
    Draw-Centered $graphics '60/60' 318 71 22 10 $c.CyanBright 6
    Draw-Centered $graphics '0/0' 292 130 22 10 $c.CyanBright 6
    Draw-Centered $graphics '成品' 318 130 22 10 $c.Muted 5

    Draw-Panel $graphics 292 146 54 24 $c.Cyan ([Drawing.Color]::FromArgb(255, 20, 76, 70))
    Fill-Rect $graphics $c.CyanBright 295 149 48 1
    Fill-Rect $graphics $c.Amber 295 166 48 1
    Draw-Centered $graphics '开始冲压' 292 146 54 24 ([Drawing.Color]::FromArgb(255, 234, 251, 247)) 7

    Draw-Panel $graphics 292 184 54 36 ([Drawing.Color]::FromArgb(255, 85, 96, 113)) `
        ([Drawing.Color]::FromArgb(255, 35, 41, 56))
    Draw-Centered $graphics '制作时间' 292 190 54 12 ([Drawing.Color]::FromArgb(255, 174, 184, 200)) 6
    Draw-Centered $graphics '6:00' 292 201 54 14 $c.Text 8
    Fill-Rect $graphics ([Drawing.Color]::FromArgb(255, 17, 20, 27)) 297 212 44 3

    $popupX = 83
    $popupY = 92
    $popupW = 136
    $popupH = 43
    Fill-Rect $graphics ([Drawing.Color]::FromArgb(153, 0, 0, 0)) ($popupX + 3) ($popupY + 4) $popupW $popupH
    Draw-Panel $graphics $popupX $popupY $popupW $popupH `
        ([Drawing.Color]::FromArgb(255, 104, 119, 129)) ([Drawing.Color]::FromArgb(255, 11, 16, 22))
    $popupItems = @('基础导气', '格赫娜高速导气')
    for ($index = 0; $index -lt $popupItems.Count; $index++) {
        $itemX = $popupX + 3
        $itemY = $popupY + 3 + $index * 19
        $current = $index -eq 1
        $border = if ($current) { $c.Cyan } else { [Drawing.Color]::FromArgb(255, 53, 65, 77) }
        $fill = if ($current) {
            [Drawing.Color]::FromArgb(255, 22, 60, 56)
        } else {
            [Drawing.Color]::FromArgb(255, 23, 30, 38)
        }
        $textColor = if ($current) {
            [Drawing.Color]::FromArgb(255, 200, 255, 243)
        } else {
            [Drawing.Color]::FromArgb(255, 209, 216, 228)
        }
        Draw-Panel $graphics $itemX $itemY 130 18 $border $fill
        if ($current) {
            Fill-Rect $graphics $c.CyanBright ($itemX + 2) ($itemY + 2) 2 14
        }
        Draw-Fit $graphics $popupItems[$index] ($itemX + 7) ($itemY + 5) 118 $textColor 7 5
    }
} finally {
    $graphics.Dispose()
    $background.Dispose()
}

$canvas.Save($outputPath, [Drawing.Imaging.ImageFormat]::Png)
$preview3x = [Drawing.Bitmap]::new(1080, 720, [Drawing.Imaging.PixelFormat]::Format32bppArgb)
$graphics3x = [Drawing.Graphics]::FromImage($preview3x)
try {
    $graphics3x.InterpolationMode = [Drawing.Drawing2D.InterpolationMode]::NearestNeighbor
    $graphics3x.PixelOffsetMode = [Drawing.Drawing2D.PixelOffsetMode]::Half
    $graphics3x.DrawImage($canvas, [Drawing.Rectangle]::new(0, 0, 1080, 720))
} finally {
    $graphics3x.Dispose()
    $canvas.Dispose()
}
$preview3x.Save($output3xPath, [Drawing.Imaging.ImageFormat]::Png)
$preview3x.Dispose()

Write-Output $outputPath
Write-Output $output3xPath
