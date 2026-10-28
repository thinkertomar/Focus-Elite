# 🤝 Contributing to Focus Elite

Thank you for your interest in reviewing, improving, and contributing to **Focus Elite**! We welcome code reviews, UI/UX enhancements, bug fixes, and architectural suggestions from the Android developer community.

---

## 🧭 Core Architectural Principles

Before submitting a Pull Request, please ensure your changes respect Focus Elite's three non-negotiable engineering pillars:

1. **100% Offline & Zero Internet Permission (`No android.permission.INTERNET`):**
   - Never add `android.permission.INTERNET` or any third-party analytics/telemetry SDKs.
   - Any external action (such as checking GitHub Releases or filing an issue) must be delegated via explicit user-triggered `ACTION_VIEW` / `ACTION_SEND` intents to the user's browser or clipboard.
2. **Zero Self-Lockout During Setup (Guided 4-Step Flow):**
   - Permissions and setup must follow the safe order:
     1. Usage Access (`PACKAGE_USAGE_STATS`)
     2. App Categorization (`Study`, `Self-Improvement`, `Ignored`) — with core Android system utilities automatically excluded
     3. Focus Schedule configuration
     4. Final Enforcement Shields (`Device Admin` & `Accessibility Service`) protected by a 3-minute Setup Grace Window (`SettingsTamperGuard`).
3. **No Impulsive Escape Loopholes:**
   - Rule weakening during active Focus Hours must go through `CognitiveFrictionGate` (Physical Proof-of-Effort via hardware step/squat sensors + cognitive commitment typing).
   - Calendar holidays must require at least 24 hours advance notice.

---

## 🛠️ Local Development Setup

### Prerequisites
- **JDK 17 or JDK 21**
- **Android SDK (API 35)**
- **Android Studio Ladybug (or newer)** or command-line Gradle

### Clone & Build
```bash
git clone https://github.com/thinkertomar/Focus-Elite.git
cd Focus-Elite
./gradlew assembleDebug
```
The compiled APK will be generated at:
`app/build/outputs/apk/debug/app-debug.apk`

---

## 📂 Key Modules in `app/src/main/java/com/focusguardpro/app/`

| File / Class | Responsibility |
| :--- | :--- |
| `FocusEliteApp.kt` (`FocusGuardApp.kt`) | Application entry point, global uncaught crash logger, and Room database initialization. |
| `MainActivity.kt` | Main 4-step guided onboarding checklist, dashboard cards, and navigation hub. |
| `AppCategoryPrefs.kt` | Categorizes apps (`Study`, `Improve`, `Ignore`) and automatically excludes essential Android system utilities. |
| `FocusLayerManager.kt` | Evaluates Focus Schedule windows, 30-minute daily caps, and holiday exemptions (24h advance rule). |
| `SettingsTamperGuard.kt` | Protects Android Settings during active Focus Hours while respecting the 3-minute First-Time Setup Grace Window and monotonic clock-tamper detection. |
| `CognitiveFrictionGate.kt` | Enforces physical proof-of-effort (25 steps / 10 squats) + commitment typing for emergency breaks. |
| `SystemDiagnosticsAndUpdatesManager.kt` | Version changelog registry, 7-point offline health scanner, 1-tap auto-repair, and Markdown feedback generator. |
| `UpdatesHealthAndFeedbackSectionBuilder.kt` | UI builder for the in-app **Updates, System Health & Feedback Studio** inside Help & Settings. |

---

## 🔄 Submitting Changes (Pull Requests)

1. Fork the repository and create a feature branch:
   ```bash
   git checkout -b feat/your-improvement
   ```
2. Verify that `./gradlew assembleDebug` succeeds with zero errors and that `app/src/main/AndroidManifest.xml` does not contain `INTERNET`.
3. Open a Pull Request describing:
   - What problem or UX friction your change solves
   - How you tested it on Android
