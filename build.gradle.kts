plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0" apply false
}

// Build output goes to %LOCALAPPDATA%\KineticPulseBuild instead of this
// folder, so OneDrive doesn't try to sync (and lock) hundreds of MB of
// temporary build files. Cloud builds (no LOCALAPPDATA) use the normal place.
System.getenv("LOCALAPPDATA")?.let { local ->
    val localBuildRoot = File(local, "KineticPulseBuild")
    allprojects {
        layout.buildDirectory.set(File(localBuildRoot, project.name))
    }
}
