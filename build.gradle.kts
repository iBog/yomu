plugins {
    id("com.android.application") version "9.1.0" apply false
    id("com.android.library") version "9.1.0" apply false
    id("org.jetbrains.kotlin.jvm") version "2.2.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.21" apply false
    id("com.google.dagger.hilt.android") version "2.59.2" apply false
    id("com.google.devtools.ksp") version "2.3.6" apply false
    id("io.gitlab.arturbosch.detekt") version "1.23.7"
}

detekt {
    source.setFrom(fileTree(rootDir) {
        include("app/src/**/*.kt", "core/src/**/*.kt", "ml/src/**/*.kt", "pipeline/src/**/*.kt")
    })
    config.setFrom("config/detekt/detekt.yml")
}
