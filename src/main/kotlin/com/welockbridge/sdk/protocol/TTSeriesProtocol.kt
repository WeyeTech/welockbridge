package com.welockbridge.sdk.protocol

import android.util.Log
import java.util.Calendar
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * TT Series E-Lock Protocol Handler
 *
 * Implements the TOTARGET BLE-ELOCK PROTOCOL (Version A7)
 *
 * Protocol Frame Structure:
 * | Encryption Type (1B) | Length (1B) | Business Data (nB) | CRC8 (1B) |
 *
 * Encryption Types:
 * - 0x01: No encryption
 * - 0x11: AES Encryption
 *
 * CRC8: CRC-8/MAXIM polynomial x^8 + x^5 + x^4 + 1
 *
 * @author WeLockBridge Team
 * @version 1.0.0
 */
internal object TTSeriesProtocol {
  
  private const val TAG = "WeLockBridge.TTProtocol"
  
  // Encryption types
  const val ENCRYPTION_NONE = 0x01
  const val ENCRYPTION_AES = 0x11
  
  // Commands (downstream - APP to Lock)
  const val CMD_CALIBRATE_TIME = 0x20
  const val CMD_CHECK_VERSION = 0x21
  const val CMD_SET_WORK_MODE = 0x29
  const val CMD_CHECK_STATUS = 0x12
  const val CMD_LOCK = 0x31
  const val CMD_UNLOCK = 0x37
  
  // Upload commands (upstream - Lock to APP)
  const val CMD_HEARTBEAT = 0x01
  const val CMD_BROKE_ALARM = 0x03
  const val CMD_ROD_CUT_ALARM = 0x04
  const val CMD_OPENED_ALARM = 0x05
  
  // Response codes for Check
  const val RESP_CHECK_OK = 0x62
  
  // Response codes for Lock
  const val RESP_LOCK_SUCCESS = 0x80
  const val RESP_LOCK_AGAIN = 0x81        // Already locked
  const val RESP_LOCK_IS_OPENED = 0x82
  const val RESP_LOCK_LOW_VOLTAGE = 0x83
  const val RESP_LOCK_ILLEGAL_SHELL = 0x84
  const val RESP_LOCK_ROD_CUT_ALARM = 0x86
  const val RESP_LOCK_OPENED_ALARM = 0x87
  
  // Response codes for Unlock
  const val RESP_UNLOCK_SUCCESS = 0x90
  const val RESP_UNLOCK_AGAIN = 0x91      // Already unlocked
  const val RESP_UNLOCK_WHEN_OPENED = 0x92
  const val RESP_UNLOCK_WRONG_PASSWORD = 0x93
  const val RESP_UNLOCK_ILLEGAL_OPENED = 0x94
  const val RESP_UNLOCK_EMERGENCY_OPENED = 0x95
  const val RESP_UNLOCK_OPENED_ALARM = 0x96
  const val RESP_UNLOCK_WITHOUT_SEALED = 0x97
  const val RESP_UNLOCK_ROD_CUT_ALARM = 0x98
  const val RESP_UNLOCK_OVERTIME = 0x99
  
  // Work modes
  const val WORK_MODE_SLEEP = 0x30
  const val WORK_MODE_REALTIME = 0x31
  
  // Lock status high nibble values
  const val STATUS_OPEN = 0x10
  const val STATUS_STANDBY = 0x20
  const val STATUS_NOT_READY = 0x30
  const val STATUS_SEALED = 0x40
  const val STATUS_LOCAL_SEALED = 0x50
  const val STATUS_UNSEALED = 0x60
  const val STATUS_ALARM = 0x70
  const val STATUS_CANCEL_ALARM = 0x90
  const val STATUS_ABNORMAL = 0xA0
  
  // Alarm flags (low nibble when status is ALARM)
  const val ALARM_ROD_CUT = 0x01
  const val ALARM_OPENED = 0x02
  const val ALARM_SHELL_OPENED = 0x04
  const val ALARM_EMERGENCY = 0x08
  
  // Operation sources
  const val OP_SOURCE_AUTOMATIC = 0x01
  const val OP_SOURCE_KEYBOARD = 0x02
  const val OP_SOURCE_BLE = 0x03
  
