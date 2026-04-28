plugins {
    application
}

dependencies {
    implementation(project(":easy-rpc-service"))
    implementation(project(":easy-rpc-client-sdk"))
}

application {
    mainClass.set("com.xbaimiao.easyrpc.test.RpcSmokeTestKt")
}
