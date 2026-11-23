plugins {
    id("com.android.application") version "9.5.2" apply false
    // AGP 9 compiles Kotlin itself ("built-in Kotlin"); declaring the Kotlin plugin here
    // (not applied) just selects the Kotlin version it uses.
    id("org.jetbrains.kotlin.android") version "2.4.21" apply false
}
