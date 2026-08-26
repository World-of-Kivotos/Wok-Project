$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

$repoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$atlasPath = Join-Path $repoRoot 'src\main\resources\assets\miningdim\textures\font\gunsmith_quality_names.png'
$previewPath = Join-Path $repoRoot 'build\gunsmith_legendary_quality_glyph_preview.png'
$fontPath = 'C:\Windows\Fonts\msyhbd.ttc'

if (!(Test-Path -LiteralPath $atlasPath -PathType Leaf)) {
    throw "Missing quality font atlas: $atlasPath"
}
if (!(Test-Path -LiteralPath $fontPath -PathType Leaf)) {
    throw "Missing Microsoft YaHei Bold font: $fontPath"
}

$source = [System.Drawing.Bitmap]::FromFile($atlasPath)
if ($source.Width -ne 48 -or $source.Height -ne 120) {
    throw "Unexpected quality font atlas dimensions: $($source.Width)x$($source.Height)"
}

$atlas = [System.Drawing.Bitmap]::new(48, 120, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
$atlasGraphics = [System.Drawing.Graphics]::FromImage($atlas)
$atlasGraphics.CompositingMode = [System.Drawing.Drawing2D.CompositingMode]::SourceCopy
$atlasGraphics.DrawImageUnscaled($source, 0, 0)
$atlasGraphics.FillRectangle([System.Drawing.Brushes]::Transparent, 0, 96, 48, 24)
$source.Dispose()

# Render at 4x and downsample once so the 12-pixel in-game glyph stays readable.
$scale = 4
$large = [System.Drawing.Bitmap]::new(48 * $scale, 24 * $scale,
    [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
$graphics = [System.Drawing.Graphics]::FromImage($large)
$graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
$graphics.CompositingQuality = [System.Drawing.Drawing2D.CompositingQuality]::HighQuality
$graphics.TextRenderingHint = [System.Drawing.Text.TextRenderingHint]::AntiAliasGridFit

$privateFonts = [System.Drawing.Text.PrivateFontCollection]::new()
$privateFonts.AddFontFile($fontPath)
$fontFamily = $privateFonts.Families[0]
$path = [System.Drawing.Drawing2D.GraphicsPath]::new()
$format = [System.Drawing.StringFormat]::new([System.Drawing.StringFormat]::GenericTypographic)
$format.Alignment = [System.Drawing.StringAlignment]::Center
$format.LineAlignment = [System.Drawing.StringAlignment]::Center
$layout = [System.Drawing.RectangleF]::new(0, -2, 48 * $scale, 24 * $scale)
$fontStyle = [int][System.Drawing.FontStyle]::Bold
$path.AddString('传奇', $fontFamily, $fontStyle, [single]61.0, $layout, $format)

$outline = [System.Drawing.Pen]::new([System.Drawing.Color]::FromArgb(255, 103, 20, 27), [single]5.0)
$outline.LineJoin = [System.Drawing.Drawing2D.LineJoin]::Round
$graphics.DrawPath($outline, $path)

$gradient = [System.Drawing.Drawing2D.LinearGradientBrush]::new(
    [System.Drawing.PointF]::new(0, 16),
    [System.Drawing.PointF]::new(0, 84),
    [System.Drawing.Color]::FromArgb(255, 255, 224, 118),
    [System.Drawing.Color]::FromArgb(255, 224, 101, 25))
$graphics.FillPath($gradient, $path)

$innerHighlight = [System.Drawing.Pen]::new([System.Drawing.Color]::FromArgb(150, 255, 244, 181), [single]1.5)
$graphics.DrawPath($innerHighlight, $path)

$innerHighlight.Dispose()
$gradient.Dispose()
$outline.Dispose()
$path.Dispose()
$format.Dispose()
$graphics.Dispose()
$privateFonts.Dispose()

$glyph = [System.Drawing.Bitmap]::new(48, 24, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
$glyphGraphics = [System.Drawing.Graphics]::FromImage($glyph)
$glyphGraphics.CompositingMode = [System.Drawing.Drawing2D.CompositingMode]::SourceCopy
$glyphGraphics.CompositingQuality = [System.Drawing.Drawing2D.CompositingQuality]::HighQuality
$glyphGraphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
$glyphGraphics.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
$glyphGraphics.DrawImage($large, [System.Drawing.Rectangle]::new(0, 0, 48, 24),
    0, 0, $large.Width, $large.Height, [System.Drawing.GraphicsUnit]::Pixel)
$glyphGraphics.Dispose()
$large.Dispose()

$atlasGraphics.DrawImageUnscaled($glyph, 0, 96)
$atlasGraphics.Dispose()
$atlas.Save($atlasPath, [System.Drawing.Imaging.ImageFormat]::Png)

# Nearest-neighbour enlarged preview on the actual tooltip background tone.
$preview = [System.Drawing.Bitmap]::new(480, 160, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
$previewGraphics = [System.Drawing.Graphics]::FromImage($preview)
$previewGraphics.Clear([System.Drawing.Color]::FromArgb(255, 12, 0, 14))
$previewGraphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::NearestNeighbor
$previewGraphics.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::Half
$previewGraphics.DrawImage($glyph, [System.Drawing.Rectangle]::new(80, 20, 320, 120),
    0, 0, 48, 24, [System.Drawing.GraphicsUnit]::Pixel)
$previewGraphics.Dispose()
$preview.Save($previewPath, [System.Drawing.Imaging.ImageFormat]::Png)

$glyph.Dispose()
$atlas.Dispose()
$preview.Dispose()

"atlas=$atlasPath"
"preview=$previewPath"
