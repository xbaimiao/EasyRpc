plugins {
    `java-library`
    id("com.google.protobuf")
}

val protobufVersion = "3.25.3"

dependencies {
    api("com.google.protobuf:protobuf-java:$protobufVersion")
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:$protobufVersion"
    }
}
