import org.gradle.api.tasks.JavaExec

plugins {
    kotlin("jvm")
    application
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("com.example.main_jvm.MainKt")
}

tasks.named<JavaExec>("run") {
    defaultCharacterEncoding = "UTF-8"
}


tasks.withType<JavaExec>().configureEach {
    jvmArgs(
        "-Dfile.encoding=UTF-8",
        "-Dstdout.encoding=UTF-8",
        "-Dstderr.encoding=UTF-8",
    )
}
