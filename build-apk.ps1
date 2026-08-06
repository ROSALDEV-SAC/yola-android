# build-apk.ps1 — Build completo de YOLA Android APK
# Requisitos: Android Studio, NDK, Rust targets (aarch64-linux-android)

$ErrorActionPreference = "Stop"
Write-Host "🐱 YOLA Android — Build APK" -ForegroundColor Magenta

# 1. Cross-compile daemon Rust a ARM64
Write-Host "`n[1/3] Compilando daemon para Android ARM64..." -ForegroundColor Cyan
Push-Location daemon
try {
    cargo build --release --target aarch64-linux-android
    $daemonBin = "target/aarch64-linux-android/release/yola-daemon-mobile"
    if (-not (Test-Path $daemonBin)) {
        Write-Host "ERROR: No se pudo compilar el daemon. ¿Tenés NDK instalado?" -ForegroundColor Red
        Write-Host "  Android Studio → SDK Manager → SDK Tools → NDK (Side by side)" -ForegroundColor Yellow
        exit 1
    }
    Write-Host "  Daemon ARM64: $((Get-Item $daemonBin).Length / 1KB) KB" -ForegroundColor Green
} finally { Pop-Location }

# 2. Copiar daemon a assets
Write-Host "`n[2/3] Copiando daemon a assets..." -ForegroundColor Cyan
$assetsDir = "app/src/main/assets/daemon"
New-Item -ItemType Directory -Force -Path $assetsDir | Out-Null
Copy-Item "daemon/$daemonBin" "$assetsDir/yola-daemon-mobile" -Force
Write-Host "  Listo: $assetsDir/yola-daemon-mobile" -ForegroundColor Green

# 3. Build UI
Write-Host "`n[3/3] Compilando UI..." -ForegroundColor Cyan
Push-Location ui
try {
    bun install --silent
    bun run build
    Write-Host "  UI compilada: dist/" -ForegroundColor Green
} finally { Pop-Location }

Write-Host "`n✅ Listo. Abrí Android Studio y hacé Build → Build APK" -ForegroundColor Green
Write-Host "   APK en: app/build/outputs/apk/debug/app-debug.apk" -ForegroundColor Yellow
