plugins {
    // Kotlin needs no plugin of its own: AGP 9 compiles it in-process (built-in Kotlin), so the
    // Kotlin compiler version comes with this AGP version. Per-module options live in the
    // top-level kotlin { compilerOptions { } } block of each module's build file.
    id("com.android.application") version "9.4.1" apply false
}
