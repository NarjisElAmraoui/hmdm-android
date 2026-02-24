# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Headwind MDM is an open-source Mobile Device Management (MDM) solution for Android devices. It functions as both a system launcher and device management agent, enforcing policies, managing app installation, handling remote logging, and communicating with a backend MDM server.

**Website:** https://h-mdm.com
**License:** Apache License 2.0
**Current Version:** 6.30 (versionCode 15300)

## Project Structure

This is a multi-module Gradle project:

- **app**: Main Android launcher application (`com.hmdm.launcher`)
- **lib**: Client library AAR for third-party app integration (`com.hmdm`)

Both modules use shared user ID `com.hmdm` for inter-process communication.

## Build Commands

### Building in Android Studio

Open the project in Android Studio (use default settings).

### Building from Command Line

```bash
# Build entire project (Windows)
gradlew build

# Build entire project (Linux/Mac)
./gradlew build

# Build only the app module
gradlew :app:build

# Build only the library
gradlew :lib:build

# Clean build
gradlew clean
```

**Note:** If building on Windows, ensure `local.properties` contains the correct SDK path:
```
sdk.dir=C:\\path\\to\\Android\\SDK
```

### Build Outputs

- **Main APK:** `app/build/outputs/apk/opensource/release/app-opensource-release-unsigned.apk`
- **Library AAR:** `lib/build/outputs/aar/lib-release.aar`

### Generating Signed APK

1. Set up signing keys in Android Studio
2. Build → Generate Signed Bundle / APK
3. Select APK and follow the wizard

## Running and Testing

### Running on Device/Emulator

```bash
# Install debug build
gradlew :app:installOpensourceDebug

# Run via Android Studio: Click "Run 'app'" button
```

### Setting Device Owner Rights

After installation, grant device owner privileges via ADB:

```bash
adb shell
dpm set-device-owner com.hmdm.launcher/.AdminReceiver
```

This enables silent app installation, factory reset, and full policy enforcement.

### Running Tests

```bash
# Run unit tests
gradlew :app:testOpensourceDebugUnitTest

# Run instrumented tests (requires connected device/emulator)
gradlew :app:connectedOpensourceDebugAndroidTest
```

## Architecture Overview

### Core Components

**Activities:**
- `MainActivity`: Main launcher UI, app grid display, kiosk mode enforcement, device policy application
- `InitialSetupActivity`: Device enrollment wizard with QR code provisioning
- `AdminActivity`: Admin panel for configuration and diagnostics

**Services:**
- `StatusControlService`: Foreground service for policy enforcement and UI updates
- `PushLongPollingService`: Long-polling push notification service
- `LocationService`: GPS tracking service
- `MqttService` (Eclipse Paho): MQTT-based push notifications

**Workers (AndroidX WorkManager):**
- `PushNotificationWorker`: 15-minute periodic fallback for config updates
- `SendDeviceInfoWorker`: Device status/info collection and upload
- `RemoteLogWorker`: Batched log upload
- `ScheduledAppUpdateWorker`: Daily app update checks
- `DetailedInfoWorker`: Extended device inventory collection

**Broadcast Receivers:**
- `BootReceiver`: Initialization on device boot
- `AdminReceiver`: Device admin events, QR provisioning completion
- `SimChangedReceiver`: SIM card change detection
- `ScreenOffReceiver`: Screen state monitoring

**Helpers:**
- `ConfigUpdater`: Core orchestrator for configuration updates, app installation/removal, policy application
- `SettingsHelper`: Singleton managing SharedPreferences and in-memory config cache
- `Initializer`: Multi-phase app initialization (network wait, worker scheduling, service startup)

### Key Data Flow

```
Server API (Retrofit)
    ↓
GetServerConfigTask
    ↓
SettingsHelper (SharedPreferences + cache)
    ↓
ConfigUpdater.updateConfiguration()
    ↓
- updateApplications() → App install/uninstall
- updateFiles() → File downloads
- applyPolicies() → DevicePolicyManager
    ↓
MainActivity UI updates
```

### Push Notification System

Two modes supported (configured via `ServerConfig.pushOptions`):

**MQTT Mode** (`mqttWorker` or `mqttAlarm`):
- Eclipse Paho MQTT client connects to server on port 31000
- Subscribes to topic: `device/{deviceId}`
- `PushNotificationMqttWrapper` manages connection lifecycle with auto-reconnect
- Foreground service prevents OS termination on weak devices

**Long Polling Mode** (`polling`):
- `PushLongPollingService` runs continuous HTTP polling with 300s timeout
- 5-second delay between requests

All messages processed by `PushNotificationProcessor` which handles 15+ message types including config updates, app launches, broadcasts, file operations, reboots, and custom plugin messages.

### Configuration Management

