package com.welockbridge.sdk.protocol

import android.util.Log
import java.nio.ByteBuffer
import java.util.Calendar
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * G-Series Protocol Handler - Based on WORKING POC (DigitalLockPacketCodec.kt)
 *
 * This is a direct port of the working implementation.
 * DO NOT MODIFY without testing against real device.
 *
 * Encrypted Frame Structure:
 * [Header 0xF11F][CommAddr 0xFFEE][Command 2B][Length 2B][ENCRYPTED][Checksum 1B][Tail 0xF22F]
 *
 * ENCRYPTED contains: [CRC-16 2B][Serial 6B][Random 4B][Content NB]
 *
 * Response Frame Structure:
 * [Header 0xF33F][CommAddr 2B][Command 2B][Length 2B][Content][Checksum 1B][Tail 0xF44F]
 */
internal object GSeriesProtocol {
  
  private const val TAG = "WeLockBridge.Protocol"
  
  // Frame delimiters (from working POC)
  private const val FRAME_HEADER = 0xF11F
  private const val FRAME_TAIL = 0xF22F
  
  // Communication addresses
  private const val COMM_ADDR_PLAIN = 0xFFFF
  private const val COMM_ADDR_ENCRYPTED = 0xFFEE
  
  // Response frame delimiters
  private const val RESPONSE_HEADER = 0xF33F
  private const val RESPONSE_TAIL = 0xF44F
  
  // Commands
  const val CMD_SET_PARAMS = 0x0310
  const val CMD_QUERY_PARAMS = 0x0312
  
  // Parameters
  const val PARAM_LOCK_STATE = 0x30
  const val PARAM_SEAL_STATE = 0x24
  const val PARAM_BATTERY = 0x94
  const val PARAM_PASSWORD = 0x26
  
  // Lock state values (from working POC)
  const val STATE_LOCKED_0x31 = 0x31
  const val STATE_LOCKED_0x01 = 0x01
  const val STATE_UNLOCKED_0x30 = 0x30
  const val STATE_UNLOCKED_0x00 = 0x00
  
  const val SEAL_VALUE = 0x01
  const val UNSEAL_VALUE = 0x00
  
  private var lastSerialNumber: Long = 0L
  
  /**
   * Build encrypted frame - EXACT COPY from working DigitalLockPacketCodec.kt
   */
  fun buildEncryptedFrame(commandId: Int, content: ByteArray, encryptionKey: ByteArray): ByteArray {
    Log.d(TAG, "")
    Log.d(TAG, "===========================================")
    Log.d(TAG, "BUILDING ENCRYPTED FRAME")
    Log.d(TAG, "===========================================")
    Log.d(TAG, "Command: 0x${commandId.toString(16)}")
    Log.d(TAG, "Content: ${content.size} bytes")
    Log.d(TAG, "Content hex: ${content.toHexString()}")
    
    // Step 1: Generate incremental serial number (6 bytes, timestamp-based)
    val serialNumber = generateIncrementalSerialNumber()
    Log.d(TAG, "Serial #: ${serialNumber.toHexString()}")
    
    // Step 2: Generate random number (4 bytes)
    val randomNumber = generateRandomNumber()
    Log.d(TAG, "Random #: ${randomNumber.toHexString()}")
    
    // Step 3: Build plaintext to be encrypted: [CRC][Serial][Random][Content]
    val plaintextToEncrypt = ByteBuffer.allocate(2 + 6 + 4 + content.size).apply {
      // Placeholder for CRC (will calculate after)
      put(0x00.toByte())
      put(0x00.toByte())
      // Incremental serial number
      put(serialNumber)
      // Random number
      put(randomNumber)
      // Content
      put(content)
    }.array()
    
    // Step 4: Calculate CRC-16 for content AFTER random number
    val crcInput = ByteArray(content.size)
    System.arraycopy(plaintextToEncrypt, 12, crcInput, 0, content.size)
    val crc16 = calculateCRC16(crcInput)
    
    // Update CRC in plaintext
    plaintextToEncrypt[0] = (crc16 shr 8).toByte()
    plaintextToEncrypt[1] = (crc16 and 0xFF).toByte()
    
    Log.d(TAG, "CRC-16: 0x${crc16.toString(16).padStart(4, '0')}")
    Log.d(TAG, "Plaintext to encrypt: ${plaintextToEncrypt.toHexString()}")
    Log.d(TAG, "Plaintext length: ${plaintextToEncrypt.size} bytes")
    
    // Step 5: Encrypt using AES-128 ECB with zero padding
    val encryptedContent = encryptAES128ECB(plaintextToEncrypt, encryptionKey)
    Log.d(TAG, "Encrypted length: ${encryptedContent.size} bytes (padded to 16)")
    Log.d(TAG, "Encrypted hex: ${encryptedContent.toHexString()}")
    
    // Step 6: Build final frame
    // Length = original plaintext length (before padding)
    val length = plaintextToEncrypt.size
    
    val frameWithoutChecksum = ByteBuffer.allocate(2 + 2 + 2 + 2 + encryptedContent.size).apply {
      // Header
      putShort(FRAME_HEADER.toShort())
      // Communication address (0xFFEE for encrypted)
      putShort(COMM_ADDR_ENCRYPTED.toShort())
      // Command
      putShort(commandId.toShort())
      // Length (original plaintext length)
      putShort(length.toShort())
      // Encrypted content
      put(encryptedContent)
    }.array()
    
    // Step 7: Calculate checksum (excludes header and tail)
    val checksumInput = ByteArray(frameWithoutChecksum.size - 2)
    System.arraycopy(frameWithoutChecksum, 2, checksumInput, 0, checksumInput.size)
    val checksum = calculateSunCheck(checksumInput)
    
    // Step 8: Build complete frame
    val completeFrame = ByteBuffer.allocate(frameWithoutChecksum.size + 1 + 2).apply {
      put(frameWithoutChecksum)
      put(checksum)
      putShort(FRAME_TAIL.toShort())
    }.array()
    
    Log.d(TAG, "Checksum: 0x${(checksum.toInt() and 0xFF).toString(16).padStart(2, '0')}")
    Log.d(TAG, "Final frame: ${completeFrame.size} bytes")
    Log.d(TAG, "Frame hex: ${completeFrame.toHexString()}")
    Log.d(TAG, "===========================================")
    Log.d(TAG, "")
    
    return completeFrame
  }
  
