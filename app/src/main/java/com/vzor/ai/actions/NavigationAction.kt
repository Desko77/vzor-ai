package com.vzor.ai.actions

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

class NavigationAction(private val context: Context) {

    companion object {
        private const val YANDEX_MAPS_PACKAGE = "ru.yandex.yandexmaps"
        private const val GOOGLE_MAPS_PACKAGE = "com.google.android.apps.maps"
    }

    fun navigate(destination: String): ActionResult {
        return try {
            val encodedDestination = Uri.encode(destination)

            // Try Yandex Maps first (preferred for Russian market)
            if (isAppInstalled(YANDEX_MAPS_PACKAGE)) {
                val yandexIntent = Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse("yandexmaps://maps.yandex.ru/?text=$encodedDestination&rtt=auto")
                    setPackage(YANDEX_MAPS_PACKAGE)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }

                if (context.packageManager.resolveActivity(yandexIntent, 0) != null) {
                    context.startActivity(yandexIntent)
                    return ActionResult(
                        success = true,
                        message = "Навигация до \"$destination\" через Яндекс Карты",
                        requiresConfirmation = true
                    )
                }
            }

            // Fallback to Google Maps
            if (isAppInstalled(GOOGLE_MAPS_PACKAGE)) {
                val geoIntent = Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse("geo:0,0?q=$encodedDestination")
                    setPackage(GOOGLE_MAPS_PACKAGE)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }

                if (context.packageManager.resolveActivity(geoIntent, 0) != null) {
                    context.startActivity(geoIntent)
                    return ActionResult(
                        success = true,
                        message = "Навигация до \"$destination\" через Google Maps",
                        requiresConfirmation = true
                    )
                }
            }

            // Last fallback: open in browser
            val browserIntent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("https://maps.yandex.ru/?text=$encodedDestination")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(browserIntent)
            ActionResult(
                success = true,
                message = "Навигация до \"$destination\" в браузере",
                requiresConfirmation = true
            )
        } catch (e: Exception) {
            ActionResult(false, "Не удалось построить маршрут: ${e.message}")
        }
    }

    fun navigateToAddress(address: String): ActionResult {
        return try {
            val encodedAddress = Uri.encode(address)

            // Try Yandex Maps first
            if (isAppInstalled(YANDEX_MAPS_PACKAGE)) {
                val yandexIntent = Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse("yandexmaps://maps.yandex.ru/?text=$encodedAddress&rtt=auto")
                    setPackage(YANDEX_MAPS_PACKAGE)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }

                if (context.packageManager.resolveActivity(yandexIntent, 0) != null) {
                    context.startActivity(yandexIntent)
                    return ActionResult(
                        success = true,
                        message = "Навигация по адресу \"$address\" через Яндекс Карты",
                        requiresConfirmation = true
                    )
                }
            }

            // Fallback to Google Maps with address
            if (isAppInstalled(GOOGLE_MAPS_PACKAGE)) {
                val googleIntent = Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse("google.navigation:q=$encodedAddress")
                    setPackage(GOOGLE_MAPS_PACKAGE)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }

                if (context.packageManager.resolveActivity(googleIntent, 0) != null) {
                    context.startActivity(googleIntent)
                    return ActionResult(
                        success = true,
                        message = "Навигация по адресу \"$address\" через Google Maps",
                        requiresConfirmation = true
                    )
                }
            }

            // Last fallback: open geo URI which any maps app can handle
            val geoIntent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("geo:0,0?q=$encodedAddress")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(geoIntent)
            ActionResult(
                success = true,
                message = "Навигация по адресу \"$address\"",
                requiresConfirmation = true
            )
        } catch (e: Exception) {
            ActionResult(false, "Не удалось построить маршрут по адресу: ${e.message}")
        }
    }

    private fun isAppInstalled(packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }
}
