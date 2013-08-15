import sbt._
import Keys._

import org.sbtidea.{SbtIdeaPlugin=>SbtIdea}
import sbtassembly.{Plugin=>SbtAssembly}

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
        val scalaio = {
            val namespace = "com.github.scala-incubator.io"
            val version = "0.4.2"
            Seq(namespace %% "scala-io-core" % version,
                namespace %% "scala-io-file" % version)
        }

        val jopt = "net.sf.jopt-simple" % "jopt-simple" % "4.5"

        val jeromq = "org.jeromq" % "jeromq" % "0.3.0-SNAPSHOT"

        val play_json = "play" %% "play-json" % "2.2-SNAPSHOT"

        val specs2 = "org.specs2" %% "specs2" % "2.1.1" % "test"
    }

    def info(msg: => String) {
        ConsoleLogger().log(Level.Info, msg)
    }

    val jrebelRunning = SettingKey[Boolean]("jrebel-running")

    lazy val pluginSettings = SbtIdea.settings ++ SbtAssembly.assemblySettings ++ {
        import SbtAssembly.AssemblyKeys._
        Seq(test in assembly := {},
            jarName in assembly := "IScala.jar",
            target in assembly := baseDirectory.value,
            assemblyDirectory in assembly := {
                val tmpDir = IO.createTemporaryDirectory
                info(s"Using $tmpDir for sbt-assembly temporary files")
                tmpDir
            },
            assembly <<= (assembly, assemblyDirectory in assembly) map { (outputFile, tmpDir) =>
                info(s"Cleaning up $tmpDir")
                IO.delete(tmpDir)
                outputFile
            })
    }

    lazy val projectSettings = Project.defaultSettings ++ pluginSettings ++ Seq(
        fork in run := true,
        jrebelRunning := {
            val jrebel = java.lang.Package.getPackage("com.zeroturnaround.javarebel") != null
            info(s"JRebel is ${if (jrebel) "enabled" else "disabled"}")
            jrebel
        },
        libraryDependencies ++= {
            import Dependencies._
            scalaio ++ Seq(jopt, jeromq, play_json, specs2)
        },
        libraryDependencies <+= scalaVersion("org.scala-lang" % "scala-compiler" % _)
    )

    lazy val macrosSettings = Project.defaultSettings ++ Seq(
        libraryDependencies ++= {
            import Dependencies._
            Seq(play_json, specs2)
        },
        libraryDependencies <+= scalaVersion("org.scala-lang" % "scala-reflect" % _)
    )

    lazy val IScala = Project(id="IScala", base=file("."), settings=projectSettings) dependsOn(Macros)

    lazy val Macros = Project(id="Macros", base=file("macros"), settings=macrosSettings)

    override def projects = Seq(IScala, Macros)
}
