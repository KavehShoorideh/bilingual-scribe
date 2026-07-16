// Intentionally minimal: plugin versions live in gradle/libs.versions.toml and
// each module applies exactly what it needs. Nothing is applied at the root so
// pure-JVM modules (:core) remain buildable without the Android toolchain.
tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
