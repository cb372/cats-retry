import ReleaseTransformations._
import sbtcrossproject.CrossPlugin.autoImport.crossProject

lazy val scalaVersion213 = "2.13.0"
lazy val scalaVersion212 = "2.12.8"
lazy val scalaVersion211 = "2.11.12"
lazy val scalaVersions   = List(scalaVersion213, scalaVersion212, scalaVersion211)

ThisBuild / scalaVersion := scalaVersion212

val commonSettings = Seq(
  organization := "com.github.cb372",
  publishTo := sonatypePublishTo.value,
  //releaseCrossBuild := true,
  releaseVcsSign := true,
  //releasePublishArtifactsAction := PgpKeys.publishSigned.value,
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
    )
  )
)

val moduleSettings = commonSettings ++ Seq(
  moduleName := s"cats-retry-${name.value}",
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

val catsVersion          = "2.0.0-M4"
val scalatestVersion     = "3.1.0-SNAP13"
val scalaTestPlusVersion = "1.0.0-SNAP8"
val scalacheckVersion    = "1.14.0"
val disciplineVersion    = "0.12.0-M3"
val core = crossProject(JVMPlatform, JSPlatform)
  .in(file("modules/core"))
  .settings(moduleSettings)
  .settings(
    crossScalaVersions := scalaVersions,
    libraryDependencies ++= Seq(
      "org.typelevel"     %%% "cats-core"                % catsVersion,
      "org.typelevel"     %%% "cats-laws"                % catsVersion % Test,
      "org.scalatest"     %%% "scalatest"                % scalatestVersion % Test,
      "org.scalatestplus" %%% "scalatestplus-scalacheck" % scalaTestPlusVersion % Test,
      "org.typelevel"     %%% "discipline-scalatest"     % disciplineVersion % Test,
      "org.scalacheck"    %%% "scalacheck"               % scalacheckVersion % Test
    )
  )
val coreJVM = core.jvm
val coreJS  = core.js

val catsEffect = crossProject(JVMPlatform, JSPlatform)
  .in(file("modules/cats-effect"))
  .jvmConfigure(_.dependsOn(coreJVM))
  .jsConfigure(_.dependsOn(coreJS))
  .settings(moduleSettings)
  .settings(
    crossScalaVersions := scalaVersions,
    name := "cats-effect",
    libraryDependencies ++= Seq(
      "org.typelevel"  %%% "cats-effect" % "2.0.0-M4",
      "org.scalatest"  %%% "scalatest"   % scalatestVersion % Test,
      "org.scalacheck" %%% "scalacheck"  % scalacheckVersion % Test
    )
  )
val catsEffectJVM = catsEffect.jvm
val catsEffectJS  = catsEffect.js

val monix = crossProject(JVMPlatform, JSPlatform)
  .in(file("modules/monix"))
  .jvmConfigure(_.dependsOn(coreJVM))
  .jsConfigure(_.dependsOn(coreJS))
  .settings(moduleSettings)
  .settings(
    crossScalaVersions := List(scalaVersion212, scalaVersion211),
    libraryDependencies ++= Seq(
      "io.monix"       %%% "monix"      % "3.0.0-RC3",
      "org.scalatest"  %%% "scalatest"  % scalatestVersion % Test,
      "org.scalacheck" %%% "scalacheck" % scalacheckVersion % Test
    )
  )
val monixJVM = monix.jvm
val monixJS  = monix.js

val docs = project
  .in(file("modules/docs"))
  .dependsOn(coreJVM, catsEffectJVM, monixJVM)
  .enablePlugins(MicrositesPlugin, BuildInfoPlugin)
  .settings(moduleSettings)
  .settings(
    scalacOptions -= "-Ywarn-dead-code",
    scalacOptions -= "-Ywarn-unused",
    scalacOptions += "-Ydelambdafy:inline",
    addCompilerPlugin("org.typelevel" %% "kind-projector" % "0.11.0" cross CrossVersion.full),
    crossScalaVersions := Nil,
    buildInfoKeys := Seq[BuildInfoKey](latestVersion),
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
    catsEffectJVM,
    catsEffectJS,
    monixJVM,
    monixJS,
    docs
  )
  .settings(commonSettings)
  .settings(
    publishTo := sonatypePublishTo.value, // see https://github.com/sbt/sbt-release/issues/184,
    publishArtifact := false,
    crossScalaVersions := Nil,
    releaseProcess := Seq[ReleaseStep](
      checkSnapshotDependencies,
      inquireVersions,
      runClean,
      releaseStepCommandAndRemaining("+test"),
      setReleaseVersion,
      setLatestVersion,
      commitReleaseVersion,
      tagRelease,
      releaseStepCommandAndRemaining("+publishSigned"),
      setNextVersion,
      commitNextVersion,
      releaseStepCommand("sonatypeReleaseAll"),
      pushChanges
    )
  )
