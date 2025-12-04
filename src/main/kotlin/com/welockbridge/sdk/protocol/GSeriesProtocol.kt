package com.welockbridge.sdk.protocol

import android.util.Log
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * WeLockBridge SDK - G-Series Protocol Handler
 *
 * Implements Bander Protocol V11 for G-Series digital locks.
 * Handles frame construction, encryption, and parsing.
 */
internal object GSeriesProtocol {
  
  private const val TAG = "WeLockBridge.Protocol"
  
  // Frame markers
  private const val HEADER_BYTE1 = 0xF1.toByte()
  private const val HEADER_BYTE2 = 0x1F.toByte()
  private const val TAIL_BYTE1 = 0xF2.toByte()
  private const val TAIL_BYTE2 = 0x2F.toByte()
  
  // Encryption indicators
  private const val ENCRYPTED_BYTE1 = 0xFF.toByte()
  private const val ENCRYPTED_BYTE2 = 0xEE.toByte()
  private const val PLAIN_BYTE1 = 0xFF.toByte()
  private const val PLAIN_BYTE2 = 0xFF.toByte()
  
  // Response frame markers
  private const val RESPONSE_HEADER1 = 0xF3.toByte()
  private const val RESPONSE_HEADER2 = 0x3F.toByte()
  private const val RESPONSE_TAIL1 = 0xF4.toByte()
  private const val RESPONSE_TAIL2 = 0x4F.toByte()
  
  // Commands
  const val CMD_SET_PARAMETERS = 0x0310
  const val CMD_QUERY_PARAMETERS = 0x0312
  
  // Parameters
  const val PARAM_LOCK_STATE = 0x30.toByte()
  const val PARAM_SEAL_STATE = 0x24.toByte()
  const val PARAM_BATTERY = 0x94.toByte()
  const val PARAM_PASSWORD = 0x10.toByte()
  
  // Lock state values
  const val LOCK_STATE_LOCKED = 0x01.toByte()
  const val LOCK_STATE_UNLOCKED = 0x00.toByte()
  
  private var serialCounter = 0
  
  /**
   * Build a command frame for the G-Series protocol.
   */
  fun buildFrame(
    command: Int,
    content: ByteArray,
    encryptionKey: ByteArray? = null
  ): ByteArray {
    val isEncrypted = encryptionKey != null
    
    val frameContent = if (isEncrypted) {
      buildEncryptedContent(content, encryptionKey!!)
    } else {
      content
    }
    
    val cmdHigh = ((command shr 8) and 0xFF).toByte()
    val cmdLow = (command and 0xFF).toByte()
    val lenHigh = ((frameContent.size shr 8) and 0xFF).toByte()
    val lenLow = (frameContent.size and 0xFF).toByte()
    
    val frameBuilder = mutableListOf<Byte>()
    
    // Header
    frameBuilder.add(HEADER_BYTE1)
    frameBuilder.add(HEADER_BYTE2)
    
    // Encryption indicator
    if (isEncrypted) {
      frameBuilder.add(ENCRYPTED_BYTE1)
      frameBuilder.add(ENCRYPTED_BYTE2)
    } else {
      frameBuilder.add(PLAIN_BYTE1)
      frameBuilder.add(PLAIN_BYTE2)
    }
    
    // Command
    frameBuilder.add(cmdHigh)
    frameBuilder.add(cmdLow)
    
    // Length
    frameBuilder.add(lenHigh)
    frameBuilder.add(lenLow)
    
    // Content
    frameBuilder.addAll(frameContent.toList())
    
    // Calculate checksum (SunCheck)
    val checksumData = frameBuilder.drop(2).toByteArray()
    val checksum = calculateSunCheck(checksumData)
    frameBuilder.add(checksum)
    
    // Tail
    frameBuilder.add(TAIL_BYTE1)
    frameBuilder.add(TAIL_BYTE2)
    
    val frame = frameBuilder.toByteArray()
    Log.d(TAG, "Built frame: ${frame.toHexString()}")
    return frame
  }
  
