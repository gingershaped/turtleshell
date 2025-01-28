import com.github.gradle.node.npm.task.NpxTask

plugins {
    kotlin("jvm")
    id("com.github.node-gradle.node") version "7.1.0"
}

val buildTask = tasks.register<NpxTask>("buildClient") {
    command.set("tstl")
    dependsOn(tasks.npmInstall)
    inputs.dir(project.fileTree("src"))
    inputs.dir("node_modules")
    inputs.files("tsconfig.json")
    outputs.dir("${project.buildDir}/client")
}

parent!!.kotlin.sourceSets.main {
    resources.srcDir(buildTask)
}