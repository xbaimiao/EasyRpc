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
    // 不能用 archiveClassifier="" —— 那样会和 application 插件的 jar 任务抢同一个输出路径，
    // distZip / distTar / startScripts 会因为缺少依赖声明而报错。这里给一个独立的固定名字。
    archiveFileName.set("easy-rpc-service.jar")
    manifest {
        attributes["Main-Class"] = "com.xbaimiao.easyrpc.service.ServiceMainKt"
    }
    mergeServiceFiles()
}

