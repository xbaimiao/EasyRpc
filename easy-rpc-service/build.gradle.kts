plugins {
    application
    id("com.gradleup.shadow")
}

dependencies {
    implementation(project(":easy-rpc-core"))
    implementation("io.netty:netty-all:4.1.108.Final")
    implementation("org.yaml:snakeyaml:2.3")
}

application {
    mainClass.set("com.xbaimiao.easyrpc.service.ServiceMainKt")
}

tasks.shadowJar {
    archiveClassifier.set("")
    manifest {
        attributes["Main-Class"] = "com.xbaimiao.easyrpc.service.ServiceMainKt"
    }
    mergeServiceFiles()
}