  // CRC8 lookup table (CRC-8/MAXIM)
  private val CRC8_TABLE = intArrayOf(
    0, 94, 188, 226, 97, 63, 221, 131, 194, 156, 126, 32, 163, 253, 31, 65,
    157, 195, 33, 127, 252, 162, 64, 30, 95, 1, 227, 189, 62, 96, 130, 220,
    35, 125, 159, 193, 66, 28, 254, 160, 225, 191, 93, 3, 128, 222, 60, 98,
    190, 224, 2, 92, 223, 129, 99, 61, 124, 34, 192, 158, 29, 67, 161, 255,
    70, 24, 250, 164, 39, 121, 155, 197, 132, 218, 56, 102, 229, 187, 89, 7,
    219, 133, 103, 57, 186, 228, 6, 88, 25, 71, 165, 251, 120, 38, 196, 154,
    101, 59, 217, 135, 4, 90, 184, 230, 167, 249, 27, 69, 198, 152, 122, 36,
    248, 166, 68, 26, 153, 199, 37, 123, 58, 100, 134, 216, 91, 5, 231, 185,
    140, 210, 48, 110, 237, 179, 81, 15, 78, 16, 242, 172, 47, 113, 147, 205,
    17, 79, 173, 243, 112, 46, 204, 146, 211, 141, 111, 49, 178, 236, 14, 80,
    175, 241, 19, 77, 206, 144, 114, 44, 109, 51, 209, 143, 12, 82, 176, 238,
    50, 108, 142, 208, 83, 13, 239, 177, 240, 174, 76, 18, 145, 207, 45, 115,
    202, 148, 118, 40, 171, 245, 23, 73, 8, 86, 180, 234, 105, 55, 213, 139,
    87, 9, 235, 181, 54, 104, 138, 212, 149, 203, 41, 119, 244, 170, 72, 22,
    233, 183, 85, 11, 136, 214, 52, 106, 43, 117, 151, 201, 74, 20, 246, 168,
    116, 42, 200, 150, 21, 75, 169, 247, 182, 232, 10, 84, 215, 137, 107, 53
  )
  
  /**
   * Build a plain (unencrypted) frame.
   *
   * @param businessData The command and data payload
   * @return Complete frame with encryption type, length, data, and CRC8
   */
  fun buildPlainFrame(businessData: ByteArray): ByteArray {
    Log.d(TAG, "")
    Log.d(TAG, "===========================================")
    Log.d(TAG, "BUILDING TT PLAIN FRAME")
    Log.d(TAG, "===========================================")
    Log.d(TAG, "Business data: ${businessData.size} bytes")
    Log.d(TAG, "Data hex: ${businessData.toHexString()}")
    
    val frame = ByteArray(1 + 1 + businessData.size + 1)
    frame[0] = ENCRYPTION_NONE.toByte()
    frame[1] = businessData.size.toByte()
    System.arraycopy(businessData, 0, frame, 2, businessData.size)
    
    // Calculate CRC8 over encryption type + length + business data
    val crc = calculateCRC8(frame, 0, frame.size - 1)
    frame[frame.size - 1] = crc
    
    Log.d(TAG, "CRC8: 0x${(crc.toInt() and 0xFF).toString(16).padStart(2, '0')}")
    Log.d(TAG, "Final frame: ${frame.size} bytes")
    Log.d(TAG, "Frame hex: ${frame.toHexString()}")
    Log.d(TAG, "===========================================")
    Log.d(TAG, "")
    
    return frame
  }
  
  /**
   * Build an encrypted frame using AES.
   *
   * @param businessData The command and data payload
   * @param encryptionKey 16-byte AES key
   * @return Complete frame with encryption type, length, encrypted data, and CRC8
   */
  fun buildEncryptedFrame(businessData: ByteArray, encryptionKey: ByteArray): ByteArray {
    Log.d(TAG, "")
    Log.d(TAG, "===========================================")
    Log.d(TAG, "BUILDING TT ENCRYPTED FRAME")
    Log.d(TAG, "===========================================")
    Log.d(TAG, "Business data: ${businessData.size} bytes")
    Log.d(TAG, "Data hex: ${businessData.toHexString()}")
    
    // Encrypt the business data
    val encryptedData = encryptAES128ECB(businessData, encryptionKey)
    Log.d(TAG, "Encrypted data: ${encryptedData.size} bytes")
    
    val frame = ByteArray(1 + 1 + encryptedData.size + 1)
    frame[0] = ENCRYPTION_AES.toByte()
    frame[1] = businessData.size.toByte() // Original length, not encrypted length
    System.arraycopy(encryptedData, 0, frame, 2, encryptedData.size)
    
    // Calculate CRC8 over the entire frame (minus CRC byte itself)
    val crc = calculateCRC8(frame, 0, frame.size - 1)
    frame[frame.size - 1] = crc
    
    Log.d(TAG, "CRC8: 0x${(crc.toInt() and 0xFF).toString(16).padStart(2, '0')}")
    Log.d(TAG, "Final frame: ${frame.size} bytes")
    Log.d(TAG, "Frame hex: ${frame.toHexString()}")
    Log.d(TAG, "===========================================")
    Log.d(TAG, "")
    
    return frame
  }
  