  /**
   * Build plain (non-encrypted) frame
   */
  fun buildPlainFrame(commandId: Int, content: ByteArray): ByteArray {
    Log.d(TAG, "Building plain frame for cmd 0x${commandId.toString(16)}")
    
    val frameWithoutChecksum = ByteBuffer.allocate(2 + 2 + 2 + 2 + content.size).apply {
      putShort(FRAME_HEADER.toShort())
      putShort(COMM_ADDR_PLAIN.toShort())
      putShort(commandId.toShort())
      putShort(content.size.toShort())
      put(content)
    }.array()
    
    val checksumInput = ByteArray(frameWithoutChecksum.size - 2)
    System.arraycopy(frameWithoutChecksum, 2, checksumInput, 0, checksumInput.size)
    val checksum = calculateSunCheck(checksumInput)
    
    return ByteBuffer.allocate(frameWithoutChecksum.size + 1 + 2).apply {
      put(frameWithoutChecksum)
      put(checksum)
      putShort(FRAME_TAIL.toShort())
    }.array()
  }
  
  /**
   * Build unlock command (Unseal - Parameter 0x24 = 0x00)
   */
  fun buildUnlockCommand(encryptionKey: ByteArray): ByteArray {
    // Content: [numParams=1][paramId=0x24][length=1][value=0x00]
    val content = byteArrayOf(0x01, PARAM_SEAL_STATE.toByte(), 0x01, UNSEAL_VALUE.toByte())
    return buildEncryptedFrame(CMD_SET_PARAMS, content, encryptionKey)
  }
  
  /**
   * Build lock command (Seal - Parameter 0x24 = 0x01)
   */
  fun buildLockCommand(encryptionKey: ByteArray): ByteArray {
    // Content: [numParams=1][paramId=0x24][length=1][value=0x01]
    val content = byteArrayOf(0x01, PARAM_SEAL_STATE.toByte(), 0x01, SEAL_VALUE.toByte())
    return buildEncryptedFrame(CMD_SET_PARAMS, content, encryptionKey)
  }
  
  /**
   * Build query status command (Query Parameter 0x30 - Lock State)
   */
  fun buildQueryStatusCommand(encryptionKey: ByteArray): ByteArray {
    // Content: [paramId=0x30]
    val content = byteArrayOf(PARAM_LOCK_STATE.toByte())
    return buildEncryptedFrame(CMD_QUERY_PARAMS, content, encryptionKey)
  }
  
