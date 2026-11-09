
plugins {
    id("com.gtnewhorizons.gtnhconvention")
}

tasks.test {
    useJUnitPlatform()
}

// The development render harness (com.akashic.futura.dev, enabled by a futura3d-harness file in run/client) never
// ships: it is only packed with -Pharness, e.g. ./gradlew runClient -Pharness
val withHarness = providers.gradleProperty("harness").isPresent
tasks.named<Jar>("jar") {
    if (!withHarness) {
        exclude("com/akashic/futura/dev/**")
    }
}