  /**
   * Build a Lock command.
   *
   * Frame: CMD(1B) + LockID(4B) + Key(6B) + DATETIME(6B)
   *
   * @param lockId 4-byte lock ID
   * @param password 6-byte password/key
   * @param encryptionKey Optional AES key for encryption
   * @return Complete command frame
   */
  fun buildLockCommand(lockId: ByteArray, password: ByteArray, encryptionKey: ByteArray? = null): ByteArray {
    val datetime = getCurrentDateTime()
    val businessData = ByteArray(1 + 4 + 6 + 6)
    businessData[0] = CMD_LOCK.toByte()
    System.arraycopy(lockId, 0, businessData, 1, 4)
    System.arraycopy(password, 0, businessData, 5, 6)
    System.arraycopy(datetime, 0, businessData, 11, 6)
    
    Log.d(TAG, "Building LOCK command")
    Log.d(TAG, "LockID: ${lockId.toHexString()}")
    Log.d(TAG, "Password: ${password.toHexString()}")
    Log.d(TAG, "DateTime: ${datetime.toHexString()}")
    
    return if (encryptionKey != null) {
      buildEncryptedFrame(businessData, encryptionKey)
    } else {
      buildPlainFrame(businessData)
    }
  }
  
  /**
   * Build an Unlock command.
   *
   * Frame: CMD(1B) + LockID(4B) + Key(6B) + DATETIME(6B)
   *
   * @param lockId 4-byte lock ID
   * @param password 6-byte password/key
   * @param encryptionKey Optional AES key for encryption
   * @return Complete command frame
   */
  fun buildUnlockCommand(lockId: ByteArray, password: ByteArray, encryptionKey: ByteArray? = null): ByteArray {
    val datetime = getCurrentDateTime()
    val businessData = ByteArray(1 + 4 + 6 + 6)
    businessData[0] = CMD_UNLOCK.toByte()
    System.arraycopy(lockId, 0, businessData, 1, 4)
    System.arraycopy(password, 0, businessData, 5, 6)
    System.arraycopy(datetime, 0, businessData, 11, 6)
    
    Log.d(TAG, "Building UNLOCK command")
    Log.d(TAG, "LockID: ${lockId.toHexString()}")
    Log.d(TAG, "Password: ${password.toHexString()}")
    Log.d(TAG, "DateTime: ${datetime.toHexString()}")
    
    return if (encryptionKey != null) {
      buildEncryptedFrame(businessData, encryptionKey)
    } else {
      buildPlainFrame(businessData)
    }
  }
  
  /**
   * Build a Check Status command.
   *
   * Frame: CMD(1B) + LockID(4B) + Key(6B) + DATETIME(6B)
   *
   * @param lockId 4-byte lock ID
   * @param password 6-byte password/key
   * @param encryptionKey Optional AES key for encryption
   * @return Complete command frame
   */
  fun buildCheckStatusCommand(lockId: ByteArray, password: ByteArray, encryptionKey: ByteArray? = null): ByteArray {
    val datetime = getCurrentDateTime()
    val businessData = ByteArray(1 + 4 + 6 + 6)
    businessData[0] = CMD_CHECK_STATUS.toByte()
    System.arraycopy(lockId, 0, businessData, 1, 4)
    System.arraycopy(password, 0, businessData, 5, 6)
    System.arraycopy(datetime, 0, businessData, 11, 6)
    
    Log.d(TAG, "Building CHECK STATUS command")
    Log.d(TAG, "LockID: ${lockId.toHexString()}")
    
    return if (encryptionKey != null) {
      buildEncryptedFrame(businessData, encryptionKey)
    } else {
      buildPlainFrame(businessData)
    }
  }
  
  /**
   * Build a Calibrate Time command.
   *
   * Frame: CMD(1B) + DATETIME(6B)
   *
   * @param encryptionKey Optional AES key for encryption
   * @return Complete command frame
   */
  fun buildCalibrateTimeCommand(encryptionKey: ByteArray? = null): ByteArray {
    val datetime = getCurrentDateTime()
    val businessData = ByteArray(1 + 6)
    businessData[0] = CMD_CALIBRATE_TIME.toByte()
    System.arraycopy(datetime, 0, businessData, 1, 6)
    
    Log.d(TAG, "Building CALIBRATE TIME command")
    Log.d(TAG, "DateTime: ${datetime.toHexString()}")
    
    return if (encryptionKey != null) {
      buildEncryptedFrame(businessData, encryptionKey)
    } else {
      buildPlainFrame(businessData)
    }
  }
  
