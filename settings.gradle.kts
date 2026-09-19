pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
        maven("https://maven.kikugie.dev/releases") { name = "KikuGie Releases" }
        maven("https://maven.kikugie.dev/snapshots") { name = "KikuGie Snapshots" }
        mavenCentral()
        gradlePluginPortal()
    }

    plugins {
        id("net.fabricmc.fabric-loom") version providers.gradleProperty("loom_version")
        id("net.fabricmc.fabric-loom-remap") version providers.gradleProperty("loom_version")
        id("dev.kikugie.loom-back-compat") version "0.3"
        id("org.jetbrains.kotlin.jvm") version providers.gradleProperty("kotlin_version")
    }
}

plugins {
    id("dev.kikugie.loom-back-compat") version "0.3"
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

extra["loomx.loom_version"] = providers.gradleProperty("loom_version").get()
extra["loomx.loom_remap_plugin"] = "net.fabricmc.fabric-loom-remap"
extra["loomx.loom_unobf_plugin"] = "net.fabricmc.fabric-loom"

loomx {
    // Minecraft 26.x ships unobfuscated, and this mod targets nothing older.
    unobfuscated { true }
}

rootProject.name = "SkysMapExposer"