  /**
   * Build authentication command - FROM WORKING POC
   */
  fun buildAuthCommand(password: String, encryptionKey: ByteArray?): ByteArray {
    val passwordBytes = password.toByteArray(Charsets.US_ASCII)
    // Content: [numParams=1][paramId=0x26][length][password]
    val content = ByteArray(3 + passwordBytes.size)
    content[0] = 0x01 // numParams
    content[1] = PARAM_PASSWORD.toByte()
    content[2] = passwordBytes.size.toByte()
    System.arraycopy(passwordBytes, 0, content, 3, passwordBytes.size)
    
    return if (encryptionKey != null) {
      buildEncryptedFrame(CMD_SET_PARAMS, content, encryptionKey)
    } else {
      buildPlainFrame(CMD_SET_PARAMS, content)
    }
  }
  
  /**
   * Extract battery level from decrypted content
   */
  fun extractBatteryLevel(content: ByteArray): Int? {
    if (content.isEmpty()) return null
    
    var idx = 1 // Skip numParams
    while (idx < content.size - 1) {
      if (idx + 2 > content.size) break
      
      val paramId = content[idx++].toInt() and 0xFF
      val paramLen = content[idx++].toInt() and 0xFF
      
      if (idx + paramLen > content.size) break
      
      if (paramId == PARAM_BATTERY && paramLen > 0) {
        val level = content[idx].toInt() and 0xFF
        Log.d(TAG, "Battery level: $level%")
        return level
      }
      
      idx += paramLen
    }
    
    return null
  }
  
  /**
   * Parse response - BASED ON WORKING DigitalLock.kt parseAndHandleResponse()
   *
   * Handles:
   * 1. ACK responses (3 bytes: 20 F1 XX)
   * 2. Full encrypted response frames (F3 3F ... F4 4F)
   */
  fun parseResponse(responseBytes: ByteArray, encryptionKey: ByteArray?): ParsedResponse? {
    Log.d(TAG, "")
    Log.d(TAG, "===========================================")
    Log.d(TAG, "PARSING RESPONSE")
    Log.d(TAG, "===========================================")
    Log.d(TAG, "Size: ${responseBytes.size} bytes")
    Log.d(TAG, "Hex: ${responseBytes.toHexString()}")
    
    if (responseBytes.isEmpty()) {
      Log.w(TAG, "Empty response")
      return null
    }
    
    // Check for ACK response (3 bytes: 20 F1 XX) - FROM WORKING POC
    if (responseBytes.size == 3 &&
      responseBytes[0] == 0x20.toByte() &&
      responseBytes[1] == 0xF1.toByte()
    ) {
      val code = responseBytes[2].toInt() and 0xFF
      Log.d(TAG, "ACK Response detected: code=0x${code.toString(16)}")
      
      return when (code) {
        0x00 -> {
          Log.i(TAG, "ACK Success")
          ParsedResponse(
            command = 0,
            resultCode = 0,
            content = responseBytes,
            isSuccess = true,
            isAck = true
          )
        }
        0x01 -> {
          Log.e(TAG, "ACK Failure")
          ParsedResponse(
            command = 0,
            resultCode = 1,
            content = responseBytes,
            isSuccess = false,
            isAck = true,
            errorMessage = "Device reported failure"
          )
        }
        0x02 -> {
          Log.e(TAG, "Shackle disconnected")
          ParsedResponse(
            command = 0,
            resultCode = 2,
            content = responseBytes,
            isSuccess = false,
            isAck = true,
            errorMessage = "Shackle disconnected"
          )
        }
        else -> {
          Log.w(TAG, "Unknown ACK code: 0x${code.toString(16)}")
          ParsedResponse(
            command = 0,
            resultCode = code,
            content = responseBytes,
            isSuccess = false,
            isAck = true,
            errorMessage = "Unknown ACK code: $code"
          )
        }
      }
    }
    
    // Check for full frame response (F3 3F ... F4 4F) - FROM WORKING POC
    if (responseBytes.size >= 8) {
      val header = ((responseBytes[0].toInt() and 0xFF) shl 8) or (responseBytes[1].toInt() and 0xFF)
      val commAddr = ((responseBytes[2].toInt() and 0xFF) shl 8) or (responseBytes[3].toInt() and 0xFF)
      
      Log.d(TAG, "Header: 0x${header.toString(16)}")
      Log.d(TAG, "CommAddr: 0x${commAddr.toString(16)}")
      
      if (header == RESPONSE_HEADER) {
        val isEncrypted = (commAddr == 0xFFFF || commAddr == COMM_ADDR_ENCRYPTED)
        val cmdId = ((responseBytes[4].toInt() and 0xFF) shl 8) or (responseBytes[5].toInt() and 0xFF)
        val length = ((responseBytes[6].toInt() and 0xFF) shl 8) or (responseBytes[7].toInt() and 0xFF)
        
        Log.d(TAG, "Response: cmd=0x${cmdId.toString(16)}, len=$length, encrypted=$isEncrypted")
        
        if (isEncrypted && encryptionKey != null && responseBytes.size >= 11) {
          val contentStart = 8
          val contentEnd = responseBytes.size - 3  // Exclude checksum + tail
          
          if (contentEnd > contentStart) {
            val encryptedContent = responseBytes.copyOfRange(contentStart, contentEnd)
            Log.d(TAG, "Encrypted content: ${encryptedContent.toHexString()}")
            
            val decryptedContent = decryptResponse(encryptedContent, encryptionKey)
            Log.d(TAG, "Decrypted content: ${decryptedContent.toHexString()}")
            
            // Parse result code (first byte of decrypted content)
            val resultCode = if (decryptedContent.isNotEmpty()) {
              decryptedContent[0].toInt() and 0xFF
            } else {
              -1
            }
            
            return ParsedResponse(
              command = cmdId,
              resultCode = resultCode,
              content = decryptedContent,
              isSuccess = resultCode == 0x00,
              isAck = false
            )
          }
        } else if (!isEncrypted && responseBytes.size >= 11) {
          // Non-encrypted response
          val contentStart = 8
          val contentEnd = responseBytes.size - 3
          val content = responseBytes.copyOfRange(contentStart, maxOf(contentStart, contentEnd))
          
          val resultCode = if (content.isNotEmpty()) content[0].toInt() and 0xFF else -1
          
          return ParsedResponse(
            command = cmdId,
            resultCode = resultCode,
            content = content,
            isSuccess = resultCode == 0x00,
            isAck = false
          )
        }
      }
    }
    
    // Unknown format - return as raw
    Log.w(TAG, "Unknown response format, returning as raw")
    return ParsedResponse(
      command = 0,
      resultCode = -1,
      content = responseBytes,
      isSuccess = false,
      isAck = false,
      errorMessage = "Unknown response format"
    )
  }
  