  /**
   * Build a Check Version command.
   *
   * Frame: CMD(1B) + DATETIME(6B)
   *
   * @param encryptionKey Optional AES key for encryption
   * @return Complete command frame
   */
  fun buildCheckVersionCommand(encryptionKey: ByteArray? = null): ByteArray {
    val datetime = getCurrentDateTime()
    val businessData = ByteArray(1 + 6)
    businessData[0] = CMD_CHECK_VERSION.toByte()
    System.arraycopy(datetime, 0, businessData, 1, 6)
    
    Log.d(TAG, "Building CHECK VERSION command")
    
    return if (encryptionKey != null) {
      buildEncryptedFrame(businessData, encryptionKey)
    } else {
      buildPlainFrame(businessData)
    }
  }
  
  /**
   * Build a Set Work Mode command.
   *
   * Frame: CMD(1B) + LockID(4B) + Mode(1B)
   *
   * @param lockId 4-byte lock ID
   * @param sleepMode true for sleep mode, false for real-time mode
   * @param encryptionKey Optional AES key for encryption
   * @return Complete command frame
   */
  fun buildSetWorkModeCommand(lockId: ByteArray, sleepMode: Boolean, encryptionKey: ByteArray? = null): ByteArray {
    val businessData = ByteArray(1 + 4 + 1)
    businessData[0] = CMD_SET_WORK_MODE.toByte()
    System.arraycopy(lockId, 0, businessData, 1, 4)
    businessData[5] = if (sleepMode) WORK_MODE_SLEEP.toByte() else WORK_MODE_REALTIME.toByte()
    
    Log.d(TAG, "Building SET WORK MODE command")
    Log.d(TAG, "LockID: ${lockId.toHexString()}")
    Log.d(TAG, "Mode: ${if (sleepMode) "SLEEP" else "REALTIME"}")
    
    return if (encryptionKey != null) {
      buildEncryptedFrame(businessData, encryptionKey)
    } else {
      buildPlainFrame(businessData)
    }
  }
  
  /**
   * Build a heartbeat reply command.
   *
   * Frame: CMD(1B) + LockID(4B) + DATETIME(6B)
   *
   * @param lockId 4-byte lock ID
   * @param encryptionKey Optional AES key for encryption
   * @return Complete command frame
   */
  fun buildHeartbeatReply(lockId: ByteArray, encryptionKey: ByteArray? = null): ByteArray {
    val datetime = getCurrentDateTime()
    val businessData = ByteArray(1 + 4 + 6)
    businessData[0] = CMD_HEARTBEAT.toByte()
    System.arraycopy(lockId, 0, businessData, 1, 4)
    System.arraycopy(datetime, 0, businessData, 5, 6)
    
    return if (encryptionKey != null) {
      buildEncryptedFrame(businessData, encryptionKey)
    } else {
      buildPlainFrame(businessData)
    }
  }
  
  /**
   * Parse a response from the TT lock.
   *
   * @param responseBytes Raw response data
   * @param encryptionKey Optional AES key for decryption
   * @return Parsed response or null if invalid
   */
  fun parseResponse(responseBytes: ByteArray, encryptionKey: ByteArray? = null): ParsedResponse? {
    Log.d(TAG, "")
    Log.d(TAG, "===========================================")
    Log.d(TAG, "PARSING TT RESPONSE")
    Log.d(TAG, "===========================================")
    Log.d(TAG, "Size: ${responseBytes.size} bytes")
    Log.d(TAG, "Hex: ${responseBytes.toHexString()}")
    
    if (responseBytes.size < 3) {
      Log.w(TAG, "Response too short")
      return null
    }
    
    val encryptionType = responseBytes[0].toInt() and 0xFF
    val dataLength = responseBytes[1].toInt() and 0xFF
    
    Log.d(TAG, "Encryption type: 0x${encryptionType.toString(16)}")
    Log.d(TAG, "Data length: $dataLength")
    
    // Verify CRC
    val receivedCrc = responseBytes[responseBytes.size - 1]
    val calculatedCrc = calculateCRC8(responseBytes, 0, responseBytes.size - 1)
    if (receivedCrc != calculatedCrc) {
      Log.w(TAG, "CRC mismatch: received=0x${(receivedCrc.toInt() and 0xFF).toString(16)}, calculated=0x${(calculatedCrc.toInt() and 0xFF).toString(16)}")
      // Continue anyway, some devices may have CRC issues
    }
    
    // Extract business data
    val businessData = if (encryptionType == ENCRYPTION_AES && encryptionKey != null) {
      // Decrypt
      val encryptedLength = responseBytes.size - 3 // Remove encryption type, length, CRC
      val encryptedData = responseBytes.copyOfRange(2, 2 + encryptedLength)
      decryptAES128ECB(encryptedData, encryptionKey).copyOfRange(0, dataLength)
    } else {
      responseBytes.copyOfRange(2, 2 + dataLength)
    }
    
    Log.d(TAG, "Business data: ${businessData.toHexString()}")
    
    if (businessData.isEmpty()) {
      Log.w(TAG, "Empty business data")
      return null
    }
    
    val command = businessData[0].toInt() and 0xFF
    Log.d(TAG, "Response command: 0x${command.toString(16)}")
    
    return parseBusinessData(command, businessData)
  }
  
