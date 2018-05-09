
val commonDeps = Seq(
  "org.typelevel" %% "cats-core" % "1.1.0",
  "org.scalatest" %% "scalatest" % "3.0.5" % Test,
  "org.scalacheck" %% "scalacheck" % "1.14.0" % Test
)
val commonSettings = Seq(
  scalaVersion := "2.12.6",
  crossScalaVersions := Seq("2.11.12", scalaVersion.value),
  scalacOptions ++= Seq(
    "-language:higherKinds"
  ),
  scalacOptions in (Test, compile) += "-Ypartial-unification",
  libraryDependencies ++= commonDeps,
  scalafmtOnCompile := true
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

val root = project.in(file(".")).aggregate(core, `cats-effect`)
