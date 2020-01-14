import sbtcrossproject.CrossPlugin.autoImport.crossProject

lazy val scalaVersion213 = "2.13.0"
lazy val scalaVersion212 = "2.12.10"
lazy val scalaVersion211 = "2.11.12"
lazy val scalaVersions   = List(scalaVersion213, scalaVersion212, scalaVersion211)

ThisBuild / scalaVersion := scalaVersion212

val commonSettings = Seq(
  organization := "com.github.cb372",
  publishTo := sonatypePublishToBundle.value,
  pomIncludeRepository := { _ =>
    false
  },
  publishMavenStyle := true,
  licenses := Seq(
    "Apache License, Version 2.0" -> url(
      "http://www.apache.org/licenses/LICENSE-2.0.html"
    )
  ),
  homepage := Some(url("https://cb372.github.io/cats-retry/")),
  developers := List(
    Developer(
      id = "cb372",
      name = "Chris Birchall",
      email = "chris.birchall@gmail.com",
      url = url("https://github.com/cb372")
    ),
    Developer(
      id = "LukaJCB",
      name = "Luka Jacobowitz",
      email = "luka.jacobowitz@gmail.com",
      url = url("https://github.com/LukaJCB")
    )
  )
)

val moduleSettings = commonSettings ++ Seq(
  scalacOptions ++= Seq(
    "-Xfuture",
    "-Ywarn-dead-code",
    "-Ywarn-unused",
    "-deprecation",
    "-encoding",
    "UTF-8",
    "-language:higherKinds",
    "-unchecked"
  ),
  scalacOptions in (Test, compile) ++= {
    if (scalaVersion.value.startsWith("2.13"))
      Nil
    else
      List("-Ypartial-unification")
  },
  scalafmtOnCompile := true
)

val catsVersion          = "2.0.0"
val catsEffectVersion    = "2.0.0"
val catsMtlVersion       = "0.7.0"
val scalatestVersion     = "3.1.0"
val scalaTestPlusVersion = "3.1.0.0-RC2"
val scalacheckVersion    = "1.14.3"
val disciplineVersion    = "1.0.0-RC2"

val core = crossProject(JVMPlatform, JSPlatform)
  .in(file("modules/core"))
  .settings(moduleSettings)
  .settings(
    name := "cats-retry",
    crossScalaVersions := scalaVersions,
    libraryDependencies ++= Seq(
      "org.typelevel"     %%% "cats-core"                % catsVersion,
      "org.typelevel"     %%% "cats-effect"              % catsEffectVersion,
      "org.scalatest"     %%% "scalatest"                % scalatestVersion % Test,
      "org.scalacheck"    %%% "scalacheck"               % scalacheckVersion % Test,
      "org.typelevel"     %%% "cats-laws"                % catsVersion % Test,
      "org.scalatest"     %%% "scalatest"                % scalatestVersion % Test,
      "org.scalatestplus" %%% "scalatestplus-scalacheck" % scalaTestPlusVersion % Test,
      "org.typelevel"     %%% "discipline-scalatest"     % disciplineVersion % Test,
      "org.scalacheck"    %%% "scalacheck"               % scalacheckVersion % Test
    )
  )
val coreJVM = core.jvm
val coreJS  = core.js

val alleycatsRetry = crossProject(JVMPlatform, JSPlatform)
  .in(file("modules/alleycats"))
  .jvmConfigure(_.dependsOn(coreJVM))
  .jsConfigure(_.dependsOn(coreJS))
  .settings(moduleSettings)
  .settings(
    name := "alleycats-retry",
    crossScalaVersions := scalaVersions,
    libraryDependencies ++= Seq(
      "org.scalatest"     %%% "scalatest"                % scalatestVersion     % Test,
      "org.scalacheck"    %%% "scalacheck"               % scalacheckVersion    % Test,
      "org.typelevel"     %%% "cats-laws"                % catsVersion          % Test,
      "org.scalatest"     %%% "scalatest"                % scalatestVersion     % Test,
      "org.scalatestplus" %%% "scalatestplus-scalacheck" % scalaTestPlusVersion % Test,
      "org.typelevel"     %%% "discipline-scalatest"     % disciplineVersion    % Test,
      "org.scalacheck"    %%% "scalacheck"               % scalacheckVersion    % Test
    )
  )
val alleycatsJVM = alleycatsRetry.jvm
val alleycatsJS  = alleycatsRetry.js

val mtlRetry = crossProject(JVMPlatform, JSPlatform)
  .in(file("modules/mtl"))
  .jvmConfigure(_.dependsOn(coreJVM))
  .jsConfigure(_.dependsOn(coreJS))
  .settings(moduleSettings)
  .settings(
    name := "cats-retry-mtl",
    crossScalaVersions := scalaVersions,
    libraryDependencies ++= Seq(
      "org.typelevel" %%% "cats-mtl-core" % catsMtlVersion,
      "org.scalatest" %%% "scalatest"     % scalatestVersion % Test
    )
  )
val mtlJVM = mtlRetry.jvm
val mtlJS  = mtlRetry.js

val docs = project
  .in(file("modules/docs"))
  .dependsOn(coreJVM, alleycatsJVM, mtlJVM)
  .enablePlugins(MicrositesPlugin, BuildInfoPlugin)
  .settings(moduleSettings)
  .settings(
    scalacOptions -= "-Ywarn-dead-code",
    scalacOptions -= "-Ywarn-unused",
    scalacOptions += "-Ydelambdafy:inline",
    addCompilerPlugin(
      "org.typelevel" %% "kind-projector" % "0.11.0" cross CrossVersion.full
    ),
    libraryDependencies ++= Seq(
      "io.monix" %%% "monix" % "3.1.0"
    ),
    crossScalaVersions := Nil,
    buildInfoPackage := "retry",
    publishArtifact := false,
    micrositeName := "cats-retry",
    micrositeAuthor := "Chris Birchall",
    micrositeDescription := "cats-retry",
    micrositeBaseUrl := "/cats-retry",
    micrositeDocumentationUrl := "/cats-retry/docs",
    micrositeHomepage := "https://github.com/cb372/cats-retry",
    micrositeGithubOwner := "cb372",
    micrositeGithubRepo := "cats-retry",
    micrositeGitterChannel := true,
    micrositeGitterChannelUrl := "typelevel/cats-retry",
    micrositeTwitterCreator := "@cbirchall",
    micrositeCompilingDocsTool := WithMdoc,
    mdocIn := (sourceDirectory in Compile).value / "mdoc",
    micrositeShareOnSocial := true
  )

val root = project
  .in(file("."))
  .aggregate(
    coreJVM,
    coreJS,
    alleycatsJVM,
    alleycatsJS,
    mtlJVM,
    mtlJS,
    docs
  )
  .settings(commonSettings)
  .settings(
    publishArtifact := false,
    crossScalaVersions := Nil
  )
