enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        gradlePluginPortal()
        maven {
            name = "FabricMC"
            setUrl("https://maven.fabricmc.net")
        }
        maven {
            name = "NeoForge"
            setUrl("https://maven.neoforged.net/releases")}
        maven {
            name = "Kotlin for Forge"
            setUrl("https://thedarkcolour.github.io/KotlinForForge/")
        }
    }
}

rootProject.name = "wgpu-mc"

subProject("rust")
subProject("fabric")
subProject("neoforge")

fun subProject(name: String) {
    setupSubproject("wgpu-mc-$name") {
        projectDir = file(name)
    }
}

inline fun setupSubproject(name: String, block: ProjectDescriptor.() -> Unit) {
    include(name)
    project(":$name").apply(block)
}

