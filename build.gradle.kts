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

    // Compiled against for the hooks into each map, and loaded in the dev client for testing. Never
    // bundled: players install their map mod themselves. `-PmapMod=xaero` (the default),
    // `journeymap`, `both` or `none` picks which the dev client runs with.
    val mapMod = findProperty("mapMod")?.toString() ?: "xaero"
    modCompileOnly("maven.modrinth:xaeros-world-map:${property("xaero_worldmap_version")}")
    modCompileOnly("maven.modrinth:xaeros-minimap:${property("xaero_minimap_version")}")
    if (mapMod == "xaero" || mapMod == "both") {
        modLocalRuntime("maven.modrinth:xaeros-world-map:${property("xaero_worldmap_version")}")
        modLocalRuntime("maven.modrinth:xaeros-minimap:${property("xaero_minimap_version")}")
    }
    // JourneyMap's API, which the plugin talks to it through; and JourneyMap itself, for this mod's
    // hooks into it (mixin/journeymap), as Xaero's hooks are compiled against Xaero. Never bundled.
    compileOnly(files(nestedJar("maven.modrinth:journeymap:${property("journeymap_version")}", "META-INF/jars/journeymap-api-", "journeymap-api")))
    modCompileOnly("maven.modrinth:journeymap:${property("journeymap_version")}")
    if (mapMod == "journeymap" || mapMod == "both") {
        modLocalRuntime("maven.modrinth:journeymap:${property("journeymap_version")}")
    }

    modCompileOnly("com.terraformersmc:modmenu:${property("modmenu_version")}")

    // Experimental terrain fill hands chunks to whichever of these is installed. Compiled against
    // only, and only touched when the mod is loaded; never bundled.
    modCompileOnly("maven.modrinth:bobby:${property("bobby_version")}")
    // A Voxy jar for this Minecraft version dropped in `.gradle/voxy/` (gitignored) is used until
    // that version is on Modrinth; Voxy is All Rights Reserved, so it is never committed.
    val localVoxy = rootProject.file(".gradle/voxy/voxy-${property("minecraft_version")}.jar")
    if (localVoxy.exists()) modCompileOnly(files(localVoxy)) else modCompileOnly("maven.modrinth:voxy:${property("voxy_version")}")
    modCompileOnly("maven.modrinth:distanthorizons:${property("distanthorizons_version")}")

    compileOnly(files(nestedJar("maven.modrinth:xaeros-world-map:${property("xaero_worldmap_version")}", "META-INF/jars/xaerolib-", "xaerolib")))

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

/**
 * A jar shipped only inside another mod's jar, copied out of the copy Gradle already has, for the
 * compiler: Xaero's Lib (inside Xaero's World Map, which extends one of its classes) and
 * JourneyMap's API (inside JourneyMap). Compile-only: at run time Fabric loads each from inside
 * its mod as usual.
 */
fun nestedJar(coordinate: String, prefix: String, name: String): File {
    val outer = configurations.detachedConfiguration(dependencies.create(coordinate)).apply { isTransitive = false }.singleFile
    // Kept in .gradle/ rather than build/, so that `clean` in the same run cannot delete it
    // between Gradle reading this script and compiling against it.
    val target = layout.projectDirectory.file(".gradle/nested/$name.jar").asFile
    if (!target.exists() || target.lastModified() < outer.lastModified()) {
        target.parentFile.mkdirs()
        ZipFile(outer).use { zip ->
            val nested = zip.entries().asSequence().first { it.name.startsWith(prefix) }
            zip.getInputStream(nested).use { input -> target.outputStream().use { input.copyTo(it) } }
        }
    }
    return target
}

// Minecraft 26.x is built for Java 25, and the mixins here weave into its classes, so the mod has
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
    "xaero_worldmap_min" to property("xaero_worldmap_min"),
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
    from(rootProject.file("LICENSE")) { rename { "${it}_${base.archivesName.get()}" } }
}

// A development client drives itself through the steps in the file SME_AUTOTEST names (AutoTest).
tasks.withType<JavaExec>().configureEach {
    System.getenv("SME_AUTOTEST")?.let { systemProperty("skysmapexposer.autotest", it) }
}
