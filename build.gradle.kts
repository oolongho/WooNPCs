// WooNPCs 构建脚本
// 技术栈：Java 21 + Paper 1.21 API（纯反射路线，不使用 paperweight-userdev）+ shadow fat jar

plugins {
    `java`
    id("com.gradleup.shadow") version "9.4.2"
}

group = "com.oolongho"
version = "1.0.0-SNAPSHOT"
description = "WooNPCs - 基于 Paper 的高性能 NPC 插件"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
    // PaperMC 仓库（包含 paper-api 及相关依赖）
    maven {
        url = uri("https://repo.papermc.io/repository/maven-public/")
    }
}

dependencies {
    // 仅编译期依赖 Paper API，运行时由 1.21+ 服务端提供
    compileOnly("io.papermc.paper:paper-api:1.21.1-R0.1-SNAPSHOT")
    // 用于 @ApiStatus / @NotNull 等注解
    compileOnly("org.jetbrains:annotations:24.1.0")
}

tasks {
    compileJava {
        options.encoding = Charsets.UTF_8.name()
        options.release = 21
    }

    javadoc {
        options.encoding = Charsets.UTF_8.name()
    }

    processResources {
        filteringCharset = Charsets.UTF_8.name()
        // 将版本号注入 paper-plugin.yml
        filesMatching("paper-plugin.yml") {
            expand("version" to project.version)
        }
    }

    // 普通 jar 加 plain 后缀，避免与 shadowJar 输出同名冲突
    jar {
        archiveClassifier.set("plain")
    }

    // 输出无后缀的 fat jar：WooNPCs-1.0.0-SNAPSHOT.jar（主产物）
    // com.gradleup.shadow 9.x 会自动把 shadowJar 附加到 assemble 生命周期，无需显式 dependsOn
    shadowJar {
        archiveClassifier.set("")
        archiveBaseName.set("WooNPCs")
    }
}
