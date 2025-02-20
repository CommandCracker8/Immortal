import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.7.20"
    kotlin("plugin.serialization") version "1.7.20"
    id("com.github.johnrengelman.shadow") version "7.1.2"

    `maven-publish`
    java
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

repositories {
    mavenCentral()

    maven("https://jitpack.io")
}

dependencies {
    //compileOnly(kotlin("stdlib"))
    //compileOnly(kotlin("reflect"))

    compileOnly("net.luckperms:api:5.4")

    implementation("com.github.Minestom:Minestom:9dab3183e5")
    compileOnly("com.github.EmortalMC:Acquaintance:6987f0b3f2")
    api("com.github.EmortalMC:KStom:50b2b882fa")
    api("com.github.emortaldev.Particable:Particlable:latest")

    api("com.github.emortalmc:rayfast:1.0.0")

    api("org.tinylog:tinylog-api-kotlin:2.5.0")
    compileOnly("org.redisson:redisson:3.17.7")
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.6.4")
    compileOnly("org.jetbrains.kotlinx:kotlinx-serialization-json:1.4.0")
}

tasks {
    processResources {
        filesMatching("extension.json") {
            expand(project.properties)
        }
    }

    named<ShadowJar>("shadowJar") {
        archiveBaseName.set(project.name)
        mergeServiceFiles()
        //minimize()
        dependencies {
            exclude(dependency("com.tinylog:tinylog-api-kotlin"))
            //exclude(dependency("com.github.emortaldev:Particable"))
            //exclude(dependency("com.github.emortaldev:Kstom"))
        }
    }

    build { dependsOn(shadowJar) }
}

val compileKotlin: KotlinCompile by tasks
compileKotlin.kotlinOptions.jvmTarget = JavaVersion.VERSION_17.toString()

compileKotlin.kotlinOptions {
    freeCompilerArgs = listOf("-Xinline-classes")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = project.properties["group"] as? String?
            artifactId = project.name
            version = project.properties["version"] as? String?

            from(components["java"])
        }
    }
}