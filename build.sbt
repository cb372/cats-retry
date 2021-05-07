import sbtcrossproject.CrossPlugin.autoImport.crossProject

lazy val scalaVersion213 = "2.13.5"
lazy val scalaVersion212 = "2.12.13"
lazy val scalaVersion3   = "3.0.0-RC3"
lazy val scalaVersions   = List(scalaVersion213, scalaVersion212, scalaVersion3)

ThisBuild / scalaVersion := scalaVersion212
Global / onChangedBuildSource := ReloadOnSourceChanges

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
    "-Xfuture",
    "-Ywarn-dead-code",
    "-Ywarn-unused",
    "-deprecation",
    "-encoding",
    "UTF-8",
    "-language:higherKinds",
    "-language:implicitConversions",
    "-unchecked"
  ),
  Test / compile / scalacOptions ++= {
    if (scalaVersion.value.startsWith("2.12"))
      List("-Ypartial-unification")
    else
      Nil
  },
  scalafmtOnCompile := true
)

val moduleJsSettings = Seq(
  Test / scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.CommonJSModule) }
)

val catsVersion            = "2.6.0"
val catsEffectVersion      = "2.5.0"
val catsMtlVersion         = "1.2.0"
val munitVersion           = "0.7.25"
val disciplineMunitVersion = "1.0.8"

val core = crossProject(JVMPlatform, JSPlatform)
  .in(file("modules/core"))
  .settings(moduleSettings)
  .settings(
    name := "cats-retry",
    crossScalaVersions := scalaVersions,
    libraryDependencies ++= Seq(
      "org.typelevel" %%% "cats-core"        % catsVersion,
      "org.typelevel" %%% "cats-effect"      % catsEffectVersion,
      "org.typelevel" %%% "cats-laws"        % catsVersion            % Test,
      "org.typelevel" %%% "discipline-munit" % disciplineMunitVersion % Test
    ),
    mimaPreviousArtifacts := Set.empty
  )
  .jsSettings(moduleJsSettings)
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
      "org.scalameta" %%% "munit" % munitVersion % Test
    ),
    mimaPreviousArtifacts := Set.empty
  )
  .jsSettings(moduleJsSettings)
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
      "org.typelevel" %%% "cats-mtl" % catsMtlVersion,
      "org.scalameta" %%% "munit"    % munitVersion % Test
    ),
    mimaPreviousArtifacts := Set.empty
  )
  .jsSettings(moduleJsSettings)
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
      "org.typelevel" %% "kind-projector" % "0.11.3" cross CrossVersion.full
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
