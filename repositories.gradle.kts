repositories {
    System.getenv("NEKO_PINNED_MAVEN")?.let { pinned ->
        exclusiveContent {
            forRepository { maven { url = uri(pinned) } }
            filter { includeVersion("org.jetbrains.kotlin", "kotlin-compiler-embeddable", "2.0.21") }
        }
    }
    google()
    mavenCentral()
    gradlePluginPortal()
    maven(url = "https://jitpack.io")
}