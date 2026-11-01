package com.roman.zemzeme.mesh

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class GattClientCallbackValidationTest {

    private val expectedDescriptorUuid = UUID.fromString("00000000-0000-0000-0000-000000000029")
    private val expectedCharacteristicUuid = UUID.fromString("00000000-0000-0000-0000-00000000002a")
    private val otherUuid = UUID.fromString("00000000-0000-0000-0000-00000000002b")

    @Test
    fun acceptsCallbackOnlyFromTheTrackedGattInstance() {
        val currentGatt = Any()
        val staleGatt = Any()

        assertTrue(GattClientCallbackValidation.isCurrentConnection(currentGatt, currentGatt))
        assertFalse(GattClientCallbackValidation.isCurrentConnection(staleGatt, currentGatt))
        assertFalse(GattClientCallbackValidation.isCurrentConnection(currentGatt, null))

        assertTrue(GattClientCallbackValidation.isStaleConnection(staleGatt, currentGatt))
        assertFalse(GattClientCallbackValidation.isStaleConnection(currentGatt, currentGatt))
        assertFalse(GattClientCallbackValidation.isStaleConnection(currentGatt, null))
    }

    @Test
    fun acceptsCallbacksOnlyFromTheActiveConnectionGeneration() {
        assertTrue(GattClientCallbackValidation.isCurrentGeneration(7L, 7L))
        assertFalse(GattClientCallbackValidation.isCurrentGeneration(6L, 7L))
        assertFalse(GattClientCallbackValidation.isCurrentGeneration(7L, 8L))
        assertFalse(GattClientCallbackValidation.isCurrentGeneration(7L, null))
    }

    @Test
    fun acceptsOnlyTheExpectedCccdOnTheExpectedCharacteristic() {
        assertTrue(
            GattClientCallbackValidation.isExpectedCccdDescriptor(
                expectedDescriptorUuid,
                expectedCharacteristicUuid,
                expectedDescriptorUuid,
                expectedCharacteristicUuid
            )
        )
        assertFalse(
            GattClientCallbackValidation.isExpectedCccdDescriptor(
                otherUuid,
                expectedCharacteristicUuid,
                expectedDescriptorUuid,
                expectedCharacteristicUuid
            )
        )
        assertFalse(
            GattClientCallbackValidation.isExpectedCccdDescriptor(
                expectedDescriptorUuid,
                otherUuid,
                expectedDescriptorUuid,
                expectedCharacteristicUuid
            )
        )
    }
}
