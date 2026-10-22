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

val localIdeaPath = providers.gradleProperty("localIdeaPath")
    .orElse(providers.environmentVariable("LOCAL_IDEA_PATH"))
val macDefaultIdea = file("/Applications/IntelliJ IDEA.app")
val useLocalIdea262 = (localIdeaPath.isPresent && file(localIdeaPath.get()).exists())
    || macDefaultIdea.exists()

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(if (useLocalIdea262) 25 else 21))
    }
}

tasks.withType<JavaCompile> {
    options.release.set(21)
    options.encoding = "UTF-8"
}

dependencies {
    intellijPlatform {
        if (localIdeaPath.isPresent && file(localIdeaPath.get()).exists()) {
            local(file(localIdeaPath.get()))
        } else if (macDefaultIdea.exists()) {
            local(macDefaultIdea)
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
            sinceBuild = "242"
            untilBuild = provider { null }
        }
    }
    buildSearchableOptions = false
}

tasks.test {
    useJUnitPlatform()
}
