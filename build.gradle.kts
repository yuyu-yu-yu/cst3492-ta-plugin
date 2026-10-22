plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "edu.tongji.cst3492"
version = "1.0.0"

repositories {
    maven { url = uri("https://maven.aliyun.com/repository/public") }
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

// Use a local IDE only when explicitly configured (Gradle property `localIdeaPath` / env `LOCAL_IDEA_PATH`)
// or when the default macOS install exists; otherwise download IntelliJ IDEA Community 2025.1.
val localIdeaPath = providers.gradleProperty("localIdeaPath")
    .orElse(providers.environmentVariable("LOCAL_IDEA_PATH"))
val macDefaultIdea = file("/Applications/IntelliJ IDEA.app")
val explicitLocalIdea = localIdeaPath.orNull?.takeIf { it.isNotBlank() }?.let { file(it) }?.takeIf { it.exists() }
val localIdea = explicitLocalIdea ?: macDefaultIdea.takeIf { it.exists() }
// A local (2026.x) IDE bundles JBR 25 and requires a JDK 25 toolchain; the downloaded 2025.1 runs on JDK 21.
// Bytecode is always compiled with --release 21, so both setups produce identical plugin classes.
val useLocalIdea = localIdea != null

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(if (useLocalIdea) 25 else 21))
    }
}

tasks.withType<JavaCompile> {
    options.release.set(21)
    options.encoding = "UTF-8"
}

dependencies {
    intellijPlatform {
        val localIde = localIdea
        if (localIde != null) {
            local(localIde)
        } else {
            intellijIdeaCommunity("2025.1")
        }
        bundledPlugin("com.intellij.java")
    }

    // PDF parsing (Apache PDFBox & Tika as referenced in assignment)
    implementation("org.apache.pdfbox:pdfbox:3.0.3")
    implementation("org.apache.tika:tika-core:2.9.2")

    // JSON processing
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")

    // Testing
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // IntelliJ Platform Gradle Plugin 2.x test runtime needs org.junit.rules.TestRule (JUnit 4) on the classpath
    testRuntimeOnly("junit:junit:4.13.2")
}

intellijPlatform {
    pluginConfiguration {
        id = "edu.tongji.cst3492.ta"
        name = "CST3492 Course TA Assistant"
        version = project.version.toString()
        description = """
            AI Coding Assistant & Course TA for CST3492 (Java Enterprise Application Development).
            Provides RAG-based course Q&A with explicit source & page citations and editor context awareness.
        """.trimIndent()
        ideaVersion {
            sinceBuild = "242"
            untilBuild = provider { null }
        }
    }
    buildSearchableOptions = false
}

tasks.test {
    useJUnitPlatform()
}