  /**
   * Build encrypted content with CRC, serial, and random bytes.
   */
  private fun buildEncryptedContent(content: ByteArray, key: ByteArray): ByteArray {
    // Calculate CRC-16 of content
    val crc = calculateCRC16(content)
    val crcHigh = ((crc shr 8) and 0xFF).toByte()
    val crcLow = (crc and 0xFF).toByte()
    
    // Generate serial number (BCD timestamp-based)
    val serial = generateSerialNumber()
    
    // Random bytes
    val random = byteArrayOf(
      (System.currentTimeMillis() and 0xFF).toByte(),
      ((System.currentTimeMillis() shr 8) and 0xFF).toByte()
    )
    
    // Plain content: CRC(2) + Serial(6) + Random(2) + Content
    val plainContent = ByteArray(2 + 6 + 2 + content.size)
    plainContent[0] = crcHigh
    plainContent[1] = crcLow
    System.arraycopy(serial, 0, plainContent, 2, 6)
    System.arraycopy(random, 0, plainContent, 8, 2)
    System.arraycopy(content, 0, plainContent, 10, content.size)
    
    // Pad to 16-byte boundary
    val paddedLength = ((plainContent.size + 15) / 16) * 16
    val paddedContent = ByteArray(paddedLength)
    System.arraycopy(plainContent, 0, paddedContent, 0, plainContent.size)
    
    // Encrypt with AES-128 ECB
    return encryptAES(paddedContent, key)
  }
  
  /**
   * Build authentication command with password.
   */
  fun buildAuthCommand(password: String, encryptionKey: ByteArray?): ByteArray {
    val passwordBytes = password.toByteArray(Charsets.US_ASCII)
    val content = ByteArray(1 + passwordBytes.size)
    content[0] = PARAM_PASSWORD
    System.arraycopy(passwordBytes, 0, content, 1, passwordBytes.size)
    
    return buildFrame(CMD_SET_PARAMETERS, content, encryptionKey)
  }
  
  /**
   * Build lock command.
   */
  fun buildLockCommand(encryptionKey: ByteArray?): ByteArray {
    val content = byteArrayOf(PARAM_LOCK_STATE, LOCK_STATE_LOCKED)
    return buildFrame(CMD_SET_PARAMETERS, content, encryptionKey)
  }
  
  /**
   * Build unlock command.
   */
  fun buildUnlockCommand(encryptionKey: ByteArray?): ByteArray {
    val content = byteArrayOf(PARAM_LOCK_STATE, LOCK_STATE_UNLOCKED)
    return buildFrame(CMD_SET_PARAMETERS, content, encryptionKey)
  }
  
  /**
   * Build query status command.
   */
  fun buildQueryStatusCommand(encryptionKey: ByteArray?): ByteArray {
    val content = byteArrayOf(PARAM_LOCK_STATE, PARAM_BATTERY)
    return buildFrame(CMD_QUERY_PARAMETERS, content, encryptionKey)
  }
  
  /**
   * Parse a response frame from the device.
   * Now supports both Bander protocol AND raw responses from other locks.
   */
  fun parseResponse(data: ByteArray, encryptionKey: ByteArray? = null): ParsedResponse? {
    val hex = data.joinToString(" ") { String.format("%02X", it) }
    Log.d(TAG, "╔══════════════════════════════════════════════════════╗")
    Log.d(TAG, "║ 🔍 PARSING RESPONSE                                  ║")
    Log.d(TAG, "╠══════════════════════════════════════════════════════╣")
    Log.d(TAG, "║ Size: ${data.size} bytes")
    Log.d(TAG, "║ Hex: $hex")
    Log.d(TAG, "╚══════════════════════════════════════════════════════╝")
    
    if (data.isEmpty()) {
      Log.w(TAG, "Empty response")
      return null
    }
    
    // Try Bander protocol first (F3 3F ... F4 4F format)
    val startIndex = findFrameStart(data)
    val endIndex = if (startIndex >= 0) findFrameEnd(data, startIndex) else -1
    
    if (startIndex >= 0 && endIndex >= 0) {
      Log.d(TAG, "✅ Bander protocol frame detected")
      return parseBanderFrame(data, startIndex, endIndex, encryptionKey)
    }
    
    // Not Bander protocol - treat as raw response
    Log.d(TAG, "⚠️ Not Bander protocol - parsing as RAW response")
    return parseRawResponse(data)
  }
  
