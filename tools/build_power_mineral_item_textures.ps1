param(
    [string]$SourcePath = (Join-Path $PSScriptRoot 'assets\power\mineral_icons_source.png'),
    [string]$OutputDirectory = (Join-Path $PSScriptRoot '..\src\main\resources\assets\miningdim\textures\item')
)

$expectedSourceHash = 'CCBA36969348BDC61E57D2E934FE892D0061CFD79A325D885E5FCB67F0046FE6'
$actualSourceHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $SourcePath).Hash
if ($actualSourceHash -ne $expectedSourceHash) {
    throw "矿物图标源稿哈希不匹配: $actualSourceHash"
}

Add-Type -AssemblyName System.Drawing
$source = [System.Drawing.Bitmap]::FromFile($SourcePath)
try {
    if ($source.Width -ne 160 -or $source.Height -ne 563) {
        throw "矿物图标源稿尺寸必须为 160x563，实得 $($source.Width)x$($source.Height)"
    }

    $sheet = New-Object System.Drawing.Bitmap 32, 112, ([System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    try {
        for ($targetY = 0; $targetY -lt $sheet.Height; $targetY++) {
            $sourceY = [Math]::Floor(($targetY + 0.5) * $source.Height / $sheet.Height)
            for ($targetX = 0; $targetX -lt $sheet.Width; $targetX++) {
                $sourceX = [Math]::Floor(($targetX + 0.5) * $source.Width / $sheet.Width)
                $sheet.SetPixel($targetX, $targetY, $source.GetPixel($sourceX, $sourceY))
            }
        }

        $sprites = @(
            @{ Name = 'silver_ingot'; X = 0; Y = 0 },
            @{ Name = 'raw_silver'; X = 16; Y = 0 },
            @{ Name = 'tin_ingot'; X = 0; Y = 16 },
            @{ Name = 'raw_tin'; X = 16; Y = 16 },
            @{ Name = 'aluminum_ingot'; X = 0; Y = 32 },
            @{ Name = 'raw_aluminum'; X = 16; Y = 32 },
            @{ Name = 'chromium_ingot'; X = 0; Y = 48 },
            @{ Name = 'raw_chromium'; X = 16; Y = 48 },
            @{ Name = 'tungsten_ingot'; X = 0; Y = 64 },
            @{ Name = 'raw_tungsten'; X = 16; Y = 64 },
            @{ Name = 'nickel_ingot'; X = 0; Y = 80 },
            @{ Name = 'raw_nickel'; X = 16; Y = 80 },
            @{ Name = 'borax'; X = 0; Y = 96 }
        )

        New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
        foreach ($sprite in $sprites) {
            $texture = New-Object System.Drawing.Bitmap 16, 16, ([System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
            try {
                for ($y = 0; $y -lt 16; $y++) {
                    for ($x = 0; $x -lt 16; $x++) {
                        $texture.SetPixel($x, $y, $sheet.GetPixel($sprite.X + $x, $sprite.Y + $y))
                    }
                }
                $outputPath = Join-Path $OutputDirectory ($sprite.Name + '.png')
                $texture.Save($outputPath, [System.Drawing.Imaging.ImageFormat]::Png)
            } finally {
                $texture.Dispose()
            }
        }
    } finally {
        $sheet.Dispose()
    }
} finally {
    $source.Dispose()
}
