import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.0.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
    id("org.jetbrains.compose") version "1.7.0"
}

kotlin { jvmToolchain(17) }

// Code commun avec l'application Android
sourceSets["main"].kotlin.srcDir("../shared/src")
sourceSets["main"].resources.srcDir("../shared/data")

val os = System.getProperty("os.name").lowercase()
val fxPlatform = when {
    "win" in os -> "win"
    "mac" in os -> if (System.getProperty("os.arch") == "aarch64") "mac-aarch64" else "mac"
    else -> "linux"
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
    implementation("org.json:json:20240303")
    // Lecture audio : JavaFX Media (MP3, AAC, HLS ; aucune installation supplémentaire pour l'utilisateur)
    for (m in listOf("base", "graphics", "media")) implementation("org.openjfx:javafx-$m:21.0.5:$fxPlatform")
}

compose.desktop {
    application {
        mainClass = "app.podial.MainKt"
        nativeDistributions {
            // .dmg (macOS), .exe et .msi (Windows), .deb et .rpm (Linux)
            targetFormats(TargetFormat.Dmg, TargetFormat.Exe, TargetFormat.Msi, TargetFormat.Deb, TargetFormat.Rpm)
            packageName = "Podial"
            packageVersion = "0.5.4"
            description = "Podcasts and live radio"
            vendor = "Podial"
            modules("java.sql", "jdk.unsupported")
            // Icônes facultatives : si le fichier existe dans icons/, il est utilisé
            fun icon(n: String) = project.file("icons/$n").takeIf { it.exists() }
            linux { icon("podial.png")?.let { iconFile.set(it) } }
            windows { menuGroup = "Podial"; icon("podial.ico")?.let { iconFile.set(it) } }
            macOS { packageVersion = "1.0.4"; bundleID = "app.podial"; icon("podial.icns")?.let { iconFile.set(it) } }
        }
    }
}
