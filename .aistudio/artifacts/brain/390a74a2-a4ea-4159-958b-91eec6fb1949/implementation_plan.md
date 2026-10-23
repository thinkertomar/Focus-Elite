# Focus Elite: Professional Calisthenics Engine & App Icon

A complete upgrade delivering an elite, non-bulky workout logging experience inspired by top-tier fitness apps (Hevy / Strong), a comprehensive offline bodyweight and home-accessible exercise catalogue, and a modern dark-theme adaptive application icon for Focus Elite.

## User Review & Critical Decisions

> [!IMPORTANT]
> Based on your feedback during clarification, the exercise catalogue focuses strictly on pure calisthenics and zero-barrier home training (bodyweight augmented with easily accessible home items such as backpacks, towels, chairs, and doorframes), avoiding commercial gym machine clutter. The logging screen is redesigned from the ground up to replace bulky forms with an elegant, compact set-by-set table with real-time rest timing.

- **Confirmed Decision 1 (Exercise Scope)**: Pure bodyweight calisthenics and home-accessible makeshift tools (backpack squats, towel rows/crunches, chair dips, doorframe pulls). All exercises remain 100% offline with zero login requirement.
- **Confirmed Decision 2 (Logging Layout)**: Professional compact set-by-set table with previous performance references, one-tap set completion checkmarks, and an automatic rest interval timer.
- **Confirmed Decision 3 (App Icon)**: Custom high-contrast dark theme icon featuring an obsidian shield with an energetic focus ring and electric cyan/violet accents, configured across adaptive and raster mipmap densities.

---

## 1. Overview & Core Concept

- **What It Does**: Transforms Focus Elite's activity module into a modern fitness companion. Users can explore a filtered library of 120+ bodyweight and home exercises with muscle anatomy badges, log workouts set-by-set with minimal tapping, track rest intervals, and analyze their training volume and consistency over time.
- **Target Audience / Persona**: Users training at home, outdoors, or in dorms who want structured, distraction-free bodyweight strength training without gym equipment or bloated subscription apps.
- **Key Value**: Frictionless, distraction-free logging that takes seconds per set, keeping training intensity high and cognitive load low.

---

## 2. User Experience & Visual Design

### Key User Flows

1. **Exercise Discovery & Selection**:
   - User opens *Calisthenics* from the Physical Activity dashboard.
   - Quick filters allow toggling by muscle group (*Chest*, *Back*, *Legs*, *Core*, *Arms*, *Full Body*) or equipment (*Body Only*, *Home Items*).
   - Tapping an exercise previews target anatomy, difficulty rating, and step-by-step instructions in a smooth bottom sheet or preview card, with an immediate **"Start Logging"** action.

