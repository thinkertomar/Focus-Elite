package com.focusguardpro.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

class PorterBridgeTest {

    @Test
    fun testPorterBridgeGracefulFallbackWhenNotRunning() {
        // When running in unit test / device without Porter daemon
        val isAvailable = PorterBridge.isAvailable()
        // It must not throw and should safely return false or true
        assertNotNull(isAvailable)

        val isEnhanced = PorterBridge.isEnhancedModeActive()
        assertFalse("Without Porter daemon, enhanced mode must be false", isEnhanced)

        // Force stop package should return false gracefully without crashing
        val result = PorterBridge.forceStopPackage("com.test.distraction")
        assertFalse("Should return false when Porter is absent", result)

        val suspendResult = PorterBridge.suspendPackage("com.test.distraction")
        assertFalse("Should return false when Porter is absent", suspendResult)

        val unsuspendResult = PorterBridge.unsuspendPackage("com.test.distraction")
        assertFalse("Should return false when Porter is absent", unsuspendResult)
    }
}
