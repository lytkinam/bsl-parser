import org.jreleaser.model.Active.*
import java.util.*

plugins {
    `java-library`
    `maven-publish`
    jacoco
    idea
    antlr
    id("cloud.rio.license") version "0.18.0"
    id("me.qoomon.git-versioning") version "6.4.4"
    id("io.freefair.javadoc-links") version "9.2.0"
    id("io.freefair.javadoc-utf-8") version "9.2.0"
    id("io.freefair.lombok") version "9.2.0"
//    id("io.freefair.maven-central.validate-poms") version "9.2.0"
    id("com.github.ben-manes.versions") version "0.54.0"
    id("ru.vyarus.pom") version "3.0.0"
    id("org.jreleaser") version "1.24.0"
    id("org.sonarqube") version "7.3.0.8198"
    id("me.champeau.jmh") version "0.7.3"
    id("com.gradleup.shadow") version "8.3.6"
}

repositories {
    mavenLocal()
    mavenCentral()
}

group = "io.github.1c-syntax"
version = "0.1.0-SNAPSHOT"


dependencies {
    antlr("org.antlr:antlr4:4.13.1")
    implementation("org.antlr:antlr4-runtime:4.13.1")

    // JSON
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")
    implementation("com.fasterxml.jackson.core:jackson-core:2.17.2")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.2")

    // Graphs
    implementation("org.jgrapht:jgrapht-core:1.5.2")
    implementation("org.jgrapht:jgrapht-io:1.5.2")

    implementation("org.jspecify:jspecify:1.0.0")

    // HTTP server for MCP
    implementation("org.eclipse.jetty:jetty-server:11.0.20")
    implementation("org.eclipse.jetty:jetty-servlet:11.0.20")

    testImplementation(platform("org.junit:junit-bom:6.0.3"))
    testImplementation("org.junit.jupiter:junit-jupiter-api")
    testImplementation("org.junit.jupiter:junit-jupiter-params")
    testImplementation("org.assertj:assertj-core:3.27.7")

    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    withSourcesJar()
    withJavadocJar()
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

sourceSets {
    main {
        java.srcDirs("src/main/java", "src/main/gen")
        resources.srcDirs("src/main/resources")
    }
    test {
        java.srcDirs("src/test/java")
        resources.srcDirs("src/test/resources")
    }
}

sourceSets.jmh {
    java.srcDirs("src/main/jmh")
    resources.srcDirs("src/jmh/resources")
}

// [bug] https://youtrack.jetbrains.com/issue/KT-46165
tasks.named<org.gradle.jvm.tasks.Jar>("sourcesJar") {
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    dependsOn(tasks.generateGrammarSource)
}

tasks.named("delombok") {
    enabled = false
}

tasks.maybeCreate("delombokTest").apply {
    enabled = false
}

tasks.processTestResources {
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
}

idea {
    module {
        // Marks the already(!) added srcDir as "generated"
        generatedSourceDirs = generatedSourceDirs + file("src/main/gen")
    }
}

jmh {
    jvmArgsAppend = listOf("-XX:+UseParallelGC")
    includeTests.set(true)
    duplicateClassesStrategy = DuplicatesStrategy.WARN
    timeUnit = "s"
}

tasks.generateGrammarSource {
    arguments = listOf(
        "-visitor",
        "-package",
        "com.github._1c_syntax.bsl.parser",
        "-encoding",
        "utf8"
    )
    outputDirectory = file("src/main/gen/com/github/_1c_syntax/bsl/parser")
}

tasks.licenseFormatMain {
    mustRunAfter(tasks.generateGrammarSource)
}

tasks.licenseMain {
    dependsOn(tasks.licenseFormatMain)
}

tasks.test {
    useJUnitPlatform()

    testLogging {
        events("passed", "skipped", "failed", "standard_error")
    }

    reports {
        html.required.set(true)
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    reports {
        xml.required.set(true)
        xml.outputLocation.set(File("${layout.buildDirectory.get()}/reports/jacoco/test/jacoco.xml"))
    }
}

tasks.javadoc {
    options {
        this as StandardJavadocDocletOptions
        links("https://javadoc.io/doc/org.antlr/antlr4-runtime/latest")
    }
}

license {
    header = rootProject.file("license/HEADER.txt")
    skipExistingHeaders = false
    strictCheck = true
    mapping("java", "SLASHSTAR_STYLE")
    ext["year"] = "2018-" + Calendar.getInstance().get(Calendar.YEAR)
    ext["name"] =
        "Alexey Sosnoviy <labotamy@gmail.com>, Nikita Fedkin <nixel2007@gmail.com>, " +
                "Sergey Batanov <sergey.batanov@dmpas.ru>"
    ext["project"] = "BSL Parser"
    include("**/*.java")
}

tasks.clean {
    doFirst {
        delete("src/main/gen", "out")
    }
}

sonar {
    properties {
        property("sonar.sourceEncoding", "UTF-8")
        property("sonar.host.url", "https://sonarcloud.io")
        property("sonar.organization", "1c-syntax")
        property("sonar.projectKey", "1c-syntax_bsl-parser")
        property("sonar.projectName", "BSL Parser")
        property("sonar.scm.exclusions.disabled", "true")
        property(
            "sonar.coverage.jacoco.xmlReportPaths",
            "${layout.buildDirectory.get()}/reports/jacoco/test/jacoco.xml"
        )
    }
}

artifacts {
    archives(tasks["jar"])
    archives(tasks["sourcesJar"])
    archives(tasks["javadocJar"])
}

publishing {
    repositories {
        maven {
            name = "staging"
            url = layout.buildDirectory.dir("staging-deploy").get().asFile.toURI()
        }
    }
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])

            pom {
                description.set("Collection of parsers for Language 1C (BSL) in ANTLR4 format.")
                url.set("https://github.com/1c-syntax/bsl-parser")
                licenses {
                    license {
                        name.set("GNU LGPL 3")
                        url.set("https://www.gnu.org/licenses/lgpl-3.0.txt")
                        distribution.set("repo")
                    }
                }
                developers {
                    developer {
                        id.set("asosnoviy")
                        name.set("Alexey Sosnoviy")
                        email.set("labotamy@gmail.com")
                        url.set("https://github.com/asosnoviy")
                        organization.set("1c-syntax")
                        organizationUrl.set("https://github.com/1c-syntax")
                    }
                    developer {
                        id.set("nixel2007")
                        name.set("Nikita Fedkin")
                        email.set("nixel2007@gmail.com")
                        url.set("https://github.com/nixel2007")
                        organization.set("1c-syntax")
                        organizationUrl.set("https://github.com/1c-syntax")
                    }
                    developer {
                        id.set("dmpas")
                        name.set("Sergey Batanov")
                        email.set("sergey.batanov@dmpas.ru")
                        url.set("https://github.com/dmpas")
                        organization.set("1c-syntax")
                        organizationUrl.set("https://github.com/1c-syntax")
                    }
                    developer {
                        id.set("theshadowco")
                        name.set("Valery Maximov")
                        email.set("maximovvalery@gmail.com")
                        url.set("https://github.com/theshadowco")
                        organization.set("1c-syntax")
                        organizationUrl.set("https://github.com/1c-syntax")
                    }
                }
                scm {
                    connection.set("scm:git:git://github.com/1c-syntax/bsl-parser.git")
                    developerConnection.set("scm:git:git@github.com:1c-syntax/bsl-parser.git")
                    url.set("https://github.com/1c-syntax/bsl-parser")
                }
                issueManagement {
                    system.set("GitHub Issues")
                    url.set("https://github.com/1c-syntax/bsl-parser/issues")
                }
                ciManagement {
                    system.set("GitHub Actions")
                    url.set("https://github.com/1c-syntax/bsl-parser/actions")
                }
            }
        }
    }
}

