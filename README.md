# PdfCraft for Android

<p align="center">
  <strong>Fast, 100% Offline PDF Reader, Page Organizer, and Document Manipulation Studio</strong>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Platform-Android%207.0%2B-brightgreen" alt="Platform" />
  <img src="https://img.shields.io/badge/Language-Kotlin-blue" alt="Language" />
  <img src="https://img.shields.io/badge/UI-Jetpack%20Compose%20%28Material%203%29-indigo" alt="UI" />
  <img src="https://img.shields.io/badge/Engine-Apache%20PDFBox%20%2B%20PdfRenderer-crimson" alt="Engine" />
  <img src="https://img.shields.io/badge/Privacy-100%25%20Offline-teal" alt="Privacy" />
</p>

---

## Overview

**PdfCraft for Android** is a high-speed, local-first PDF workbench inspired by the open-source Rust desktop application [PdfCraft](https://github.com/storytold/pdfcraft). Re-engineered natively for mobile with Jetpack Compose, Android's hardware-accelerated graphics pipeline, and Apache PDFBox (`pdfbox-android`), PdfCraft provides desktop-grade document manipulation right in your pocket with zero external network requests and total data privacy.

---

## Key Features

- **📖 High-Fidelity PDF Viewer**: Continuous reader with smooth pinch-to-zoom (up to 450%), thumbnail preview carousel, page scrubber, and quick jump-to-page dialog.
- **📑 Visual Page Organizer**:
  - Interactive grid displaying real-time rendered page thumbnails.
  - Rearrange page order with tap-to-move controls.
  - Rotate individual or all pages in 90°, 180°, and 270° increments.
  - Clone/duplicate pages and delete unnecessary sheets.
  - Direct export via Android Storage Access Framework (SAF).
- **🛠️ Comprehensive Tool Suite**:
  - **Merge PDFs**: Combine multiple independent documents into a single consolidated master PDF.
  - **Split PDF**: Extract custom page ranges (e.g., `1-3`, `4,7-9`) into standalone files.
  - **Images to PDF**: Convert camera scans, notes, receipts, and gallery photos into clean A4 vector PDFs with automatic centering.
  - **PDF to Images**: Extract high-resolution PNG bitmaps of pages directly to your photo album.
  - **Watermark & Page Stamping**: Stamp custom semi-transparent diagonal confidentiality notices and automated footer page badges (`Page X of Y`).
  - **Metadata Inspector**: Read and inspect document Title, Author, Creator, Page Count, and File Size.
- **🔒 100% Offline & Private**: Zero cloud servers, zero telemetry, zero accounts. All PDF processing happens directly in local device memory.
- **📂 Document Hub**: Built-in 4-page interactive sample guide ready to explore immediately on first launch, plus local Room database history for recent files.

---

## Installing & Running via GitHub

### Option 1: Download Pre-Built APK (GitHub Actions / Releases)

1. Open this repository on GitHub.
2. Click on the **Actions** tab.
3. Select the latest **Build & Package Android APK** workflow run.
4. Scroll down to **Artifacts** and download `PdfCraft-debug-apk.zip`.
5. Unzip and transfer `app-debug.apk` to your Android device, or install via ADB:
   ```bash
   adb install app-debug.apk
   ```

*(Alternatively, any Git tag push like `git tag v1.0.0 && git push origin v1.0.0` will automatically create a GitHub Release with the APK attached!)*

---

### Option 2: Clone & Build from Source

#### Prerequisites
- **Git**
- **JDK 17 or higher** (`java -version`)
- **Android SDK** with platform `android-36` (Build Tools 36) or Android Studio

#### Build Steps

1. Clone the repository:
   ```bash
   git clone https://github.com/<your-username>/pdfcraft-android.git
   cd pdfcraft-android
   ```

2. Grant execute permission to the Gradle wrapper:
   ```bash
   chmod +x gradlew
   ```

3. Build the debug APK:
   ```bash
   ./gradlew assembleDebug
   ```

4. Install the APK onto a connected Android phone or emulator:
   ```bash
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```

---

## Tech Stack & Architecture

- **UI Framework**: Modern declarative UI with [Jetpack Compose](https://developer.android.com/jetpack/compose) & [Material Design 3 (M3)](https://m3.material.io/).
- **PDF Engines**:
  - `android.graphics.pdf.PdfRenderer`: Hardware-accelerated bitmap rasterization for instant rendering and responsive zoom/pan.
  - `android.graphics.pdf.PdfDocument`: Canvas-based page compilation, transforms, and exports.
  - `com.tom-roush:pdfbox-android:2.0.27.0`: Apache PDFBox port for Android document manipulation, resource loading, and metadata extraction.
- **Local Persistence**: [Room Database (SQLite)](https://developer.android.com/training/data-storage/room) for caching recent documents and quick reopen.
- **Concurrency & State**: Kotlin Coroutines & `MutableStateFlow` following MVVM (Model-View-ViewModel) architecture.
- **File Sharing**: Android `FileProvider` with Storage Access Framework (SAF) integration for secure, zero-permission document export.

---

## System Requirements

- **Minimum SDK**: Android 7.0 (API level 24)
- **Target SDK**: Android 16 (API level 36)
- **Architecture**: Supports all Android ABIs (`arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86`).

---

## License

This project is licensed under the Apache 2.0 License.
