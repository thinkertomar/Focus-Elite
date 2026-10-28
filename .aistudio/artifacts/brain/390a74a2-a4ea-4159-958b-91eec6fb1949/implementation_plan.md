# Focus Elite — Zero-Escape Fortress & Strict Commitment Architecture

Transform Focus Elite from a customizable focus assistant into an inescapable, self-sovereign behavioral fortress by closing all system-level, clock-spoofing, calendar-exemption, and category-reassignment loopholes while requiring real **Physical Proof-of-Effort** (hardware-verified steps/squats + anti-paste cognitive typing) for any emergency override.

## User Review & Critical Decisions

> [!IMPORTANT]
> Your confirmed architectural choices from Phase 1 govern this upgrade:

- **Confirmed Decision 1 — Physical Proof-of-Effort Gate**: Unlocking settings, promoting app categories, or pausing the shield during an active Focus Layer window or Deep Focus session requires completing **verified physical movement** (e.g., 25 real hardware-detected steps or 10 accelerometer-verified squats) **plus** typing a randomized anti-paste cognitive commitment phrase. The 1-tap `Auto-Fill` bypass chip is permanently removed.
- **Confirmed Decision 2 — Multi-Vector Anti-Tamper Protection**:
  - **Surgical Android Settings Guard**: Inspects active window hierarchy inside Android Settings and blocks access to Focus Elite App Info (Force Stop / Uninstall), Accessibility Settings, and Device Admin pages while Focus Lock is active.
  - **Date & Time Anti-Spoofing**: Blocks the Android Date & Time settings screen during active focus and pairs wall-clock time with hardware monotonic elapsed time (`SystemClock.elapsedRealtime()`) + `ACTION_TIME_CHANGED` tamper detection so shifting the device clock cannot bypass the 9:00 AM – 6:00 PM schedule.
  - **24-Hour Advance Holiday Rule**: Calendar Holiday markings and keyword exemptions only relax the Focus Layer if they were created **at least 24 hours before** the focus day begins—preventing same-day self-exemption during a moment of weakness.
- **Confirmed Decision 3 — Strict Fortress Lock by Default + Pre-Window Grace Period**: Strict Fortress Lock is enabled by default. Users can freely adjust schedules and categories during a **30-minute Pre-Window Grace Period** (e.g., 8:30 AM – 9:00 AM) or outside active focus hours, after which all rules and categories freeze automatically until the window completes.

---

## 1. Overview & Core Concept

- **What It Does**: Eliminates every backdoor that impulsive habits exploit when willpower drops. Once the Focus Layer window or a Deep Focus session begins, the app locks its own settings, freezes app category promotions, guards Android system settings against force-stopping or permission revocation, neutralizes device clock manipulation, and demands real physical exertion before allowing any emergency override.
- **Target Audience / Persona**: High-agency builders, students, and deep-work practitioners who want their phone to act as an unbreakable external commitment contract rather than a soft reminder.
- **Key Value**: Converts moments of digital temptation into either uninterrupted deep work or immediate physical exercise (steps/squats), ensuring zero unearned escapes.

---

## 2. User Experience & Visual Design

- **Key User Flows**:
  1. **Pre-Window Grace Period Banner (30 Minutes Before Focus Start)**:
     - On the Home Screen and Auxiliary Settings, a live countdown pill shows when the **Pre-Window Grace Period** is open (e.g., *"⚡ Grace Period Open: 18m left to adjust today's categories & schedule before Fortress Lock engages"*).
  2. **Active Fortress Lock Experience**:
     - Once the focus window starts, **App Categories** (`Study`, `Self-Improvement`, `Ignored`) lock into read-only mode for promotions (users can still *demote* an app to Blocked at any time, but cannot promote a Blocked app to Study/Ignored without clearing the Physical Proof-of-Effort Gate).
     - Attempting to mark "Today" as a Holiday in the Calendar displays a clear rule badge: *"🛡️ 24h Advance Rule: Same-day holidays cannot disable today's active Focus Layer. Schedule future off-days at least 24h ahead."*
  3. **Physical Proof-of-Effort Gate (Replacing Soft 10s Gate)**:
     - When a user attempts an emergency bypass (turning off the Focus Layer, modifying locked rules, or unlocking a frozen category), a dark obsidian modal launches with two mandatory stages:
       - **Stage 1 — Physical Exertion Verification**: Live hardware sensor gauge tracking **25 Real Steps** (via Step Detector / Accelerometer cadence) or **10 Calisthenics Squats** (via vertical G-force peak-and-valley detection).
       - **Stage 2 — Anti-Paste Cognitive Reflection**: Once physical reps reach 100%, the text input unlocks with clipboard paste disabled (`setCustomSelectionActionModeCallback`) requiring the user to type a dynamically generated discipline statement.
  4. **Surgical Settings Tamper Intercept**:
     - If the user opens Android Settings and navigates to **Focus Elite App Info**, **Accessibility**, **Device Admin**, or **Date & Time** while Fortress Lock is active, the screen is immediately closed via back-navigation and replaced by a high-contrast **Fortress Tamper Shield** overlay explaining why system settings are protected during focus hours.

