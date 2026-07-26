plugins {
    id("com.gradleup.shadow")
}

dependencies {
    implementation(project(":easy-rpc-client-sdk"))
    compileOnly("io.papermc.paper:paper-api:1.18.2-R0.1-SNAPSHOT")
    compileOnly("me.clip:placeholderapi:2.12.3")
}

tasks {
    assemble {
        dependsOn(shadowJar)
    }
    processResources {
        outputs.upToDateWhen { false }
        expand("version" to project.version)
    }
    shadowJar {
        archiveBaseName.set("easy-rpc-client-plugin")
        archiveClassifier.set("")
        relocate("kotlin", "com.xbaimiao.easyrpc.plugin.shadow.kotlin")
    }
}
