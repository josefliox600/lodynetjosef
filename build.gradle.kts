import com.android.build.gradle.BaseExtension
import com.lagradost.cloudstream3.gradle.CloudstreamExtension
import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

val forcedKotlinVersion = "2.3.20"

buildscript {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
    dependencies {
        classpath("com.android.tools.build:gradle:8.7.3")
        classpath("com.github.recloudstream:gradle:-SNAPSHOT")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:$forcedKotlinVersion")
    }

    configurations.classpath {
        resolutionStrategy.eachDependency {
            if (requested.group == "org.jetbrains.kotlin") {
                useVersion(forcedKotlinVersion)
                because("Force Kotlin version compatible with CloudStream dependency metadata")
            }
        }
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }

    configurations.all {
        resolutionStrategy.eachDependency {
            if (requested.group == "org.jetbrains.kotlin") {
                useVersion(forcedKotlinVersion)
            }
        }
    }
}

fun Project.cloudstream(configuration: CloudstreamExtension.() -> Unit) =
    extensions.getByName("cloudstream").let { it as CloudstreamExtension }.configuration()

fun Project.android(configuration: BaseExtension.() -> Unit) =
    extensions.getByName("android").let { it as BaseExtension }.configuration()

subprojects {
    apply(plugin = "com.android.library")
    apply(plugin = "kotlin-android")
    apply(plugin = "com.lagradost.cloudstream3.gradle")

    cloudstream {
        setRepo(System.getenv("GITHUB_REPOSITORY") ?: "josefliox600/lodynetjosef")
    }

    android {
        compileSdkVersion(35)

        defaultConfig {
            minSdk = 21
            targetSdk = 35
        }

        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_1_8
            targetCompatibility = JavaVersion.VERSION_1_8
        }
    }

    tasks.withType(KotlinJvmCompile::class.java).configureEach {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_1_8)
            freeCompilerArgs.addAll(
                "-Xno-call-assertions",
                "-Xno-param-assertions",
                "-Xno-receiver-assertions"
            )
        }
    }

    dependencies {
        add("cloudstream", "com.lagradost:cloudstream3:pre-release")
        add("implementation", kotlin("stdlib", forcedKotlinVersion))
        add("implementation", "org.jetbrains.kotlin:kotlin-stdlib:$forcedKotlinVersion")
        add("implementation", "org.jetbrains.kotlin:kotlin-stdlib-jdk8:$forcedKotlinVersion")
        add("implementation", "org.jetbrains.kotlin:kotlin-reflect:$forcedKotlinVersion")

        add("implementation", "com.github.Blatzar:NiceHttp:0.4.11")
        add("implementation", "org.jsoup:jsoup:1.18.3")
        add("implementation", "com.fasterxml.jackson.module:jackson-module-kotlin:2.13.1")
    }
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