**ServerConfig** (`json/ServerConfig.java`) contains all device settings:
- Kiosk mode settings (`kioskMode`, `mainApp`, `kioskHome`, etc.)
- Device policies (GPS, Bluetooth, WiFi, mobile data, screen timeout, brightness, volume)
- Security settings (password policies, factory reset, lock commands)
- Applications list with installation URLs and managed settings
- Remote files to download
- UI customization (background, colors, icons, layout)
- Push notification settings

Configuration updates triggered by:
1. Push notification: `TYPE_CONFIG_UPDATED`
2. Periodic worker: every 15 minutes
3. Manual sync: User-initiated in MainActivity
4. Boot-time: `BootReceiver` via `Initializer`

### Server Communication

**ServerService** (`server/ServerService.java`) defines REST API using Retrofit:

**Authentication:** SHA1 signature-based requests
- Header: `X-Request-Signature: SHA1(REQUEST_SIGNATURE + path)`
- Secret defined in `build.gradle`: `REQUEST_SIGNATURE` BuildConfig field

**Key Endpoints:**
```
POST /{project}/rest/public/sync/configuration/{deviceId}     - Enroll device
GET  /{project}/rest/public/sync/configuration/{deviceId}     - Get config
POST /{project}/rest/public/sync/info                         - Send device info
GET  /{project}/rest/notification/polling/{deviceId}          - Long polling
GET  /{project}/rest/plugins/devicelog/log/rules/{deviceId}   - Log config
POST /{project}/rest/plugins/devicelog/log/list/{deviceId}    - Upload logs
```

**Dual-server support:** Primary + secondary URLs for failover (configured in `build.gradle`)

### Database Schema

SQLite database (`DatabaseHelper`) with tables:
- `LogTable`: Remote logs awaiting upload
- `LogConfigTable`: Logging rules from server
- `InfoHistoryTable`: Device info history
- `RemoteFileTable`: Downloaded file metadata
- `LocationTable`: GPS tracking history
- `DownloadTable`: App download/installation history

### Library Module (lib/)

Third-party apps can integrate with Headwind MDM using the client library.

**HeadwindMDM API** (`HeadwindMDM.java`):
```java
HeadwindMDM mdm = HeadwindMDM.getInstance();
mdm.connect(context, eventHandler);
String serverUrl = mdm.getServerUrl();
String deviceId = mdm.getDeviceId();
```

**AIDL Interface** (`IMdmApi.aidl`):
- `queryConfig()`: Get MDM configuration
- `log()`: Send log messages to remote logger
- `queryAppPreference()` / `setAppPreference()`: App-specific preferences synced with server
- `forceConfigUpdate()`: Trigger configuration refresh

**Note:** Apps using the library should declare `android:sharedUserId="com.hmdm"` in AndroidManifest.xml for full integration.

## Critical Build Configuration

The `app/build.gradle` file contains crucial BuildConfig fields that customize the MDM behavior:

### Server URLs (must be customized for deployment)
```gradle
buildConfigField("String", "BASE_URL", "\"https://app.h-mdm.com\"")
buildConfigField("String", "SECONDARY_BASE_URL", "\"https://app.h-mdm.com\"")
buildConfigField("String", "SERVER_PROJECT", "\"\"")
```

### Device ID Strategy
```gradle
buildConfigField("String", "DEVICE_ID_CHOICE", "\"user\"")
// Options: "user", "suggest", "imei", "serial", "mac"
```

### Security & Authentication
```gradle
buildConfigField("String", "REQUEST_SIGNATURE", "\"changeme-C3z9vi54\"")
buildConfigField("Boolean", "CHECK_SIGNATURE", "false")
buildConfigField("Boolean", "TRUST_ANY_CERTIFICATE", "false")
```

**IMPORTANT:** `REQUEST_SIGNATURE` must match the server's shared secret. `TRUST_ANY_CERTIFICATE` should only be `true` for testing with self-signed certificates.

### Push Notifications
```gradle
buildConfigField("Boolean", "ENABLE_PUSH", "true")
buildConfigField("Integer", "MQTT_PORT", "31000")
buildConfigField("Boolean", "MQTT_SERVICE_FOREGROUND", "true")
```

### System Privileges
```gradle
buildConfigField("Boolean", "SYSTEM_PRIVILEGES", "false")
```

Set to `true` if signing APK with system keys. Enables silent installation and automatic device owner rights acquisition.

### Library API Key
```gradle
buildConfigField("String", "LIBRARY_API_KEY", "\"changeme-8gzk321W\"")
```

API key for privileged library requests (access to IMEI/serial numbers).

### Feature Flags
```gradle
buildConfigField("Boolean", "REQUEST_SERVER_URL", "true")
buildConfigField("Boolean", "USE_ACCESSIBILITY", "false")
buildConfigField("Boolean", "ENABLE_KIOSK_WITHOUT_OVERLAYS", "false")
buildConfigField("Boolean", "SET_DEFAULT_LAUNCHER_EARLY", "false")
```

## Development Patterns

