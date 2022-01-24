import sbtcrossproject.CrossPlugin.autoImport.crossProject

lazy val scalaVersion212 = "2.12.14"
lazy val scalaVersion213 = "2.13.6"
lazy val scalaVersion3   = "3.0.1"
lazy val scalaVersions   = List(scalaVersion212, scalaVersion213, scalaVersion3)

ThisBuild / scalaVersion := scalaVersion213

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
  ),
  mimaPreviousArtifacts := Set.empty
)

val moduleSettings = commonSettings ++ Seq(
  scalacOptions ++= Seq(
    "-deprecation",
    "-encoding",
    "UTF-8",
    "-language:higherKinds",
    "-unchecked"
  ),
  Test / compile / scalacOptions ++= {
    if (scalaVersion.value.startsWith("2.13"))
      List("-Ywarn-dead-code", "-Ywarn-unused")
    else if (scalaVersion.value.startsWith("2.12"))
      List(
        "-Ywarn-dead-code",
        "-Ywarn-unused",
        "-Ypartial-unification",
        "-Xfuture"
      )
    else // scala 3.x
      List("-language:implicitConversions")
  },
  scalafmtOnCompile := true
)

val catsVersion          = "2.6.1"
val catsEffectVersion    = "3.1.1"
val catsMtlVersion       = "1.2.1"
val scalatestVersion     = "3.2.11"
val scalaTestPlusVersion = "3.2.9.0"
val scalacheckVersion    = "1.15.4"
val disciplineVersion    = "2.1.5"

val core = crossProject(JVMPlatform, JSPlatform)
  .in(file("modules/core"))
  .settings(moduleSettings)
  .settings(
    name := "cats-retry",
    crossScalaVersions := scalaVersions,
    libraryDependencies ++= Seq(
      "org.typelevel"     %%% "cats-core"            % catsVersion,
      "org.typelevel"     %%% "cats-effect"          % catsEffectVersion,
      "org.scalatest"     %%% "scalatest"            % scalatestVersion     % Test,
      "org.scalacheck"    %%% "scalacheck"           % scalacheckVersion    % Test,
      "org.typelevel"     %%% "cats-laws"            % catsVersion          % Test,
      "org.scalatestplus" %%% "scalacheck-1-15"      % scalaTestPlusVersion % Test,
      "org.typelevel"     %%% "discipline-scalatest" % disciplineVersion    % Test
    ),
    mimaPreviousArtifacts := Set.empty
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
      "org.scalatest"     %%% "scalatest"            % scalatestVersion     % Test,
      "org.scalacheck"    %%% "scalacheck"           % scalacheckVersion    % Test,
      "org.typelevel"     %%% "cats-laws"            % catsVersion          % Test,
      "org.scalatestplus" %%% "scalacheck-1-15"      % scalaTestPlusVersion % Test,
      "org.typelevel"     %%% "discipline-scalatest" % disciplineVersion    % Test
    ),
    mimaPreviousArtifacts := Set.empty
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
      "org.typelevel" %%% "cats-mtl"  % catsMtlVersion,
      "org.scalatest" %%% "scalatest" % scalatestVersion % Test
    ),
    mimaPreviousArtifacts := Set.empty
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
      "org.typelevel" %% "kind-projector" % "0.13.0" cross CrossVersion.full
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
    mdocIn := (Compile / sourceDirectory).value / "mdoc",
    micrositeShareOnSocial := true,
    micrositePushSiteWith := GitHub4s,
    micrositeGithubToken := sys.env.get("GITHUB_TOKEN")
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
