val finchVersion = "0.28.0"
val circeVersion = "0.11.1"
val scalatestVersion = "3.0.5"

lazy val root = (project in file("."))
  .settings(
    name := "twine-server",
    scalaVersion := "2.12.7",
    libraryDependencies ++= Seq(
      "com.github.finagle" %% "finchx-core"  % finchVersion,
      "com.github.finagle" %% "finchx-circe"  % finchVersion,
      "io.circe" %% "circe-generic" % circeVersion,
      "org.scalatest"      %% "scalatest"    % scalatestVersion % "test",
      "nl.gn0s1s" %% "base64" % "0.2.2-RC1",
      "biz.neumann" %% "nice-url-encode-decode" % "1.5",
      "com.github.pathikrit" %% "better-files" % "3.7.1",
      "ch.qos.logback" % "logback-classic" % "1.2.3",
      "com.typesafe.scala-logging" %% "scala-logging" % "3.9.2"
    )
  )
