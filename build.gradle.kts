plugins {
    id("com.android.application") version "9.5.0" apply false
    id("com.android.library") version "9.5.0" apply false
    id("org.jetbrains.kotlin.jvm") version "2.4.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
    id("com.google.dagger.hilt.android") version "2.60.1" apply false
    id("com.google.devtools.ksp") version "2.3.12" apply false
    id("io.gitlab.arturbosch.detekt") version "1.23.8"
}

detekt {
    source.setFrom(fileTree(rootDir) {
        include("app/src/**/*.kt", "core/src/**/*.kt", "ml/src/**/*.kt", "pipeline/src/**/*.kt")
    })
    config.setFrom("config/detekt/detekt.yml")
}