- **Visual Identity & Theme**:
  - *Aesthetic Direction*: Tactical Obsidian & High-Contrast Telemetry (authoritative, zero-clutter, precision-engineered dark surfaces).
  - *Color Palette & Mood*:
    - `--background`: Deep Obsidian (`#09090B`)
    - `--surface`: Elevated Zinc Card (`#18181B`) with `#27272A` structural hairlines
    - `--primary`: Electric Indigo / Cobalt (`#3B82F6`) for active focus states
    - `--accent`: Emerald Pulse (`#10B981`) for verified physical sensor progress
    - `--warning`: Crimson Alert (`#EF4444` on `#450A0A`) for tamper interception and Fortress Lock badges
  - *Typography & Hierarchy*: Bold geometric headers with tabular monospace numerals (`Typeface.MONOSPACE`) for countdowns, rep counters, and monotonic lock timers.

---

## 3. Key Product Decisions & Trade-Offs

- **Decision 1: Surgical Node Inspection in Android Settings vs. Blanket Blocking Settings**
  - *Chosen Approach*: Inspect `AccessibilityNodeInfo` text and view hierarchy inside `com.android.settings` to block only tamper-sensitive sub-screens (Focus Elite App Info, Accessibility, Device Admin, Date & Time) while still allowing benign settings like Wi-Fi or Bluetooth.
  - *Why*: Blanket-blocking the entire Settings app frustrates users who need to connect Bluetooth headphones or switch Wi-Fi during work, whereas surgical node inspection stops uninstallation, force-stop, permission revocation, and clock tampering without breaking daily utility.
- **Decision 2: Dual-Clock Monotonic Anchor vs. Network Time Lookup**
  - *Chosen Approach*: Record a monotonic snapshot (`SystemClock.elapsedRealtime()` paired with `System.currentTimeMillis()`) whenever the Focus Layer or Watchdog runs, and listen for `ACTION_TIME_CHANGED` / `ACTION_TIMEZONE_CHANGED`.
  - *Why*: Works 100% offline with zero network latency. If the user shifts the wall clock backward or forward to escape the 9:00 AM – 6:00 PM window, the drift between wall-clock delta and monotonic elapsed delta immediately flags a clock-tamper state and maintains the lock using monotonic time.
- **Decision 3: Asymmetric Category Freeze**
  - *Chosen Approach*: Freeze *relaxing* rules (moving Blocked → Study/Improvement/Ignored, or increasing daily limits) during active focus, while always permitting *tightening* rules (blocking additional distracting apps or lowering limits).
  - *Why*: A user who discovers a new distraction mid-workday should be able to block it immediately, but must never be able to whitelist a distraction on impulse.

---

## 4. Technical Architecture & Data Strategy *(Technical Reference)*

### Architecture & Component Diagram

```
┌──────────────────────────────────────────────────────────────────────────────┐
│                        ZERO-ESCAPE FORTRESS ENGINE                           │
├───────────────────────┬──────────────────────────┬───────────────────────────┤
│  System Tamper Guard  │  Monotonic Time Anchor   │  24h Holiday Validator    │
│  (Accessibility Node  │  (Wall vs Elapsed Drift  │  (Creation Timestamp      │
│   Tree Inspector)     │   & Broadcast Receiver)  │   Audit in Room DB)       │
└───────────┬───────────┴────────────┬─────────────┴─────────────┬─────────────┘
            │                        │                           │
            ▼                        ▼                           ▼
┌──────────────────────────────────────────────────────────────────────────────┐
│                     CENTRAL POLICY & COMMITMENT CORE                         │
│  • Strict Fortress Lock ON by default + 30m Pre-Window Edit Grace Period     │
│  • Asymmetric Category Freeze (Blocks Promotions during Active Focus)        │
│  • Adaptive Break & Manual Override Gated by Physical Proof-of-Effort        │
└────────────────────────────────────┬─────────────────────────────────────────┘
                                     │
                                     ▼
┌──────────────────────────────────────────────────────────────────────────────┐
│                    PHYSICAL PROOF-OF-EFFORT FRICTION GATE                    │
│  ┌─────────────────────────────────┐    ┌─────────────────────────────────┐  │
│  │ Stage 1: Hardware Sensor Engine │───▶│ Stage 2: Anti-Paste Reflection  │  │
│  │ • 25 Steps or 10 Squats (Live)  │    │ • Dynamic Randomized Prompt     │  │
│  │ • Cadence & G-Force Validation  │    │ • Clipboard/Autofill Disabled   │  │
│  └─────────────────────────────────┘    └─────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────────────────────┘
```