  /**
   * Parse Bander protocol frame (original format)
   */
  private fun parseBanderFrame(data: ByteArray, startIndex: Int, endIndex: Int, encryptionKey: ByteArray?): ParsedResponse? {
    val frame = data.copyOfRange(startIndex, endIndex + 2)
    if (frame.size < 11) {
      Log.w(TAG, "Frame too short: ${frame.size}")
      return null
    }
    
    val isEncrypted = frame[2] == ENCRYPTED_BYTE1 && frame[3] == ENCRYPTED_BYTE2
    val command = ((frame[4].toInt() and 0xFF) shl 8) or (frame[5].toInt() and 0xFF)
    val length = ((frame[6].toInt() and 0xFF) shl 8) or (frame[7].toInt() and 0xFF)
    
    if (frame.size < 8 + length + 3) {
      Log.w(TAG, "Frame content incomplete")
      return null
    }
    
    val encryptedContent = frame.copyOfRange(8, 8 + length)
    
    val content = if (isEncrypted && encryptionKey != null) {
      try {
        val decrypted = decryptAES(encryptedContent, encryptionKey)
        if (decrypted.size > 10) {
          decrypted.copyOfRange(10, decrypted.size)
        } else {
          decrypted
        }
      } catch (e: Exception) {
        Log.e(TAG, "Decryption failed: ${e.message}")
        encryptedContent
      }
    } else {
      encryptedContent
    }
    
    val resultCode = if (content.isNotEmpty()) content[0].toInt() and 0xFF else -1
    
    return ParsedResponse(
      command = command,
      resultCode = resultCode,
      content = content,
      isSuccess = resultCode == 0x00
    )
  }
  
  /**
   * Parse raw response from non-Bander locks (Nordic UART, etc.)
   * Attempts to extract useful information from any response format.
   */
  private fun parseRawResponse(data: ByteArray): ParsedResponse {
    Log.d(TAG, "📝 Analyzing raw response...")
    
    // Log first few bytes for analysis
    if (data.isNotEmpty()) {
      Log.d(TAG, "   First byte: ${String.format("0x%02X", data[0])} = ${data[0].toInt() and 0xFF}")
    }
    if (data.size >= 2) {
      Log.d(TAG, "   Second byte: ${String.format("0x%02X", data[1])} = ${data[1].toInt() and 0xFF}")
    }
    
    // Try to detect common response patterns
    val firstByte = if (data.isNotEmpty()) data[0].toInt() and 0xFF else 0
    
    // Many locks use first byte as status: 0x00 = success, 0x01 = locked, etc.
    val isSuccess = firstByte == 0x00 || firstByte == 0x01
    
    // Try to find lock state (common patterns)
    var lockState: Byte? = null
    var batteryLevel: Int? = null
    
    // Pattern 1: Status byte followed by lock state
    if (data.size >= 2) {
      // Some locks: [status] [lock_state] where lock_state: 0=unlocked, 1=locked
      if (data[1] == 0x00.toByte() || data[1] == 0x01.toByte()) {
        lockState = data[1]
        Log.d(TAG, "   Possible lock state at byte[1]: ${if (lockState == 0x01.toByte()) "LOCKED" else "UNLOCKED"}")
      }
    }
    
    // Pattern 2: Look for battery percentage (value 0-100)
    for (i in data.indices) {
      val value = data[i].toInt() and 0xFF
      if (value in 1..100) {
        batteryLevel = value
        Log.d(TAG, "   Possible battery at byte[$i]: $value%")
        break
      }
    }
    
    Log.d(TAG, "   Interpretation: success=$isSuccess")
    
    return ParsedResponse(
      command = 0,
      resultCode = firstByte,
      content = data,
      isSuccess = isSuccess
    )
  }
  
  /**
   * Extract lock state from response content.
   * Supports both Bander protocol and raw responses.
   */
  fun extractLockState(content: ByteArray): Boolean? {
    if (content.isEmpty()) return null
    
    // Bander protocol: look for PARAM_LOCK_STATE marker
    var i = 0
    while (i < content.size - 1) {
      if (content[i] == PARAM_LOCK_STATE) {
        val state = content[i + 1] == LOCK_STATE_LOCKED
        Log.d(TAG, "Lock state (Bander): ${if (state) "LOCKED" else "UNLOCKED"}")
        return state
      }
      i++
    }
    
    // Raw response: try common patterns
    // Pattern 1: First byte is status (0=success), second byte is lock state
    if (content.size >= 2) {
      val secondByte = content[1].toInt() and 0xFF
      if (secondByte == 0 || secondByte == 1) {
        val state = secondByte == 1
        Log.d(TAG, "Lock state (raw pattern 1): ${if (state) "LOCKED" else "UNLOCKED"}")
        return state
      }
    }
    
    // Pattern 2: First byte is lock state directly
    if (content.size >= 1) {
      val firstByte = content[0].toInt() and 0xFF
      if (firstByte == 0 || firstByte == 1) {
        val state = firstByte == 1
        Log.d(TAG, "Lock state (raw pattern 2): ${if (state) "LOCKED" else "UNLOCKED"}")
        return state
      }
    }
    
    Log.d(TAG, "Could not extract lock state from response")
    return null
  }
  
