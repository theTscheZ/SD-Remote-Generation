# A1111 Remote — PC Power Control (Windows)

This optional helper allows the Android app to start/stop an A1111 session and
schedule Windows shutdown. It works **before A1111 launches**, unlike an A1111
extension. Wake-on-LAN is sent separately by the phone.

## Install on the Windows PC

1. Make sure Python is installed. A1111's bundled Python may also work, but a
   normal Python 3 installation is easiest.
2. Get the `pc-control/pc_control_helper.py` file from this repository.
3. Open a terminal in the folder where you saved it, then run:

   ```powershell
   py pc_control_helper.py setup
   ```

   Enter the **full path** to your existing `webui-user.bat`, and the A1111
   port (e.g. `42000` if you use `--port 42000`). Setup generates a unique,
   random controller token and stores it in your Windows user profile under
   `%LOCALAPPDATA%\A1111Remote\controller.json`. **Never upload this file or
   share its token.**
4. Start the controller:

   ```powershell
   py pc_control_helper.py serve
   ```

   Keep that process running. To launch it automatically after signing in to
   Windows, create a shortcut to a `.bat` file containing the command above,
   and place the shortcut inside the Windows startup folder opened with
   `Win+R` → `shell:startup`. Use the full path to the script in the
   command and use the appropriate working directory. This starts only after
   Windows sign-in; it does **not** run when the PC is powered off.

5. Windows Firewall: permit inbound **TCP 42001** on private LAN and/or
   trusted Tailscale peers **only**. Ideally scope the firewall rule to
   your phone's trusted IPs. **Never forward port 42001 on your router**.
   No administrator rights should be necessary for the helper itself.
6. In the Android app, open **PC**. Enter the controller token and the
   computer's wired-Ethernet MAC address. PC addresses are reused from the
   **Verbindung** tab; the helper port is always `42001`. Tap Refresh.
   Start A1111 with the **A1111 starten** button.

**Important:** These commands use HTTP on your private LAN/Tailscale; the
token is transmitted without end-to-end encryption on plain HTTP. Use
only a private/trusted network and apply Windows firewall restrictions.
Use Tailscale for remote connections, **not** internet port forwarding.

## Wake-on-LAN

- Enable Wake-on-LAN in your motherboard UEFI/BIOS and Windows Ethernet adapter.
  Usually the PC must remain connected via **wired Ethernet**.
- In Windows Device Manager → network adapter → Properties, enable
  Wake on Magic Packet, and allow the adapter to wake the machine.
  Some PCs require disabling Windows Fast Startup for reliable WoL shutdown wake.
- Get the **Ethernet physical address** with `ipconfig /all` (do not use the
  Wi-Fi adapter or Tailscale's virtual MAC).
- Connect the phone to the *same home Wi-Fi* as the PC's Ethernet network.
  The Android app sends a magic packet over its Wi-Fi network on UDP port 9.
  Depending on your router and BIOS, powering on from a full shutdown
  may not be supported; sleep wake can work when shutdown wake does not.
- WoL does **not** automatically work outside your home network. Tailscale
  cannot wake the PC by itself while the PC is off. Remote wake needs an
  always-on home device/router capable of relaying the magic packet.

## What the controller does

- `GET /v1/status` reports PC and A1111 state.
- `POST /v1/start` launches only the preconfigured `webui-user.bat`.
- `POST /v1/stop` stops only an A1111 process tree launched by this helper.
  It cannot stop a WebUI that you launched manually, or one started before
  the helper was restarted.
- `POST /v1/shutdown` schedules a Windows shutdown in 30 seconds.
- `POST /v1/shutdown/cancel` attempts to cancel pending shutdown.

All endpoints require the `X-Controller-Token` header and reject
non-private source IPs; there is **no remote shell**, arbitrary path execution,
or remote configuration API. If the helper restarts, an already running A1111
process will not be "managed" until it is restarted by the helper.

## Troubleshooting

If Android says **PC-Steuerung nicht erreichbar**:

- Verify the PC is on and `py pc_control_helper.py serve` is running.
- Confirm the LAN IP and Tailscale IP in the app point to the **PC**, not
  the phone. The A1111 port and controller port are different
  (A1111: 42000 in your setup; controller: 42001).
- Verify Windows Defender Firewall allows TCP 42001 only from your
  trusted devices.
- Reenter the token printed by setup. If you rerun setup, it generates
  a new one and invalidates the old token.
- If Tailscale interferes with LAN connectivity, test LAN IP locally first.
