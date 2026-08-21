# 🍒 Barbafruit

A family-friendly Android game for the **Meta Portal (1st Gen, Android 9 / API 28)** that uses the device's front-facing camera and on-device ML Kit face detection to let you control a hungry **Barbaloot bear** with your head — no hands required!

Inspired by Dr. Seuss's *The Lorax*: Truffula fruits tumble down from the Truffula tree canopy, and the Barbaloot scampers back and forth (steered by your head!) to catch as many as it can.

> Barbafruit is a fork of [HippoMuncher](https://github.com/compscirunner/HippoMuncher), rethemed and reworked for very young players.

---

## 📸 Gameplay

### 🍒 Fruit Frenzy (default mode)

Designed for toddlers and young kids — **nothing bad can happen**:

- Only Truffula fruits fall. No bombs, no boots, no rocks.
- Missing a fruit costs nothing — no strikes, no fail state, no sad sounds.
- Catch as many fruits as you can before the timer runs out (**30 seconds, 1 minute, 2 minutes, or ∞ endless** — pick in the menu).
- Every 10th fruit, **the Lorax pops up to cheer** with confetti and a happy chirp.
- On Easy, the catch box is wider than the bear looks, so near-misses still count.
- Timed rounds always end in a confetti celebration: *"You caught 23 truffula fruits!"* — and in endless mode the game simply never ends.

### 🪨 Classic mode

The original HippoMuncher rules, for older players:

- Rocks and muddy boots fall alongside the fruit — eating 3 ends the game
- Letting 3 fruits fall off screen also ends the game

### Both modes

- **Head tracking controls** — move your head to steer the Barbaloot
- **3 difficulty levels** — Easy, Medium, Hard (adjust fall speed & spawn rate)
- **High score tracking** per mode, timer length, and difficulty
- **Full sound effects** — background music, catch sounds, celebration fanfare
- **Hamburger menu** — game mode, timer, difficulty, recalibrate, reset high score

---

## 📦 Download & Install (Prebuilt APK)

> **Requirements:** Android device with ADB enabled, or a Meta Portal with Developer Mode on.

1. Download the latest APK from the [**Releases**](../../releases/latest) page
2. Connect your device via USB
3. Install via ADB:

```bash
adb install -r Barbafruit.apk
```

4. Launch the app from your launcher (Nova Launcher recommended on Meta Portal)

---

## 🔨 Building from Source

### Prerequisites

| Tool | Version |
|------|---------|
| Android Studio | Hedgehog or newer |
| JDK | 17 or 21 |
| Android SDK | API 33 (compileSdk), targeting API 28 |
| Gradle | 8.x (wrapper included) |

### Clone the repo

```bash
git clone https://github.com/starbrightlab/barbafruit.git
cd barbafruit
```

### Configure local SDK path

Create `local.properties` in the project root (not committed to git):

```properties
sdk.dir=C\:\\Users\\YourName\\AppData\\Local\\Android\\Sdk
```

Or let Android Studio generate it automatically when you open the project.

### Build a debug APK (signed, ready to sideload)

```bash
./gradlew assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk
```

### Build a release APK

The release build is configured to sign with the local debug keystore for easy sideloading. No Play Store keystore needed.

```bash
./gradlew assembleRelease -x lintVitalRelease
# Output: app/build/outputs/apk/release/app-release.apk
```

> The `-x lintVitalRelease` flag skips a Play Store lint check that flags `targetSdk 28` — intentional for Meta Portal compatibility.

### Install to connected device

```bash
# Debug build
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Release build
adb install -r app/build/outputs/apk/release/app-release.apk
```

### Launch via ADB (useful on Meta Portal which has a locked launcher)

```bash
adb shell am start -n com.family.barbafruit/.MainActivity
```

---

## 🏗️ Project Structure

```
app/src/main/
├── java/com/family/barbafruit/
│   ├── MainActivity.kt          # Activity, drawer menu, CameraX setup
│   ├── GameSurfaceView.kt       # Game loop, rendering, physics (TextureView)
│   ├── FaceTrackerAnalyzer.kt   # ML Kit face detection → normalized face coords
│   └── SoundFx.kt               # SoundPool (effects) + MediaPlayer (music)
├── res/
│   ├── layout/activity_main.xml # DrawerLayout wrapping game + nav drawer
│   ├── raw/                     # .ogg sound files
│   └── mipmap-*/                # App icons at all densities
└── AndroidManifest.xml
```

---

## 🎮 How It Works

1. **CameraX** streams frames from the front camera to `FaceTrackerAnalyzer`
2. **ML Kit Face Detection** locates the face and reports its normalized X/Y position and size
3. A **calibration step** captures your neutral head position and scale to use as the origin, and adjusts for lateral offset so the Barbaloot centers on your face
4. The **game loop** (running at ~60 fps on a `TextureView`) lerps the Barbaloot toward the tracked face X position
5. Truffula fruits spawn at random X positions and fall at speed determined by the selected **difficulty**
6. In **Fruit Frenzy** the round ends when the timer runs out — always with a celebration. In **Classic**, the game ends when 3 rocks/boots are eaten OR 3 fruits are dropped

---

## 📱 Device Notes

This game is specifically designed for the **Meta Portal (1st Gen)**:
- `minSdk 28` / `targetSdk 28` (Android 9)
- Uses **bundled ML Kit** face detection model (no Play Services / GMS download required)
- **TextureView** used instead of SurfaceView so the slide-out drawer menu renders correctly above the game
- Landscape orientation locked

---

## 🪪 License

MIT License — feel free to fork, modify, and share!
