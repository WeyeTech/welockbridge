package com.welockbridge.sdk.protocol

import android.util.Log
import java.nio.ByteBuffer
import java.util.Calendar
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * G-Series Protocol Handler - IMPROVED ERROR HANDLING
 *
 * KEY IMPROVEMENTS:
 * 1. Better handling of malformed responses
 * 2. More lenient parameter parsing
 * 3. Proper bounds checking
 * 4. Fallback logic for missing parameters
 */
internal object GSeriesProtocol {
  
  private const val TAG = "WeLockBridge.Protocol"
  
  private const val FRAME_HEADER = 0xF11F
  private const val FRAME_TAIL = 0xF22F
  
  private const val COMM_ADDR_PLAIN = 0xFFFF
  private const val COMM_ADDR_ENCRYPTED = 0xFFEE
  
  private const val RESPONSE_HEADER = 0xF33F
  private const val RESPONSE_TAIL = 0xF44F
  
  const val CMD_SET_PARAMS = 0x0310
  const val CMD_QUERY_PARAMS = 0x0312
  
  const val PARAM_LOCK_STATE = 0x30
  const val PARAM_SEAL_STATE = 0x24
  const val PARAM_BATTERY = 0x94
  const val PARAM_PASSWORD = 0x26
  
  const val STATE_LOCKED_0x31 = 0x31
  const val STATE_LOCKED_0x01 = 0x01
  const val STATE_UNLOCKED_0x30 = 0x30
  const val STATE_UNLOCKED_0x00 = 0x00
  
  const val SEAL_VALUE = 0x01
  const val UNSEAL_VALUE = 0x00
  
  private var lastSerialNumber: Long = 0L
  
