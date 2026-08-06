# build-apk.ps1 — Build completo de YOLA Android APK
# Requisitos: Android Studio, NDK, Rust targets (aarch64-linux-android)

$ErrorActionPreference = "Stop"
Write-Host "YOLA Android — Build APK" -ForegroundColor Magenta

# 0. Configurar Android SDK/NDK para Cargo
Write-Host "[0/3] Configurando Android SDK/NDK..." -ForegroundColor Cyan
$sdkDir = $null
if (Test-Path "local.properties") {
    $match = Select-String -Path "local.properties" -Pattern '^sdk\.dir=(.+)$' | Select-Object -First 1
    if ($match) {
        $sdkDir = $match.Matches.Groups[1].Value -replace '\\\\', '\'
        $sdkDir = $sdkDir -replace '\\:', ':'
    }
}
if (-not $sdkDir) { $sdkDir = "$env:LOCALAPPDATA\Android\Sdk" }
if (-not (Test-Path $sdkDir)) { Write-Host "ERROR: Android SDK no encontrado en $sdkDir" -ForegroundColor Red; exit 1 }
Write-Host "  SDK: $sdkDir"

$ndkDir = Get-ChildItem "$sdkDir\ndk" -Directory -ErrorAction SilentlyContinue | Sort-Object Name -Descending | Select-Object -First 1
if (-not $ndkDir) { Write-Host "ERROR: NDK no instalado" -ForegroundColor Red; exit 1 }
Write-Host "  NDK: $($ndkDir.Name)"

$env:ANDROID_NDK_HOME = $ndkDir.FullName
$toolchainBin = Join-Path $ndkDir.FullName "toolchains\llvm\prebuilt\windows-x86_64\bin"
$env:PATH = "$toolchainBin;$env:PATH"

# 1. Compilar daemon Rust a ARM64
Write-Host "[1/3] Compilando daemon para Android ARM64..." -ForegroundColor Cyan
Push-Location daemon
try {
    cargo build --release --target aarch64-linux-android
    $daemonBin = "target/aarch64-linux-android/release/yola-daemon-mobile"
    if (-not (Test-Path $daemonBin)) {
        Write-Host "ERROR: No se pudo compilar el daemon." -ForegroundColor Red
        exit 1
    }
    Write-Host "  Daemon ARM64: $((Get-Item $daemonBin).Length / 1KB) KB" -ForegroundColor Green
} finally { Pop-Location }

# 2. Copiar daemon a assets
Write-Host "[2/3] Copiando daemon a assets..." -ForegroundColor Cyan
$assetsDir = "app/src/main/assets/daemon"
New-Item -ItemType Directory -Force -Path $assetsDir | Out-Null
Copy-Item "daemon/$daemonBin" "$assetsDir/yola-daemon-mobile" -Force
Write-Host "  Listo: $assetsDir/yola-daemon-mobile" -ForegroundColor Green

# 3. Build UI
Write-Host "[3/3] Compilando UI..." -ForegroundColor Cyan
Push-Location ui
try {
    bun install --silent
    bun run build
    Write-Host "  UI compilada: dist/" -ForegroundColor Green
} finally { Pop-Location }

Write-Host ""
Write-Host "Listo. Abri Android Studio y hace Build -> Build APK" -ForegroundColor Green
Write-Host "  APK en: app/build/outputs/apk/debug/app-debug.apk" -ForegroundColor Yellow
