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

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

tasks.withType<JavaCompile> {
    options.release.set(21)
}

dependencies {
    intellijPlatform {
        val localIdea = file("/Applications/IntelliJ IDEA.app")
        if (localIdea.exists()) {
            local(localIdea)
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
            sinceBuild = "241"
            untilBuild = provider { null }
        }
    }
    buildSearchableOptions = false
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

tasks.test {
    useJUnitPlatform()
}
