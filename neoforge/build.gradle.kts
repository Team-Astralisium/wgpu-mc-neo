import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.tasks.Jar
import org.gradle.language.jvm.tasks.ProcessResources

plugins {
	`java-library`
	`maven-publish`
	kotlin("jvm") version "2.2.20"
	id("net.neoforged.gradle.userdev") version "7.1.25"
	id("fr.stardustenterprises.rust.importer") version "2.1.0"
	id("io.freefair.lombok") version "8.4"
}

val neoforgeMinecraftVersion = providers.gradleProperty("neoforge_minecraft_version").get()
val neoforgeNeoVersion = providers.gradleProperty("neoforge_neo_version").get()
val neoforgeModVersion = providers.gradleProperty("neoforge_mod_version").get()
val neoforgeMavenGroup = providers.gradleProperty("neoforge_maven_group").get()

val modId = "wgpu_mc"
val modName = "wgpu-mc"
val modLicense = "GPLv3"
val loaderVersionRange = "[1,)"
val minecraftVersionRange = "[$neoforgeMinecraftVersion, 1.21.11)"

group = neoforgeMavenGroup
version = neoforgeModVersion

base {
	archivesName.set(modId)
}

sourceSets.main {
	resources {
		srcDir("src/generated/resources")
		exclude("**/*.bbmodel")
		exclude("**/.cache")
		exclude("assets/electrum/**")
	}
}

java {
	toolchain {
		languageVersion.set(JavaLanguageVersion.of(21))
	}
	withSourcesJar()
}

val localRuntime = configurations.named("localRuntime")
configurations.named("runtimeClasspath") {
	extendsFrom(localRuntime.get())
}

dependencies {
	implementation("net.neoforged:neoforge:$neoforgeNeoVersion")
	implementation("thedarkcolour:kotlinforforge-neoforge:5.10.0")
	rustImport(project(":wgpu-mc-rust"))
}

repositories {
	maven {
		name = "Kotlin for Forge"
		setUrl("https://thedarkcolour.github.io/KotlinForForge/")
	}
}
val unpackExports = tasks.register<Copy>("unpackExports") {
	from(zipTree(layout.buildDirectory.file("resources/main/export.zip")))
	into(layout.buildDirectory.dir("resources/main"))
	finalizedBy("deleteExports")
}

tasks.register<Delete>("deleteExports") {
	description = "Deletes the exported files after they have been processed."
    delete(layout.buildDirectory.file("resources/main/export.zip"))
}

tasks.named<ProcessResources>("processResources") {
	finalizedBy(unpackExports, "deleteExports")

	val replaceProperties = mapOf(
		"minecraft_version" to neoforgeMinecraftVersion,
		"minecraft_version_range" to minecraftVersionRange,
		"neo_version" to neoforgeNeoVersion,
		"loader_version_range" to loaderVersionRange,
		"mod_id" to modId,
		"mod_name" to modName,
		"mod_license" to modLicense,
		"mod_version" to neoforgeModVersion
	)
	inputs.properties(replaceProperties)

	filesMatching("META-INF/neoforge.mods.toml") {
		expand(replaceProperties)
	}
}

tasks.named<Jar>("jar") {
	dependsOn(unpackExports, "deleteExports")
}

listOf("runClient", "runData", "runGameTestServer", "runServer").forEach { runTaskName ->
	tasks.matching { it.name == runTaskName }.configureEach {
		dependsOn(unpackExports)
	}
}

tasks.withType<Jar>().configureEach {
	from(rootProject.file("LICENSE")) {
		rename { "${it}_${base.archivesName.get()}" }
	}
}

tasks.withType<JavaCompile>().configureEach {
	options.encoding = "UTF-8"
}

publishing {
	publications {
		register<MavenPublication>("mavenJava") {
			from(components["java"])
		}
	}
	repositories {
		maven {
			url = uri(layout.projectDirectory.dir("repo"))
		}
	}
}