  /**
   * Decrypt response content - FROM WORKING DigitalLockPacketCodec.kt
   */
  fun decryptResponse(encryptedContent: ByteArray, key: ByteArray): ByteArray {
    if (key.size != 16) {
      Log.e(TAG, "Invalid key size: ${key.size}")
      return byteArrayOf()
    }
    
    // Encrypted content must be multiple of 16 (AES block size)
    val paddedLength = ((encryptedContent.size + 15) / 16) * 16
    val paddedInput = encryptedContent.copyOf(paddedLength)
    
    Log.d(TAG, "Decrypting ${encryptedContent.size} bytes (padded to $paddedLength)")
    
    return try {
      val cipher = Cipher.getInstance("AES/ECB/NoPadding")
      val secretKey = SecretKeySpec(key, "AES")
      cipher.init(Cipher.DECRYPT_MODE, secretKey)
      val decrypted = cipher.doFinal(paddedInput)
      
      Log.d(TAG, "Decrypted raw: ${decrypted.toHexString()}")
      
      // Structure after decryption:
      // [0-1]: CRC-16 (2 bytes)
      // [2-7]: Serial number (6 bytes)
      // [8-11]: Random number (4 bytes)
      // [12+]: Actual content (parameters)
      
      if (decrypted.size >= 12) {
        val content = decrypted.copyOfRange(12, decrypted.size)
        Log.d(TAG, "Content after removing CRC/serial/random: ${content.toHexString()}")
        content
      } else {
        Log.w(TAG, "Decrypted data too short: ${decrypted.size}")
        decrypted
      }
    } catch (e: Exception) {
      Log.e(TAG, "Decryption failed: ${e.message}")
      byteArrayOf()
    }
  }
  