  /**
   * Parse business data based on command type.
   */
  private fun parseBusinessData(command: Int, data: ByteArray): ParsedResponse {
    return when (command) {
      // Lock/Unlock/Check responses
      RESP_LOCK_SUCCESS, RESP_LOCK_AGAIN, RESP_LOCK_IS_OPENED,
      RESP_LOCK_LOW_VOLTAGE, RESP_LOCK_ILLEGAL_SHELL, RESP_LOCK_ROD_CUT_ALARM,
      RESP_LOCK_OPENED_ALARM -> {
        parseLockUnlockResponse(command, data, true)
      }
      
      RESP_UNLOCK_SUCCESS, RESP_UNLOCK_AGAIN, RESP_UNLOCK_WHEN_OPENED,
      RESP_UNLOCK_WRONG_PASSWORD, RESP_UNLOCK_ILLEGAL_OPENED,
      RESP_UNLOCK_EMERGENCY_OPENED, RESP_UNLOCK_OPENED_ALARM,
      RESP_UNLOCK_WITHOUT_SEALED, RESP_UNLOCK_ROD_CUT_ALARM,
      RESP_UNLOCK_OVERTIME -> {
        parseLockUnlockResponse(command, data, false)
      }
      
      RESP_CHECK_OK -> {
        parseLockUnlockResponse(command, data, null)
      }
      
      // Heartbeat
      CMD_HEARTBEAT -> {
        parseHeartbeatResponse(data)
      }
      
      // Alarm responses
      CMD_BROKE_ALARM, CMD_ROD_CUT_ALARM, CMD_OPENED_ALARM -> {
        parseAlarmResponse(command, data)
      }
      
      // Time calibration response
      CMD_CALIBRATE_TIME -> {
        ParsedResponse(
          command = command,
          isSuccess = true,
          lockId = null,
          batteryLevel = null,
          lockStatus = null,
          errorMessage = null
        )
      }
      
      // Version response
      CMD_CHECK_VERSION -> {
        parseVersionResponse(data)
      }
      
      // Work mode response
      CMD_SET_WORK_MODE -> {
        parseWorkModeResponse(data)
      }
      
      else -> {
        Log.w(TAG, "Unknown response command: 0x${command.toString(16)}")
        ParsedResponse(
          command = command,
          isSuccess = false,
          lockId = null,
          batteryLevel = null,
          lockStatus = null,
          errorMessage = "Unknown command: 0x${command.toString(16)}"
        )
      }
    }
  }
  
  /**
   * Parse Lock/Unlock/Check response.
   *
   * Response format: CMD(1B) + LockID(4B) + Battery(1B) + LockStatus(1B) + Reserved(1B) + OpSource(1B) + DateTime(6B)
   */
  private fun parseLockUnlockResponse(command: Int, data: ByteArray, isLockCommand: Boolean?): ParsedResponse {
    val isSuccess = when (command) {
      RESP_LOCK_SUCCESS, RESP_LOCK_AGAIN -> true
      RESP_UNLOCK_SUCCESS, RESP_UNLOCK_AGAIN -> true
      RESP_CHECK_OK -> true
      else -> false
    }
    
    val errorMessage = getErrorMessage(command)
    
    if (data.size < 15) {
      Log.w(TAG, "Lock/Unlock response too short: ${data.size} bytes")
      return ParsedResponse(
        command = command,
        isSuccess = isSuccess,
        lockId = null,
        batteryLevel = null,
        lockStatus = null,
        errorMessage = errorMessage
      )
    }
    
    // Extract fields
    val lockId = data.copyOfRange(1, 5)
    val batteryLevel = data[5].toInt() and 0xFF
    val lockStatus = data[6].toInt() and 0xFF
    val opSource = data[8].toInt() and 0xFF
    
    Log.d(TAG, "LockID: ${lockId.toHexString()}")
    Log.d(TAG, "Battery: $batteryLevel%")
    Log.d(TAG, "LockStatus: 0x${lockStatus.toString(16)} (${getLockStatusDescription(lockStatus)})")
    Log.d(TAG, "OpSource: 0x${opSource.toString(16)} (${getOpSourceDescription(opSource)})")
    
    return ParsedResponse(
      command = command,
      isSuccess = isSuccess,
      lockId = lockId,
      batteryLevel = batteryLevel,
      lockStatus = lockStatus,
      opSource = opSource,
      errorMessage = if (!isSuccess) errorMessage else null
    )
  }
  
