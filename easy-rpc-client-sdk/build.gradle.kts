plugins {
    `java-library`
}

dependencies {
    api(project(":easy-rpc-core"))
    api("io.netty:netty-all:4.1.108.Final")
}
