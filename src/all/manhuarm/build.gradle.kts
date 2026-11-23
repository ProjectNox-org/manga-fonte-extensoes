import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Manhuarm"
    versionCode = 2
    contentWarning = ContentWarning.MIXED
    libVersion = "1.6"
    theme = "manhuarm"

    listOf("ar", "en", "es", "fr", "id", "it", "pt-BR").forEach {
        source {
            lang = it
            baseUrl = "https://manhuarmtl.com"
        }
    }
}

android {
    sourceSets.named("test") {
        kotlin.directories.add("test")
    }
}

dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.bundles.common)
    testImplementation(libs.tachiyomi.lib.v16)
}

tasks.matching { it.name.startsWith("ksp") && it.name.contains("UnitTest") }.configureEach {
    enabled = false
}
