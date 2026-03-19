# Meta Wearables DAT SDK 0.5.0 — Real API Reference

Extracted from actual AAR JARs via `javap`. Use this instead of documentation.

## Changes in 0.5.0 (from 0.4.0)

- `capturePhoto()` now returns `DatResult<PhotoData, CaptureError>` (was `kotlin.Result<PhotoData>`)
- New sealed interface `CaptureError` with typed error cases
- New `LinkState` enum on Device (replaces boolean `available`)
- 720x1280 high-resolution video streaming support
- R8 minification fixes

## Critical differences from what was coded

| What code says | Real API |
|----------------|----------|
| `Wearables.initialize(context)` sync | `Wearables.initialize(context)` is **suspend**, returns `DatResult` |
| `Wearables.checkPermissionStatus(perm)` sync | **suspend function**, returns `DatResult<PermissionStatus, PermissionError>` |
| `Wearables.startStreamSession(ctx, sel, cfg)` | Extension function: needs `import com.meta.wearable.dat.camera.startStreamSession` |
| `RegistrationState.REGISTERED` (enum) | **Sealed class**: `is RegistrationState.Registered` |
| `PermissionStatus.Granted` (enum) | **Sealed interface**: `is PermissionStatus.Granted` |
| `DatResult.getOrNull()` | Value class (inline), API: `.getOrNull()`, `.onSuccess{}`, `.onFailure{}` |
| `capturePhoto()` returns `DatResult?` | **suspend**, returns `DatResult<PhotoData, CaptureError>` |

## Wearables (com.meta.wearable.dat.core.Wearables)

```
object Wearables {
    suspend fun initialize(context: Context): DatResult<...>  // NOT sync!
    fun reset()

    val registrationState: StateFlow<RegistrationState>
    val devices: StateFlow<Set<DeviceIdentifier>>
    val devicesMetadata: Map<DeviceIdentifier, StateFlow<DeviceMetadata>>

    fun startRegistration(activity: Activity)   // sync, opens Meta AI app
    fun startUnregistration(activity: Activity)  // sync

    suspend fun checkPermissionStatus(permission: Permission): DatResult<PermissionStatus, PermissionError>

    fun getDeviceSessionState(deviceId: DeviceIdentifier): StateFlow<SessionState>
}
```

## StreamSession (com.meta.wearable.dat.camera.StreamSession)

```
interface StreamSession {
    val state: StateFlow<StreamSessionState>
    val videoStream: Flow<VideoFrame>
    suspend fun capturePhoto(): DatResult<PhotoData, CaptureError>
    fun close()
}
```

## Extension function (com.meta.wearable.dat.camera.startStreamSession)

```
// MUST import: import com.meta.wearable.dat.camera.startStreamSession
fun Wearables.startStreamSession(
    context: Context,
    deviceSelector: DeviceSelector,
    config: StreamConfiguration = StreamConfiguration()
): StreamSession
```

## RegistrationState (sealed class, NOT enum)

```
sealed class RegistrationState {
    class Available : RegistrationState()
    class Registered : RegistrationState()
    class Registering : RegistrationState()
    class Unavailable : RegistrationState()
    class Unregistering : RegistrationState()
}
```

Usage: `when (state) { is RegistrationState.Registered -> ... }`

## PermissionStatus (sealed interface)

```
sealed interface PermissionStatus {
    object Granted : PermissionStatus
    object Denied : PermissionStatus
}
```

Usage: `status is PermissionStatus.Granted`

## StreamSessionState (enum)

```
enum class StreamSessionState {
    STARTING, STARTED, STREAMING, STOPPING, STOPPED, CLOSED
}
```

## VideoFrame (data class)

```
data class VideoFrame(
    val buffer: ByteBuffer,   // I420 YUV data
    val width: Int,
    val height: Int,
    val presentationTimeUs: Long
)
```

## PhotoData (sealed interface)

```
sealed interface PhotoData {
    data class Bitmap(val bitmap: android.graphics.Bitmap) : PhotoData
    data class HEIC(val data: ByteBuffer) : PhotoData
}
```

## CaptureError (sealed interface) — NEW in 0.5.0

```
sealed interface CaptureError : DatError {
    object DeviceDisconnected : CaptureError
    object NotStreaming : CaptureError
    object CaptureInProgress : CaptureError
    object CaptureFailed : CaptureError
}
```

## LinkState (enum) — NEW in 0.5.0

```
enum class LinkState { CONNECTING, CONNECTED, DISCONNECTED }
```

Replaces boolean `Device.available`. Access via `Device.linkState`.

## StreamConfiguration (data class)

```
data class StreamConfiguration(
    val videoQuality: VideoQuality = ...,
    val frameRate: Int = ...
)
```

## VideoQuality (enum)

```
enum class VideoQuality { HIGH, MEDIUM, LOW }
```

## Permission (enum)

```
enum class Permission { CAMERA }
```

## DatResult<T, E> (value/inline class)

```
value class DatResult<T, E : DatError> {
    fun getOrNull(): T?
    fun getOrThrow(): T
    fun isSuccess(): Boolean
    fun isFailure(): Boolean
    fun errorOrNull(): E?
    fun onSuccess(action: (T) -> Unit): DatResult
    fun onFailure(action: (E, Throwable?) -> Unit): DatResult
    fun <R> map(transform: (T) -> R): DatResult
}
```

## Required imports for GlassesManager

```kotlin
import com.meta.wearable.dat.camera.StreamSession
import com.meta.wearable.dat.camera.startStreamSession  // extension function!
import com.meta.wearable.dat.camera.types.*
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.types.DatResult
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.RegistrationState
```

## Build requirements

- `minSdk = 29` (mwdat-camera requires Android 10+)
- GitHub token with `read:packages` scope in `local.properties`:
  ```
  github_token=ghp_xxx
  sdk.dir=C:/Users/.../Android/Sdk
  ```