  /**
   * Extract battery level from response content.
   * Supports both Bander protocol and raw responses.
   */
  fun extractBatteryLevel(content: ByteArray): Int? {
    if (content.isEmpty()) return null
    
    // Bander protocol: look for PARAM_BATTERY marker
    var i = 0
    while (i < content.size - 1) {
      if (content[i] == PARAM_BATTERY) {
        val level = content[i + 1].toInt() and 0xFF
        Log.d(TAG, "Battery (Bander): $level%")
        return level
      }
      i++
    }
    
    // Raw response: look for reasonable battery value (1-100)
    for (j in content.indices) {
      val value = content[j].toInt() and 0xFF
      if (value in 1..100) {
        Log.d(TAG, "Battery (raw at byte[$j]): $value%")
        return value
      }
    }
    
    return null
  }
  
  private fun findFrameStart(data: ByteArray): Int {
    for (i in 0 until data.size - 1) {
      if (data[i] == RESPONSE_HEADER1 && data[i + 1] == RESPONSE_HEADER2) {
        return i
      }
      if (data[i] == HEADER_BYTE1 && data[i + 1] == HEADER_BYTE2) {
        return i
      }
    }
    return -1
  }
  
  private fun findFrameEnd(data: ByteArray, startIndex: Int): Int {
    for (i in startIndex until data.size - 1) {
      if (data[i] == RESPONSE_TAIL1 && data[i + 1] == RESPONSE_TAIL2) {
        return i
      }
      if (data[i] == TAIL_BYTE1 && data[i + 1] == TAIL_BYTE2) {
        return i
      }
    }
    return -1
  }
  
  private fun generateSerialNumber(): ByteArray {
    serialCounter++
    val serial = ByteArray(6)
    val time = System.currentTimeMillis()
    
    // BCD format: YY MM DD HH MM SS
    serial[0] = ((time / 1000 / 60 / 60 / 24 / 365 % 100) and 0xFF).toByte()
    serial[1] = ((time / 1000 / 60 / 60 / 24 / 30 % 12 + 1) and 0xFF).toByte()
    serial[2] = ((time / 1000 / 60 / 60 / 24 % 30 + 1) and 0xFF).toByte()
    serial[3] = ((time / 1000 / 60 / 60 % 24) and 0xFF).toByte()
    serial[4] = ((time / 1000 / 60 % 60) and 0xFF).toByte()
    serial[5] = ((serialCounter % 60) and 0xFF).toByte()
    
    return serial
  }
  
  private fun calculateSunCheck(data: ByteArray): Byte {
    var sum = 0
    for (b in data) {
      sum += (b.toInt() and 0xFF)
    }
    var check = ((sum.inv() + 1) and 0xFF)
    if (check >= 0xF0) {
      check -= 0xF0
    }
    return check.toByte()
  }
  
  private fun calculateCRC16(data: ByteArray): Int {
    var crc = 0xFFFF
    for (b in data) {
      crc = crc xor ((b.toInt() and 0xFF) shl 8)
      for (i in 0 until 8) {
        crc = if ((crc and 0x8000) != 0) {
          (crc shl 1) xor 0x1021
        } else {
          crc shl 1
        }
      }
      crc = crc and 0xFFFF
    }
    return crc
  }
  
  private fun encryptAES(data: ByteArray, key: ByteArray): ByteArray {
    val cipher = Cipher.getInstance("AES/ECB/NoPadding")
    val secretKey = SecretKeySpec(key, "AES")
    cipher.init(Cipher.ENCRYPT_MODE, secretKey)
    return cipher.doFinal(data)
  }
  
  private fun decryptAES(data: ByteArray, key: ByteArray): ByteArray {
    val cipher = Cipher.getInstance("AES/ECB/NoPadding")
    val secretKey = SecretKeySpec(key, "AES")
    cipher.init(Cipher.DECRYPT_MODE, secretKey)
    return cipher.doFinal(data)
  }
  
  private fun ByteArray.toHexString(): String {
    return joinToString(" ") { "%02X".format(it) }
  }
  
  data class ParsedResponse(
    val command: Int,
    val resultCode: Int,
    val content: ByteArray,
    val isSuccess: Boolean
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
}