  /**
   * Parse heartbeat response.
   *
   * Format: CMD(1B) + LockID(4B) + Battery(1B) + LockStatus(1B) + Reserved(1B) + DateTime(6B)
   */
  private fun parseHeartbeatResponse(data: ByteArray): ParsedResponse {
    if (data.size < 14) {
      Log.w(TAG, "Heartbeat response too short: ${data.size} bytes")
      return ParsedResponse(
        command = CMD_HEARTBEAT,
        isSuccess = true,
        lockId = null,
        batteryLevel = null,
        lockStatus = null,
        errorMessage = null
      )
    }
    
    val lockId = data.copyOfRange(1, 5)
    val batteryLevel = data[5].toInt() and 0xFF
    val lockStatus = data[6].toInt() and 0xFF
    
    Log.d(TAG, "Heartbeat - LockID: ${lockId.toHexString()}, Battery: $batteryLevel%, Status: 0x${lockStatus.toString(16)}")
    
    return ParsedResponse(
      command = CMD_HEARTBEAT,
      isSuccess = true,
      lockId = lockId,
      batteryLevel = batteryLevel,
      lockStatus = lockStatus,
      isHeartbeat = true,
      errorMessage = null
    )
  }
  
  /**
   * Parse alarm response.
   */
  private fun parseAlarmResponse(command: Int, data: ByteArray): ParsedResponse {
    val alarmType = when (command) {
      CMD_BROKE_ALARM -> "BROKE_ALARM"
      CMD_ROD_CUT_ALARM -> "ROD_CUT_ALARM"
      CMD_OPENED_ALARM -> "OPENED_ALARM"
      else -> "UNKNOWN_ALARM"
    }
    
    Log.w(TAG, "Alarm received: $alarmType")
    
    val lockId = if (data.size >= 5) data.copyOfRange(1, 5) else null
    val batteryLevel = if (data.size >= 6) data[5].toInt() and 0xFF else null
    val lockStatus = if (data.size >= 7) data[6].toInt() and 0xFF else null
    
    return ParsedResponse(
      command = command,
      isSuccess = false,
      lockId = lockId,
      batteryLevel = batteryLevel,
      lockStatus = lockStatus,
      isAlarm = true,
      alarmType = alarmType,
      errorMessage = "Alarm: $alarmType"
    )
  }
  
  /**
   * Parse version response.
   */
  private fun parseVersionResponse(data: ByteArray): ParsedResponse {
    val version = if (data.size > 1) {
      String(data.copyOfRange(1, data.size), Charsets.US_ASCII)
    } else {
      "Unknown"
    }
    
    Log.d(TAG, "Version: $version")
    
    return ParsedResponse(
      command = CMD_CHECK_VERSION,
      isSuccess = true,
      lockId = null,
      batteryLevel = null,
      lockStatus = null,
      version = version,
      errorMessage = null
    )
  }
  
  /**
   * Parse work mode response.
   */
  private fun parseWorkModeResponse(data: ByteArray): ParsedResponse {
    if (data.size < 11) {
      return ParsedResponse(
        command = CMD_SET_WORK_MODE,
        isSuccess = true,
        lockId = null,
        batteryLevel = null,
        lockStatus = null,
        errorMessage = null
      )
    }
    
    val lockId = data.copyOfRange(1, 5)
    val workMode = data[5].toInt() and 0xFF
    val isSleepMode = workMode == WORK_MODE_SLEEP
    
    Log.d(TAG, "Work mode: ${if (isSleepMode) "SLEEP" else "REALTIME"}")
    
    return ParsedResponse(
      command = CMD_SET_WORK_MODE,
      isSuccess = true,
      lockId = lockId,
      batteryLevel = null,
      lockStatus = null,
      workMode = workMode,
      errorMessage = null
    )
  }
  
