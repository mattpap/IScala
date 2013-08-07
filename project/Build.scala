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
            "Sonatype Snapshots" at "http://oss.sonatype.org/content/repositories/snapshots"
        )
    )

    object Dependencies {
        val lift = {
            val namespace= "net.liftweb"
            val version = "2.5.1"
            Seq(namespace %% "lift-util" % version,
                namespace %% "lift-json" % version)
        }

        val zeromq = "org.zeromq" % "jzmq" % "2.2.1"

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
            lift ++ Seq(zeromq, specs2)
        }
    )

    lazy val IScala = Project(id="IScala", base=file("."), settings=projectSettings)

    override def projects = Seq(IScala)
}
