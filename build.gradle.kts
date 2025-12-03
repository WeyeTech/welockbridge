// build.gradle.kts (module: welockbridge)

import org.gradle.api.JavaVersion
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication

buildscript {
  repositories {
    google()
    mavenCentral()
  }
  dependencies {
    classpath("com.android.tools.build:gradle:8.7.3")
    classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:1.9.20")
  }
}

apply(plugin = "com.android.library")
apply(plugin = "org.jetbrains.kotlin.android")
apply(plugin = "maven-publish")

repositories {
  google()
  mavenCentral()
}

// ============================================================================
// SDK Version Configuration  (JitPack coordinates)
// ============================================================================

val sdkVersion = "1.1.0"
val sdkGroupId = "com.github.WeyeTech"
val sdkArtifactId = "welockbridge"

group = sdkGroupId
version = sdkVersion

// ============================================================================
// ANDROID LIBRARY CONFIG
// ============================================================================

configure<com.android.build.gradle.LibraryExtension> {
  namespace = "com.welockbridge.sdk"
  compileSdk = 36
  
  defaultConfig {
    minSdk = 24
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    consumerProguardFiles("consumer-rules.pro")
  }
  
  buildTypes {
    getByName("release") {
      isMinifyEnabled = true
      proguardFiles(
        getDefaultProguardFile("proguard-android-optimize.txt"),
        "proguard-rules.pro"
      )
    }
    getByName("debug") {
      isMinifyEnabled = false
    }
  }
  
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  
  buildFeatures {
    buildConfig = true
  }
  
  // Enable sources JAR for documentation
  publishing {
    singleVariant("release") {
      withSourcesJar()
    }
  }
}

// Configure Kotlin options
tasks.withType<KotlinCompile>().configureEach {
  kotlinOptions {
    jvmTarget = "11"
  }
}

// ============================================================================
// DEPENDENCIES
// ============================================================================

dependencies {
  // Kotlin
  add("implementation", "org.jetbrains.kotlin:kotlin-stdlib:1.9.20")
  add("implementation", "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
  add("implementation", "org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
  
  // AndroidX
  add("implementation", "androidx.core:core-ktx:1.12.0")
  add("implementation", "androidx.appcompat:appcompat:1.6.1")
  add("implementation", "androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
  
  // Testing
  add("testImplementation", "junit:junit:4.13.2")
  add("testImplementation", "org.mockito:mockito-core:5.5.0")
  add("androidTestImplementation", "androidx.test.ext:junit:1.1.5")
  add("androidTestImplementation", "androidx.test.espresso:espresso-core:3.5.1")
}

// ============================================================================
// PUBLISHING CONFIGURATION
// ============================================================================

afterEvaluate {
  configure<PublishingExtension> {
    publications {
      create<MavenPublication>("release") {
        from(components["release"])
        
        groupId = sdkGroupId
        artifactId = sdkArtifactId
        version = sdkVersion
        
        pom {
          name.set("WeLockBridge BLE SDK")
          description.set("Professional BLE SDK for G-Series digital locks")
          url.set("https://github.com/WeyeTech/welockbridge") // 🔴 CHANGED
        }
      }
    }
    
    repositories {
      // Publish to local Maven for testing
      mavenLocal()
      
      // GitHub Packages - keep commented for now
      /*
      maven {
          name = "GitHubPackages"
          url = uri("https://maven.pkg.github.com/WeyeTech/welockbridge")
          credentials {
              username = System.getenv("GITHUB_USERNAME") ?: ""
              password = System.getenv("GITHUB_TOKEN") ?: ""
          }
      }
      */
    }
  }
}

// ============================================================================
// CUSTOM TASKS
// ============================================================================

// Build release AAR
tasks.register("buildRelease") {
  dependsOn("assembleRelease")
  doLast {
    println("✓ Release AAR built: build/outputs/aar/welockbridge-release.aar")
  }
}

// Publish to local Maven
tasks.register("publishLocal") {
  dependsOn("publishToMavenLocal")
  doLast {
    println("✓ Published to Maven Local: $sdkGroupId:$sdkArtifactId:$sdkVersion")
  }
}