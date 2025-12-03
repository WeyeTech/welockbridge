package com.welockbridge.sdk.core

//================================================================================
//WELOCKBRIDGE SDK v1.1.0 - JITPACK UPDATE
//================================================================================
//
//CHANGES SUMMARY:
//- Removed ALL hardcoded values (MAC prefixes, name patterns, service UUIDs)
//- Added SdkConfig.kt for configuration
//- Updated BleDeviceScanner.kt to use config
//- Updated WeLockBridgeSdk.kt to accept config
//- Updated README.md
//
//FILES TO UPDATE:
//1. NEW FILE: src/main/kotlin/com/welockbridge/sdk/core/SdkConfig.kt
//2. REPLACE: src/main/kotlin/com/welockbridge/sdk/scanner/BleDeviceScanner.kt
//3. REPLACE: src/main/kotlin/com/welockbridge/sdk/WeLockBridgeSdk.kt
//4. REPLACE: README.md
//
//================================================================================
//FILE 1: SdkConfig.kt (NEW FILE)
//Path: src/main/kotlin/com/welockbridge/sdk/core/SdkConfig.kt
//================================================================================


import java.util.UUID

/**
 * SDK Configuration for device identification.
 *
 * All device identification patterns are configurable - no hardcoded values.
 *
 * Usage:
 * ```kotlin
 * val config = SdkConfig.Builder()
 *     .addMacPrefix("DC:0D")
 *     .addNamePattern("g4-")
 *     .addServiceUuid("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
 *     .build()
 *
 * val sdk = WeLockBridgeSdk.getInstance(context, config)
 * ```
 */
data class SdkConfig(
  val macPrefixes: List<String>,
  val namePatterns: List<String>,
  val serviceUuids: List<UUID>,
  val minRssi: Int,
  val scanDurationMs: Long,
  val connectionTimeoutMs: Long,
  val autoIdentifyUnknown: Boolean
) {
  
  class Builder {
    private val macPrefixes = mutableListOf<String>()
    private val namePatterns = mutableListOf<String>()
    private val serviceUuids = mutableListOf<UUID>()
    private var minRssi: Int = -90
    private var scanDurationMs: Long = 30000L
    private var connectionTimeoutMs: Long = 15000L
    private var autoIdentifyUnknown: Boolean = true
    
    fun addMacPrefix(prefix: String): Builder {
      val normalized = prefix.uppercase().trim()
      if (normalized.isNotEmpty() && !macPrefixes.contains(normalized)) {
        macPrefixes.add(normalized)
      }
      return this
    }
    
    fun addMacPrefixes(prefixes: List<String>): Builder {
      prefixes.forEach { addMacPrefix(it) }
      return this
    }
    
    fun addNamePattern(pattern: String): Builder {
      val normalized = pattern.lowercase().trim()
      if (normalized.isNotEmpty() && !namePatterns.contains(normalized)) {
        namePatterns.add(normalized)
      }
      return this
    }
    
    fun addNamePatterns(patterns: List<String>): Builder {
      patterns.forEach { addNamePattern(it) }
      return this
    }
    
    fun addServiceUuid(uuid: UUID): Builder {
      if (!serviceUuids.contains(uuid)) {
        serviceUuids.add(uuid)
      }
      return this
    }
    
    fun addServiceUuid(uuidString: String): Builder {
      try {
        val uuid = UUID.fromString(uuidString)
        addServiceUuid(uuid)
      } catch (e: Exception) {
        // Invalid UUID, ignore
      }
      return this
    }
    
    fun addServiceUuids(uuids: List<UUID>): Builder {
      uuids.forEach { addServiceUuid(it) }
      return this
    }
    
    fun setMinRssi(rssi: Int): Builder {
      minRssi = rssi
      return this
    }
    
    fun setScanDuration(durationMs: Long): Builder {
      scanDurationMs = durationMs
      return this
    }
    
    fun setConnectionTimeout(timeoutMs: Long): Builder {
      connectionTimeoutMs = timeoutMs
      return this
    }
    
    fun setAutoIdentifyUnknown(enabled: Boolean): Builder {
      autoIdentifyUnknown = enabled
      return this
    }
    
    fun build(): SdkConfig {
      return SdkConfig(
        macPrefixes = macPrefixes.toList(),
        namePatterns = namePatterns.toList(),
        serviceUuids = serviceUuids.toList(),
        minRssi = minRssi,
        scanDurationMs = scanDurationMs,
        connectionTimeoutMs = connectionTimeoutMs,
        autoIdentifyUnknown = autoIdentifyUnknown
      )
    }
  }
  
  companion object {
    fun empty(): SdkConfig {
      return Builder().build()
    }
    
    fun fromJson(json: String): SdkConfig {
      val builder = Builder()
      
      try {
        val macPrefixesRegex = """"macPrefixes"\s*:\s*\[(.*?)\]""".toRegex()
        macPrefixesRegex.find(json)?.groupValues?.get(1)?.let { arrayContent ->
          """"([^"]+)"""".toRegex().findAll(arrayContent).forEach {
            builder.addMacPrefix(it.groupValues[1])
          }
        }
        
        val namePatternsRegex = """"namePatterns"\s*:\s*\[(.*?)\]""".toRegex()
        namePatternsRegex.find(json)?.groupValues?.get(1)?.let { arrayContent ->
          """"([^"]+)"""".toRegex().findAll(arrayContent).forEach {
            builder.addNamePattern(it.groupValues[1])
          }
        }
        
        val serviceUuidsRegex = """"serviceUuids"\s*:\s*\[(.*?)\]""".toRegex()
        serviceUuidsRegex.find(json)?.groupValues?.get(1)?.let { arrayContent ->
          """"([^"]+)"""".toRegex().findAll(arrayContent).forEach {
            builder.addServiceUuid(it.groupValues[1])
          }
        }
        
        val minRssiRegex = """"minRssi"\s*:\s*(-?\d+)""".toRegex()
        minRssiRegex.find(json)?.groupValues?.get(1)?.toIntOrNull()?.let {
          builder.setMinRssi(it)
        }
        
        val scanDurationRegex = """"scanDurationMs"\s*:\s*(\d+)""".toRegex()
        scanDurationRegex.find(json)?.groupValues?.get(1)?.toLongOrNull()?.let {
          builder.setScanDuration(it)
        }
        
        val connectionTimeoutRegex = """"connectionTimeoutMs"\s*:\s*(\d+)""".toRegex()
        connectionTimeoutRegex.find(json)?.groupValues?.get(1)?.toLongOrNull()?.let {
          builder.setConnectionTimeout(it)
        }
        
      } catch (e: Exception) {
        // Return empty config on parse error
      }
      
      return builder.build()
    }
  }
  
  fun hasPatterns(): Boolean {
    return macPrefixes.isNotEmpty() || namePatterns.isNotEmpty() || serviceUuids.isNotEmpty()
  }
  
  fun withMacPrefix(prefix: String): SdkConfig {
    return copy(macPrefixes = macPrefixes + prefix.uppercase().trim())
  }
  
  fun withNamePattern(pattern: String): SdkConfig {
    return copy(namePatterns = namePatterns + pattern.lowercase().trim())
  }
  
  fun withServiceUuid(uuid: UUID): SdkConfig {
    return copy(serviceUuids = serviceUuids + uuid)
  }
}