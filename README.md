# Gather

Gather is a native Kotlin Android prototype for temporary, offline photo rooms. The minimum Android version is Android 10 (API 29). It has no login, server, or internet room mode.

## Open the app

Install Android Studio and use its Setup Wizard to install Android SDK Platform 36 and Platform Tools. Android Gradle Plugin 8.13 requires Gradle 8.13 and JDK 17. Gradle 8.13 cannot be run on JDK 27, so use JDK 17 for the bootstrap below. This workspace does not include Gradle wrapper files. You can keep another Gradle version installed; download and extract Gradle 8.13 beside it:

```bat
mkdir "%USERPROFILE%\tools"
curl.exe -L "https://services.gradle.org/distributions/gradle-8.13-bin.zip" -o "%TEMP%\gradle-8.13-bin.zip"
tar -xf "%TEMP%\gradle-8.13-bin.zip" -C "%USERPROFILE%\tools"
```

In the same CMD window, set `JAVA_HOME` to your JDK 17 install path (replace the example path), then generate the wrapper and build:

```bat
set "JAVA_HOME=C:\path\to\jdk-17"
set "PATH=%JAVA_HOME%\bin;%PATH%"
cd /d "C:\Users\Zamirah Alec\sshot"
"%USERPROFILE%\tools\gradle-8.13\bin\gradle.bat" wrapper --gradle-version 8.13
gradlew.bat assembleDebug
```

The first build needs internet access to download Gradle and resolve Google Maven and Maven Central dependencies. The debug APK is written to `app\build\outputs\apk\debug\app-debug.apk`. After generating the wrapper, open this folder in Android Studio or continue building with `gradlew.bat`.

The project uses Android Gradle Plugin 8.13.2, Nearby Connections 19.5.1, Google Code Scanner 16.1.0, and ZXing Core 3.5.3. The Android Studio embedded JDK or another JDK 17 installation can run Gradle.

Nearby Connections and Google Code Scanner both require Google Play services on the device. Google Code Scanner owns its scanning UI, so Gather does not request camera permission. The scanner module should be installed before a gathering if the phone has not used it before. Nearby operation itself uses local Bluetooth and Wi-Fi capabilities and does not require internet or a shared Wi-Fi network.

## V1 behavior

- The first launch stores a display name locally. There is no account flow.
- A host creates a QR invitation, approves each join request, can pause new requests, remove guests, and end the room. Rooms hold at most eight members including the host.
- A visible foreground notification keeps an active room service running in the background. Guest sessions expire ten minutes after losing the host; the host's room expires ten minutes after its service heartbeat stops. An OS alarm also clears room data if Android stops the process or the phone restarts, and reconnecting before expiry keeps the room alive.
- Automatic photo discovery is opt-in and uses the room's capture-time window. Candidate URIs and review state stay on their owner's phone. If broad access is denied or limited, the Android photo picker can add eligible photos manually.
- A photo enters the room only when its owner turns on its Share control. The room relays a bounded JPEG preview, sorted by capture time and labeled with the owner's display name.
- Requesting a full-size image sends the original bytes through the host only while the owner is reachable. Received copies are saved under Pictures/Gather. A transfer is capped at 100 MiB of transient storage.
- Ending or expiring a room removes app-managed candidates, previews, and temporary files. Original library photos and recipient copies saved under Pictures/Gather remain.

## Device support still to verify

The workspace has no Android SDK, JDK, Gradle, or connected device installed, so this project has not been compiled or exercised on phones in this environment. Real-device work is still needed for Nearby permission prompts and transfer behavior on Android 10 and current Android, foreground service restrictions, QR handoff, alarm timing under battery controls, reconnection timing, and eight-member throughput. Google Play services must be present; some Android devices do not include it.
