# Fork development and releases

This flake follows [drew-council/nix-project-template](https://github.com/drew-council/nix-project-template): flake-utils, direnv, treefmt-nix, Topiary/Nushell, and the template's local pre-commit formatter hook. Android-specific additions are JDK 21, SDK platform 37, build-tools 36/37, adb, and the patched SDK aapt2 override required on NixOS. Use the checked-in Gradle wrapper, not a separately installed Gradle.

```sh
nix develop
./gradlew :app:testDebugUnitTest :app:assembleRelease --no-daemon
nix fmt
nix flake check
```

Alternatively allow `.envrc` with `direnv allow`. The shell supplies `JAVA_HOME`, `ANDROID_HOME`, and `ANDROID_SDK_ROOT`; no machine-specific `local.properties` is needed. SDK licenses are accepted by the flake. Gradle downloads Maven dependencies into the normal user cache; this is a development shell, not a fully offline Nix APK derivation.

## Signing

The fork keeps `com.hermes.client` and uses its own stable signing key. The first install requires uninstalling upstream Hermes because Android rejects an update signed by a different certificate. Future fork updates use the same certificate and install normally through Obtainium. Do not uninstall an existing *fork* build to update it.

Keep the private keystore and passwords outside Git and the Nix store. Local `keystore.properties` is gitignored and should be mode 600; point `storeFile` at a private file outside this checkout. Never put passwords in command arguments, flake values, or build logs. The upstream release workflow supports GitHub Actions secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, and `KEY_PASSWORD` for future automated releases.

A local release build writes `app/build/outputs/apk/release/app-release.apk`. Verify it with the SDK's `apksigner verify --verbose --print-certs`, then publish only the signed APK and its SHA-256 checksum. Retain the signing-key backup for as long as this fork is distributed. Never publish keystores, password files, or `keystore.properties` as release assets.