  /**
   * Extract lock state from decrypted content - FROM WORKING DigitalLock.kt
   */
  fun extractLockState(content: ByteArray): Boolean? {
    if (content.isEmpty()) return null
    
    Log.d(TAG, "Extracting lock state from: ${content.toHexString()}")
    
    var idx = 0
    val numParams = content[idx++].toInt() and 0xFF
    Log.d(TAG, "Total parameters: $numParams")
    
    while (idx < content.size - 1) {
      if (idx + 2 > content.size) break
      
      val paramId = content[idx++].toInt() and 0xFF
      val paramLen = content[idx++].toInt() and 0xFF
      
      if (idx + paramLen > content.size) {
        Log.w(TAG, "Parameter length exceeds content")
        break
      }
      
      val paramValue = if (paramLen > 0) content.copyOfRange(idx, idx + paramLen) else byteArrayOf()
      idx += paramLen
      
      Log.d(TAG, "Param 0x${paramId.toString(16)} (${paramLen}B): ${paramValue.toHexString()}")
      
      if (paramId == PARAM_LOCK_STATE && paramValue.isNotEmpty()) {
        val stateValue = paramValue[0].toInt() and 0xFF
        return when (stateValue) {
          STATE_UNLOCKED_0x00, STATE_UNLOCKED_0x30 -> {
            Log.i(TAG, "Lock state: UNLOCKED (0x${stateValue.toString(16)})")
            false
          }
          STATE_LOCKED_0x01, STATE_LOCKED_0x31 -> {
            Log.i(TAG, "Lock state: LOCKED (0x${stateValue.toString(16)})")
            true
          }
          else -> {
            Log.w(TAG, "Unknown lock state value: 0x${stateValue.toString(16)}")
            null
          }
        }
      }
    }
    
    Log.w(TAG, "Lock state parameter (0x30) not found")
    return null
  }
  
  /**
   * Parse set parameter response - FROM WORKING DigitalLock.kt
   */
  fun parseSetParameterResponse(content: ByteArray): SetParameterResult {
    if (content.isEmpty()) {
      Log.w(TAG, "Empty set response")
      return SetParameterResult(success = false, errorMessage = "Empty response")
    }
    
    Log.d(TAG, "Parsing set response: ${content.toHexString()}")
    
    // Single byte response (result flag)
    if (content.size == 1) {
      val flag = content[0].toInt() and 0xFF
      return when (flag) {
        0x00 -> {
          Log.i(TAG, "Command SUCCESS")
          SetParameterResult(success = true)
        }
        0x01 -> {
          Log.e(TAG, "Command FAILURE")
          SetParameterResult(success = false, errorMessage = "Command failed")
        }
        0x04 -> {
          Log.e(TAG, "Serial number incorrect")
          SetParameterResult(success = false, errorMessage = "Serial number incorrect")
        }
        0x05 -> {
          Log.e(TAG, "CRC error")
          SetParameterResult(success = false, errorMessage = "CRC error")
        }
        0x06 -> {
          Log.e(TAG, "Other error")
          SetParameterResult(success = false, errorMessage = "Other error")
        }
        else -> {
          Log.w(TAG, "Unknown response flag: 0x${flag.toString(16)}")
          SetParameterResult(success = false, errorMessage = "Unknown error: $flag")
        }
      }
    }
    
    // Multi-parameter response
    var idx = 0
    val numParams = content[idx++].toInt() and 0xFF
    var allSuccess = true
    val results = mutableMapOf<Int, Boolean>()
    
    while (idx < content.size - 1) {
      val paramId = content[idx++].toInt() and 0xFF
      val result = content[idx++].toInt() and 0xFF
      
      val success = result == 0x00
      results[paramId] = success
      if (!success) allSuccess = false
      
      Log.d(TAG, "Param 0x${paramId.toString(16)}: ${if (success) "SUCCESS" else "FAILURE"}")
    }
    
    return SetParameterResult(success = allSuccess, parameterResults = results)
  }
  
  // =========================================================================
  // HELPER METHODS - FROM WORKING POC
  // =========================================================================
  
  /**
   * Generate incremental serial number (6 bytes) - FROM WORKING POC
   * Uses current timestamp in BCD format: [YY][MM][DD][HH][mm][SS]
   */
  private fun generateIncrementalSerialNumber(): ByteArray {
    val calendar = Calendar.getInstance()
    
    return byteArrayOf(
      toBCD(calendar.get(Calendar.YEAR) % 100),
      toBCD(calendar.get(Calendar.MONTH) + 1),
      toBCD(calendar.get(Calendar.DAY_OF_MONTH)),
      toBCD(calendar.get(Calendar.HOUR_OF_DAY)),
      toBCD(calendar.get(Calendar.MINUTE)),
      toBCD(calendar.get(Calendar.SECOND))
    ).also { serialNum ->
      val serialLong = serialNum.toLongValue()
      if (serialLong <= lastSerialNumber) {
        Log.w(TAG, "Serial number not incrementing!")
      }
      lastSerialNumber = serialLong
    }
  }
  
