import sbtcrossproject.CrossPlugin.autoImport.crossProject
import _root_.org.typelevel.sbt.tpolecat.TpolecatPlugin.autoImport._
import _root_.org.typelevel.scalacoptions.ScalacOptions

lazy val scalaVersion212 = "2.12.19"
lazy val scalaVersion213 = "2.13.13"
lazy val scalaVersion3   = "3.3.4"
lazy val scalaVersions   = List(scalaVersion212, scalaVersion213, scalaVersion3)

inThisBuild(
  Seq(
    scalaVersion := scalaVersion213,
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
    scalafmtOnCompile     := true
  )
)

val catsVersion          = "2.10.0"
val catsEffectVersion    = "3.5.4"
val catsMtlVersion       = "1.4.0"
val scalatestVersion     = "3.2.18"
val scalaTestPlusVersion = "3.2.18.0"
val scalacheckVersion    = "1.17.0"
val disciplineVersion    = "2.2.0"

val core = crossProject(JVMPlatform, JSPlatform)
  .in(file("modules/core"))
  .settings(
    name               := "cats-retry",
    crossScalaVersions := scalaVersions,
    libraryDependencies ++= Seq(
      "org.typelevel"     %%% "cats-core"       % catsVersion,
      "org.typelevel"     %%% "cats-effect"     % catsEffectVersion,
      "org.scalatest"     %%% "scalatest"       % scalatestVersion     % Test,
      "org.scalacheck"    %%% "scalacheck"      % scalacheckVersion    % Test,
      "org.typelevel"     %%% "cats-laws"       % catsVersion          % Test,
      "org.scalatestplus" %%% "scalacheck-1-17" % scalaTestPlusVersion % Test,
      "org.typelevel" %%% "discipline-scalatest" % disciplineVersion % Test
    ),
    mimaPreviousArtifacts := Set(
      "com.github.cb372" %%% "cats-retry" % "3.1.0"
    ),
    tpolecatExcludeOptions += ScalacOptions.lintPackageObjectClasses,
    Test / tpolecatExcludeOptions += ScalacOptions.warnNonUnitStatement
  )
  .jsSettings(
    // work around https://github.com/typelevel/sbt-tpolecat/issues/102
    tpolecatScalacOptions +=
      ScalacOptions.other("-scalajs", sv => sv.major == 3L)
  )
val coreJVM = core.jvm
val coreJS  = core.js

val alleycatsRetry = crossProject(JVMPlatform, JSPlatform)
  .in(file("modules/alleycats"))
  .jvmConfigure(_.dependsOn(coreJVM))
  .jsConfigure(_.dependsOn(coreJS))
  .settings(
    name               := "alleycats-retry",
    crossScalaVersions := scalaVersions,
    libraryDependencies ++= Seq(
      "org.scalatest"     %%% "scalatest"       % scalatestVersion     % Test,
      "org.scalacheck"    %%% "scalacheck"      % scalacheckVersion    % Test,
      "org.typelevel"     %%% "cats-laws"       % catsVersion          % Test,
      "org.scalatestplus" %%% "scalacheck-1-17" % scalaTestPlusVersion % Test,
      "org.typelevel" %%% "discipline-scalatest" % disciplineVersion % Test
    ),
    mimaPreviousArtifacts := Set(
      "com.github.cb372" %%% "alleycats-retry" % "3.1.0"
    ),
    Test / tpolecatExcludeOptions += ScalacOptions.warnNonUnitStatement
  )
  .jsSettings(
    tpolecatScalacOptions += ScalacOptions
      .other("-scalajs", sv => sv.major == 3L)
  )
val alleycatsJVM = alleycatsRetry.jvm
val alleycatsJS  = alleycatsRetry.js

val mtlRetry = crossProject(JVMPlatform, JSPlatform)
  .in(file("modules/mtl"))
  .jvmConfigure(_.dependsOn(coreJVM))
  .jsConfigure(_.dependsOn(coreJS))
  .settings(
    name               := "cats-retry-mtl",
    crossScalaVersions := scalaVersions,
    libraryDependencies ++= Seq(
      "org.typelevel" %%% "cats-mtl"  % catsMtlVersion,
      "org.scalatest" %%% "scalatest" % scalatestVersion % Test
    ),
    mimaPreviousArtifacts := Set(
      "com.github.cb372" %%% "cats-retry-mtl" % "3.1.0"
    ),
    tpolecatExcludeOptions += ScalacOptions.lintPackageObjectClasses,
    Test / tpolecatExcludeOptions += ScalacOptions.warnNonUnitStatement
  )
  .jsSettings(
    // work around https://github.com/typelevel/sbt-tpolecat/issues/102
    tpolecatScalacOptions +=
      ScalacOptions.other("-scalajs", sv => sv.major == 3L)
  )
val mtlJVM = mtlRetry.jvm
val mtlJS  = mtlRetry.js

val docs = project
  .in(file("modules/docs"))
  .dependsOn(coreJVM, alleycatsJVM, mtlJVM)
  .enablePlugins(MicrositesPlugin, BuildInfoPlugin)
  .settings(
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
    alleycatsJVM,
    alleycatsJS,
    mtlJVM,
    mtlJS,
    docs
  )
  .settings(
    publishArtifact    := false,
    crossScalaVersions := Nil
  )
