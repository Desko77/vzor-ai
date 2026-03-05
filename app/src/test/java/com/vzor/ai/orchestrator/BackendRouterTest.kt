package com.vzor.ai.orchestrator

import com.vzor.ai.data.local.PreferencesManager
import com.vzor.ai.domain.model.NetworkType
import com.vzor.ai.domain.model.RoutingContext
import com.vzor.ai.domain.model.RoutingDecision
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class BackendRouterTest {

    private lateinit var router: BackendRouter

    @Before
    fun setUp() {
        router = BackendRouter(mockk<PreferencesManager>(relaxed = true))
    }

    // --- Offline ---

    @Test
    fun `offline network routes to OFFLINE`() {
        val ctx = routingContext(network = NetworkType.OFFLINE)
        assertEquals(RoutingDecision.OFFLINE, router.route(ctx))
    }

    @Test
    fun `offline takes priority over low battery`() {
        val ctx = routingContext(network = NetworkType.OFFLINE, battery = 5)
        assertEquals(RoutingDecision.OFFLINE, router.route(ctx))
    }

    // --- Low battery ---

    @Test
    fun `low battery on wifi routes to CLOUD`() {
        val ctx = routingContext(network = NetworkType.WIFI, battery = 15, x2Available = true)
        assertEquals(RoutingDecision.CLOUD, router.route(ctx))
    }

    @Test
    fun `low battery on LTE routes to CLOUD`() {
        val ctx = routingContext(network = NetworkType.LTE, battery = 10)
        assertEquals(RoutingDecision.CLOUD, router.route(ctx))
    }

    @Test
    fun `battery exactly 20 is not low`() {
        val ctx = routingContext(network = NetworkType.WIFI, battery = 20, x2Available = true, x2Queue = 100)
        assertEquals(RoutingDecision.LOCAL_AI, router.route(ctx))
    }

    @Test
    fun `battery 19 is low`() {
        val ctx = routingContext(network = NetworkType.WIFI, battery = 19, x2Available = true, x2Queue = 100)
        assertEquals(RoutingDecision.CLOUD, router.route(ctx))
    }

    // --- Wi-Fi without X2 ---

    @Test
    fun `wifi without X2 routes to CLOUD`() {
        val ctx = routingContext(network = NetworkType.WIFI, battery = 80, x2Available = false)
        assertEquals(RoutingDecision.CLOUD, router.route(ctx))
    }

    // --- Wi-Fi with X2, queue ok ---

    @Test
    fun `wifi with X2 and low queue routes to LOCAL_AI`() {
        val ctx = routingContext(network = NetworkType.WIFI, battery = 80, x2Available = true, x2Queue = 200)
        assertEquals(RoutingDecision.LOCAL_AI, router.route(ctx))
    }

    @Test
    fun `wifi with X2 and queue 799 routes to LOCAL_AI`() {
        val ctx = routingContext(network = NetworkType.WIFI, battery = 80, x2Available = true, x2Queue = 799)
        assertEquals(RoutingDecision.LOCAL_AI, router.route(ctx))
    }

    // --- Wi-Fi with X2, queue overloaded ---

    @Test
    fun `wifi with X2 and queue exactly 800 routes to CLOUD`() {
        val ctx = routingContext(network = NetworkType.WIFI, battery = 80, x2Available = true, x2Queue = 800)
        assertEquals(RoutingDecision.CLOUD, router.route(ctx))
    }

    @Test
    fun `wifi with X2 and high queue routes to CLOUD`() {
        val ctx = routingContext(network = NetworkType.WIFI, battery = 80, x2Available = true, x2Queue = 2000)
        assertEquals(RoutingDecision.CLOUD, router.route(ctx))
    }

    // --- LTE ---

    @Test
    fun `LTE routes to CLOUD`() {
        val ctx = routingContext(network = NetworkType.LTE, battery = 80)
        assertEquals(RoutingDecision.CLOUD, router.route(ctx))
    }

    // --- Helper ---

    private fun routingContext(
        network: NetworkType = NetworkType.WIFI,
        battery: Int = 80,
        x2Available: Boolean = false,
        x2Queue: Long = 0
    ) = RoutingContext(
        networkType = network,
        batteryLevel = battery,
        x2Available = x2Available,
        x2QueueWaitMs = x2Queue
    )
}
