dependencies {
    compileOnly(project(":common"))
    compileOnly(project(":common-env"))
    compileOnly(project(":common-util"))

    testImplementation(project(":common-util"))
}