  /**
   * Check if the lock status indicates locked state.
   */
  fun isLocked(lockStatus: Int): Boolean {
    val statusType = lockStatus and 0xF0
    return statusType == STATUS_SEALED || statusType == STATUS_LOCAL_SEALED
  }
  
  /**
   * Check if the lock status indicates unlocked state.
   */
  fun isUnlocked(lockStatus: Int): Boolean {
    val statusType = lockStatus and 0xF0
    return statusType == STATUS_UNSEALED || statusType == STATUS_OPEN
  }
  
  /**
   * Get lock status description.
   */
  fun getLockStatusDescription(lockStatus: Int): String {
    val statusType = lockStatus and 0xF0
    val alarmFlags = lockStatus and 0x0F
    
    val statusStr = when (statusType) {
      STATUS_OPEN -> "OPEN"
      STATUS_STANDBY -> "STANDBY"
      STATUS_NOT_READY -> "NOT_READY"
      STATUS_SEALED -> "SEALED"
      STATUS_LOCAL_SEALED -> "LOCAL_SEALED"
      STATUS_UNSEALED -> "UNSEALED"
      STATUS_ALARM -> "ALARM"
      STATUS_CANCEL_ALARM -> "CANCEL_ALARM"
      STATUS_ABNORMAL -> "ABNORMAL"
      else -> "UNKNOWN(0x${statusType.toString(16)})"
    }
    
    if (statusType == STATUS_ALARM && alarmFlags != 0) {
      val alarms = mutableListOf<String>()
      if ((alarmFlags and ALARM_ROD_CUT) != 0) alarms.add("ROD_CUT")
      if ((alarmFlags and ALARM_OPENED) != 0) alarms.add("OPENED")
      if ((alarmFlags and ALARM_SHELL_OPENED) != 0) alarms.add("SHELL_OPENED")
      if ((alarmFlags and ALARM_EMERGENCY) != 0) alarms.add("EMERGENCY")
      return "$statusStr[${alarms.joinToString(",")}]"
    }
    
    return statusStr
  }
  
  /**
   * Get operation source description.
   */
  private fun getOpSourceDescription(opSource: Int): String {
    return when (opSource) {
      OP_SOURCE_AUTOMATIC -> "AUTOMATIC"
      OP_SOURCE_KEYBOARD -> "KEYBOARD"
      OP_SOURCE_BLE -> "BLE"
      else -> "UNKNOWN($opSource)"
    }
  }
  
  /**
   * Get error message for response code.
   */
  private fun getErrorMessage(command: Int): String? {
    return when (command) {
      RESP_LOCK_SUCCESS, RESP_UNLOCK_SUCCESS, RESP_CHECK_OK -> null
      RESP_LOCK_AGAIN -> "Lock already locked"
      RESP_LOCK_IS_OPENED -> "Cannot lock: lock is open"
      RESP_LOCK_LOW_VOLTAGE -> "Cannot lock: low battery"
      RESP_LOCK_ILLEGAL_SHELL -> "Illegal shell removal"
      RESP_LOCK_ROD_CUT_ALARM -> "Cannot lock: rod cut alarm"
      RESP_LOCK_OPENED_ALARM -> "Cannot lock: opened alarm"
      RESP_UNLOCK_AGAIN -> "Lock already unlocked"
      RESP_UNLOCK_WHEN_OPENED -> "Unlock when already open"
      RESP_UNLOCK_WRONG_PASSWORD -> "Wrong password"
      RESP_UNLOCK_ILLEGAL_OPENED -> "Cannot unlock: illegal open detected"
      RESP_UNLOCK_EMERGENCY_OPENED -> "Cannot unlock: emergency open"
      RESP_UNLOCK_OPENED_ALARM -> "Cannot unlock: opened alarm"
      RESP_UNLOCK_WITHOUT_SEALED -> "Cannot unlock: not sealed"
      RESP_UNLOCK_ROD_CUT_ALARM -> "Cannot unlock: rod cut alarm"
      RESP_UNLOCK_OVERTIME -> "Unlock timeout"
      else -> "Unknown error: 0x${command.toString(16)}"
    }
  }
  
  /**
   * Get current date/time as 6 bytes: YY MM DD HH MM SS
   */
  private fun getCurrentDateTime(): ByteArray {
    val calendar = Calendar.getInstance()
    return byteArrayOf(
      (calendar.get(Calendar.YEAR) % 100).toByte(),
      (calendar.get(Calendar.MONTH) + 1).toByte(),
      calendar.get(Calendar.DAY_OF_MONTH).toByte(),
      calendar.get(Calendar.HOUR_OF_DAY).toByte(),
      calendar.get(Calendar.MINUTE).toByte(),
      calendar.get(Calendar.SECOND).toByte()
    )
  }
  
