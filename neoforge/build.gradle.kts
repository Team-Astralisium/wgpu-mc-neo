import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.tasks.Jar
import org.gradle.language.jvm.tasks.ProcessResources

plugins {
	`java-library`
	`maven-publish`
	kotlin("jvm") version "2.3.20"
	id("net.neoforged.gradle.userdev") version "7.1.21"
}

val neoforgeMinecraftVersion = providers.gradleProperty("neoforge_minecraft_version").get()
val neoforgeNeoVersion = providers.gradleProperty("neoforge_neo_version").get()
val neoforgeModVersion = providers.gradleProperty("neoforge_mod_version").get()
val neoforgeMavenGroup = providers.gradleProperty("neoforge_maven_group").get()
val neoforgeKotlinVersion = providers.gradleProperty("neoforge_kotlin_version").getOrElse("2.3.20")

val modId = "wgpu_mc"
val modName = "wgpu-mc"
val modLicense = "LGPLv2.1"
val loaderVersionRange = "[3,)"
// Minecraft 26.1 is the new stable modding baseline; 26.2 is the next feature release.
val minecraftVersionRange = "[$neoforgeMinecraftVersion,$neoforgeMinecraftVersion.999)"

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
		// Minecraft 26.1 moved to Java 25.
		languageVersion.set(JavaLanguageVersion.of(25))
	}
	withSourcesJar()
}

kotlin {
	jvmToolchain(25)
}

// Align the Kotlin stdlib with the one shipped by NeoForge's own Kotlin support.
dependencies {
	constraints {
		implementation("org.jetbrains.kotlin:kotlin-stdlib:$neoforgeKotlinVersion") {
			because("NeoForge 26.1 ships a Kotlin runtime; keep the compile/runtime classpath aligned.")
		}
	}
}

val localRuntime = configurations.named("localRuntime")
configurations.named("runtimeClasspath") {
	extendsFrom(localRuntime.get())
}

dependencies {
	implementation("net.neoforged:neoforge:$neoforgeNeoVersion")
}

repositories {
	maven {
		name = "NeoForged"
		setUrl("https://maven.neoforged.net/releases")
	}
	mavenCentral()
}

// ---------------------------------------------------------------------------
// Native library packaging
//
// The previous (1.21.1) revision pulled the JNI bridge in through the
// `fr.stardustenterprises.rust.importer` plugin, which is not available for the
// NeoForge 26.1 toolchain. The native library is now picked straight out of the
// Rust workspace output, and the task degrades to a no-op until it has been
// built with `cargo build --release`.
// ---------------------------------------------------------------------------
val rustProjectDir = rootProject.layout.projectDirectory.dir("rust")
val rustReleaseDir = rustProjectDir.dir("target/release")
val nativeLibraryFileName = System.mapLibraryName("wgpu_mc_jni")
val nativeLibrary = rustReleaseDir.file(nativeLibraryFileName)

val copyNatives = tasks.register<Copy>("copyNatives") {
	description = "Copies the freshly built Rust JNI bridge into this module's resources."
	group = "build"
	onlyIf { nativeLibrary.asFile.exists() }
	from(nativeLibrary) {
		into("assets/$modId/natives")
	}
	into(layout.buildDirectory.dir("generated/natives"))
}

sourceSets.main {
	resources.srcDir(copyNatives.map { it.destinationDir })
}

val unpackExports = tasks.register<Copy>("unpackExports") {
	onlyIf { layout.buildDirectory.file("resources/main/export.zip").get().asFile.exists() }
	from(zipTree(layout.buildDirectory.file("resources/main/export.zip")))
	into(layout.buildDirectory.dir("resources/main"))
	finalizedBy("deleteExports")
}

tasks.register<Delete>("deleteExports") {
	description = "Deletes the exported files after they have been processed."
	delete(layout.buildDirectory.file("resources/main/export.zip"))
}

tasks.named<ProcessResources>("processResources") {
	dependsOn(copyNatives)
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

// ---------------------------------------------------------------------------
// NeoForge's early loading window is incompatible with this mod.
//
// It draws its splash screen through OpenGL *from Minecraft's render thread*, which only works
// because Blaze3D's GL backend makes a context current on that thread. This mod deliberately does
// not (`WgpuBackend#setWindowHints` asks for GLFW_NO_API), so the first GL call from that thread
// aborts the JVM:
//
//   FATAL ERROR in native method: No context is current ...
//       at org.lwjgl.opengl.GL11C.glIsEnabled(Native Method)
//       at net.neoforged.fml.earlydisplay.render.GlState.readFromOpenGL(GlState.java:129)
//
// Whether the early window is created is decided by FML before any mod is loaded, so the mod
// cannot turn it off itself - the only place to say so is FML's own config. This task writes that
// one key, so a fresh run directory works without anyone having to remember why it is needed.
// ---------------------------------------------------------------------------
val configureEarlyWindow = tasks.register("configureEarlyWindow") {
	description = "Disables NeoForge's early loading window, which needs a GL context this mod never creates."
	group = "neoforge"

	val configFile = layout.projectDirectory.file("runs/client/config/fml.toml").asFile
	outputs.file(configFile)

	doLast {
		val key = "earlyWindowControl"
		val wanted = "$key = false"

		if (!configFile.exists()) {
			// The rest of the file is FML's to write: its config spec fills in every key it does
			// not find, so seeding just this one is enough.
			configFile.parentFile.mkdirs()
			configFile.writeText("#Written by the wgpu-mc build; see configureEarlyWindow in build.gradle.kts\n$wanted\n")
			logger.lifecycle("Seeded ${configFile.path} with $wanted")
			return@doLast
		}

		val lines = configFile.readLines()
		val rewritten = lines.map { line ->
			if (line.trimStart().startsWith("$key ") || line.trimStart().startsWith("$key=")) wanted else line
		}

		if (rewritten != lines) {
			configFile.writeText(rewritten.joinToString("\n", postfix = "\n"))
			logger.lifecycle("Set $wanted in ${configFile.path}")
		}
	}
}

tasks.matching { it.name == "runClient" }.configureEach {
	dependsOn(configureEarlyWindow)
}

// The backend binds the Rust C ABI through java.lang.foreign, which is a restricted method on
// JDK 24+. Without this flag a dev run logs a warning per downcall; a production launcher is
// expected to pass the same flag, and the mod still works if it does not. Both names are needed:
// the mod's own classes are loaded as the named module `wgpu_mc`, while the FFM calls that reach
// the JDK from the unnamed module (the class path) are covered by ALL-UNNAMED.
tasks.matching { it.name.startsWith("run") }.configureEach {
	if (this is JavaExec) {
		jvmArgs("--enable-native-access=ALL-UNNAMED,wgpu_mc")
	}
}

// Diagnostics - the frame/trace dumps behind `dev.birb.wgpu.backend.Diagnostics` - are switched on
// per run instead of from here, because the launcher's JVM arguments turned out to be one more
// thing that can silently not arrive:
//
//   gradlew :wgpu-mc-neoforge:runClient -Dwgpu_mc.diagnostics=true     # or
//   New-Item neoforge/runs/client/wgpu-dump-frames                     # no launcher support needed
//
// The marker file is the one that works everywhere, so it is the documented one.

tasks.withType<Jar>().configureEach {
	from(rootProject.file("LICENSE")) {
		rename { "${it}_${base.archivesName.get()}" }
	}
}

tasks.withType<JavaCompile>().configureEach {
	options.encoding = "UTF-8"
	options.release.set(25)
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