### Adding a New Push Message Type

1. Add constant to `PushMessage.java`:
   ```java
   public static final String TYPE_YOUR_ACTION = "yourAction";
   ```

2. Handle in `PushNotificationProcessor.doInBackground()`:
   ```java
   case PushMessage.TYPE_YOUR_ACTION:
       // Handle action
       break;
   ```

3. Update server-side push message generation

### Adding a New Configuration Field

1. Add field to `ServerConfig.java`:
   ```java
   @JsonProperty("yourField")
   private String yourField;
   // + getter/setter
   ```

2. Update `ConfigUpdater.updateConfiguration()` to apply the setting

3. Update MainActivity or relevant UI component to display/use the value

4. Update server API to include the new field in configuration responses

### Extending Remote Logging

1. Add new rule type to `RemoteLogConfig.java`
2. Update `LogConfigTable` schema if needed (increment `DATABASE_VERSION`)
3. Modify `RemoteLogger.log()` to handle new filtering logic
4. Update `RemoteLogWorker` if new upload strategy needed

### Creating New Device Policies

1. Add fields to `ServerConfig.java`
2. Implement policy application in `Utils.setUserRestrictions()` or `SystemUtils` methods
3. Test with device owner rights enabled
4. Update server configuration UI and API

## Common Development Tasks

### Debugging Push Notifications

```bash
# Watch MQTT logs
adb logcat | grep -i mqtt

# Watch all Headwind MDM logs
adb logcat | grep com.hmdm.launcher

# Test push message manually via server API
# or send MQTT message to device/{deviceId}
```

### Debugging Device Admin Issues

Set `DEVICE_ADMIN_DEBUG = true` in `build.gradle` to enable detailed admin receiver logging.

### Testing Configuration Updates

1. Modify configuration on server
2. Trigger update:
   - Send push notification (TYPE_CONFIG_UPDATED)
   - Wait for 15-minute periodic worker
   - Tap "Sync" in admin panel (4-tap unlock on main screen)

### Working with Local Server

Update `build.gradle`:
```gradle
buildConfigField("String", "BASE_URL", "\"http://192.168.1.100:8080\"")
buildConfigField("Boolean", "TRUST_ANY_CERTIFICATE", "true")
```

## Code Style and Conventions

- **Naming:** Java standard (PascalCase classes, camelCase methods/variables)
- **Threading:** Use WorkManager for periodic tasks, AsyncTask for one-off background operations
- **Logging:** Use `RemoteLogger.log()` for production logging, `Log.d/e()` for debug only
- **Error Handling:** Always handle MDMException when calling library methods
- **Resource Naming:** `activity_main.xml`, `fragment_setup.xml`, `item_app.xml`
- **Constants:** Define in `Const.java` for app-wide use

## Dependencies

Key libraries:
- **Retrofit 2.3.0**: HTTP client for REST API
- **Jackson 2.9.4**: JSON serialization/deserialization
- **Eclipse Paho 1.2.0**: MQTT client
- **Picasso 2.5.2**: Image loading and caching
- **AndroidX WorkManager 2.9.1**: Background task scheduling
- **ZXing 4.1.0**: QR code scanning
- **Commons IO 2.0.1**: File utilities

## Important Notes

- **Device Owner Limitation:** Many MDM features require device owner rights. Apps cannot become device owner after user setup is complete (Android 7+). Use QR provisioning or `adb` command for testing.
- **Shared User ID:** The `android:sharedUserId="com.hmdm"` attribute enables third-party apps to access MDM services via the library module.
- **Accessibility Service:** The `USE_ACCESSIBILITY` feature (app blocking) is banned by Google Play Protect in some regions. Use with caution.
- **Kiosk Mode:** Requires `SYSTEM_ALERT_WINDOW` permission to overlay other apps. Some devices may restrict this.
- **MQTT Port:** Default 31000 must be open on server firewall.
- **Request Signature:** Critical security feature. Ensure `REQUEST_SIGNATURE` matches between client and server.

## Troubleshooting

**App not receiving push notifications:**
- Check `ENABLE_PUSH` is `true`
- Verify MQTT port 31000 is accessible
- Check `PushNotificationWorker` logs for fallback polling
- Ensure device has internet connectivity

**Silent installation not working:**
- Verify device owner rights: `adb shell dpm list-owners`
- Check `SYSTEM_PRIVILEGES` flag if using system-signed APK
- Ensure app has `REQUEST_INSTALL_PACKAGES` permission

**Configuration not updating:**
- Check server signature if `CHECK_SIGNATURE = true`
- Verify `REQUEST_SIGNATURE` matches server
- Check network connectivity
- Review `GetServerConfigTask` logs

**Kiosk mode not enforcing:**
- Ensure device owner rights granted
- Check `SYSTEM_ALERT_WINDOW` permission granted
- Verify `kioskMode = true` in configuration
- Check for conflicting accessibility services
