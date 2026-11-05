# A1111 Remote — Android

Native Android client (Kotlin + Jetpack Compose) for controlling a **self-hosted AUTOMATIC1111 Stable Diffusion WebUI** on a Windows PC.

> **Status:** v0.1.0 early prototype. App and API payload are implemented; compatibility with your particular AUTOMATIC1111/ADetailer versions still needs an on-device test. Older images from the PC output folders are not synced yet. The app has not been tested against a running PC here.

## Included in v0.1

- LAN and Tailscale endpoint configuration with connection check and fallback
- Optional HTTP Basic API authentication (password encrypted using Android Keystore)
- `txt2img`: positive/negative prompts, steps, CFG, width, height, batch, seed, sampler, scheduler, checkpoint
- LoRA list and add-to-prompt action
- Hires. Fix scale and denoising
- ADetailer: enabled, detector, prompts, confidence, denoising, mask blur, padding, dilation, masked inpainting; detect installed extension; list detector choices when exposed by `/sdapi/v1/script-info`
- Progress bar, intermittent live preview, interrupt/skip
- Locally stored results, thumbnails, image export to Android Pictures
- Last-used generation settings saved locally

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

## Technical architecture

- Jetpack Compose + Material 3 UI
- Single Android ViewModel for state, API service based on OkHttp and `org.json`
- HTTP endpoints: `/sdapi/v1/options`, `/sdapi/v1/sd-models`, `/sdapi/v1/samplers`, optional `/sdapi/v1/schedulers`, `/sdapi/v1/loras`, `/sdapi/v1/script-info`; generation `/sdapi/v1/txt2img`, status `/sdapi/v1/progress`, actions `/sdapi/v1/skip` and `/sdapi/v1/interrupt`
- ADetailer payload: `alwayson_scripts.ADetailer.args = [true, false, {...}]`
- Credentials encrypted via Android Keystore (AES-GCM); backup disabled. Images stored in app-private storage unless explicitly exported.

## Next steps

1. Test LAN and Tailscale against the actual AUTOMATIC1111 server/extension versions.
2. Add remote image history (small PC companion/gallery service), metadata parsing and presets.
3. Add img2img/inpainting, multiple ADetailer units, finer-grained extension settings and robust long-job handling.

Sources: [AUTOMATIC1111 API](https://github.com/AUTOMATIC1111/stable-diffusion-webui/wiki/API), [ADetailer REST API](https://github.com/Bing-su/adetailer/wiki/REST-API).
