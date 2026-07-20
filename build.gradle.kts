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
    // PlaceholderAPI 仓库（soft-depend 编译期使用）
    maven {
        url = uri("https://repo.extendedclip.com/releases/")
    }
}

dependencies {
    // 仅编译期依赖 Paper API，运行时由 1.21+ 服务端提供
    compileOnly("io.papermc.paper:paper-api:1.21.1-R0.1-SNAPSHOT")
    // 用于 @ApiStatus / @NotNull 等注解
    compileOnly("org.jetbrains:annotations:24.1.0")
    // Netty：NpcInteractListener 注入 ChannelDuplexHandler 需要（运行时由服务端提供）
    compileOnly("io.netty:netty-transport:4.1.115.Final")
    // PlaceholderAPI：软依赖，运行时由服务端提供
    compileOnly("me.clip:placeholderapi:2.11.6")
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
        // 将版本号注入 plugin.yml
        filesMatching("plugin.yml") {
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