jreleaser {
    signing {
        active = ALWAYS
        armored = true
    }
    deploy {
        maven {
            mavenCentral {
                create("release-deploy") {
                    active = RELEASE
                    url = "https://central.sonatype.com/api/v1/publisher"
                    stagingRepository("build/staging-deploy")
                }
            }
            nexus2 {
                create("snapshot-deploy") {
                    active = SNAPSHOT
                    snapshotUrl = "https://central.sonatype.com/repository/maven-snapshots/"
                    applyMavenCentralRules = true
                    snapshotSupported = true
                    closeRepository = true
                    releaseRepository = true
                    stagingRepository("build/staging-deploy")
                }
            }
        }
    }
}


tasks.register<JavaExec>("runSdql") {
    group = "application"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.github._1c_syntax.bsl.parser.sdql.SdqlCli")
    args = listOf("examples/example.sql", "examples/SDBL_PARS")
}

tasks.register<JavaExec>("runSdql258") {
    group = "application"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.github._1c_syntax.bsl.parser.sdql.SdqlCli")
    args = listOf("examples/example_258.sql", "examples/SDBL_PARS")
}

tasks.register<JavaExec>("runSdqlMiddle") {
    group = "application"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.github._1c_syntax.bsl.parser.sdql.SdqlCli")
    args = listOf("examples/middle_example.sql", "examples/SDBL_PARS")
}

tasks.register<JavaExec>("runSdqlUnion") {
    group = "application"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.github._1c_syntax.bsl.parser.sdql.SdqlCli")
    args = listOf("examples/sdbl/union_subquery_example.sql", "examples/SDBL_PARS")
}

tasks.register<JavaExec>("runMcpServer") {
    group = "application"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.github._1c_syntax.bsl.parser.sdql.mcp.SdqlMcpServer")
    args = listOf("--port=8080", "--artifacts-dir=examples")
}

tasks.register<JavaExec>("runMcpQuery1cServer") {
    group = "application"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.github._1c_syntax.bsl.parser.sdql.mcp_query_1c.McpQuery1cServer")
    args = listOf("--config=mcp_query_1c.properties")
}

tasks.shadowJar {
    archiveClassifier.set("all")
    mergeServiceFiles()
    manifest {
        attributes["Main-Class"] = "com.github._1c_syntax.bsl.parser.sdql.mcp.SdqlMcpServer"
    }
}
