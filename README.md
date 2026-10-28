# 🛡️ Focus Elite

> **100% Offline Digital Wellbeing, Fortress Distraction Shield, Time Analytics & Physical Proof-of-Effort Engine for Android**

[![Platform](https://img.shields.io/badge/Platform-Android%208.0%2B%20(API%2026%2B)-3DDC84?logo=android&logoColor=white)](https://github.com/thinkertomar/Focus-Elite)
[![Package](https://img.shields.io/badge/Package-com.focuselite.app-6366F1)](https://github.com/thinkertomar/Focus-Elite)
[![Language](https://img.shields.io/badge/Language-Kotlin-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Privacy](https://img.shields.io/badge/Privacy-100%25%20Offline%20%E2%80%A2%20Zero%20Internet%20Permission-10B981)](./SECURITY.md)
[![Version](https://img.shields.io/badge/Release-v1.3%20(Build%204)-38BDF8)](https://github.com/thinkertomar/Focus-Elite/releases)
[![License: MIT](https://img.shields.io/badge/License-MIT-F59E0B.svg)](./LICENSE)

---

## 📖 Overview

**Focus Elite (`com.focuselite.app`)** is an open-source, high-discipline, privacy-first Android application engineered to eliminate digital distractions, reclaim deep work hours, and connect screen discipline with physical movement.

Unlike cloud-based screen time trackers that harvest telemetry, **Focus Elite does not request `android.permission.INTERNET`**. The Android OS kernel strictly prevents the app from opening network sockets—meaning **zero bytes of your usage data, habits, or personal logs can ever leave your device**.

---

## ✨ Core Features & Architecture

### 1. 🚀 Guided 4-Step First-Time Setup (Zero Self-Lockout Order)
To ensure new users can configure their rules smoothly without triggering anti-tamper protections while inside Android Settings, Focus Elite enforces a logical **4-Step Setup Flow**:
1. **Step 1 — Grant Usage Access (`PACKAGE_USAGE_STATS`):** Enables accurate on-device screen time and 30-minute app cap tracking.
2. **Step 2 — Define App Criteria (`Study` • `Self-Improvement` • `Ignored`):** Whitelist productive apps and classify distractions before turning on enforcement.
3. **Step 3 — Set Focus Schedule Window:** Customize active focus hours (default `9:00 AM – 6:00 PM`) and active days of the week.
4. **Step 4 — Enable Final Enforcement Shields Last:** Activate **Unrestricted Battery**, **Device Admin**, and the **Accessibility Blocker Shield** last, protected by an automatic **3-minute Setup Grace Window** so Android Settings never blocks you during initial configuration.

### 2. ⚙️ Automatic System Apps Exclusion (24/7 Safe Device Utilities)
All core Android system apps and essential device utilities—including **Phone/Dialer, SMS/Messages, Camera, Clock/Alarm, Calculator, Contacts, Calendar, Files, Settings, Permission Controller, Authenticators, and UPI/Payment apps**—are automatically excluded from the blocklist and allowed 24/7 so you never experience issues with any system-provided feature. Pre-installed distraction apps (such as YouTube, Chrome, or social apps) remain fully categorizable and blockable.

### 3. 🔒 Fortress Focus Layer & Physical Proof-of-Effort Gate
- **No-Escape Focus Schedule:** Protects your productive window with an active Accessibility Shield, Device Admin anti-uninstall lock, and monotonic clock-tamper detection.
- **Physical Proof-of-Effort Unlock:** Emergency 15-minute breaks or rule edits during active focus hours require verified hardware movement (**25 steps** or **10 squats** via on-device sensors) plus a cognitive commitment typing challenge.
- **24-Hour Advance Calendar Holiday Rule:** Calendar holidays must be scheduled at least **24 hours in advance**, preventing impulsive same-day holiday bypasses.

### 4. 📊 Time Analysis Bar Graphs, Daily Time Notebook & Calisthenics
- **Smart 30-Minute Daily Caps & Blue Tick (`✓`) Indicator:** Flags non-study apps exceeding 30 minutes of daily usage and marks enforced apps with a sky-blue tick (`✓`).
- **24-Hour Time Splitter & Analytics:** Log offline hours in the **Daily Time Notebook** and inspect 7-day stacked bar charts and 24-hour distribution breakdowns.
- **Physical Activity & Calisthenics Studio:** Track daily steps, kilometres, active minutes, calories burned, and structured calisthenics progressions using local hardware sensors.

### 5. 🛠️ Built-In Update Showcase, 7-Point Offline Health Scanner & Feedback Studio (`v1.3`)
Located inside **Hub & Guide → `🛠️ Updates & Health`**:
- **🎉 What's New Presentation & GitHub Release Launcher:** Automatically notifies you when a new version is installed, presents a full visual breakdown of new features, and provides a 1-tap browser launcher to download & install updates in-place from [GitHub Releases](https://github.com/thinkertomar/Focus-Elite/releases) without losing data.
- **🩺 7-Point Offline Error Detector & Auto-Repair:** Scans Privacy Seal, SQLite Room Database, Usage Access, App Criteria, Device Admin, Accessibility Shield, and Battery/Clock Integrity—with a **1-Tap Auto-Repair** button and local crash/anomaly log.
- **💬 Privacy-Driven Feedback & Upgrade Studio:** Write feature requests, self-opinions, or bug reports with an optional 7-point diagnostic snapshot and copy a clean Markdown report directly to your clipboard or open GitHub Issues.

---

## 🔒 100% Offline & Zero-Internet Privacy Guarantee

| Permission | Status in Focus Elite | Purpose (100% Local On-Device) |
| :--- | :--- | :--- |
| `android.permission.INTERNET` | ❌ **NEVER REQUESTED** | Guaranteed zero network access; no data can ever leave your phone. |
| `ACCESS_FINE_LOCATION` / `COARSE_LOCATION` | ❌ **NEVER REQUESTED** | Zero GPS or location tracking. |
| `PACKAGE_USAGE_STATS` | ✅ Local Only | Calculates daily screen time and 30-minute app limits on-device. |
| `BIND_ACCESSIBILITY_SERVICE` | ✅ Local Only | Detects when a blocked app or distracting browser URL opens to display the local Focus Shield. |
| `BIND_DEVICE_ADMIN` | ✅ Local Only | Prevents impulse uninstallation during active focus sessions. |
| `ACTIVITY_RECOGNITION` | ✅ Local Only | Counts hardware steps and squats for fitness stats and Proof-of-Effort unlocks. |

---

## 📲 Installation, Play Protect & In-Place Updates

### Installing from GitHub Releases
1. Download **`Focus-Elite-latest.apk`** from the [Latest Release](https://github.com/thinkertomar/Focus-Elite/releases).
2. **Note on Google Play Protect (Sideloaded APKs):** Because Focus Elite is installed outside the Google Play Store and uses `AccessibilityService` + `DeviceAdmin` to block distracting apps, Android's Play Protect may display a standard precautionary prompt (*"App blocked to protect your device"*). Tap **"More details" → "Install anyway"**. (See [SECURITY.md](./SECURITY.md) for why Focus Elite is provably safe with zero internet permissions.)
3. **Android 13+ Restricted Settings:** Before enabling the Accessibility Shield in Step 4, if Android shows *"Restricted setting"*, open **Android Settings → Apps → Focus Elite → Top-right `⋮` menu → "Allow restricted settings"**.

### Updating Without Losing Data
Once you are on `com.focuselite.app`, installing any newer release APK from [GitHub Releases](https://github.com/thinkertomar/Focus-Elite/releases) updates the app **in-place** while preserving all your schedules, categories, and history.

---

## 🛠️ Building from Source & Contributing

```bash
git clone https://github.com/thinkertomar/Focus-Elite.git
cd Focus-Elite
./gradlew assembleDebug
```

We welcome code reviews, architectural guidance, and Pull Requests! Please read:
- [**CONTRIBUTING.md**](./CONTRIBUTING.md) — Architecture overview, coding standards, and PR workflow
- [**SECURITY.md**](./SECURITY.md) — Zero-Internet verification and permission model
- [**LICENSE**](./LICENSE) — MIT License
