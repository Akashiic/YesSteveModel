
plugins {
    id("com.gtnewhorizons.gtnhconvention")
}

tasks.test {
    useJUnitPlatform()
}

// The development render harness (com.akashic.futura.dev, enabled by a futura3d-harness file in run/client) never
// ships: it is only packed with -Pharness, e.g. ./gradlew runClient -Pharness
val withHarness = providers.gradleProperty("harness").isPresent
// With shadowed dependencies (Concentus) the release jar comes from shadowJar, which reads the compiled classes itself
// rather than the jar task's output, so both need the exclusion.
tasks.withType<Jar>().matching { it.name == "jar" || it.name == "shadowJar" }.configureEach {
    if (!withHarness) {
        exclude("com/akashic/futura/dev/**")
    }
}