  fun buildEncryptedFrame(commandId: Int, content: ByteArray, encryptionKey: ByteArray): ByteArray {
    Log.d(TAG, "")
    Log.d(TAG, "===========================================")
    Log.d(TAG, "BUILDING ENCRYPTED FRAME")
    Log.d(TAG, "===========================================")
    Log.d(TAG, "Command: 0x${commandId.toString(16)}")
    Log.d(TAG, "Content: ${content.size} bytes")
    Log.d(TAG, "Content hex: ${content.toHexString()}")
    
    val serialNumber = generateIncrementalSerialNumber()
    Log.d(TAG, "Serial #: ${serialNumber.toHexString()}")
    
    val randomNumber = generateRandomNumber()
    Log.d(TAG, "Random #: ${randomNumber.toHexString()}")
    
    val plaintextToEncrypt = ByteBuffer.allocate(2 + 6 + 4 + content.size).apply {
      put(0x00.toByte())
      put(0x00.toByte())
      put(serialNumber)
      put(randomNumber)
      put(content)
    }.array()
    
    val crcInput = ByteArray(content.size)
    System.arraycopy(plaintextToEncrypt, 12, crcInput, 0, content.size)
    val crc16 = calculateCRC16(crcInput)
    
    plaintextToEncrypt[0] = (crc16 shr 8).toByte()
    plaintextToEncrypt[1] = (crc16 and 0xFF).toByte()
    
    Log.d(TAG, "CRC-16: 0x${crc16.toString(16).padStart(4, '0')}")
    Log.d(TAG, "Plaintext to encrypt: ${plaintextToEncrypt.toHexString()}")
    Log.d(TAG, "Plaintext length: ${plaintextToEncrypt.size} bytes")
    
    val encryptedContent = encryptAES128ECB(plaintextToEncrypt, encryptionKey)
    Log.d(TAG, "Encrypted length: ${encryptedContent.size} bytes (padded to 16)")
    Log.d(TAG, "Encrypted hex: ${encryptedContent.toHexString()}")
    
    val length = plaintextToEncrypt.size
    
    val frameWithoutChecksum = ByteBuffer.allocate(2 + 2 + 2 + 2 + encryptedContent.size).apply {
      putShort(FRAME_HEADER.toShort())
      putShort(COMM_ADDR_ENCRYPTED.toShort())
      putShort(commandId.toShort())
      putShort(length.toShort())
      put(encryptedContent)
    }.array()
    
    val checksumInput = ByteArray(frameWithoutChecksum.size - 2)
    System.arraycopy(frameWithoutChecksum, 2, checksumInput, 0, checksumInput.size)
    val checksum = calculateSunCheck(checksumInput)
    
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
  
  fun buildUnlockCommand(encryptionKey: ByteArray): ByteArray {
    val content = byteArrayOf(0x01, PARAM_SEAL_STATE.toByte(), 0x01, UNSEAL_VALUE.toByte())
    return buildEncryptedFrame(CMD_SET_PARAMS, content, encryptionKey)
  }
  
  fun buildLockCommand(encryptionKey: ByteArray): ByteArray {
    val content = byteArrayOf(0x01, PARAM_SEAL_STATE.toByte(), 0x01, SEAL_VALUE.toByte())
    return buildEncryptedFrame(CMD_SET_PARAMS, content, encryptionKey)
  }
  
  fun buildQueryStatusCommand(encryptionKey: ByteArray): ByteArray {
    val content = byteArrayOf(PARAM_LOCK_STATE.toByte())
    return buildEncryptedFrame(CMD_QUERY_PARAMS, content, encryptionKey)
  }
  
  fun buildAuthCommand(password: String, encryptionKey: ByteArray?): ByteArray {
    val passwordBytes = password.toByteArray(Charsets.US_ASCII)
    val content = ByteArray(3 + passwordBytes.size)
    content[0] = 0x01
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
   * IMPROVED: Extract battery level with better error handling
   */
  fun extractBatteryLevel(content: ByteArray): Int? {
    if (content.isEmpty()) return null
    
    try {
      var idx = 1
      while (idx < content.size - 1) {
        if (idx + 2 > content.size) break
        
        val paramId = content[idx++].toInt() and 0xFF
        val paramLen = content[idx++].toInt() and 0xFF
        
        // Bounds check
        if (paramLen < 0 || paramLen > 255 || idx + paramLen > content.size) {
          Log.w(TAG, "Invalid param length: $paramLen at index $idx, breaking")
          break
        }
        
        if (paramId == PARAM_BATTERY && paramLen > 0) {
          val level = content[idx].toInt() and 0xFF
          Log.d(TAG, "Battery level: $level%")
          return level
        }
        
        idx += paramLen
      }
    } catch (e: Exception) {
      Log.e(TAG, "Error extracting battery: ${e.message}")
    }
    
    return null
  }
  
  /**
   * IMPROVED: Parse response with better error handling
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
    
    // Check for ACK response
    if (responseBytes.size == 3 &&
      responseBytes[0] == 0x20.toByte() &&
      responseBytes[1] == 0xF1.toByte()
    ) {
      val code = responseBytes[2].toInt() and 0xFF
      Log.d(TAG, "ACK Response detected: code=0x${code.toString(16)}")
      
      return when (code) {
        0x00 -> ParsedResponse(0, 0, responseBytes, true, true)
        0x01 -> ParsedResponse(0, 1, responseBytes, false, true, "Device reported failure")
        0x02 -> ParsedResponse(0, 2, responseBytes, false, true, "Shackle disconnected")
        else -> ParsedResponse(0, code, responseBytes, false, true, "Unknown ACK code: $code")
      }
    }
    
    // Check for full frame response
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
        
        // Add validation for length
        if (length < 0 || length > 1000) {
          Log.w(TAG, "Invalid length field: $length, treating as malformed")
          return ParsedResponse(cmdId, -1, responseBytes, false, false, "Invalid length")
        }
        
        if (isEncrypted && encryptionKey != null && responseBytes.size >= 11) {
          val contentStart = 8
          val contentEnd = responseBytes.size - 3
          
          if (contentEnd > contentStart) {
            val encryptedContent = responseBytes.copyOfRange(contentStart, contentEnd)
            Log.d(TAG, "Encrypted content: ${encryptedContent.toHexString()}")
            
            val decryptedContent = decryptResponse(encryptedContent, encryptionKey)
            Log.d(TAG, "Decrypted content: ${decryptedContent.toHexString()}")
            
            val resultCode = if (decryptedContent.isNotEmpty()) {
              decryptedContent[0].toInt() and 0xFF
            } else {
              -1
            }
            
            return ParsedResponse(cmdId, resultCode, decryptedContent, resultCode == 0x00, false)
          }
        } else if (!isEncrypted && responseBytes.size >= 11) {
          val contentStart = 8
          val contentEnd = responseBytes.size - 3
          val content = responseBytes.copyOfRange(contentStart, maxOf(contentStart, contentEnd))
          
          val resultCode = if (content.isNotEmpty()) content[0].toInt() and 0xFF else -1
          
          return ParsedResponse(cmdId, resultCode, content, resultCode == 0x00, false)
        }
      }
    }
    
    Log.w(TAG, "Unknown response format, returning as raw")
    return ParsedResponse(0, -1, responseBytes, false, false, "Unknown response format")
  }
  
  fun decryptResponse(encryptedContent: ByteArray, key: ByteArray): ByteArray {
    if (key.size != 16) {
      Log.e(TAG, "Invalid key size: ${key.size}")
      return byteArrayOf()
    }
    
    val paddedLength = ((encryptedContent.size + 15) / 16) * 16
    val paddedInput = encryptedContent.copyOf(paddedLength)
    
    Log.d(TAG, "Decrypting ${encryptedContent.size} bytes (padded to $paddedLength)")
    
    return try {
      val cipher = Cipher.getInstance("AES/ECB/NoPadding")
      val secretKey = SecretKeySpec(key, "AES")
      cipher.init(Cipher.DECRYPT_MODE, secretKey)
      val decrypted = cipher.doFinal(paddedInput)
      
      Log.d(TAG, "Decrypted raw: ${decrypted.toHexString()}")
      
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
   * IMPROVED: Extract lock state with robust error handling
   */
  fun extractLockState(content: ByteArray): Boolean? {
    if (content.isEmpty()) {
      Log.w(TAG, "Empty content for lock state extraction")
      return null
    }
    
    Log.d(TAG, "Extracting lock state from: ${content.toHexString()}")
    
    try {
      var idx = 0
      
      // First byte should be number of parameters
      if (idx >= content.size) {
        Log.w(TAG, "Content too short")
        return null
      }
      
      val numParams = content[idx++].toInt() and 0xFF
      Log.d(TAG, "Total parameters: $numParams")
      
      // If numParams is 0, this might be a single-byte result code
      if (numParams == 0 && content.size == 1) {
        Log.w(TAG, "Single byte response (result code), no lock state")
        return null
      }
      
      // Parse parameters
      var paramsParsed = 0
      while (idx < content.size - 1 && paramsParsed < numParams) {
        if (idx + 2 > content.size) {
          Log.w(TAG, "Not enough data for parameter header at index $idx")
          break
        }
        
        val paramId = content[idx++].toInt() and 0xFF
        val paramLen = content[idx++].toInt() and 0xFF
        
        // Validate parameter length
        if (paramLen < 0 || paramLen > 255) {
          Log.w(TAG, "Invalid parameter length: $paramLen")
          break
        }
        
        if (idx + paramLen > content.size) {
          Log.w(TAG, "Parameter length $paramLen exceeds remaining content (${content.size - idx} bytes)")
          break
        }
        
        val paramValue = if (paramLen > 0) {
          content.copyOfRange(idx, minOf(idx + paramLen, content.size))
        } else {
          byteArrayOf()
        }
        idx += paramLen
        paramsParsed++
        
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
      
      Log.w(TAG, "Lock state parameter (0x30) not found after parsing $paramsParsed parameters")
    } catch (e: Exception) {
      Log.e(TAG, "Exception extracting lock state: ${e.message}", e)
    }
    
    return null
  }
  
  fun parseSetParameterResponse(content: ByteArray): SetParameterResult {
    if (content.isEmpty()) {
      Log.w(TAG, "Empty set response")
      return SetParameterResult(success = false, errorMessage = "Empty response")
    }
    
    Log.d(TAG, "Parsing set response: ${content.toHexString()}")
    
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
  
  // Helper methods remain the same
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
  
  private fun generateRandomNumber(): ByteArray {
    return ByteArray(4).apply {
      java.util.Random().nextBytes(this)
    }
  }
  
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
  
  private fun encryptAES128ECB(plaintext: ByteArray, key: ByteArray): ByteArray {
    val aesKey = if (key.size == 16) {
      key
    } else {
      Log.w(TAG, "Key size ${key.size}, adjusting to 16 bytes")
      ByteArray(16).also { adjusted ->
        System.arraycopy(key, 0, adjusted, 0, minOf(key.size, 16))
      }
    }
    
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