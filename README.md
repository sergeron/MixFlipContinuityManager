# Mix Flip Continuity Manager for Xiaomi MIX Flip

**Mix Flip Continuity Manager** is an open-source utility designed specifically for **Xiaomi MIX Flip** (running Xiaomi HyperOS). It provides an all-in-one control center to manage **Flip App Continuity** (seamlessly continuing apps when closing the flip) and **Cover Screen Permissions** (allowing apps to launch and run on the outer display) without requiring root permissions.

---

## Key Features

1. **Flip Continuity Manager (Tab 1)**
   - Toggle HyperOS **Global Continuity** (`flip_continuity_enabled`).
   - Enable or disable per-application continuity so chosen apps (video players, music apps, navigation, etc.) continue running on the outer screen immediately after closing the device.
   - Real-time search and filter for both user and system applications.

2. **Outer Screen Launcher & Permissions (Tab 2)**
   - Allow any installed application to launch directly on Xiaomi MIX Flip's outer display.
   - **Enable All**: Batch-enable outer screen support for all installed applications with a single click.
   - **Apply After Reboot**: HyperOS resets the in-memory allowed cover screen list upon phone reboot. This one-tap feature restores all your previously enabled apps in seconds.
   - Optional scaling optimizations for built-in utilities (calculator, contacts).

3. **Built-in ContinuityProvider Database Manager**
   - Direct inspection of the underlying `content://com.android.settings.continuity.ContinuityProvider/packages` table.
   - View exact package names, user IDs, and active state flags (`enable=1` vs `enable=0`).
   - Permanently delete individual stale or unwanted database records.
   - **Clear Disabled Records**: One-click cleanup of all `enable=0` inactive entries while preserving active records safely.

4. **Multi-Language Support & Modern Material 3 UI**
   - Automatically adapts to system language: English (default) and Polish (Polski).
   - Built-in interactive **3-Step Onboarding Guide** (available anytime via the `?` icon in the toolbar).
   - Built with Material Design 3 and dynamic edge-to-edge system insets.

---

## Requirements

- **Device**: Xiaomi MIX Flip (`ruyi`) running Xiaomi HyperOS (Android 14+).
- **Shizuku**: Running and authorized for Mix Flip Continuity Manager.
  - Shizuku provides ADB-level shell permissions (UID 2000) locally on your device without requiring root or an active PC connection.
  - Download and set up Shizuku from [GitHub](https://github.com/RikkaApps/Shizuku) or [Google Play](https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api).

---

## Quick Start Guide

### Step 1: Start Shizuku
1. Open the **Shizuku** app and start the service (via Wireless Debugging or root).
2. Open **Mix Flip Continuity Manager**. When prompted, tap **Grant permissions** to authorize the app.

### Step 2: Configure Outer Screen (Tab 2)
1. Switch to the **Outer Screen** tab.
2. Tap **Enable all** (or toggle your favorite individual apps) so HyperOS allows them to run on the outer cover display.
3. *Note: When you restart your phone, simply open the app and tap **Apply after reboot** to restore your list.*

### Step 3: Enable Continuity (Tab 1)
1. Switch to the **Continuity (Flip)** tab.
2. Ensure **Global Continuity** is switched **ON** at the top.
3. Turn on the switch for each app you want to keep running seamlessly when you fold the phone closed.

---

## How It Works (Technical Details)

- **Cover Screen Support**: HyperOS restricts apps from launching on the secondary outer display via `miui.app.MiuiFreeFormManager.setSmallWindowAppAllowedList`. Mix Flip Continuity Manager invokes this API through Shizuku's shell bridge and records allowed packages into local preferences.
- **App Continuity Persistence**: Xiaomi Settings stores continuity choices in an internal SQLite database exposed via `content://com.android.settings.continuity.ContinuityProvider`. Mix Flip Continuity Manager includes a standalone Java bridge executed via `app_process` (UID 2000) to directly perform queries, inserts, and deletions without requiring root access.

---

## Installation

The easiest way to get Mix Flip Continuity Manager is using the pre-compiled APK:

1. Go to the [**Releases**](releases) section on GitHub.
2. Download the latest `MixFlipContinuityManager-v1.0.0.apk`.
3. Open the downloaded file on your Xiaomi MIX Flip and confirm installation.
4. Follow the [Quick Start Guide](#-quick-start-guide) to get started.

---

## Building from Source

To build the APK locally:

```bash
git clone https://github.com/your-username/MixFlipContinuityManager.git
cd MixFlipContinuityManager
./gradlew assembleRelease
```

The compiled APK will be located in `app/build/outputs/apk/release/`.

---

## 📄 License & Disclaimer

This project is open-source software provided under the [Apache License 2.0](LICENSE).

*Disclaimer: This is an independent open-source tool and is not affiliated with, authorized, maintained, or endorsed by Xiaomi Inc.*
