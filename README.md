# gattLibrary

Android BLE libraries extracted from FitBridge. The build includes five Android libraries and two test-only modules.

| Module | Role |
| --- | --- |
| gatt-core | Shared BLE configuration, wire definitions, and byte codecs |
| gatt-central | BLE discovery, upstream connections, measurements, and control through FitnessGattClient |
| gatt-server | Shared GATT and FTMS server implementation |
| gatt-peripheral | FTMS advertising and downstream bridge |
| gatt-simulator | Debug simulator and machine personas |
| gatt-fixtures, gatt-conformance | Test data and cross-module regression coverage |

Use JDK 21 and install Android SDK 37.1. Configure the SDK with ANDROID_HOME or an untracked local.properties. Run the checked-in Gradle wrapper. On Windows, use gradlew.bat.

```powershell
.\gradlew.bat testDebugUnitTest :gatt-core:releaseApiCheck :gatt-central:releaseApiCheck :gatt-server:releaseApiCheck :gatt-peripheral:releaseApiCheck :gatt-simulator:releaseApiCheck --continue --no-daemon --console=plain
```

For composite builds, include this checkout with includeBuild("../android-gattLibrary"). Use the coordinates com.gmail.absolutebollockscreations.gattlibrary:gatt-central for the central client and add other modules as needed. Use a debug dependency for gatt-simulator.

The [module provenance](docs/evidence/lib-extraction/stage3-module-provenance.md), [evidence tiers](docs/evidence/lib-extraction/device-evidence-tiers.md), and [divergence register](docs/evidence/lib-extraction/divergence-register.md) accompany the fixtures and module notices.
