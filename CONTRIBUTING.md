# Contribuyendo a YOLA

¡Gracias por contribuir! YOLA es un ecosistema de agentes de IA. Cada repo es una capa independiente.

## Cómo contribuir

1. **Fork** el repo
2. **Creá un branch** (`git checkout -b feat/mi-cambio`)
3. **Hacé tus cambios** (leé AGENTS.md para guía específica del repo)
4. **Probá** que compile (`./gradlew assembleDebug`)
5. **Commit** con mensaje claro (`feat:`, `fix:`, `docs:`)
6. **Push** y abrí un Pull Request

## Reglas

- Kotlin + Jetpack Compose en `app/`. Rust en `daemon/`. Cada capa es independiente.
- El daemon se cross-compila a ARM64 — no uses crates incompatibles con Android NDK.
- La UI WebView carga SolidJS desde assets o localhost — no hardcodees URLs de producción.
- Mantené los cambios quirúrgicos. Un propósito por PR.
- Respetá el estilo de código existente.
- Los textos de UI van en español.

## Reportar bugs

Abrí un [issue](https://github.com/ROSALDEV-SAC/yola-android/issues) con:
- Descripción del bug
- Pasos para reproducir
- Dispositivo Android y versión de OS

## Código de conducta

Sé respetuoso. YOLA es construida por una comunidad global.

> YOLA by Sayri · Lima, Perú