2. **Streamlined Set Logging**:
   - The active workout displays an elegant, compact set table:
     - **Set Number**: (#1, #2, #3, ...)
     - **Previous**: Shows reps/weight/hold from the previous session for progressive overload tracking.
     - **Target / Input**: Compact numeric fields with quick `+`/`-` micro-steppers for reps and hold seconds.
     - **Checkmark Action**: One-tap completion button that marks the set complete, highlights the row in soft emerald green, and triggers the rest timer.
   - **Rest Interval Countdown**: A floating pill or non-intrusive timer bar smoothly counts down (e.g., 60s, 90s, or custom) with tactile vibration and sound ping on zero.

3. **Session Completion & Local History**:
   - Tapping **"Finish Workout"** presents a concise session recap card displaying total sets, aggregate reps, total hold time, and estimated volume.
   - Saves directly to the local Room database (`AppDatabase` / `ExerciseLogDao`).
   - The *History* tab displays a chronological timeline of completed workouts with date filtering and personal best indicators.

### Visual Identity & Theme

- **Aesthetic Direction**: Obsidian Luxury Dark — deep black OLED canvas (`#0E1117`), dark slate card surfaces (`#181D26`), neon cyan accents (`#00E5FF`), and electric violet highlights (`#7C4DFF`).
- **Color Palette**:
  - Background Base: `#0E1117`
  - Elevated Cards: `#1A202C`
  - Accent Cyan: `#00E5FF`
  - Accent Violet: `#7C4DFF`
  - Success / Completed Set: `#00E676`
  - Text Primary: `#FFFFFF`
  - Text Secondary: `#94A3B8`
- **Typography & Hierarchy**:
  - Section Headers: Bold Sans 18sp with tight letter spacing.
  - Set Data Table: Monospace / Tabular numerals for clean vertical alignment across sets.
  - Badges & Chips: Compact 11sp uppercase text with pill backgrounds.
- **Component Styling & Layout**:
  - Thin 1dp bordered cards with subtle neon corner accents.
  - Zero bulky full-screen input dialogs; all inputs are inline table cells.
  - Minimum 48dp touch targets on checkmark and stepper buttons for sweaty finger accuracy.

---

## 3. Key Product Decisions & Trade-Offs

- **Decision 1: Compact Table Layout vs. Bulky Multi-Step Forms**
  - *Chosen Approach*: Inline set-by-set table matching modern pro apps (Hevy, Strong).
  - *Why*: In active workouts, users need to see all completed sets at a glance and enter the next set in under 3 seconds. Multi-step wizards or large card stacks create friction and fatigue.
  - *Alternatives Considered*: Modal dialogs per set (rejected: too many clicks).
- **Decision 2: Curated Home & Bodyweight Catalogue vs. Full 800+ Gym Machine Dump**
  - *Chosen Approach*: 111 bodyweight exercises augmented with 12 essential home-item variations (backpack squats, towel rows, chair dips, doorway pulls).
  - *Why*: Adding hundreds of gym machine exercises (barbells, cable towers, Smith machines) dilutes Focus Elite's core identity as a distraction-free home & calisthenics utility.
- **Decision 3: Integrated Rest Timer with Non-Intrusive Banner**
  - *Chosen Approach*: Compact sticky bar at the bottom of the screen with quick +30s / Skip buttons.
  - *Why*: Fullscreen countdown timers block the user from previewing upcoming sets or instructions.

---

## 4. Technical Architecture & Data Strategy

### Architecture & Component Diagram

```
┌────────────────────────────────────────────────────────────────────────┐
│                          CalisthenicsActivity                          │
├────────────────────────────────────────────────────────────────────────┤
│  ┌───────────────────────┬──────────────────────┬───────────────────┐  │
│  │     Log Workout Tab   │    Catalogue Tab     │    History Tab    │  │
│  │  ┌─────────────────┐  │  ┌────────────────┐  │  ┌─────────────┐  │  │
│  │  │ Active Exercise │  │  │ Search & Muscle│  │  │ Total Volume│  │  │
│  │  │ Header & Sheet  │  │  │ Group Chips    │  │  │ Stats & PRs │  │  │
│  │  ├─────────────────┤  │  ├────────────────┤  │  ├─────────────┤  │  │
│  │  │ Set-by-Set Table│  │  │ Filtered Grid  │  │  │ Timeline of │  │  │
│  │  │ (Reps, Sec, Check│ │  │ Cards with     │  │  │ Sessions    │  │  │
│  │  ├─────────────────┤  │  │ Anatomy Badges │  │  │ (Room DB)   │  │  │
│  │  │ Sticky Rest Bar │  │  └────────────────┘  │  └─────────────┘  │  │
│  │  └─────────────────┘  │                      │                   │  │
│  └───────────────────────┴──────────────────────┴───────────────────┘  │
└────────────────────────────────────┬───────────────────────────────────┘
                                     │
           ┌─────────────────────────┴─────────────────────────┐
           ▼                                                   ▼
┌───────────────────────────────┐           ┌────────────────────────────────┐
│   CalisthenicsRepository      │           │         AppDatabase            │
├───────────────────────────────┤           ├────────────────────────────────┤
│ • calisthenics_exercises.json │           │ • ExerciseLog Entity           │
│ • 111 bodyweight exercises    │           │ • ExerciseLogDao queries       │
│ • 12 home-item variations     │           │ • Local SQLite persistence     │
│ • Muscle & equipment indexing │           │ • Timestamped sets & reps      │
└───────────────────────────────┘           └────────────────────────────────┘
```

### Data Model & State

- **`CalisthenicsExercise`**: ID, name, primaryMuscles, secondaryMuscles, level, category, equipment (`body only` or `home item`), instructions list.
- **`WorkoutSetState`**: Set index, targetReps, targetDurationSec, actualReps, actualDurationSec, isCompleted, timestamp.
- **`ExerciseLog` (Room Entity)**: Stored per completed set with `exerciseName`, `reps`, `durationSeconds`, `timestamp`, and `sessionUuid`.

### Implementation Sequence

1. **Asset & Icon Generation**:
   - Generate sleek, dark-themed "Focus Elite" icon using `generate_image`.
   - Create multi-density raster PNGs (`mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}`) and round icon variants.
   - Configure adaptive icon XML layers.
2. **Dataset Enrichment**:
   - Augment `calisthenics_exercises.json` with home-accessible makeshift exercises (Backpack Squats, Towel Crunches, Chair Dips, Doorframe Incline Rows, Towel Hamstring Curls, Backpack Overhead Press).
3. **UI Layout Modernization (`activity_calisthenics.xml`)**:
   - Build the sleek, space-efficient set table component (`item_workout_set.xml`).
   - Implement the bottom sticky rest timer pill with quick adjustments.
   - Modernize the catalogue search bar and muscle group horizontal chip row.
4. **Activity Logic Refinement (`CalisthenicsActivity.kt`)**:
   - Dynamic set rows with inline text change and checkmark toggles.
   - Countdown timer handler with sound/vibration feedback.
   - Smooth tab navigation and session finish workflow with database commit.
