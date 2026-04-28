plugins {
    application
}

dependencies {
    implementation(project(":easy-rpc-core"))
    implementation("io.netty:netty-all:4.1.108.Final")
}

application {
    mainClass.set("com.xbaimiao.easyrpc.service.ExampleServiceKt")
}

