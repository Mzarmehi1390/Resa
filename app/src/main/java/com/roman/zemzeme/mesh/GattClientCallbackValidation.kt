package com.roman.zemzeme.mesh

import java.util.UUID

internal object GattClientCallbackValidation {
    fun isCurrentConnection(callbackGatt: Any, trackedGatt: Any?): Boolean =
        callbackGatt === trackedGatt

    fun isExpectedCccdDescriptor(
        descriptorUuid: UUID,
        characteristicUuid: UUID,
        expectedDescriptorUuid: UUID,
        expectedCharacteristicUuid: UUID
    ): Boolean =
        descriptorUuid == expectedDescriptorUuid &&
            characteristicUuid == expectedCharacteristicUuid
}
