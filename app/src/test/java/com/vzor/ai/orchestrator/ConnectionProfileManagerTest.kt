package com.vzor.ai.orchestrator

import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionProfileManagerTest {

    @Test
    fun `HOME_WIFI profile has correct label`() {
        assertEquals("Дом (Wi-Fi)", ConnectionProfile.HOME_WIFI.label)
    }

    @Test
    fun `OTHER_WIFI profile has correct label`() {
        assertEquals("Wi-Fi", ConnectionProfile.OTHER_WIFI.label)
    }

    @Test
    fun `MOBILE profile has correct label`() {
        assertEquals("Мобильная сеть", ConnectionProfile.MOBILE.label)
    }

    @Test
    fun `OFFLINE profile has correct label`() {
        assertEquals("Офлайн", ConnectionProfile.OFFLINE.label)
    }

    @Test
    fun `all profiles are unique`() {
        val profiles = ConnectionProfile.entries
        assertEquals(4, profiles.size)
        assertEquals(profiles.size, profiles.toSet().size)
    }

    @Test
    fun `profile enum values exist`() {
        // Проверяем что valueOf работает для всех профилей
        assertEquals(ConnectionProfile.HOME_WIFI, ConnectionProfile.valueOf("HOME_WIFI"))
        assertEquals(ConnectionProfile.OTHER_WIFI, ConnectionProfile.valueOf("OTHER_WIFI"))
        assertEquals(ConnectionProfile.MOBILE, ConnectionProfile.valueOf("MOBILE"))
        assertEquals(ConnectionProfile.OFFLINE, ConnectionProfile.valueOf("OFFLINE"))
    }
}
