# Скачивает libmpv-2.dll (dev-билд) в mpv/ для desktop-плеера.
# Запуск: powershell -File tools\fetch-libmpv.ps1
# Свежий URL: https://sourceforge.net/projects/mpv-player-windows/files/libmpv/
$ErrorActionPreference = "Stop"

$url = "https://sourceforge.net/projects/mpv-player-windows/files/libmpv/mpv-dev-x86_64-20260830-git-e8673660ab.7z/download"

$dir = Join-Path $env:TEMP ("mpv-dev-" + [guid]::NewGuid())
New-Item -ItemType Directory -Force -Path $dir | Out-Null
$archive = Join-Path $dir "mpv-dev.7z"

Invoke-WebRequest -Uri $url -OutFile $archive -MaximumRedirection 10
tar -xf $archive -C $dir

$dest = Join-Path $PSScriptRoot "..\mpv"
New-Item -ItemType Directory -Force -Path $dest | Out-Null
Copy-Item (Join-Path $dir "libmpv-2.dll") $dest -Force
Remove-Item $dir -Recurse -Force

Write-Host "libmpv-2.dll положена в $dest"
