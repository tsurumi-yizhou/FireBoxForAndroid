package com.firebox.android

import org.junit.Assert.assertEquals
import org.junit.Test

class TargetedCallbackRegistryTest {
    @Test
    fun registerAndUnregister_onlyNotifyCurrentCallback() {
        val events = linkedMapOf<String, MutableList<Boolean>>()
        val registry =
            TargetedCallbackRegistry<String, String> { callback, connected ->
                events.getOrPut(callback) { mutableListOf() }.add(connected)
            }

        registry.register("binder-A", "A")
        registry.register("binder-B", "B")
        registry.unregister("binder-B")

        assertEquals(listOf(true), events["A"])
        assertEquals(listOf(true, false), events["B"])
    }
}