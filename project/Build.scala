import sbt._
import Keys._

import org.sbtidea.SbtIdeaPlugin

object ProjectBuild extends Build {
    override lazy val settings = super.settings ++ Seq(
        organization := "org.refptr",
        version := "0.1",
        scalaVersion := "2.10.2",
        scalacOptions ++= Seq("-deprecation", "-unchecked", "-feature", "-language:_"),
        shellPrompt := { state =>
            "refptr (%s)> ".format(Project.extract(state).currentProject.id)
        },
        cancelable := true,
        resolvers ++= Seq(
            "Sonatype Releases" at "http://oss.sonatype.org/content/repositories/releases",
            "Sonatype Snapshots" at "http://oss.sonatype.org/content/repositories/snapshots",
            "Typesafe Releases" at "https://typesafe.artifactoryonline.com/typesafe/maven-releases/",
            "Typesafe Snapshots" at "https://typesafe.artifactoryonline.com/typesafe/maven-snapshots/",
            "Mandubian Releases" at "https://github.com/mandubian/mandubian-mvn/raw/master/releases/",
            "Mandubian Snapshots" at "https://github.com/mandubian/mandubian-mvn/raw/master/snapshots/")
    )

    object Dependencies {
        val lift = {
            val namespace= "net.liftweb"
            val version = "2.5.1"
            Seq(namespace %% "lift-util" % version,
                namespace %% "lift-json" % version)
        }

        val scalaio = {
            val namespace = "com.github.scala-incubator.io"
            val version = "0.4.2"
            Seq(namespace %% "scala-io-core" % version,
                namespace %% "scala-io-file" % version)
        }

        val play_json = "play" %% "play-json" % "2.2-SNAPSHOT"

        val jeromq = "org.jeromq" % "jeromq" % "0.3.0-SNAPSHOT"

        val specs2 = "org.specs2" %% "specs2" % "2.1.1" % "test"
    }

    val jrebelRunning = SettingKey[Boolean]("jrebel-running")

    lazy val pluginSettings = SbtIdeaPlugin.settings

    lazy val projectSettings = Project.defaultSettings ++ pluginSettings ++ Seq(
        fork in run := true,
        jrebelRunning := {
            val jrebel = java.lang.Package.getPackage("com.zeroturnaround.javarebel") != null
            ConsoleLogger().log(Level.Info, s"JRebel is ${if (jrebel) "enabled" else "disabled"}")
            jrebel
        },
        libraryDependencies ++= {
            import Dependencies._
            lift ++ scalaio ++ Seq(jeromq, play_json, specs2)
        }
    )

    lazy val IScala = Project(id="IScala", base=file("."), settings=projectSettings)

    override def projects = Seq(IScala)
}
