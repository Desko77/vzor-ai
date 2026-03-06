# Meta Wearables DAT SDK — справочник интеграции

## Версия SDK

- **Артефакты:** `com.meta.wearable:mwdat-core:0.4.0`, `mwdat-camera:0.4.0`, `mwdat-mockdevice:0.4.0`
- **Maven:** `https://maven.pkg.github.com/facebook/meta-wearables-dat-android`
- **Auth:** GitHub PAT (classic) с scope `read:packages`
- **Docs:** https://wearables.developer.meta.com/docs/build-integration-android/
- **API Reference:** https://wearables.developer.meta.com/docs/reference/android/dat/0.3

## Ключевые классы и импорты

```kotlin
// Core
import com.meta.wearable.dat.core.Wearables              // Главный entry point (singleton)
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector  // Автоселектор устройства
import com.meta.wearable.dat.core.types.Permission              // Permission.CAMERA
import com.meta.wearable.dat.core.types.PermissionStatus        // Sealed: Granted / Denied
import com.meta.wearable.dat.core.types.RegistrationState       // Sealed: Unavailable/Available/Registering/Registered/Unregistering
import com.meta.wearable.dat.core.types.DatResult               // Result<T, E> type (since 0.3.0)

// Camera
import com.meta.wearable.dat.camera.StreamSession                // Сессия стриминга
import com.meta.wearable.dat.camera.types.StreamConfiguration    // quality + fps
import com.meta.wearable.dat.camera.types.StreamSessionState     // STREAMING / STOPPED
import com.meta.wearable.dat.camera.types.VideoFrame             // Кадр (I420 YUV в ByteBuffer, width, height, presentationTimeUs)
import com.meta.wearable.dat.camera.types.VideoQuality           // LOW / MEDIUM / HIGH
import com.meta.wearable.dat.camera.types.PhotoData              // Результат capturePhoto()
```

## Жизненный цикл

### 1. Инициализация (Application.onCreate)
```kotlin
Wearables.initialize(context)
```
> Должен быть вызван ДО любых обращений к SDK. Вызов до init → WearablesError.NOT_INITIALIZED.

### 2. Регистрация устройства
```kotlin
// ⚠️ DAT SDK 0.4.0: требуется Activity, не Context!
Wearables.startRegistration(activity)

// Наблюдение за состоянием
Wearables.registrationState.collectLatest { state ->
    // Sealed class: Unavailable, Available, Registering, Registered, Unregistering
}

// Отвязать устройство
Wearables.startUnregistration(activity)
```

### 3. Проверка разрешений
```kotlin
// checkPermissionStatus возвращает DatResult<PermissionStatus, PermissionError>
val result = Wearables.checkPermissionStatus(Permission.CAMERA)
val status = result.getOrNull()
if (status == PermissionStatus.Granted) {  // Sealed class, не enum!
    // Камера доступна
} else {
    // Запросить через Wearables.RequestPermissionContract()
    // requestPermissionLauncher.launch(Permission.CAMERA)
}
```

### 4. Стриминг камеры
```kotlin
val config = StreamConfiguration(
    videoQuality = VideoQuality.MEDIUM,
    frameRate = 24
)

val session: StreamSession = Wearables.startStreamSession(
    context,
    AutoDeviceSelector(),
    config
)

// Состояние стрима
session.state.collectLatest { state: StreamSessionState ->
    // STREAMING, STOPPED, etc.
}

// Видеокадры
session.videoStream.collectLatest { frame: VideoFrame ->
    val buffer = frame.buffer  // ByteBuffer с raw bytes
    val width = frame.width
    val height = frame.height
}
```

### 5. Фото
```kotlin
// capturePhoto() возвращает Result<PhotoData>
session.capturePhoto()
    ?.onSuccess { photoData ->
        when (photoData) {
            is PhotoData.Bitmap -> {
                // Прямой Bitmap → compress(JPEG, 90, stream)
                photoData.bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
            is PhotoData.HEIC -> {
                // Raw HEIC bytes в ByteBuffer
                val bytes = ByteArray(photoData.data.remaining())
                photoData.data.get(bytes)
                // Decode через BitmapFactory или использовать напрямую
            }
        }
    }
    ?.onFailure { error -> Log.e(TAG, "Photo capture failed: $error") }
```

### 6. Закрытие
```kotlin
session.close()
```

## AndroidManifest

```xml
<!-- APPLICATION_ID: 0 = Developer Mode, иначе ID от Wearables Developer Center -->
<meta-data
    android:name="com.meta.wearable.mwdat.APPLICATION_ID"
    android:value="0" />

<!-- Callback URI scheme для регистрации через Meta AI app -->
<intent-filter>
    <action android:name="android.intent.action.VIEW" />
    <category android:name="android.intent.category.DEFAULT" />
    <category android:name="android.intent.category.BROWSABLE" />
    <data android:scheme="vzor-ai" android:host="wearables-callback" />
</intent-filter>
```

## VideoFrame обработка (I420 → JPEG)

**ВАЖНО:** `VideoFrame.buffer` содержит I420 YUV данные, НЕ JPEG!

```kotlin
// 1. Извлечь I420 bytes из VideoFrame
val buffer = frame.buffer
val i420Bytes = ByteArray(buffer.remaining())
buffer.get(i420Bytes)
buffer.position(originalPosition) // restore для переиспользования SDK

// 2. Конвертировать I420 → NV21 (Android формат)
// I420: [Y][U][V] (planar)
// NV21: [Y][VU interleaved] (semi-planar)
val nv21 = convertI420toNV21(i420Bytes, frame.width, frame.height)

// 3. NV21 → JPEG через YuvImage
val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
val out = ByteArrayOutputStream()
yuvImage.compressToJpeg(Rect(0, 0, width, height), 50, out) // 50% для стриминга
val jpegBytes = out.toByteArray()
```

## Ограничения BT Classic

- Макс. разрешение: 720p (автоматически снижается при перегрузке BW)
- Макс. fps: 30 (автоматический ladder: сначала снижает resolution, потом fps)
- Минимальный fps: 15 (гарантия SDK)
- Рекомендуемые настройки: MEDIUM quality, 24 fps

## Mock Device Kit

- `mwdat-mockdevice` — тестовый артефакт для эмуляции очков
- Позволяет тестировать media handling без реального устройства
- Конфигурируется с sample media files (видео/фото)
- Docs: https://wearables.developer.meta.com/docs/mock-device-kit

## Текущая интеграция в Vzor

### Реализовано (Stage 8)
- `VzorApp.onCreate()` → `Wearables.initialize()`
- `GlassesManager.initializeDatSdk()` → инициализация + наблюдение registrationState
- `GlassesManager.connect()` → DAT registration + BT HFP для аудио
- `GlassesManager.capturePhoto()` → StreamSession.capturePhoto() → JPEG bytes
- `GlassesManager.startCameraStream()` → StreamSession + videoStream collection
- `CameraStreamHandler` → rate-limited кадры через FrameSampler
- Manifest: APPLICATION_ID + callback URI scheme

### Архитектурные решения
1. **DAT SDK для камеры, BT HFP для аудио** — DAT SDK не предоставляет аудио API, используем системный BT HFP
2. **Временная стрим-сессия для одиночных фото** — если нет активного стрима, создаём сессию с fps=1, HIGH quality
3. **Автоматический device selector** — AutoDeviceSelector() для простоты, без ручного выбора устройства
4. **JPEG 90%** — баланс качества/размера для photo capture
5. **Frame rate 24fps** — безопасный дефолт, не перегружает BT bandwidth