  /**
   * Calculate CRC8 using MAXIM polynomial.
   */
  private fun calculateCRC8(data: ByteArray, start: Int, length: Int): Byte {
    var crc = 0
    for (i in start until start + length) {
      crc = CRC8_TABLE[crc xor (data[i].toInt() and 0xFF)]
    }
    return crc.toByte()
  }
  
  /**
   * Encrypt data using AES-128 ECB.
   */
  private fun encryptAES128ECB(plaintext: ByteArray, key: ByteArray): ByteArray {
    val aesKey = if (key.size == 16) {
      key
    } else {
      ByteArray(16).also { adjusted ->
        System.arraycopy(key, 0, adjusted, 0, minOf(key.size, 16))
      }
    }
    
    // Pad to 16-byte blocks
    val paddedLength = ((plaintext.size + 15) / 16) * 16
    val paddedPlaintext = ByteArray(paddedLength)
    System.arraycopy(plaintext, 0, paddedPlaintext, 0, plaintext.size)
    
    val cipher = Cipher.getInstance("AES/ECB/NoPadding")
    val secretKey = SecretKeySpec(aesKey, "AES")
    cipher.init(Cipher.ENCRYPT_MODE, secretKey)
    
    return cipher.doFinal(paddedPlaintext)
  }
  
  /**
   * Decrypt data using AES-128 ECB.
   */
  private fun decryptAES128ECB(ciphertext: ByteArray, key: ByteArray): ByteArray {
    val aesKey = if (key.size == 16) {
      key
    } else {
      ByteArray(16).also { adjusted ->
        System.arraycopy(key, 0, adjusted, 0, minOf(key.size, 16))
      }
    }
    
    val cipher = Cipher.getInstance("AES/ECB/NoPadding")
    val secretKey = SecretKeySpec(aesKey, "AES")
    cipher.init(Cipher.DECRYPT_MODE, secretKey)
    
    return cipher.doFinal(ciphertext)
  }
  
  /**
   * Convert lock ID from numeric string to 4-byte array.
   * E.g., "83181001" -> [0x20, 0x7E, 0x03, 0xE9]
   */
  fun lockIdFromString(lockIdString: String): ByteArray {
    require(lockIdString.length == 8) { "Lock ID must be 8 digits" }
    
    val high = lockIdString.substring(0, 4).toInt()
    val low = lockIdString.substring(4, 8).toInt()
    
    return byteArrayOf(
      ((high shr 8) and 0xFF).toByte(),
      (high and 0xFF).toByte(),
      ((low shr 8) and 0xFF).toByte(),
      (low and 0xFF).toByte()
    )
  }
  
  /**
   * Convert 4-byte lock ID to numeric string.
   */
  fun lockIdToString(lockId: ByteArray): String {
    require(lockId.size == 4) { "Lock ID must be 4 bytes" }
    
    val high = ((lockId[0].toInt() and 0xFF) shl 8) or (lockId[1].toInt() and 0xFF)
    val low = ((lockId[2].toInt() and 0xFF) shl 8) or (lockId[3].toInt() and 0xFF)
    
    return "%04d%04d".format(high, low)
  }
  
  /**
   * Convert password string to 6-byte array (ASCII).
   */
  fun passwordFromString(password: String): ByteArray {
    require(password.length == 6) { "Password must be 6 characters" }
    return password.toByteArray(Charsets.US_ASCII)
  }
  
  private fun ByteArray.toHexString(): String = joinToString(" ") { "%02X".format(it) }
  
  /**
   * Parsed response data class.
   */
  data class ParsedResponse(
    val command: Int,
    val isSuccess: Boolean,
    val lockId: ByteArray?,
    val batteryLevel: Int?,
    val lockStatus: Int?,
    val opSource: Int? = null,
    val version: String? = null,
    val workMode: Int? = null,
    val isHeartbeat: Boolean = false,
    val isAlarm: Boolean = false,
    val alarmType: String? = null,
    val errorMessage: String?
  ) {
    override fun equals(other: Any?): Boolean {
      if (this === other) return true
      if (other !is ParsedResponse) return false
      return command == other.command &&
          isSuccess == other.isSuccess &&
          lockId?.contentEquals(other.lockId ?: byteArrayOf()) == true
    }
    
    override fun hashCode(): Int {
      var result = command
      result = 31 * result + isSuccess.hashCode()
      result = 31 * result + (lockId?.contentHashCode() ?: 0)
      return result
    }
  }
}
