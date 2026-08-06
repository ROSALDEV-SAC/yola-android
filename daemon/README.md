# yola-daemon-mobile

Daemon YOLA mínimo compilado para Android ARM.

## Build

Requiere Android NDK y Rust targets:

```bash
rustup target add aarch64-linux-android
cargo build --release --target aarch64-linux-android
```

## Binario

`target/aarch64-linux-android/release/yola-daemon-mobile`

Copiar a `yola-android/app/src/main/assets/` para embeber en el APK.
