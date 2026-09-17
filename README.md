# AliVPN v0.2 — real TUN client

AliVPN is an Android VPN client prototype using the sing-box `libbox` Android library.

## Real path

Android app
→ VpnService
→ TUN file descriptor
→ libbox / sing-box
→ selected VLESS / VMess / Trojan / Shadowsocks outbound
→ Internet

The app verifies the tunnel with an HTTPS request to `https://api.ipify.org`.

## Supported public share formats in the parser

- VLESS
- VMess
- Trojan
- Shadowsocks

Some third-party transport formats can be present in public lists without being supported by the upstream AAR build. Those are skipped instead of being falsely marked as connected.

## Build on GitHub

`.github/workflows/build.yml` builds `app-debug.apk`.

## License

The embedded libbox component is GPLv3. Keep the included GPL notice and the corresponding source/license information when distributing AliVPN.
