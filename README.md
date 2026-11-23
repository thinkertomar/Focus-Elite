# 🛡️ Focus Elite

> **100% Offline Personal Discipline OS — Fortress Distraction Shield, Discipline Calendar, Daily Journal, Task Manager, Smart Sleep, Physical Telemetry & JEE Practice Suite for Android**

[![Platform](https://img.shields.io/badge/Platform-Android%208.0%2B%20(API%2026%2B)-3DDC84?logo=android&logoColor=white)](https://github.com/thinkertomar/Focus-Elite)
[![Package](https://img.shields.io/badge/Package-com.focuselite.app-6366F1)](https://github.com/thinkertomar/Focus-Elite)
[![Language](https://img.shields.io/badge/Language-Kotlin-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Privacy](https://img.shields.io/badge/Privacy-100%25%20Offline%20%E2%80%A2%20Zero%20Internet%20Permission-10B981)](./SECURITY.md)
[![Build Status](https://github.com/thinkertomar/Focus-Elite/actions/workflows/build-apk.yml/badge.svg)](https://github.com/thinkertomar/Focus-Elite/actions)
[![Version](https://img.shields.io/badge/Release-v1.5.3%20(Build%209)-00B8D4)](https://github.com/thinkertomar/Focus-Elite/releases)
[![License: MIT](https://img.shields.io/badge/License-MIT-F59E0B.svg)](./LICENSE)

---

## 🚀 What's New in Milestone v1.5.3 (Build 9)

Focus Elite **v1.5.3** evolves Focus Elite into a complete, integrated **Personal Discipline Management System** while preserving every existing Focus Shield, Holiday, Sleep, and Practice workflow:

- 📅 **Redesigned Discipline Calendar (`Month` · `Week` · `Agenda`):** Switch seamlessly between Month grid, 7-day Week strip, and chronological Agenda with distinct 5-signal indicators (**Steps Goal, Workouts, Holidays, Diary, Tasks**), a compact monthly discipline summary, selected-day overview with inline task toggles, and 1-tap quick actions while strictly preserving the **24-hour advance Focus Holiday rule**.
- ✍️ **Distraction-Free Daily Journal & Searchable Archive:** Clean writing editor with automatic local draft recovery, explicit saved/draft status, optional mood & 1–5 energy ratings, custom tags, collapsible reflection prompts, word count, and searchable chronological history—running alongside the preserved **Daily Time Notebook**.
- ✅ **Integrated Task Manager (`Day` · `Overdue` · `Upcoming` · `Done`):** Full task CRUD tied directly to calendar dates with **Low / Normal / High** priorities, optional due times & exact reminders (with 1-tap "Mark Done" notification actions), **Daily / Weekdays / Weekly** recurrence, overdue indicators, 1-tap rescheduling, and daily completion progress bars.
- 📈 **Transparent Discipline Insights (`7d` · `30d` · `90d`):** Compact real-data charts for step trends, workout frequency, task completion, journal consistency, focus sessions, and productive vs. distracted screen time—never confusing missing records with failed goals.
- 🌙 **Smart Multi-Segment Sleep Engine & Deep Physical Activity Telemetry:** Automatically detects overnight sleep even with brief night awakenings or phone checks, scores sleep efficiency, and adds **Move Minutes** hourly charts plus rich walk/workout session breakdowns (distance, pace, active time, calories).
- 🔔 **Smart Context-Aware Notifications & Pay Safe Mode:** Dynamic day-long coaching notifications (morning planning, midday focus & fitness nudges, evening progress, night summary) plus a **1-tap Pay Safe Mode** Quick Settings tile & notification control for UPI/banking apps.
- ⚡ **Instant Usage Stats Sync & Uninstalled App Retention:** Accelerated usage-stats caching and persistent daily usage records, labels, and badges even when a distracting app is uninstalled mid-day.
- 🩹 **Android 11+ Startup Stability Fix:** Corrected system bar theming lifecycle across Calendar, Journal, and Tasks and hardened local database reads for crash-free launches across Android 8–15.

---

## 📖 Overview

**Focus Elite (`com.focuselite.app`)** is an open-source, high-discipline, privacy-first Android application engineered to eliminate digital distractions, structure your daily calendar, journal, and tasks, and connect screen discipline with real physical effort and sleep recovery.

Unlike cloud-based screen time trackers that harvest telemetry, **Focus Elite explicitly strips `android.permission.INTERNET` and `ACCESS_NETWORK_STATE` at the manifest level**. The Android OS kernel prevents the app from opening network sockets—meaning **zero bytes of your usage data, journal entries, tasks, sleep records, or habits can ever leave your device**.

---

## ✨ Core Features & Architecture

### 1. 📅 Integrated Discipline Calendar, Daily Journal & Task Manager
- **Unified Discipline Calendar:** Month, Week, and Agenda views with a readable 5-signal legend (`Steps`, `Workout`, `Holiday`, `Diary`, `Tasks`), Today shortcut, and selected-day overview with direct quick actions (`+ Task`, `Write Diary`, `Holiday`).
- **Daily Journal & Chronological Archive:** Distraction-free editor with auto-saved local drafts, reflection prompts, mood/energy tracking, tags, and instant keyword/mood/tag search across past entries.
- **Calendar-Linked Tasks:** Priorities, due times, scheduled local reminders, recurring task generation, overdue alerts, and bulk "Move All to Today" rescheduling.
- **Discipline Insights:** Honest 7-day, 30-day, and 90-day consistency analytics powered exclusively by real on-device logs.

### 2. 🔒 Fortress Focus Layer, Pay Safe Mode & Physical Proof-of-Effort Gate
- **No-Escape Focus Schedule:** Protects your productive window with an active Accessibility Shield, Device Admin anti-uninstall lock, and monotonic clock-tamper detection.
- **Physical Proof-of-Effort Unlock:** Emergency 15-minute breaks or rule edits during active focus hours require verified hardware movement (**25 steps** or **10 squats** via on-device sensors) plus a cognitive commitment typing challenge.
- **24-Hour Advance Calendar Holiday Rule:** Rest-day holidays must be scheduled at least **24 hours in advance**, preventing impulsive same-day Focus Layer bypasses.
- **Pay Safe Mode (Quick Tile & Notification Control):** Temporarily pause accessibility interference for UPI/payment apps cleanly with quick restoration back to shield mode.

### 3. 🌙 Smart Sleep Detection & Deep Physical Activity Studio
- **Multi-Segment Overnight Sleep Tracking:** Reconstructs true sleep duration across brief middle-of-the-night awakenings and short phone checks, logging structured sleep summaries directly into the **Daily Time Notebook**.
- **Physical Activity, Move Minutes & Calisthenics:** Hardware step tracking, Health Connect support, Move Minutes hourly bar charts, session pace/calorie/distance breakdowns, and structured calisthenics logs.

### 4. 📊 Instant Usage Stats, Browser Reconciliation & Uninstalled App Retention
- **Fast Usage Stats Engine:** Warm-cached usage aggregation for near-instant screen-time loading and live 1-second 30-minute daily cap enforcement.
- **Uninstalled App Retention:** Preserves daily screen time, app names, and category badges for apps used and later uninstalled during the day.
- **Reconciled Browser Usage Access:** Merges per-browser telemetry with **24/7 Study Website exemptions**, keeping learning portals open while blocking browser distractions.
- **Automatic System App Exclusion:** Core Android utilities (Dialer, SMS, Camera, Clock, Calculator, Settings, Authenticators, UPI/Payment apps) are automatically excluded from blocking.

### 5. 🔔 Smart Context-Aware Notifications & Guided 4-Step Setup
- **Adaptive Daily Smart Notification:** Evolves automatically from morning task/goal setup → midday focus & movement check-ins → evening progress digest → night full-day discipline summary.
- **Zero Self-Lockout 4-Step Setup:** Guides users through Usage Access → App Criteria → Focus Schedule → Final Enforcement Shields (with a 3-minute Setup Grace Window).

### 6. 📚 Unlocked JEE Practice Suite, Themes & 7-Point Offline Health Scanner
- **Unlocked JEE Practice Suite:** Offline access to **2,427 high-contrast Physics, Chemistry, and Mathematics question diagrams** with LaTeX math rendering.
- **Ocean Cyan, AMOLED Black & Daylight White:** Consistent design system supporting Ocean Cyan default accent, True AMOLED Black (`#000000`), Daylight White, and custom accent palettes.
- **7-Point Offline Health Scanner & Backup/Restore:** Built-in local diagnostics, auto-repair, and encrypted/local backup & restore workflows.

---

## 🔒 100% Offline & Zero-Internet Privacy Guarantee

| Permission | Status in Focus Elite | Purpose (100% Local On-Device) |
| :--- | :--- | :--- |
| `android.permission.INTERNET` | ❌ **EXPLICITLY REMOVED** | Stripped via `tools:node="remove"`—no data can ever leave your device. |
| `ACCESS_NETWORK_STATE` | ❌ **EXPLICITLY REMOVED** | Zero network telemetry or connectivity probing. |
| `PACKAGE_USAGE_STATS` | ✅ Local Only | Calculates daily screen time, uninstalled app retention, and 30-minute caps on-device. |
| `BIND_ACCESSIBILITY_SERVICE` | ✅ Local Only | Detects blocked apps or distracting browser URLs to show the local Focus Shield. |
| `BIND_DEVICE_ADMIN` | ✅ Local Only | Prevents impulse uninstallation during active focus sessions. |
| `ACTIVITY_RECOGNITION` & Health Connect | ✅ Local Only | Counts hardware steps, Move Minutes, squats, and workout distance on-device. |
| `POST_NOTIFICATIONS` | ✅ Local Only | Powers Smart Daily Notifications, task reminders, and Pay Safe Mode quick actions. |

---

## 📲 Installation, Play Protect & In-Place Updates

### Installing from GitHub Actions or Releases
1. Download the latest **`focus-elite-v1.5.3-debug`** APK from [GitHub Actions](https://github.com/thinkertomar/Focus-Elite/actions) or [GitHub Releases](https://github.com/thinkertomar/Focus-Elite/releases).
2. **Note on Google Play Protect (Sideloaded APKs):** Because Focus Elite is installed outside Google Play and uses `AccessibilityService` + `DeviceAdmin` to block distracting apps, Play Protect may show a standard prompt (*"App blocked to protect your device"*). Tap **"More details" → "Install anyway"**. (See [SECURITY.md](./SECURITY.md) for how Focus Elite is provably offline.)
3. **Android 13+ Restricted Settings:** Before enabling the Accessibility Shield in Step 4, if Android displays *"Restricted setting"*, open **Android Settings → Apps → Focus Elite → Top-right `⋮` menu → "Allow restricted settings"**.

### Updating Without Losing Data
Installing any newer release APK from GitHub updates the app **in-place** while preserving all your calendar data, journal entries, tasks, holidays, sleep records, schedules, categories, and usage history.

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
