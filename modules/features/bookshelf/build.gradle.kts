plugins {
    id("qdhelper.android.feature")
}

android {
    namespace = "com.xiyunmn.qdhelper.feature.bookshelf"
}

dependencies {
    implementation(project(":feature-api"))
}
