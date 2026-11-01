package com.roman.zemzeme.mesh

import android.bluetooth.BluetoothGatt
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID

class GattCharacteristicWriteValidationTest {

    private val expectedUuid = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val otherUuid = UUID.fromString("00000000-0000-0000-0000-000000000002")

    @Test
    fun acceptsOnlyExpectedCharacteristicAtZeroOffsetWithoutPrepareWrite() {
        assertEquals(
            BluetoothGatt.GATT_SUCCESS,
            validateGattCharacteristicWrite(expectedUuid, expectedUuid, preparedWrite = false, offset = 0)
        )
    }

    @Test
    fun rejectsUnknownCharacteristic() {
        assertEquals(
            BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED,
            validateGattCharacteristicWrite(otherUuid, expectedUuid, preparedWrite = false, offset = 0)
        )
    }

    @Test
    fun rejectsPreparedWritesBecausePacketWritesAreAtomic() {
        assertEquals(
            BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED,
            validateGattCharacteristicWrite(expectedUuid, expectedUuid, preparedWrite = true, offset = 0)
        )
    }

    @Test
    fun rejectsNonZeroAndNegativeOffsets() {
        assertEquals(
            BluetoothGatt.GATT_INVALID_OFFSET,
            validateGattCharacteristicWrite(expectedUuid, expectedUuid, preparedWrite = false, offset = 1)
        )
        assertEquals(
            BluetoothGatt.GATT_INVALID_OFFSET,
            validateGattCharacteristicWrite(expectedUuid, expectedUuid, preparedWrite = false, offset = -1)
        )
    }
}
