import ReleaseTransformations._

val commonDeps = Seq(
  "org.typelevel" %% "cats-core" % "1.1.0",
  "org.scalatest" %% "scalatest" % "3.0.5" % Test,
  "org.scalacheck" %% "scalacheck" % "1.14.0" % Test
)

val commonSettings = Seq(
  organization := "com.github.cb372",
  publishTo := sonatypePublishTo.value,
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
  )
)

val moduleSettings = commonSettings ++ Seq(
  moduleName := s"cats-retry-${name.value}",
  scalacOptions ++= Seq(
    "-language:higherKinds"
  ),
  scalacOptions in (Test, compile) += "-Ypartial-unification",
  libraryDependencies ++= commonDeps,
  scalafmtOnCompile := true
)

val core = project.in(file("modules/core"))
    .settings(moduleSettings)

val `cats-effect` = project.in(file("modules/cats-effect"))
  .dependsOn(core)
  .settings(moduleSettings)
  .settings(
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect" % "0.10.1"
    )
  )

val `monix` = project.in(file("modules/monix"))
  .dependsOn(core)
  .settings(moduleSettings)
  .settings(
    libraryDependencies ++= Seq(
      "io.monix" %% "monix" % "3.0.0-RC1"
    )
  )

val docs = project.in(file("modules/docs"))
  .dependsOn(core, `cats-effect`, `monix`)
  .enablePlugins(MicrositesPlugin)
  .settings(moduleSettings)
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

val root = project.in(file("."))
  .aggregate(core, `cats-effect`, docs, `monix`)
  .settings(commonSettings)
  .settings(
    publishTo := sonatypePublishTo.value, // see https://github.com/sbt/sbt-release/issues/184
    publishArtifact := false,
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
