# Stable test signing key

`wifi-password-guard.p12` is intentionally committed so local and GitHub Actions builds produce
APKs with the same signature. It is a public test key, not a secure release identity.

- Alias: `wifi-password-guard`
- Certificate SHA-256: `03:3A:6B:A5:75:AD:6A:56:E6:32:08:9A:3A:1F:52:B5:1E:F7:80:B8:2B:5E:EC:33:22:F8:6A:85:FB:40:CA:09`

Replace this key and the corresponding values in `app/build.gradle.kts` before using the project
for a trusted public release. Keep the replacement key permanently; Android cannot install an
update signed by a different key over an existing installation.
