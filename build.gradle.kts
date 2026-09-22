// A build that only reports which JVMs took part, so the bootstrap can be observed.
tasks.register("whichJvm") {
    val clientJvm = providers.systemProperty("org.gradle.wrapper.projectDir").orElse("n/a")
    doLast {
        println("Daemon JVM: ${System.getProperty("java.home")} (${System.getProperty("java.version")})")
        println("Project dir the script passed: ${clientJvm.get()}")
    }
}
