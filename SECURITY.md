# 🛡️ Security & Offline Privacy Policy

## 1. 100% Offline, Zero-Internet Architecture

**Focus Elite (`com.focuselite.app`)** is engineered from the ground up with a strict **Zero-Network Privacy Guarantee**:

- ❌ **`android.permission.INTERNET` is NEVER requested** in `app/src/main/AndroidManifest.xml`.
- ❌ **`android.permission.ACCESS_NETWORK_STATE` is NEVER requested**.
- ❌ **`android.permission.ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` are NEVER requested**.

Because Android enforces network access at the Linux kernel level via UID socket groups (`AID_INET`), an app without `android.permission.INTERNET` **cannot open TCP/UDP sockets or transmit a single byte of data off your device**. All screen time metrics, app classifications, focus schedules, diary entries, and fitness logs remain 100% inside local on-device storage (`Room` SQLite database and encrypted/private `SharedPreferences`).

### Verify It Yourself in 5 Seconds
You can audit the manifest directly in the repository or inspect any compiled APK using `aapt2`:
```bash
grep -i "INTERNET" app/src/main/AndroidManifest.xml
# Output: (empty — zero internet permissions exist)
```

---

## 2. Why Google Play Protect Flags Sideloaded Accessibility & Device Admin Apps

When installing **Focus Elite** directly from [GitHub Releases](https://github.com/thinkertomar/Focus-Elite/releases) (sideloading outside the Google Play Store), **Google Play Protect** may display a precautionary prompt such as:
> *"App blocked to protect your device — This app can request access to sensitive data..."*

### Why does this happen?
1. **High-Privilege Focus Enforcement APIs:** To detect when a distracting app opens during Focus Hours and prevent impulsive uninstallation mid-session, Focus Elite declares:
   - `android.permission.BIND_ACCESSIBILITY_SERVICE` (Focus Blocker Shield)
   - `android.permission.BIND_DEVICE_ADMIN` (Anti-Tamper Uninstall Lock)
   - `android.permission.SYSTEM_ALERT_WINDOW` (Focus Shield Overlay)
   - `android.permission.PACKAGE_USAGE_STATS` (On-Device Screen Time Analytics)
2. **Sideloaded APK Heuristic:** Android 13+ / 14+ automatically shows a Play Protect warning for **any** APK downloaded from a browser (including GitHub) that declares `BIND_ACCESSIBILITY_SERVICE` or `BIND_DEVICE_ADMIN`.

### Why is Focus Elite provably safe?
Even though Focus Elite uses `BIND_ACCESSIBILITY_SERVICE` to detect foreground app package names and browser address bars locally, **it has no `INTERNET` permission**. It is physically impossible for the app to transmit anything it sees to any server.
- To proceed during installation from GitHub Releases, tap **"More details" → "Install anyway"**.
- On Android 13+, if Android greys out the Accessibility Service toggle for a sideloaded APK ("Restricted Setting"), open **Android Settings → Apps → Focus Elite → Top-right `⋮` menu → "Allow restricted settings"**, then enable the Accessibility Shield.

---

## 3. Reporting a Bug or Security/Tamper Bypass

If you discover a way to bypass the Focus Layer during active Focus Hours, or encounter an issue:
1. Open **Focus Elite → Hub & Guide (`⚙️`) → `🛠️ Updates & Health`**.
2. Scroll to **Step 3: Privacy-Driven Feedback & Bug Reporter**, select **🐞 Bug / Error Report**, and tap **`📋 Copy Professional Report`** (which attaches a local 7-point diagnostic snapshot without any personal data).
3. Open a new issue at [GitHub Issues](https://github.com/thinkertomar/Focus-Elite/issues/new).
