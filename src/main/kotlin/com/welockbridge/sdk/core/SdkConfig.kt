package com.welockbridge.sdk.core

import java.util.UUID

/**
 * SDK Configuration for device identification.
 *
 * This allows full customization of how devices are identified
 * without any hardcoded values in the SDK.
 *
 * Usage:
 * ```kotlin
 * val config = SdkConfig.Builder()
 *     .addMacPrefix("DC:0D")
 *     .addMacPrefix("E8:31")
 *     .addNamePattern("g4-")
 *     .addNamePattern("lock")
 *     .addServiceUuid(UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e"))
 *     .setMinRssi(-85)
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
  
  /**
   * Builder for SdkConfig
   */
  class Builder {
    private val macPrefixes = mutableListOf<String>()
    private val namePatterns = mutableListOf<String>()
    private val serviceUuids = mutableListOf<UUID>()
    private var minRssi: Int = -90
    private var scanDurationMs: Long = 30000L
    private var connectionTimeoutMs: Long = 15000L
    private var autoIdentifyUnknown: Boolean = true
    
    /**
     * Add MAC address prefix for device identification.
     * Example: "DC:0D" or "DC:0D:30"
     */
    fun addMacPrefix(prefix: String): Builder {
      val normalized = prefix.uppercase().trim()
      if (normalized.isNotEmpty() && !macPrefixes.contains(normalized)) {
        macPrefixes.add(normalized)
      }
      return this
    }
    
    /**
     * Add multiple MAC prefixes at once.
     */
    fun addMacPrefixes(prefixes: List<String>): Builder {
      prefixes.forEach { addMacPrefix(it) }
      return this
    }
    
    /**
     * Add device name pattern for identification (case-insensitive).
     * Example: "g4-", "lock", "bander"
     */
    fun addNamePattern(pattern: String): Builder {
      val normalized = pattern.lowercase().trim()
      if (normalized.isNotEmpty() && !namePatterns.contains(normalized)) {
        namePatterns.add(normalized)
      }
      return this
    }
    
    /**
     * Add multiple name patterns at once.
     */
    fun addNamePatterns(patterns: List<String>): Builder {
      patterns.forEach { addNamePattern(it) }
      return this
    }
    
    /**
     * Add service UUID for device identification.
     */
    fun addServiceUuid(uuid: UUID): Builder {
      if (!serviceUuids.contains(uuid)) {
        serviceUuids.add(uuid)
      }
      return this
    }
    
    /**
     * Add service UUID from string.
     */
    fun addServiceUuid(uuidString: String): Builder {
      try {
        val uuid = UUID.fromString(uuidString)
        addServiceUuid(uuid)
      } catch (e: Exception) {
        // Invalid UUID, ignore
      }
      return this
    }
    
    /**
     * Add multiple service UUIDs at once.
     */
    fun addServiceUuids(uuids: List<UUID>): Builder {
      uuids.forEach { addServiceUuid(it) }
      return this
    }
    
    /**
     * Set minimum RSSI for device discovery.
     * Default: -90 dBm
     */
    fun setMinRssi(rssi: Int): Builder {
      minRssi = rssi
      return this
    }
    
    /**
     * Set scan duration in milliseconds.
     * Default: 30000 (30 seconds)
     */
    fun setScanDuration(durationMs: Long): Builder {
      scanDurationMs = durationMs
      return this
    }
    
    /**
     * Set connection timeout in milliseconds.
     * Default: 15000 (15 seconds)
     */
    fun setConnectionTimeout(timeoutMs: Long): Builder {
      connectionTimeoutMs = timeoutMs
      return this
    }
    
    /**
     * If true, devices with manufacturer data or service UUIDs
     * will be included even if not matched by patterns.
     * Default: true
     */
    fun setAutoIdentifyUnknown(enabled: Boolean): Builder {
      autoIdentifyUnknown = enabled
      return this
    }
    
    /**
     * Build the configuration.
     */
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
    /**
     * Create an empty config (no device identification patterns).
     * Use this and add patterns at runtime.
     */
    fun empty(): SdkConfig {
      return Builder().build()
    }
    
    /**
     * Create config from JSON string.
     * Expected format:
     * {
     *   "macPrefixes": ["DC:0D", "E8:31"],
     *   "namePatterns": ["g4-", "lock"],
     *   "serviceUuids": ["6e400001-b5a3-f393-e0a9-e50e24dcca9e"],
     *   "minRssi": -90,
     *   "scanDurationMs": 30000,
     *   "connectionTimeoutMs": 15000
     * }
     */
    fun fromJson(json: String): SdkConfig {
      val builder = Builder()
      
      try {
        // Simple JSON parsing without external library
        // Extract macPrefixes
        val macPrefixesRegex = """"macPrefixes"\s*:\s*\[(.*?)\]""".toRegex()
        macPrefixesRegex.find(json)?.groupValues?.get(1)?.let { arrayContent ->
          """"([^"]+)"""".toRegex().findAll(arrayContent).forEach {
            builder.addMacPrefix(it.groupValues[1])
          }
        }
        
        // Extract namePatterns
        val namePatternsRegex = """"namePatterns"\s*:\s*\[(.*?)\]""".toRegex()
        namePatternsRegex.find(json)?.groupValues?.get(1)?.let { arrayContent ->
          """"([^"]+)"""".toRegex().findAll(arrayContent).forEach {
            builder.addNamePattern(it.groupValues[1])
          }
        }
        
        // Extract serviceUuids
        val serviceUuidsRegex = """"serviceUuids"\s*:\s*\[(.*?)\]""".toRegex()
        serviceUuidsRegex.find(json)?.groupValues?.get(1)?.let { arrayContent ->
          """"([^"]+)"""".toRegex().findAll(arrayContent).forEach {
            builder.addServiceUuid(it.groupValues[1])
          }
        }
        
        // Extract minRssi
        val minRssiRegex = """"minRssi"\s*:\s*(-?\d+)""".toRegex()
        minRssiRegex.find(json)?.groupValues?.get(1)?.toIntOrNull()?.let {
          builder.setMinRssi(it)
        }
        
        // Extract scanDurationMs
        val scanDurationRegex = """"scanDurationMs"\s*:\s*(\d+)""".toRegex()
        scanDurationRegex.find(json)?.groupValues?.get(1)?.toLongOrNull()?.let {
          builder.setScanDuration(it)
        }
        
        // Extract connectionTimeoutMs
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
  
  /**
   * Check if config has any identification patterns.
   */
  fun hasPatterns(): Boolean {
    return macPrefixes.isNotEmpty() || namePatterns.isNotEmpty() || serviceUuids.isNotEmpty()
  }
  
  /**
   * Create a new config with additional patterns.
   */
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
