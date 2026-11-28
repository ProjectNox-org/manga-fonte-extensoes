import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "ManhuaThai"
    versionCode = 2
    contentWarning = ContentWarning.SAFE
    libVersion = "1.6"
    theme = "madara"

    source {
        lang = "th"
        baseUrl = "https://www.manhuathai.com"
    }
}

dependencies {

    implementation(project(":lib:unpacker"))
}
