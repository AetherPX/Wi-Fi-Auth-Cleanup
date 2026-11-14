# Stable test signing key

`wifi-auth-cleanup.p12` is intentionally committed so local and GitHub Actions builds produce
APKs with the same signature. It is a public test key, not a secure release identity.

- Alias: `wifi-auth-cleanup`
- Certificate SHA-256: `0D:22:1D:15:FC:FC:54:A6:35:2C:FC:D9:3C:2C:21:AF:9A:90:42:9A:41:25:FB:35:D7:6A:68:AC:A1:42:2E:99`

Replace this key and the corresponding values in `app/build.gradle.kts` before using the project
for a trusted public release. Keep the replacement key permanently; Android cannot install an
update signed by a different key over an existing installation.