### Data Model & State
- **Monotonic Time Anchor (`SharedPreferences` + `SystemState`)**:
  - Stores `last_verified_wall_ms`, `last_verified_elapsed_ms`, and `clock_tamper_detected_until_ms`. Computes real focus time using `last_verified_wall_ms + (SystemClock.elapsedRealtime() - last_verified_elapsed_ms)` whenever `abs(wallDelta - elapsedDelta) > 120_000L` (2 minutes drift).
- **24-Hour Holiday Audit (`SharedPreferences` / Room `SystemState`)**:
  - Records `holiday_created_at_<yyyyMMdd>` timestamp whenever a calendar date is marked as a holiday or a holiday keyword is added. `FocusLayerManager.getCalendarExemption()` verifies that `startOfTodayMs - createdAtMs >= 24 * 3600 * 1000L` (or that the holiday was created before the current day's focus lock period) before granting an exemption.
- **Fortress Lock Lifecycle**:
  - `KEY_STRICT_LOCK_ENABLED` defaults to `true`.
  - Computes `isInPreWindowGracePeriod(nowMs)`: returns `true` during the 30 minutes immediately preceding `getStartMinutes(context)`.

### Interactive Component & State Mapping
- **Settings Tamper Interceptor**:
  - Instead of returning early on `com.android.settings`, `BlockerAccessibilityService` invokes `SettingsTamperGuard.inspectSettingsWindow(this, rootInActiveWindow)`.
  - Scans visible node texts for sensitive combinations:
    1. App Info tamper: `("Focus Elite" or "FocusGuard") AND ("Force stop" or "Uninstall" or "Clear storage" or "Clear data")`
    2. Accessibility tamper: `"Accessibility" AND ("Focus Elite" or "FocusGuard" or "Downloaded apps" or "Installed apps")`
    3. Device Admin tamper: `("Device admin" or "Deviceadmin") AND ("Focus Elite" or "FocusGuard" or "Deactivate")`
    4. Clock tamper: `"Date & time" or "Set time" or "Automatic date & time"`
  - On match while `FocusLayerManager.isActiveNow(this)` or `FocusSessionManager.isSessionActive(this)` is true: executes `performGlobalAction(GLOBAL_ACTION_BACK)`, routes to Home, and launches `PermissionTamperWarningActivity` with the specific tamper vector logged.
- **Physical Proof-of-Effort Gate (`CognitiveFrictionGate`)**:
  - Registers `SensorManager` listener (`TYPE_STEP_DETECTOR`, `TYPE_ACCELEROMETER`) directly inside the dialog lifecycle and cleans up on `OnDismissListener`.
  - Users can toggle between **"🚶 Walk 25 Steps"** or **"🏋️ Do 10 Squats"** with a live animated progress bar and cadence anti-shake filter (260ms–1800ms step interval; 750ms–3500ms squat cycle between `< 7.8 m/s²` descent and `> 12.2 m/s²` ascent).
  - Once physical effort reaches 100%, unlocks the anti-paste `EditText` requiring a randomized commitment phrase before enabling the final bypass button.
- **Category & Schedule Lock Enforcement**:
  - In `CategorySettingsActivity`, when `FocusLayerManager.isLockedUntilCalendarHoliday(this)` is active, existing Study/Improvement/Ignored apps can be unchecked (demoted to Blocked), but checking a new app into Study/Improvement/Ignored triggers the **Physical Proof-of-Effort Gate** before allowing the promotion.
  - In `AuxiliarySettingsActivity`, modifying the active Focus Window schedule or disabling the shield during active hours requires passing the **Physical Proof-of-Effort Gate**, while edits during the 30-minute Pre-Window Grace Period are friction-free.
