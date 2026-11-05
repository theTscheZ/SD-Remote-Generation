# Release signing key (important)

The app's **release-signing keystore** must be retained across updates. A future APK can update an existing installation only if it is signed using the same application ID and compatible signing key.

- Never commit a `.jks`, `.keystore`, `keystore.properties`, or the passwords to GitHub (including private repositories).
- Store the original signing keystore in a safe location on your own computer and make an encrypted backup in a separate location.
- Record the key alias and signing configuration in a private password manager. Do not put secrets in README, logs or issue comments.
- The debug APK in GitHub Actions is signed by the CI debug key and **is not** a stable release signature for app updates.
- Before shipping a first signed release, configure a release signing key. Reuse the **same** keystore for future releases, and increment `versionCode`.
- If later using GitHub Actions for release signing, supply the keystore and credentials as GitHub Actions secrets and remove the key file after the job, without printing secrets.

## Current status
Release signing has **not yet been configured**, and no signing keystore was created or stored here. We must explicitly set this up before the first distributed release.

Note: Android's *Android Keystore* API used to encrypt saved A1111 API credentials is a different concept from the **APK release signing keystore file** discussed here.
