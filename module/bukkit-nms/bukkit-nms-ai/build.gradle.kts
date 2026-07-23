dependencies {
    compileOnly(project(":common"))
    compileOnly(project(":common-util"))
    compileOnly(project(":module:bukkit-nms"))
    // 服务端
    compileOnly("ink.ptms:nms-all:1.0.0")
    compileOnly("ink.ptms.core:v12104:12104:mapped")
    // class major 已降为 21，可用 JDK 21 编译；勿与未降 major 的 minimize 混用
    compileOnly("ink.ptms.core:v260100:260100-minimize-java21")
}

kotlin {
    jvmToolchain(21)
}