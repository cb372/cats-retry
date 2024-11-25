import sbtcrossproject.CrossPlugin.autoImport.crossProject
import _root_.org.typelevel.sbt.tpolecat.TpolecatPlugin.autoImport._
import _root_.org.typelevel.scalacoptions.ScalacOptions

lazy val scalaVersion213 = "2.13.13"
lazy val scalaVersion3   = "3.3.3"
lazy val scalaVersions   = List(scalaVersion213, scalaVersion3)

inThisBuild(
  Seq(
    scalaVersion := scalaVersion3,
    organization := "com.github.cb372",
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
    mimaPreviousArtifacts := Set.empty,
    scalafmtOnCompile     := false
  )
)

val catsVersion            = "2.12.0"
val catsEffectVersion      = "3.5.4"
val catsMtlVersion         = "1.4.0"
val munitVersion           = "1.0.0"
val munitCatsEffectVersion = "2.0.0"
val disciplineVersion      = "2.0.0"

val core = crossProject(JVMPlatform, JSPlatform)
  .in(file("modules/core"))
  .settings(
    name               := "cats-retry",
    crossScalaVersions := scalaVersions,
    libraryDependencies ++= Seq(
      "org.typelevel" %%% "cats-core"         % catsVersion,
      "org.typelevel" %%% "cats-effect"       % catsEffectVersion,
      "org.scalameta" %%% "munit-scalacheck"  % munitVersion           % Test,
      "org.typelevel" %%% "munit-cats-effect" % munitCatsEffectVersion % Test,
      "org.typelevel" %%% "cats-laws"         % catsVersion            % Test,
      "org.typelevel" %%% "discipline-munit"  % disciplineVersion      % Test
    ),
    mimaPreviousArtifacts := Set.empty,
    Test / tpolecatExcludeOptions += ScalacOptions.warnNonUnitStatement
  )
val coreJVM = core.jvm
val coreJS  = core.js

val mtlRetry = crossProject(JVMPlatform, JSPlatform)
  .in(file("modules/mtl"))
  .jvmConfigure(_.dependsOn(coreJVM))
  .jsConfigure(_.dependsOn(coreJS))
  .settings(
    name               := "cats-retry-mtl",
    crossScalaVersions := scalaVersions,
    libraryDependencies ++= Seq(
      "org.typelevel" %%% "cats-mtl"         % catsMtlVersion,
      "org.scalameta" %%% "munit-scalacheck" % munitVersion % Test
    ),
    mimaPreviousArtifacts := Set.empty,
    Test / tpolecatExcludeOptions += ScalacOptions.warnNonUnitStatement
  )
val mtlJVM = mtlRetry.jvm
val mtlJS  = mtlRetry.js

val docs = project
  .in(file("modules/docs"))
  .dependsOn(coreJVM, mtlJVM)
  .enablePlugins(MicrositesPlugin, BuildInfoPlugin)
  .settings(
    scalaVersion := scalaVersion213,
    addCompilerPlugin(
      "org.typelevel" %% "kind-projector" % "0.13.3" cross CrossVersion.full
    ),
    tpolecatExcludeOptions ++= ScalacOptions.warnUnusedOptions,
    tpolecatExcludeOptions += ScalacOptions.warnNonUnitStatement,
    crossScalaVersions        := Nil,
    buildInfoPackage          := "retry",
    publishArtifact           := false,
    micrositeName             := "cats-retry",
    micrositeAuthor           := "Chris Birchall",
    micrositeDescription      := "cats-retry",
    micrositeBaseUrl          := "/cats-retry",
    micrositeDocumentationUrl := "/cats-retry/docs",
    micrositeHomepage         := "https://github.com/cb372/cats-retry",
    micrositeGithubOwner      := "cb372",
    micrositeGithubRepo       := "cats-retry",
    micrositeGitterChannel    := true,
    micrositeGitterChannelUrl := "typelevel/cats-retry",
    micrositeTwitterCreator   := "@cbirchall",
    mdocIn                    := (Compile / sourceDirectory).value / "mdoc",
    micrositeShareOnSocial    := true,
    micrositePushSiteWith     := GitHub4s,
    micrositeGithubToken      := sys.env.get("GITHUB_TOKEN")
  )

val root = project
  .in(file("."))
  .aggregate(
    coreJVM,
    coreJS,
    mtlJVM,
    mtlJS,
    docs
  )
  .settings(
    publishArtifact    := false,
    crossScalaVersions := Nil
  )
