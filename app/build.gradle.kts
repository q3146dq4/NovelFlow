plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.ktlint)
}

val appVersionName = property("app.versionName") as String
val dotEnv =
  rootProject.layout.projectDirectory
    .file(".env")
    .asFile
    .takeIf { it.exists() }
    ?.readLines()
    ?.mapNotNull { line ->
      val trimmed = line.trim()
      if (trimmed.isEmpty() || trimmed.startsWith("#") || "=" !in trimmed) {
        null
      } else {
        val key = trimmed.substringBefore("=").trim()
        val value = trimmed.substringAfter("=").trim()
        key to value
      }
    }?.toMap()
    .orEmpty()

fun envVar(key: String): String? =
  providers.environmentVariable(key).orNull?.takeIf { it.isNotBlank() }
    ?: dotEnv[key]?.takeIf { it.isNotBlank() }

val releaseSigningEnvKeys =
  listOf(
    "KEYSTORE_PATH",
    "KEY_ALIAS",
    "KEYSTORE_PASSWORD",
  )
val missingReleaseSigningEnvKeys =
  releaseSigningEnvKeys.filter { envVar(it).isNullOrBlank() }

android {
  namespace = "com.novelflow.app"
  // compileSdk controls which Android API level the app is compiled against.
  // Raising it does not opt the app into new platform runtime behavior.
  compileSdk = 37
  defaultConfig {
    applicationId = "com.novelflow.app"
    // minSdk is the oldest Android version this app supports.
    minSdk = 31
    // targetSdk opts the app into platform behavior changes up to this API level.
    // Keep it separate from compileSdk so dependency API requirements can be met
    // without adopting newer runtime behavior before it is tested.
    //noinspection OldTargetApi - Setting it to 37 nags me, so I'll stay in 36 for awhile.
    targetSdk = 36
    versionCode = (property("app.versionCode") as String).toInt()
    versionName = appVersionName
  }
  signingConfigs {
    if (missingReleaseSigningEnvKeys.isEmpty()) {
      create("release") {
        storeFile = file(requireNotNull(envVar("KEYSTORE_PATH")))
        keyAlias = requireNotNull(envVar("KEY_ALIAS"))
        storePassword = requireNotNull(envVar("KEYSTORE_PASSWORD"))
        keyPassword = envVar("KEY_PASSWORD") ?: requireNotNull(envVar("KEYSTORE_PASSWORD"))
      }
    }
  }
  buildTypes {
    release {
      isMinifyEnabled = true
      isShrinkResources = true
      if (missingReleaseSigningEnvKeys.isEmpty()) {
        signingConfig = signingConfigs.getByName("release")
      }
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
    create("prerelease") {
      initWith(getByName("release"))
      signingConfig = signingConfigs.getByName("debug")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  androidResources {
    noCompress += "js"
  }
}

kotlin {
  jvmToolchain(17)
}

ktlint {
  additionalEditorconfig.set(mapOf("max_line_length" to "150"))
}

dependencies {
  implementation(libs.androidx.appcompat)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.preference)
  implementation(libs.androidx.recyclerview)
  implementation(libs.androidx.swiperefreshlayout)
  implementation(libs.androidx.webkit)
  implementation(libs.material)
}

val validateReleaseSigning =
  tasks.register("validateReleaseSigning") {
    description = "Validate environment variable required for signing APK."
    doLast {
      check(missingReleaseSigningEnvKeys.isEmpty()) {
        "Missing release signing environment variables: ${missingReleaseSigningEnvKeys.joinToString()}"
      }
    }
  }

tasks
  .matching { it.name == "assembleRelease" }
  .configureEach {
    mustRunAfter(validateReleaseSigning)
  }

tasks.register<Copy>("buildReleaseApk") {
  group = "build"
  description = "Builds the signed release APK and copies it to the root apk directory."
  dependsOn(validateReleaseSigning)
  dependsOn("assembleRelease")

  val releaseApk = layout.buildDirectory.file("outputs/apk/release/app-release.apk")
  from(releaseApk)
  into(rootProject.layout.projectDirectory.dir("apk"))
  rename { "NovelFlow-v$appVersionName.apk" }

  doFirst {
    check(releaseApk.get().asFile.exists()) {
      "Release APK was not found: ${releaseApk.get().asFile}"
    }
  }
}
