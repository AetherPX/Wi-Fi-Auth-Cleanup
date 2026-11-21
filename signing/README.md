# Signing setup

No private key or password is stored in this repository.

## GitHub Actions

Official builds read the following Repository Secrets:

| Secret | Value |
| --- | --- |
| `SIGNING_KEY_BASE64` | Single-line Base64 representation of the PKCS#12 file |
| `SIGNING_STORE_PASSWORD` | PKCS#12 store password |
| `SIGNING_KEY_ALIAS` | Signing key alias |
| `SIGNING_KEY_PASSWORD` | Signing key password |

Create the Base64 value locally without writing it into the repository:

```bash
base64 -w 0 /secure/path/wifi-auth-cleanup.p12
```

Push and manual builds fail when any required secret is missing. Pull requests without access to
Repository Secrets use a temporary certificate and therefore do not produce update-compatible APKs.

## Local builds

Create an ignored `signing.properties` file in the repository root:

```properties
storeFile=/absolute/path/to/wifi-auth-cleanup.p12
storePassword=your-store-password
keyAlias=your-key-alias
keyPassword=your-key-password
```

Keep the keystore outside the repository. `signing.properties`, `*.p12`, `*.jks`, and `*.keystore`
are ignored by Git.

Current release certificate SHA-256:

```text
0D:22:1D:15:FC:FC:54:A6:35:2C:FC:D9:3C:2C:21:AF:9A:90:42:9A:41:25:FB:35:D7:6A:68:AC:A1:42:2E:99
```
