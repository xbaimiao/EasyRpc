plugins {
    `java-library`
    id("com.google.protobuf")
}

val protobufVersion = "3.25.3"

dependencies {
    api("com.google.protobuf:protobuf-java:$protobufVersion")
    api("io.netty:netty-buffer:4.1.108.Final")
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:$protobufVersion"
    }
}
