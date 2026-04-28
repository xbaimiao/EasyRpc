pluginManagement {
    repositories {
        mavenLocal()
        maven("https://maven.xbaimiao.com/repository/maven-public/")
        mavenCentral()
        gradlePluginPortal()
        maven("https://repo.papermc.io/repository/maven-public/")
    }
    plugins {
        kotlin("jvm") version "1.9.20"
        id("com.gradleup.shadow") version "8.3.5"
        id("com.google.protobuf") version "0.9.1"
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenLocal()
        maven("https://maven.xbaimiao.com/repository/maven-public/")
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
    }
}

rootProject.name = "EasyRpc"

include(":easy-rpc-core")
include(":easy-rpc-service")
include(":easy-rpc-client-sdk")
include(":easy-rpc-client-plugin")
include(":easy-rpc-test")