  private fun toBCD(value: Int): Byte {
    val tens = value / 10
    val ones = value % 10
    return ((tens shl 4) or ones).toByte()
  }
  
  private fun ByteArray.toLongValue(): Long {
    var result = 0L
    for (b in this) {
      result = (result shl 8) or (b.toLong() and 0xFF)
    }
    return result
  }
  
  /**
   * Generate random number (4 bytes)
   */
  private fun generateRandomNumber(): ByteArray {
    return ByteArray(4).apply {
      java.util.Random().nextBytes(this)
    }
  }
  
  /**
   * Calculate CRC-16 CCITT - FROM WORKING POC
   * Polynomial: 0x1021, Initial: 0xFFFF
   */
  private fun calculateCRC16(data: ByteArray): Int {
    var crc = 0xFFFF
    val polynomial = 0x1021
    
    for (b in data) {
      for (i in 0 until 8) {
        val bit = ((b.toInt() shr (7 - i)) and 1) == 1
        val c15 = ((crc shr 15) and 1) == 1
        crc = crc shl 1
        if (c15 xor bit) {
          crc = crc xor polynomial
        }
      }
    }
    
    return crc and 0xFFFF
  }
  
  /**
   * Calculate SunCheck checksum - FROM WORKING POC
   * Sum all bytes, negate, add 1, subtract 16 if > 0xF0
   */
  private fun calculateSunCheck(data: ByteArray): Byte {
    var sum = 0
    for (b in data) {
      sum += (b.toInt() and 0xFF)
    }
    
    sum = (sum and 0xFF)
    var checksum = ((sum.inv() + 1) and 0xFF)
    
    if (checksum > 0xF0) {
      checksum -= 16
    }
    
    return checksum.toByte()
  }
  
  /**
   * Encrypt using AES-128 ECB with zero padding - FROM WORKING POC
   */
  private fun encryptAES128ECB(plaintext: ByteArray, key: ByteArray): ByteArray {
    // Ensure key is exactly 16 bytes
    val aesKey = if (key.size == 16) {
      key
    } else {
      Log.w(TAG, "Key size ${key.size}, adjusting to 16 bytes")
      ByteArray(16).also { adjusted ->
        System.arraycopy(key, 0, adjusted, 0, minOf(key.size, 16))
      }
    }
    
    // Pad to 16-byte blocks with zeros
    val paddedLength = ((plaintext.size + 15) / 16) * 16
    val paddedPlaintext = ByteArray(paddedLength)
    System.arraycopy(plaintext, 0, paddedPlaintext, 0, plaintext.size)
    
    Log.d(TAG, "AES: Input ${plaintext.size}B -> Padded ${paddedLength}B")
    
    val cipher = Cipher.getInstance("AES/ECB/NoPadding")
    val secretKey = SecretKeySpec(aesKey, "AES")
    cipher.init(Cipher.ENCRYPT_MODE, secretKey)
    
    return cipher.doFinal(paddedPlaintext)
  }
  
  private fun ByteArray.toHexString(): String = joinToString(" ") { "%02X".format(it) }
  
  // =========================================================================
  // DATA CLASSES
  // =========================================================================
  
  data class ParsedResponse(
    val command: Int,
    val resultCode: Int,
    val content: ByteArray,
    val isSuccess: Boolean,
    val isAck: Boolean = false,
    val errorMessage: String? = null
  ) {
    override fun equals(other: Any?): Boolean {
      if (this === other) return true
      if (other !is ParsedResponse) return false
      return command == other.command && resultCode == other.resultCode && content.contentEquals(other.content)
    }
    
    override fun hashCode(): Int {
      var result = command
      result = 31 * result + resultCode
      result = 31 * result + content.contentHashCode()
      return result
    }
  }
  
  data class SetParameterResult(
    val success: Boolean,
    val errorMessage: String? = null,
    val parameterResults: Map<Int, Boolean> = emptyMap()
  )
}
