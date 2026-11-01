package com.roman.zemzeme.mesh

import java.util.UUID

internal object GattClientCallbackValidation {
    fun isCurrentGeneration(callbackGeneration: Long, activeGeneration: Long?): Boolean =
        activeGeneration != null && callbackGeneration == activeGeneration

    fun isCurrentConnection(callbackGatt: Any, trackedGatt: Any?): Boolean =
        callbackGatt === trackedGatt

    fun isStaleConnection(callbackGatt: Any, trackedGatt: Any?): Boolean =
        trackedGatt != null && callbackGatt !== trackedGatt

    fun isExpectedCccdDescriptor(
        descriptorUuid: UUID,
        characteristicUuid: UUID,
        expectedDescriptorUuid: UUID,
        expectedCharacteristicUuid: UUID
    ): Boolean =
        descriptorUuid == expectedDescriptorUuid &&
            characteristicUuid == expectedCharacteristicUuid
}
