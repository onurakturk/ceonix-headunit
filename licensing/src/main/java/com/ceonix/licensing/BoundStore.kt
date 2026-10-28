package com.ceonix.licensing

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.annotation.TargetApi
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * What survives between launches: Google's signed answer, exactly as it arrived, plus the
 * installer that was in place when it was accepted.
 *
 * Storing the signed response rather than a "licensed" flag is the point. Editing the file cannot
 * forge a licence — the signature is checked again on every read — and the encryption below ties
 * the file to this device, so copying it elsewhere yields nothing readable.
 */
internal class StoredLicense(
    val signedData: String,
    val signature: String,
    val installer: String,
)

/**
 * Keeps [StoredLicense] encrypted with an AES-256-GCM key that lives in the Android Keystore
 * (TEE-backed where the hardware has one). The key never leaves the device and is deleted with the
 * app, so a file copied to another phone, or restored after a reinstall, cannot be decrypted.
 *
 * No user authentication on the key: the head unit has no lock screen worth speaking of and must
 * open unattended. No StrongBox either — it is absent on most head-unit-class hardware and slow
 * where present, for a key that is used a handful of times per launch.
 *
 * Below API 23 there is no Keystore AES. The record is written unencrypted there (still
 * signature-checked); a device that has Keystore AES refuses such a file, so it cannot be planted
 * on a modern device to bypass the binding.
 *
 * Failures never escape: any problem reads as "nothing stored".
 */
internal class BoundStore(
    context: Context,
    private val keyAlias: String,
    fileName: String,
    private val log: (String) -> Unit,
) {
    private val file = File(noBackupDir(context), fileName)
    private val aad = context.packageName.toByteArray(Charsets.UTF_8)

    sealed class Read {
        object Missing : Read()
        /** Present but not decryptable here: other device, key gone, Keystore fault, or edited. */
        object Unreadable : Read()
        class Ok(val record: StoredLicense) : Read()
    }

    fun read(): Read {
        if (!file.exists()) return Read.Missing
        return try {
            val bytes = file.readBytes()
            val input = DataInputStream(ByteArrayInputStream(bytes))
            if (input.readInt() != MAGIC) return Read.Unreadable.also { log("store: bad header") }
            val plain = when (val mode = input.readByte().toInt()) {
                MODE_BOUND -> {
                    if (Build.VERSION.SDK_INT < 23) return Read.Unreadable
                    val iv = ByteArray(input.readUnsignedByte()).also { input.readFully(it) }
                    val cipherText = input.readBytes()
                    decrypt(iv, cipherText) ?: return Read.Unreadable
                }
                MODE_PLAIN -> {
                    if (Build.VERSION.SDK_INT >= 23) {
                        log("store: refusing an unbound record on a device with Keystore AES")
                        return Read.Unreadable
                    }
                    input.readBytes()
                }
                else -> return Read.Unreadable.also { log("store: unknown mode $mode") }
            }
            Read.Ok(deserialize(plain))
        } catch (e: Exception) {
            log("store: read failed: ${e.javaClass.simpleName}: ${e.message}")
            Read.Unreadable
        }
    }

    fun write(record: StoredLicense): Boolean {
        return try {
            val plain = serialize(record)
            val out = ByteArrayOutputStream()
            DataOutputStream(out).use { data ->
                data.writeInt(MAGIC)
                if (Build.VERSION.SDK_INT >= 23) {
                    val (iv, cipherText) = encrypt(plain)
                    data.writeByte(MODE_BOUND)
                    data.writeByte(iv.size)
                    data.write(iv)
                    data.write(cipherText)
                } else {
                    data.writeByte(MODE_PLAIN)
                    data.write(plain)
                }
            }
            // Write-then-rename, so a crash mid-write cannot leave a half file behind.
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeBytes(out.toByteArray())
            if (!tmp.renameTo(file)) {
                file.delete()
                if (!tmp.renameTo(file)) error("rename failed")
            }
            true
        } catch (e: Exception) {
            log("store: write failed: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    fun delete() {
        file.delete()
    }

    @TargetApi(23)
    private fun encrypt(plain: ByteArray): Pair<ByteArray, ByteArray> {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, existingKey() ?: createKey())
        cipher.updateAAD(aad)
        return cipher.iv to cipher.doFinal(plain)
    }

    @TargetApi(23)
    private fun decrypt(iv: ByteArray, cipherText: ByteArray): ByteArray? {
        val key = existingKey() ?: return null.also { log("store: binding key missing") }
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.updateAAD(aad)
            cipher.doFinal(cipherText)
        } catch (e: Exception) {
            log("store: decrypt failed: ${e.javaClass.simpleName}")
            null
        }
    }

    @TargetApi(23)
    private fun existingKey(): SecretKey? {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        return keyStore.getKey(keyAlias, null) as? SecretKey
    }

    @TargetApi(23)
    private fun createKey(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        log("store: created the device binding key")
        return generator.generateKey()
    }

    private fun serialize(record: StoredLicense): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { data ->
            data.writeByte(RECORD_VERSION)
            data.writeUTF(record.signedData)
            data.writeUTF(record.signature)
            data.writeUTF(record.installer)
        }
        return out.toByteArray()
    }

    private fun deserialize(bytes: ByteArray): StoredLicense {
        val data = DataInputStream(ByteArrayInputStream(bytes))
        val version = data.readByte().toInt()
        require(version == RECORD_VERSION) { "record version $version" }
        return StoredLicense(
            signedData = data.readUTF(),
            signature = data.readUTF(),
            installer = data.readUTF(),
        )
    }

    private fun DataInputStream.readBytes(): ByteArray {
        val out = ByteArrayOutputStream()
        copyTo(out)
        return out.toByteArray()
    }

    private companion object {
        const val MAGIC = 0x43584C31 // "CXL1"
        const val MODE_PLAIN = 0
        const val MODE_BOUND = 1
        const val RECORD_VERSION = 1
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128

        fun noBackupDir(context: Context): File =
            if (Build.VERSION.SDK_INT >= 21) context.noBackupFilesDir else context.filesDir
    }
}
