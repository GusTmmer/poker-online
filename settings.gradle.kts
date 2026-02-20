plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.5.0"
}

rootProject.name = "Poker"

include("poker-engine")
include("server-gcp")
