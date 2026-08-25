plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(17)
}

val utf8JvmArgs = listOf(
    "-Dfile.encoding=UTF-8",
    "-Dsun.jnu.encoding=UTF-8",
    "-Dnative.encoding=UTF-8",
    "-Dstdout.encoding=UTF-8",
    "-Dstderr.encoding=UTF-8",
    "-Dsun.stdout.encoding=UTF-8",
    "-Dsun.stderr.encoding=UTF-8",
    "-Dconsole.encoding=UTF-8",
)

application {
    mainClass.set("movierenamer.MainKt")
    applicationDefaultJvmArgs = utf8JvmArgs
}

dependencies {
    implementation(project(":core"))
}

tasks.withType<JavaExec>().configureEach {
    jvmArgs(utf8JvmArgs)
}

tasks.named<CreateStartScripts>("startScripts") {
    doLast {
        val script = windowsScript.readText()
        if ("chcp 65001" in script) return@doLast
        windowsScript.writeText(
            script.replace(
                "if \"%OS%\"==\"Windows_NT\" setlocal",
                "if \"%OS%\"==\"Windows_NT\" setlocal\r\nchcp 65001 >NUL",
            ),
        )
    }
}
