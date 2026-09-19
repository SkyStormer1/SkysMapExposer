import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.zip.ZipFile

plugins {
    id("net.fabricmc.fabric-loom")
    // Minecraft 26.x ships unobfuscated, which stock Loom's mapping lookup does not handle on its
    // own; this supplies the official Mojang mappings for it.
    id("dev.kikugie.loom-back-compat")
    id("org.jetbrains.kotlin.jvm")
}

version = "${property("mod_version")}+${property("minecraft_version")}"
group = property("maven_group")!!

base {
    archivesName = property("archives_base_name") as String
}

repositories {
    mavenCentral()
    maven("https://maven.terraformersmc.com/releases/") {
        name = "Terraformers"
        content { includeGroupAndSubgroups("com.terraformersmc") }
    }
    maven("https://api.modrinth.com/maven") {
        name = "Modrinth"
        content { includeGroup("maven.modrinth") }
    }
}

dependencies {
    minecraft("com.mojang:minecraft:${property("minecraft_version")}")
    loomx.applyMojangMappings()

    modImplementation("net.fabricmc:fabric-loader:${property("loader_version")}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_api_version")}")
    modImplementation("net.fabricmc:fabric-language-kotlin:${property("fabric_kotlin_version")}")

    // Compiled against for the mixin into its map screen, and loaded in the dev client for testing.
    // Never bundled: players install Xaero's World Map themselves.
    modCompileOnly("maven.modrinth:xaeros-world-map:${property("xaero_worldmap_version")}")
    modLocalRuntime("maven.modrinth:xaeros-world-map:${property("xaero_worldmap_version")}")
    modCompileOnly("maven.modrinth:xaeros-minimap:${property("xaero_minimap_version")}")
    modLocalRuntime("maven.modrinth:xaeros-minimap:${property("xaero_minimap_version")}")

    modCompileOnly("com.terraformersmc:modmenu:${property("modmenu_version")}")

    compileOnly(files(xaeroLibJar()))

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

/**
 * Xaero's Lib ships only inside Xaero's World Map jar, not as a download of its own, and the
 * compiler needs it because Xaero's map screen extends one of its classes. This copies it out of
 * the World Map jar Gradle already has. Compile-only: at run time Fabric loads it from inside
 * Xaero's jar as usual.
 */
fun xaeroLibJar(): File {
    val worldMap = configurations.detachedConfiguration(
        dependencies.create("maven.modrinth:xaeros-world-map:${property("xaero_worldmap_version")}")
    ).apply { isTransitive = false }.singleFile
    // Kept in .gradle/ rather than build/, so that `clean` in the same run cannot delete it
    // between Gradle reading this script and compiling against it.
    val target = layout.projectDirectory.file(".gradle/xaerolib/xaerolib.jar").asFile
    if (!target.exists() || target.lastModified() < worldMap.lastModified()) {
        target.parentFile.mkdirs()
        ZipFile(worldMap).use { zip ->
            val nested = zip.entries().asSequence().first { it.name.startsWith("META-INF/jars/xaerolib-") }
            zip.getInputStream(nested).use { input -> target.outputStream().use { input.copyTo(it) } }
        }
    }
    return target
}

// Minecraft 26.2 is built for Java 25, and the mixins here weave into its classes, so the mod has
// to be compiled for the same release rather than an older one.
val javaVersion = JavaVersion.VERSION_25

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = javaVersion.majorVersion.toInt()
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_25
    }
}

java {
    withSourcesJar()
    sourceCompatibility = javaVersion
    targetCompatibility = javaVersion
}

val resourceProperties = mapOf(
    "version" to version,
    "minecraft_version" to property("minecraft_version"),
    "loader_version" to property("loader_version"),
    "fabric_kotlin_version" to property("fabric_kotlin_version"),
    "java_version" to javaVersion.majorVersion,
)

tasks.processResources {
    val properties = resourceProperties
    inputs.properties(properties)
    filesMatching("fabric.mod.json") { expand(properties) }
    filesMatching("skysmapexposer*.mixins.json") { expand(properties) }
}

tasks.jar {
    from("LICENSE") { rename { "${it}_${base.archivesName.get()}" } }
}
