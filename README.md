# A1111 Remote — Android

Native Android client (Kotlin + Jetpack Compose) for controlling a **self-hosted AUTOMATIC1111 Stable Diffusion WebUI** on a Windows PC.

> **Status:** v0.3.0 prototype. Generated APK compiles in CI; actual PC integration must still be tested on an Android device.

## Included in v0.1

- LAN and Tailscale endpoint configuration with connection check and fallback
- Optional HTTP Basic API authentication (password encrypted using Android Keystore)
- `txt2img`: positive/negative prompts, steps, CFG, width, height, batch, seed, sampler, scheduler, checkpoint
- Search all LoRAs from the PC using `/sdapi/v1/loras`; tap to add a LoRA tag to the prompt, tap again to remove it
- txt2img, img2img (with image selection and denoising), and Extras (upscaling and restoration) workflows
- Hires. Fix scale and denoising
- ADetailer: independently toggleable Face and Hand tabs, plus optional additional tabs; detector, prompts, confidence, denoising, mask blur, padding, mask erosion/dilation
- Progress bar, intermittent live preview, interrupt/skip
- Locally stored results, thumbnails, image export to Android Pictures
- Last-used generation settings saved locally
- Optional PC bridge: restore PC generation parameters by mode from `params.txt` or mode-specific output metadata; browse the 80 most recent images from PC output directories and download originals into the Android photo gallery

## Install / build

Requires Android Studio with JDK 17, Android SDK Platform 36, and a device with Android 10 (API 29) or newer.

1. Clone this repository and open the root directory in Android Studio.
2. Wait for Gradle sync and install the `app` configuration on your device.
3. Alternatively, use the **Android CI** GitHub Actions workflow: after a successful workflow, download the `A1111Remote-debug-apk` artifact from the run. Extract the ZIP and install `app-debug.apk`.
4. No Android app-side API key or public server is necessary. Your PC and VPN must be configured first.

## Configure AUTOMATIC1111 on Windows

Edit `webui-user.bat` and add flags to **the existing** `COMMANDLINE_ARGS` value (keep any flags you already use):

```bat
set COMMANDLINE_ARGS=--api --listen --api-auth YOUR_USERNAME:YOUR_STRONG_PASSWORD
```

- Start AUTOMATIC1111 normally and confirm the WebUI loads.
- Run `ipconfig` and note your computer's LAN IPv4 address, e.g. `192.168.1.100`.
- In the Android app, use `http://192.168.1.100:7860` under **WLAN-Adresse**. Substitute your actual IP/port.
- Add the API username/password chosen above. API auth protects the API endpoint, but HTTP itself is not encrypted: use only a trusted local network or private VPN.
- The Windows firewall must permit access on your trusted private network. Never forward TCP port 7860 from your router to the internet.

**Note:** `--listen` exposes the WebUI service to your LAN. Other WebUI interfaces might not require API authentication even if `--api-auth` is configured. Do not treat it as a secure public-facing server. Prefer restricting the firewall to your private network and Tailscale peers.

### Remote use with Tailscale

