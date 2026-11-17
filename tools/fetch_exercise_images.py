#!/usr/bin/env python3
"""
One-shot offline image fetcher for the Calisthenics catalogue (free-exercise-db, Unlicense / public domain).

Run from the project root (the folder containing `app/`):
    python3 tools/fetch_exercise_images.py

- Reads app/src/main/assets/calisthenics_exercises.json
- Downloads every image listed in each exercise's "images" array
  into app/src/main/assets/exercises/<id>/<n>.jpg  (bundled in the APK -> works fully offline)
- Safe to re-run: existing valid files are skipped, interrupted downloads resume
- Fills a few custom home-item exercises with a reference movement (ALIASES below) and patches the JSON
- Optional: --shrink 720  (needs Pillow; re-encodes to max width 720px to cut APK size)
"""
import json
import os
import shutil
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed

BASES = [
    "https://raw.githubusercontent.com/yuhonas/free-exercise-db/main/exercises/",
    "https://raw.githubusercontent.com/yuhonas/free-exercise-db/main/dist/exercises/",
]

# custom exercise id -> dataset exercise id whose photos show the same movement
ALIASES = {
    "backpack_squat": "Bodyweight_Squat",
    "towel_floor_crunches": "Crunches",
    "chair_dips": "Bench_Dips",
}

ROOT = os.getcwd()
JSON_PATH = os.path.join(ROOT, "app", "src", "main", "assets", "calisthenics_exercises.json")
OUT_DIR = os.path.join(ROOT, "app", "src", "main", "assets", "exercises")


def is_jpeg(path):
    try:
        with open(path, "rb") as f:
            return f.read(2) == b"\xff\xd8" and os.path.getsize(path) > 1024
    except OSError:
        return False


def download(rel):
    dest = os.path.join(OUT_DIR, rel)
    if is_jpeg(dest):
        return rel, "skipped", 0
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    last_err = "unknown"
    for base in BASES:
        url = base + urllib.parse.quote(rel)
        for attempt in range(3):
            try:
                req = urllib.request.Request(url, headers={"User-Agent": "focus-elite-image-fetch/1.0"})
                with urllib.request.urlopen(req, timeout=30) as resp:
                    data = resp.read()
                if data[:2] != b"\xff\xd8":
                    last_err = "not a JPEG"
                    break
                tmp = dest + ".part"
                with open(tmp, "wb") as f:
                    f.write(data)
                os.replace(tmp, dest)
                return rel, "downloaded", len(data)
            except urllib.error.HTTPError as e:
                last_err = "HTTP %d" % e.code
                if e.code == 404:
                    break
                time.sleep(1.5 * (attempt + 1))
            except Exception as e:  # network hiccup
                last_err = str(e)
                time.sleep(1.5 * (attempt + 1))
    return rel, "failed: " + last_err, 0


def shrink_all(max_width):
    try:
        from PIL import Image
    except ImportError:
        print("Pillow not installed - skipping --shrink (pip install pillow)")
        return
    saved = 0
    for folder, _, files in os.walk(OUT_DIR):
        for name in files:
            if not name.lower().endswith(".jpg"):
                continue
            p = os.path.join(folder, name)
            before = os.path.getsize(p)
            with Image.open(p) as im:
                if im.width <= max_width:
                    continue
                h = int(im.height * max_width / im.width)
                im = im.convert("RGB").resize((max_width, h))
                im.save(p, "JPEG", quality=82, optimize=True)
            saved += before - os.path.getsize(p)
    print("Shrink saved %.1f MB" % (saved / 1048576))


def apply_aliases(exercises):
    changed = 0
    by_id = {e["id"]: e for e in exercises}
    for custom_id, source_id in ALIASES.items():
        ex = by_id.get(custom_id)
        src = by_id.get(source_id)
        if not ex or not src or ex.get("images"):
            continue
        new_paths = []
        for i, rel in enumerate(src.get("images", [])):
            src_file = os.path.join(OUT_DIR, rel)
            if not is_jpeg(src_file):
                continue
            new_rel = "%s/%d.jpg" % (custom_id, i)
            dst = os.path.join(OUT_DIR, new_rel)
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            shutil.copyfile(src_file, dst)
            new_paths.append(new_rel)
        if new_paths:
            ex["images"] = new_paths
            changed += 1
    return changed


def main():
    if not os.path.isfile(JSON_PATH):
        sys.exit("Run this from the project root (folder that contains app/). Missing: " + JSON_PATH)

    with open(JSON_PATH, encoding="utf-8") as f:
        exercises = json.load(f)

    wanted = []
    for ex in exercises:
        wanted.extend(ex.get("images") or [])
    wanted = sorted(set(wanted))
    print("Fetching %d images for %d exercises..." % (len(wanted), len(exercises)))

    counts = {"downloaded": 0, "skipped": 0}
    failed = []
    total_bytes = 0
    with ThreadPoolExecutor(max_workers=8) as pool:
        futures = [pool.submit(download, rel) for rel in wanted]
        for i, fut in enumerate(as_completed(futures), 1):
            rel, status, size = fut.result()
            total_bytes += size
            if status in counts:
                counts[status] += 1
            else:
                failed.append((rel, status))
            if i % 25 == 0 or i == len(futures):
                print("  %d/%d" % (i, len(futures)))

    if "--shrink" in sys.argv:
        idx = sys.argv.index("--shrink")
        width = int(sys.argv[idx + 1]) if idx + 1 < len(sys.argv) and sys.argv[idx + 1].isdigit() else 720
        shrink_all(width)

    patched = apply_aliases(exercises)
    if patched:
        with open(JSON_PATH, "w", encoding="utf-8") as f:
            json.dump(exercises, f, ensure_ascii=False, indent=2)
        print("Linked reference images for %d custom exercises." % patched)

    print("Done: %d downloaded (%.1f MB), %d already present, %d failed."
          % (counts["downloaded"], total_bytes / 1048576, counts["skipped"], len(failed)))
    for rel, status in failed:
        print("  FAILED", rel, status)
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
