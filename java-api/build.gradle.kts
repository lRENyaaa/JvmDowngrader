
fun SourceSet.inputOf(sourceSet: SourceSet) {
    compileClasspath += sourceSet.compileClasspath
    runtimeClasspath += sourceSet.runtimeClasspath
}

fun SourceSet.outputOf(sourceSet: SourceSet) {
    compileClasspath += sourceSet.output
    runtimeClasspath += sourceSet.output
}

operator fun JavaVersion.rangeTo(that: JavaVersion): Array<JavaVersion> {
    return JavaVersion.values().copyOfRange(this.ordinal, that.ordinal + 1)
}

sourceSets {
    for (vers in JavaVersion.VERSION_1_8..JavaVersion.VERSION_16) {
        val set = create("java${vers.ordinal + 2}") {
            inputOf(main.get())
            outputOf(main.get())
        }
    }
}

dependencies {
    implementation(project(":"))
}

for (vers in JavaVersion.VERSION_1_8..JavaVersion.VERSION_16) {
    tasks.getByName("compileJava${vers.ordinal + 2}Java") {
        (this as JavaCompile).configCompile(vers);
    }
}

tasks.jar {
    from(*sourceSets.toList().map { it.output }.toTypedArray())
}

fun JavaCompile.configCompile(version: JavaVersion) {
    sourceCompatibility = version.toString()
    targetCompatibility = version.toString()

    options.encoding = "UTF-8"
    options.release.set(version.ordinal + 1)
}