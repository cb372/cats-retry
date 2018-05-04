libraryDependencies ++= Seq(
  "org.typelevel" %% "cats-core" % "1.1.0",
  "org.scalatest" %% "scalatest" % "3.0.5",
  "org.scalacheck" %% "scalacheck" % "1.14.0"
)

scalacOptions ++= Seq(
  "-language:higherKinds"
)
scalacOptions in (Test, compile) += "-Ypartial-unification"

scalafmtOnCompile := true
