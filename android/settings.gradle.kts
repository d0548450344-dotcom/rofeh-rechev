pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "rofeh-rechev"

include(":core", ":transport", ":protocol", ":simulation", ":data", ":checks")

fun androidSdkDir(): java.io.File? {
    val env = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
    if (!env.isNullOrBlank()) {
        val dir = java.io.File(env)
        if (dir.isDirectory) return dir
    }
    val local = java.io.File(rootDir, "local.properties")
    if (!local.isFile) return null
    val props = java.util.Properties()
    local.inputStream().use { props.load(it) }
    val path = props.getProperty("sdk.dir") ?: return null
    val dir = java.io.File(path)
    return if (dir.isDirectory) dir else null
}

val sdk = androidSdkDir()
val platforms = sdk?.let { java.io.File(it, "platforms") }
if (sdk != null && platforms != null && platforms.isDirectory) {
    include(":app")
    println("Android SDK found at ${sdk.path}. Including :app.")
} else {
    println("Android SDK not found. :app sources are on disk but are not compiled in this environment.")
}
