
val Http4sVersion = "0.20.0"
val CirceVersion = "0.13.0"
val SttpVersion = "2.0.7"
val ScalaCacheVersion = "0.28.0"


lazy val root = (project in file("."))
  .settings(
    organization := "serhii",
    name := "task",
    version := "0.0.1-SNAPSHOT",
    scalaVersion := "2.12.11",
    scalacOptions ++= Seq(
      "-deprecation",
      "-encoding", "UTF-8",
      "-language:higherKinds",
      "-language:postfixOps",
      "-feature",
      "-Ypartial-unification",
    ),
    libraryDependencies ++= Seq(
      "org.http4s" %% "http4s-blaze-server" % Http4sVersion,
      "org.http4s" %% "http4s-blaze-client" % Http4sVersion,
      "org.http4s" %% "http4s-circe" % Http4sVersion,
      "org.http4s" %% "http4s-dsl" % Http4sVersion,
      "io.circe" %% "circe-generic" % CirceVersion,
      "com.softwaremill.sttp.client" %% "core" % SttpVersion,
      "com.softwaremill.sttp.client" %% "circe" % SttpVersion,
      "ch.qos.logback" % "logback-classic" % "1.2.3",
      "com.typesafe.scala-logging" %% "scala-logging" % "3.9.0",
      "com.softwaremill.sttp.client" %% "async-http-client-backend-cats" % SttpVersion,
      "org.scalatest" %% "scalatest" % "3.1.1" % Test,
      "org.scalamock" %% "scalamock" % "4.4.0" % Test,
      "com.typesafe" % "config" % "1.3.3"
    )
  )

scalafmtOnCompile in ThisBuild := true
