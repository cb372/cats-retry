import ReleaseTransformations._

val commonDeps = Seq(
  "org.typelevel" %% "cats-core" % "1.1.0",
  "org.scalatest" %% "scalatest" % "3.0.5" % Test,
  "org.scalacheck" %% "scalacheck" % "1.14.0" % Test
)

val commonSettings = Seq(
  organization := "com.github.cb372",
  moduleName := s"cats-retry-${name.value}",
  scalacOptions ++= Seq(
    "-language:higherKinds"
  ),
  scalacOptions in (Test, compile) += "-Ypartial-unification",
  libraryDependencies ++= commonDeps,
  scalafmtOnCompile := true,
  publishTo := sonatypePublishTo.value
)

val core = project.in(file("modules/core"))
    .settings(commonSettings)

val `cats-effect` = project.in(file("modules/cats-effect"))
  .dependsOn(core)
  .settings(commonSettings)
  .settings(
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect" % "0.10.1"
    )
  )

val docs = project.in(file("modules/docs"))
  .dependsOn(core, `cats-effect`)
  .enablePlugins(MicrositesPlugin)
  .settings(commonSettings)
  .settings(
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
    micrositeShareOnSocial := true
  )

val releaseSettings = Seq(
  releaseCrossBuild := true,
  releasePublishArtifactsAction := PgpKeys.publishSigned.value,
  pomIncludeRepository := { _ => false },
  publishMavenStyle := true,
  licenses := Seq("Apache License, Version 2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0.html")),
  homepage := Some(url("https://cb372.github.io/cats-retry/")),
  developers := List(
    Developer(
      id    = "cb372",
      name  = "Chris Birchall",
      email = "chris.birchall@gmail.com",
      url   = url("https://github.com/cb372")
    )
  ),
  releaseProcess := Seq[ReleaseStep](
    checkSnapshotDependencies,
    inquireVersions,
    runClean,
    runTest,
    setReleaseVersion,
    commitReleaseVersion,
    tagRelease,
    publishArtifacts,
    setNextVersion,
    commitNextVersion,
    releaseStepCommand("sonatypeReleaseAll"),
    pushChanges
  )
)

val root = project.in(file("."))
  .aggregate(core, `cats-effect`, docs)
  .settings(releaseSettings)
  .settings(
    publishTo := sonatypePublishTo.value, // see https://github.com/sbt/sbt-release/issues/184
    publishArtifact := false
  )