1. Install [Tailscale](https://tailscale.com/) on both the Windows PC and your Android phone, and sign them into the same tailnet.
2. Copy the PC's Tailscale IPv4 address (typically `100.x.y.z`) and enter `http://100.x.y.z:7860` in the Android app.
3. On the PC, allow the service on the Tailscale interface in Windows Firewall if necessary.
4. Connect in the app: the LAN endpoint is tried first, then the Tailscale endpoint. This also works over mobile data as long as Tailscale is connected.

**Do not open router ports** or use public A1111 share links for this app. The Android client accepts unencrypted HTTP only with an explicitly configured private IPv4 endpoint; HTTPS endpoints are also supported.

## PC bridge extension (required for PC gallery + last PC parameters)

AUTOMATIC1111's normal `--api` endpoints can list LoRAs, but cannot read arbitrary
existing output files or the WebUI's `params.txt`. This repository therefore
contains `scripts/a1111_remote_bridge.py`: a **read-only, authenticated**
AUTOMATIC1111 extension which uses the existing WebUI port, not a second server.

1. Because WebUI is started with `--listen`, extension installs in the WebUI
   are disabled for security. Install locally instead: open a command prompt in
   your `stable-diffusion-webui` folder and run:

   ```bat
   git clone https://github.com/theTscheZ/SD-Remote-Generation extensions/sd-remote-bridge
   ```

   Alternatively, copy the file `scripts/a1111_remote_bridge.py` from this repo
   to `stable-diffusion-webui/extensions/sd-remote-bridge/scripts/`.
   Do **not** enable `--enable-insecure-extension-access`.
2. Add **both** `--api` and `--api-auth USER:STRONG_PASSWORD` to your existing
   `webui-user.bat` launch flags; keep your existing flags and port.
3. Restart AUTOMATIC1111. The log should say
   `[A1111 Remote] PC history bridge active`.
4. In Android, tap **Verbindung testen** again. Then **PC** inside Galerie shows
   PC images. Tap an image and **Original herunterladen** to save a copy to the
   phone in `Pictures/A1111 Remote`. The PC originals stay untouched.
5. In Generate, **Letzte PC-Parameter übernehmen** reads the WebUI's most
   recently recorded generation settings from `params.txt` (falls back to PNG
   metadata from an existing output if the file isn't present).

The PC bridge intentionally refuses access when `--api-auth` is missing,
and it only reads the configured A1111 output directories. It does not
expose a generic file browser, delete images, or open any new network port.
As Basic authentication over HTTP is unencrypted, only use it on your
trusted local network or over Tailscale. Never forward your WebUI port to
the public internet.

**APK signing:** GitHub Actions currently produces ephemeral debug-signed
APKs. A stable private release signing keystore must be configured separately
before Android can reliably install future builds as in-place updates. Never
commit a private keystore or its password to this public repository.

## Stable Android signing and in-place updates

The project's private PKCS12 keystore and credentials are backed up in the owner's private ChatGPT Library folder \`/A1111-Remote\` (NOT in this public repository). Since prior debug APKs were signed with short-lived runner keys, moving to the stable key requires a **one-time uninstall**. Install the stable-key-signed APK v0.3.0 afterward. Following releases must use the same certificate and a higher versionCode to install over it.

To make GitHub Actions produce updateable signed release APKs, the owner must configure 2 repository secrets in **Settings > Secrets and variables > Actions**:

- \`A1111_SIGNING_P12_BASE64\`: base64 of the private \`a1111-remote-update-key.p12\`. PowerShell: \`[Convert]::ToBase64String([IO.File]::ReadAllBytes("C:\path\a1111-remote-update-key.p12"))\`
- \`A1111_SIGNING_PASSWORD\`: password from the private \`signing-credentials.txt\`.

After that, download the \`A1111Remote-STABLE-signed-release\` GitHub Actions artifact. Without configured secrets CI outputs only a clearly marked, **non-updateable** temporary debug APK. Never commit a private signing keystore or password to this public repository.

## PC parameter recovery by mode

In txt2img, the bridge tries A1111's saved \`params.txt\` and falls back to txt2img output metadata. In img2img, it uses the latest saved img2img image metadata. For Extras, only upscaler and scale can be restored when these were included in saved Extras output metadata; other Extras fields remain at the existing app setting. You must update the installed bridge script and restart the WebUI after the v0.3.0 update.

## Technical architecture

- Jetpack Compose + Material 3 UI
- Single Android ViewModel for state, API service based on OkHttp and `org.json`
- HTTP endpoints: `/sdapi/v1/options`, `/sdapi/v1/sd-models`, `/sdapi/v1/samplers`, optional `/sdapi/v1/schedulers`, `/sdapi/v1/loras`, `/sdapi/v1/script-info`; generation `/sdapi/v1/txt2img`, status `/sdapi/v1/progress`, actions `/sdapi/v1/skip` and `/sdapi/v1/interrupt`
- ADetailer payload: `alwayson_scripts.ADetailer.args = [true, false, {...}]`
- Credentials encrypted via Android Keystore (AES-GCM); backup disabled. Images stored in app-private storage unless explicitly exported.

## Next steps

1. Test LAN and Tailscale against the actual AUTOMATIC1111 server/extension versions.
2. Improve PC gallery pagination, image metadata views and presets.
3. Add img2img/inpainting, multiple ADetailer units, finer-grained extension settings and robust long-job handling.

Sources: [AUTOMATIC1111 API](https://github.com/AUTOMATIC1111/stable-diffusion-webui/wiki/API), [ADetailer REST API](https://github.com/Bing-su/adetailer/wiki/REST-API).
