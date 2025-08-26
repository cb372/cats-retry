import sbtcrossproject.CrossPlugin.autoImport.crossProject
import _root_.org.typelevel.scalacoptions.ScalacOptions

lazy val scalaVersion3 = "3.3.6"
lazy val scalaVersions = List(scalaVersion3)

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
      ),
      Developer(
        id = "AlejandroBudy",
        name = "Alejandro Torres",
        email = "alejandro.torres@xebia.com",
        url = url("https://github.com/AlejandroBudy")
      ),
      Developer(
        id = "ccantarero91",
        name = "Cristian Cantarero Dávila",
        email = "cristian.cantarero@xebia.com",
        url = url("https://github.com/ccantarero91")
      )
    ),
    versionScheme         := Some("semver-spec"),
    mimaPreviousArtifacts := Set.empty,
    scalafmtOnCompile     := false
  )
)

val catsVersion             = "2.13.0"
val catsEffectVersion       = "3.6.3"
val catsMtlVersion          = "1.6.0"
val munitVersion            = "1.1.0"
val munitCatsEffectVersion  = "2.1.0"
val disciplineVersion       = "2.0.0"
val scalacheckEffectVersion = "1.0.4"

// All the versions we want to check against for binary compatibility.
// In general, every time we release a new version, we should add it here.
val mimaPreviousVersions = Set("4.0.0")

val core = crossProject(JVMPlatform, JSPlatform)
  .in(file("modules/core"))
  .settings(
    name               := "cats-retry",
    crossScalaVersions := scalaVersions,
    libraryDependencies ++= Seq(
      "org.typelevel" %%% "cats-core"         % catsVersion,
      "org.typelevel" %%% "cats-effect"       % catsEffectVersion,
      "org.scalameta" %%% "munit-scalacheck"  % munitVersion            % Test,
      "org.typelevel" %%% "munit-cats-effect" % munitCatsEffectVersion  % Test,
      "org.typelevel" %%% "scalacheck-effect" % scalacheckEffectVersion % Test,
      "org.typelevel" %%% "cats-laws"         % catsVersion             % Test,
      "org.typelevel" %%% "discipline-munit"  % disciplineVersion       % Test
    ),
    mimaPreviousArtifacts := mimaPreviousVersions.map(v => organization.value %%% name.value % v),
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
      "org.typelevel" %%% "cats-mtl"          % catsMtlVersion,
      "org.typelevel" %%% "munit-cats-effect" % munitCatsEffectVersion % Test,
      "org.scalameta" %%% "munit-scalacheck"  % munitVersion           % Test
    ),
    mimaPreviousArtifacts := Set.empty,
    mimaPreviousArtifacts := mimaPreviousVersions.map(v => organization.value %%% name.value % v),
    Test / tpolecatExcludeOptions += ScalacOptions.warnNonUnitStatement
  )
val mtlJVM = mtlRetry.jvm
val mtlJS  = mtlRetry.js

val docs = project
  .in(file("modules/docs"))
  .dependsOn(coreJVM, mtlJVM)
  .enablePlugins(MicrositesPlugin, BuildInfoPlugin)
  .settings(
    libraryDependencies ++= Seq(
      "software.amazon.awssdk" % "dynamodb" % "2.29.43"
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
