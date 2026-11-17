# 🛡️ Focus Elite

> **100% Offline Digital Wellbeing, Fortress Distraction Shield, Time Analytics, JEE Practice Suite & Physical Proof-of-Effort Engine for Android**

[![Platform](https://img.shields.io/badge/Platform-Android%208.0%2B%20(API%2026%2B)-3DDC84?logo=android&logoColor=white)](https://github.com/thinkertomar/Focus-Elite)
[![Package](https://img.shields.io/badge/Package-com.focuselite.app-6366F1)](https://github.com/thinkertomar/Focus-Elite)
[![Language](https://img.shields.io/badge/Language-Kotlin-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Privacy](https://img.shields.io/badge/Privacy-100%25%20Offline%20%E2%80%A2%20Zero%20Internet%20Permission-10B981)](./SECURITY.md)
[![Build Status](https://github.com/thinkertomar/Focus-Elite/actions/workflows/build-apk.yml/badge.svg)](https://github.com/thinkertomar/Focus-Elite/actions)
[![Version](https://img.shields.io/badge/Release-v1.5.1%20(Build%207)-38BDF8)](https://github.com/thinkertomar/Focus-Elite/releases)
[![License: MIT](https://img.shields.io/badge/License-MIT-F59E0B.svg)](./LICENSE)

---

## 🚀 What's New in Milestone v1.5.1 (Build 7)

Focus Elite **v1.5.1** is a landmark update bringing academic mastery tools, exact background usage reconciliation, instant app cap enforcement, and green automated CI builds:

- 📐 **Unlocked JEE Practice Suite (LaTeX & Diagrams):** Fully removed restrictive Exam Locks, restored all **2,427 high-contrast question diagrams**, and integrated an offline/online LaTeX math rendering engine for seamless physics, chemistry, and mathematics preparation.
- 🌐 **Reconciled Browser Usage Access:** Merges Android `UsageStats` per-browser screen time telemetry with verified **24/7 Study Website exemptions**, keeping learning portals open while strictly enforcing browser caps on distractions.
- ⏱️ **1-Second Live App Cap Watchdog:** Distracted apps are locked the exact second they cross their 30-minute daily allowance while open on screen.
- 🔐 **Deterministic Keystore & Automated CI:** Restored PKCS12 integrity and verified automated GitHub Actions workflow producing signed `focus-elite-v1.5.1-debug` APKs for seamless in-place updates.
- 🩺 **In-App Health Scanner & Update Report Card:** Launch a full 7-point diagnostic scan, view the interactive "What's New" showcase, and copy markdown status reports directly from the home screen.
- 🎨 **Ocean Cyan & AMOLED Harmony:** Polished button padding, crisp card surfaces, Ocean Cyan default palette, True AMOLED Black (`#000000`), and Daylight White (`#F8FAFC`).

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

### 4. 📚 Unlocked JEE Practice Suite & Calisthenics Studio
- **2,427 High-Contrast Question Diagrams:** Instant offline access to complete Physics, Chemistry, and Mathematics problem sets with crisp visual schematics.
- **LaTeX Math Rendering:** Clear typographic rendering of equations, matrices, integrals, and scientific notations.
- **Physical Activity & Calisthenics Engine:** Track daily steps, active minutes, and structured calisthenics progressions with hardware sensors.

### 5. 📊 Time Analysis Bar Graphs & Daily Time Notebook
- **Smart 30-Minute Daily Caps & Blue Tick (`✓`) Indicator:** Flags non-study apps exceeding 30 minutes of daily usage and marks enforced apps with a sky-blue tick (`✓`).
- **24-Hour Time Splitter & Analytics:** Log offline hours in the **Daily Time Notebook** and inspect 7-day stacked bar charts and 24-hour distribution breakdowns.

### 6. 🛠️ Built-In Update Showcase, 7-Point Offline Health Scanner & Feedback Studio (`v1.5.1`)
Located inside **Hub & Guide → `🛠️ Updates & Health`** and directly accessible from the **Home Screen Report Card**:
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

### Installing from GitHub Actions or Releases
1. Download **`focus-elite-v1.5.1-debug`** APK from the latest successful [GitHub Actions Run](https://github.com/thinkertomar/Focus-Elite/actions) or [GitHub Releases](https://github.com/thinkertomar/Focus-Elite/releases).
2. **Note on Google Play Protect (Sideloaded APKs):** Because Focus Elite is installed outside the Google Play Store and uses `AccessibilityService` + `DeviceAdmin` to block distracting apps, Android's Play Protect may display a standard precautionary prompt (*"App blocked to protect your device"*). Tap **"More details" → "Install anyway"**. (See [SECURITY.md](./SECURITY.md) for why Focus Elite is provably safe with zero internet permissions.)
3. **Android 13+ Restricted Settings:** Before enabling the Accessibility Shield in Step 4, if Android shows *"Restricted setting"*, open **Android Settings → Apps → Focus Elite → Top-right `⋮` menu → "Allow restricted settings"**.

### Updating Without Losing Data
Installing any newer release APK from GitHub updates the app **in-place** while preserving all your schedules, categories, practice history, and logs.

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
