import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Mangastash"
    versionCode = 2
    contentWarning = ContentWarning.MIXED
    libVersion = "1.6"

    source {
        baseUrl = "https://mangastash.org"
        lang = "en"
    }

    deeplink {
        path("/..*/")
        path("/..*/chapter-..*")
    }
}
