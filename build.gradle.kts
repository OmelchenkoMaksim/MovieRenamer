plugins {
    alias(libs.plugins.kotlin.jvm) apply false
}

tasks.register("run") {
    group = "application"
    description = "Runs the start module"
    dependsOn(":start:run")
}
