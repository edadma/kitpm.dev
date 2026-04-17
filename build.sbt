import xerial.sbt.Sonatype.sonatypeCentralHost

ThisBuild / licenses               := Seq("ISC" -> url("https://opensource.org/licenses/ISC"))
ThisBuild / versionScheme          := Some("semver-spec")
ThisBuild / evictionErrorLevel     := Level.Warn
ThisBuild / scalaVersion           := "3.8.3"
ThisBuild / organization           := "io.github.edadma"
ThisBuild / organizationName       := "edadma"
ThisBuild / organizationHomepage   := Some(url("https://github.com/edadma"))
ThisBuild / version                := "0.0.1"
ThisBuild / sonatypeCredentialHost := sonatypeCentralHost

ThisBuild / publishConfiguration := publishConfiguration.value.withOverwrite(true).withChecksums(Vector.empty)
ThisBuild / resolvers += Resolver.mavenLocal
ThisBuild / resolvers += Resolver.sonatypeCentralSnapshots
ThisBuild / resolvers += Resolver.sonatypeCentralRepo("releases")

ThisBuild / sonatypeProfileName := "io.github.edadma"

ThisBuild / scmInfo := Some(
  ScmInfo(
    url("https://github.com/edadma/kitpm.dev"),
    "scm:git@github.com:edadma/kitpm.dev.git",
  ),
)
ThisBuild / developers := List(
  Developer(
    id = "edadma",
    name = "Edward A. Maxedon, Sr.",
    email = "edadma@gmail.com",
    url = url("https://github.com/edadma"),
  ),
)

ThisBuild / homepage := Some(url("https://github.com/edadma/kitpm.dev"))
ThisBuild / description := "Project description here"

ThisBuild / publishTo := sonatypePublishToBundle.value

val commonSettings = Seq(
  scalacOptions ++=
    Seq(
      "-deprecation",
      "-feature",
      "-unchecked",
      "-language:postfixOps",
      "-language:implicitConversions",
      "-language:existentials",
      "-language:dynamics",
    ),
//  libraryDependencies += "org.scalatest" %%% "scalatest" % "3.2.19" % "test",
  libraryDependencies ++= Seq(
  ),
  publishMavenStyle      := true,
  Test / publishArtifact := false,
)

val commonJvmSettings = Seq(
  libraryDependencies += "org.scala-js" %% "scalajs-stubs" % "1.1.0" % "provided",
)

val commonNativeSettings = Seq(
  libraryDependencies += "org.scala-js" %% "scalajs-stubs" % "1.1.0" % "provided",
)

val commonJsSettings = Seq(
  jsEnv := new org.scalajs.jsenv.nodejs.NodeJSEnv(),
  scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.ESModule) },
  scalaJSLinkerConfig ~= { _.withSourceMap(false) },
  Test / scalaJSUseMainModuleInitializer := false,
  Test / scalaJSUseTestModuleInitializer := true,
  scalaJSUseMainModuleInitializer        := true,
)

lazy val kit = crossProject(JSPlatform, JVMPlatform, NativePlatform)
  .in(file("kit"))
  .settings(commonSettings)
  .settings(name := "kit")
  .jvmSettings(commonJvmSettings)
  .nativeSettings(commonNativeSettings)
  .jsSettings(commonJsSettings)

lazy val kitd = crossProject(JSPlatform, JVMPlatform, NativePlatform)
  .in(file("kitd"))
  .settings(commonSettings)
  .settings(name := "kitd")
  .jvmSettings(commonJvmSettings)
  .nativeSettings(commonNativeSettings)
  .jsSettings(commonJsSettings)

lazy val root = project
  .in(file("."))
  .aggregate(kit.js, kit.jvm, kit.native, kitd.js, kitd.jvm, kitd.native)
  .settings(
    name                := "kitpm.dev",
    publish / skip      := true,
    publishLocal / skip := true,
  )
