# VRCDI launcher icon generator
#
# Usage (run from the repository root):
#   powershell -ExecutionPolicy Bypass -File tools\make_icons.ps1 -Source "path\to\image.jpg"
#
# What it does:
#   1. Center-crops the image to a square and writes mipmap-mdpi/hdpi/xhdpi/xxhdpi/xxxhdpi ic_launcher.png
#   2. Writes an adaptive-icon foreground (432 px, artwork scaled to 78% so it survives circular masks)
#   3. Points mipmap-anydpi-v26/ic_launcher.xml and ic_launcher_round.xml at the new foreground
#
# Requires only Windows PowerShell and .NET System.Drawing (no extra installs).
#
# Compatibility notes:
#   - ASCII-only on purpose: Windows PowerShell reads .ps1 as ANSI when there is no BOM,
#     so non-ASCII text would break parsing on Chinese Windows.
#   - Written for Windows PowerShell 2.0 (the default on Windows 7): no $PSScriptRoot,
#     no [string]::IsNullOrWhiteSpace, no PowerShell 3+ syntax.

param(
    [string]$Source
)

$ErrorActionPreference = 'Stop'

if ([string]::IsNullOrEmpty($Source) -or $Source.Trim().Length -eq 0) {
    Write-Host 'Usage: powershell -ExecutionPolicy Bypass -File tools\make_icons.ps1 -Source "path\to\image.jpg"'
    exit 1
}
if (-not (Test-Path -LiteralPath $Source)) {
    throw ('Image not found: ' + $Source)
}

Add-Type -AssemblyName System.Drawing

$scriptPath = $MyInvocation.MyCommand.Definition
if (-not [System.IO.Path]::IsPathRooted($scriptPath)) {
    $scriptPath = Join-Path (Get-Location).Path $scriptPath
}
$scriptDir = Split-Path -Parent $scriptPath
$projectDir = Split-Path -Parent $scriptDir
$resDir = Join-Path $projectDir 'app\src\main\res'
if (-not (Test-Path -LiteralPath $resDir)) {
    throw ('Resource directory not found: ' + $resDir)
}

$sourcePath = (Resolve-Path -LiteralPath $Source).Path
$image = [System.Drawing.Image]::FromFile($sourcePath)

function Save-Icon {
    param(
        [System.Drawing.Image]$Image,
        [System.Single]$CropX,
        [System.Single]$CropY,
        [System.Single]$CropSize,
        [int]$Size,
        [double]$Scale,
        [string]$Path
    )

    $bitmap = New-Object System.Drawing.Bitmap -ArgumentList $Size, $Size
    $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
    try {
        $graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
        $graphics.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
        $graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
        $graphics.Clear([System.Drawing.Color]::Transparent)

        $drawSize = [int]($Size * $Scale)
        $offset = [int](($Size - $drawSize) / 2)
        $dest = New-Object System.Drawing.Rectangle -ArgumentList $offset, $offset, $drawSize, $drawSize
        $graphics.DrawImage($Image, $dest, $CropX, $CropY, $CropSize, $CropSize, [System.Drawing.GraphicsUnit]::Pixel)
    }
    finally {
        $graphics.Dispose()
    }

    $dir = Split-Path -Parent $Path
    if (-not (Test-Path -LiteralPath $dir)) {
        New-Item -ItemType Directory -Path $dir -Force | Out-Null
    }
    $bitmap.Save($Path, [System.Drawing.Imaging.ImageFormat]::Png)
    $bitmap.Dispose()
    Write-Host ('  ' + $Path)
}

try {
    $side = [Math]::Min($image.Width, $image.Height)
    $cropX = [System.Single](($image.Width - $side) / 2.0)
    $cropY = [System.Single](($image.Height - $side) / 2.0)
    $cropSize = [System.Single]$side

    Write-Host ('Source: ' + $sourcePath + ' (' + $image.Width + 'x' + $image.Height + ')')

    Write-Host 'Generating launcher icons:'
    Save-Icon -Image $image -CropX $cropX -CropY $cropY -CropSize $cropSize -Size 48  -Scale 1.0 -Path (Join-Path $resDir 'mipmap-mdpi\ic_launcher.png')
    Save-Icon -Image $image -CropX $cropX -CropY $cropY -CropSize $cropSize -Size 72  -Scale 1.0 -Path (Join-Path $resDir 'mipmap-hdpi\ic_launcher.png')
    Save-Icon -Image $image -CropX $cropX -CropY $cropY -CropSize $cropSize -Size 96  -Scale 1.0 -Path (Join-Path $resDir 'mipmap-xhdpi\ic_launcher.png')
    Save-Icon -Image $image -CropX $cropX -CropY $cropY -CropSize $cropSize -Size 144 -Scale 1.0 -Path (Join-Path $resDir 'mipmap-xxhdpi\ic_launcher.png')
    Save-Icon -Image $image -CropX $cropX -CropY $cropY -CropSize $cropSize -Size 192 -Scale 1.0 -Path (Join-Path $resDir 'mipmap-xxxhdpi\ic_launcher.png')

    Write-Host 'Generating adaptive icon foreground:'
    Save-Icon -Image $image -CropX $cropX -CropY $cropY -CropSize $cropSize -Size 432 -Scale 0.78 -Path (Join-Path $resDir 'mipmap-xxxhdpi\ic_launcher_foreground.png')
}
finally {
    $image.Dispose()
}

$adaptive = @"
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background" />
    <foreground android:drawable="@mipmap/ic_launcher_foreground" />
</adaptive-icon>
"@

$anydpi = Join-Path $resDir 'mipmap-anydpi-v26'
$utf8NoBom = New-Object System.Text.UTF8Encoding -ArgumentList $false
[System.IO.File]::WriteAllText((Join-Path $anydpi 'ic_launcher.xml'), $adaptive, $utf8NoBom)
[System.IO.File]::WriteAllText((Join-Path $anydpi 'ic_launcher_round.xml'), $adaptive, $utf8NoBom)

Write-Host ''
Write-Host 'Done. Rebuild to apply the icon: gradlew assembleDebug'
