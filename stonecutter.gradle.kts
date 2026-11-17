plugins {
    id("dev.kikugie.stonecutter")
    id("net.fabricmc.fabric-loom") apply false
    id("org.jetbrains.kotlin.jvm") apply false
}

// The version the source in src/ is switched to. `gradlew build` builds every version.
stonecutter active "26.3"
