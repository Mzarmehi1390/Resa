package com.roman.zemzeme.crypto

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.roman.zemzeme.noise.NoiseEncryptionService
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Arrays

@RunWith(RobolectricTestRunner::class)
class EncryptionServiceTest {

    private lateinit var context: Context
    private lateinit var encryptionService: EncryptionService

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        encryptionService = EncryptionService(context)
    }

    @Test
    fun `test clearPersistentIdentity changes keys`() {
        // 1. Get initial keys
        val initialStaticKey = encryptionService.getStaticPublicKey()
        val initialSigningKey = encryptionService.getSigningPublicKey()
        val initialFingerprint = encryptionService.getIdentityFingerprint()

        assertNotNull("Initial static key should not be null", initialStaticKey)
        assertNotNull("Initial signing key should not be null", initialSigningKey)

        // 2. Call clearPersistentIdentity (Panic Mode)
        encryptionService.clearPersistentIdentity()

        // 3. Get keys again. 
        val afterStaticKey = encryptionService.getStaticPublicKey()
        val afterSigningKey = encryptionService.getSigningPublicKey()
        val afterFingerprint = encryptionService.getIdentityFingerprint()

        // 4. Verify keys are different (Panic Mode should clear/rotate in-memory keys)
        // Note: We use string comparison for byte arrays to be safe in assertion messages
        assertNotEquals("Static key should change after panic", 
            Arrays.toString(initialStaticKey), Arrays.toString(afterStaticKey))
        
        assertNotEquals("Signing key should change after panic", 
            Arrays.toString(initialSigningKey), Arrays.toString(afterSigningKey))

        assertNotEquals("Fingerprint should change after panic", 
            initialFingerprint, afterFingerprint)
    }
    @Test
    fun `legacy sign method produces a real Ed25519 signature`() {
        val payload = "resa-signature-regression".toByteArray()
        val signature = encryptionService.sign(payload)
        val publicKey = encryptionService.getSigningPublicKey()

        assertNotNull(publicKey)
        assertTrue("Signature must not be empty", signature.isNotEmpty())
        assertTrue(
            "Signature must verify against the local Ed25519 identity",
            encryptionService.verifyEd25519Signature(signature, payload, publicKey!!)
        )
        assertFalse(
            "Modified data must not verify",
            encryptionService.verifyEd25519Signature(signature, payload + byteArrayOf(1), publicKey)
        )
    }

    @Test
    fun `legacy verify method fails closed without pinned peer signing key`() {
        val payload = "resa-signature-regression".toByteArray()
        val signature = encryptionService.signData(payload)!!

        assertFalse(encryptionService.verify(signature, payload, "peer-with-no-key"))
    }

}
