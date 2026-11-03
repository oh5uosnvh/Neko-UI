pluginManagement {
    repositories {
        // Kotlin DSL's transitive stdlib/BOM are Maven Central artifacts. Do not depend
        // solely on Plugin Portal redirects for them on clean CI runners.
        mavenCentral()
        gradlePluginPortal()
    }
}
