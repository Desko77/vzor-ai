package com.vzor.ai.orchestrator

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import com.vzor.ai.data.local.PreferencesManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Профиль подключения — определяет сетевое окружение и рекомендуемый бэкенд.
 */
enum class ConnectionProfile(val label: String) {
    /** Домашняя Wi-Fi — доступен локальный AI Max сервер. */
    HOME_WIFI("Дом (Wi-Fi)"),

    /** Рабочая/публичная Wi-Fi — только облако. */
    OTHER_WIFI("Wi-Fi"),

    /** Мобильная сеть LTE/5G — облако. */
    MOBILE("Мобильная сеть"),

    /** Нет подключения — on-device модель. */
    OFFLINE("Офлайн")
}

/**
 * Автоматически определяет текущий профиль подключения на основе
 * типа сети и SSID Wi-Fi. BackendRouter использует профиль для
 * маршрутизации запросов к оптимальному AI бэкенду.
 *
 * Конфигурация домашнего SSID хранится в PreferencesManager.
 */
@Singleton
class ConnectionProfileManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: PreferencesManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _currentProfile = MutableStateFlow(ConnectionProfile.OFFLINE)

    /** Текущий профиль подключения (обновляется автоматически). */
    val currentProfile: StateFlow<ConnectionProfile> = _currentProfile.asStateFlow()

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            scope.launch { updateProfile() }
        }

        override fun onLost(network: Network) {
            scope.launch { updateProfile() }
        }

        override fun onCapabilitiesChanged(
            network: Network,
            capabilities: NetworkCapabilities
        ) {
            scope.launch { updateProfile() }
        }
    }

    /**
     * Начинает мониторинг сетевых изменений.
     * Вызывается при старте приложения.
     */
    fun startMonitoring() {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(request, networkCallback)
        scope.launch { updateProfile() }
    }

    /**
     * Останавливает мониторинг сетевых изменений.
     */
    fun stopMonitoring() {
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (_: IllegalArgumentException) {
            // Callback не был зарегистрирован
        }
    }

    /**
     * Принудительно обновляет профиль подключения.
     */
    suspend fun updateProfile() {
        _currentProfile.value = detectCurrentProfile()
    }

    private suspend fun detectCurrentProfile(): ConnectionProfile {
        val activeNetwork = connectivityManager.activeNetwork
            ?: return ConnectionProfile.OFFLINE

        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
            ?: return ConnectionProfile.OFFLINE

        val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        if (!hasInternet) return ConnectionProfile.OFFLINE

        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                val currentSsid = getCurrentSsid()
                val homeSsid = getHomeSsid()

                if (currentSsid != null && homeSsid.isNotBlank() &&
                    currentSsid.equals(homeSsid, ignoreCase = true)
                ) {
                    ConnectionProfile.HOME_WIFI
                } else {
                    ConnectionProfile.OTHER_WIFI
                }
            }
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                ConnectionProfile.MOBILE
            }
            else -> ConnectionProfile.OFFLINE
        }
    }

    @Suppress("DEPRECATION")
    private fun getCurrentSsid(): String? {
        return try {
            val wifiManager = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val info = wifiManager?.connectionInfo
            info?.ssid?.removePrefix("\"")?.removeSuffix("\"")
        } catch (_: SecurityException) {
            null
        }
    }

    private suspend fun getHomeSsid(): String {
        return prefs.homeSsid.first()
    }
}
