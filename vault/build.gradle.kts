plugins { alias(libs.plugins.android.library); alias(libs.plugins.kotlin.android); alias(libs.plugins.ksp) }
android {
    namespace = "com.fortress.reader.vault"
    compileSdk = 35
    defaultConfig { minSdk = 29 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
// Room schema JSONs are exported for migration authoring and MigrationTestHelper.
ksp { arg("room.schemaLocation", "${projectDir}/schemas") }
dependencies {
    api(project(":security"))
    implementation(project(":document"))
    implementation(libs.coroutines.android)
    api(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
}
