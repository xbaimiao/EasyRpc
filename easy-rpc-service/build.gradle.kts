plugins {
    application
}

dependencies {
    implementation(project(":easy-rpc-core"))
}

application {
    mainClass.set("com.xbaimiao.easyrpc.service.ExampleServiceKt")
}

