import sbt._
import Keys._

import sbtassembly.{Plugin=>SbtAssembly}
import org.sbtidea.SbtIdeaPlugin
import com.typesafe.sbt.SbtNativePackager

import play.api.libs.json.Json

object Dependencies {
    val isScala_2_10 = Def.setting {
        scalaVersion.value.startsWith("2.10")
    }

    def scala_2_10(moduleID: ModuleID) =
        Def.setting { if (isScala_2_10.value) Seq(moduleID) else Seq.empty }

    def scala_2_11_+(moduleID: ModuleID) =
        Def.setting { if (!isScala_2_10.value) Seq(moduleID) else Seq.empty }

    val scalaio = {
        val namespace = "com.github.scala-incubator.io"
        val version = "0.4.3"
        Seq(namespace %% "scala-io-core" % version,
            namespace %% "scala-io-file" % version)
    }

    val ivy = Def.setting {
        val organization = if (isScala_2_10.value) "org.scala-sbt" else "org.refptr.sbt211"
        organization % "ivy" % "0.13.6"
    }

    val scopt = "com.github.scopt" %% "scopt" % "3.2.0"

    val jeromq = "org.zeromq" % "jeromq" % "0.3.4"

    val play_json = "com.typesafe.play" %% "play-json" % "2.4.0-M1"

    val slick = "com.typesafe.slick" %% "slick" % "2.1.0"

    val codec = "commons-codec" % "commons-codec" % "1.9"

    val sqlite = "org.xerial" % "sqlite-jdbc" % "3.7.2"

    val slf4j = "org.slf4j" % "slf4j-nop" % "1.6.4"

    val specs2 = "org.specs2" %% "specs2" % "2.3.11" % Test

    val reflect = Def.setting { "org.scala-lang" % "scala-reflect" % scalaVersion.value }

    val compiler = Def.setting { "org.scala-lang" % "scala-compiler" % scalaVersion.value }

    val paradise = "org.scalamacros" % "paradise" % "2.0.1" cross CrossVersion.full

    val quasiquotes = scala_2_10("org.scalamacros" %% "quasiquotes" % "2.0.0")

    val xml = scala_2_11_+("org.scala-lang.modules" %% "scala-xml" % "1.0.2")

    val spark = "org.apache.spark" % "spark-repl_2.10" % "1.2.0-SNAPSHOT" % Provided
}

object IScalaBuild extends Build {
    override lazy val settings = super.settings ++ Seq(
        organization := "org.refptr.iscala",
        name := "IScala",
        version := "0.3-SNAPSHOT",
        description := "Scala-language backend for IPython",
        homepage := Some(url("http://iscala.github.io")),
        licenses := Seq("MIT-style" -> url("http://www.opensource.org/licenses/mit-license.php")),
        scalaVersion := "2.11.2",
        crossScalaVersions := Seq("2.10.4", "2.11.2"),
        scalacOptions ++= Seq("-deprecation", "-unchecked", "-feature", "-language:_"),
        addCompilerPlugin(Dependencies.paradise),
        shellPrompt := { state =>
            "refptr (%s)> ".format(Project.extract(state).currentProject.id)
        },
        cancelable := true)

    val release = taskKey[File]("Create a set of archives and installers for new release")

    val ipyCommands = settingKey[List[(String, String)]]("IPython commands (e.g. console) and their command line options")

    val debugPort = settingKey[Int]("Port for remote debugging")
    val debugCommand = taskKey[Seq[String]]("JVM command line options enabling remote debugging")

    val develScripts = taskKey[Seq[File]]("Development scripts generated in bin/2.xx/")
    val userScripts = taskKey[Seq[File]]("User scripts generated in target/scala-2.xx/bin/")

    val kernelArgs = taskKey[List[String]]("...")
    val kernelSpec = taskKey[KernelSpec]("...")
    val writeKernelSpec = taskKey[Unit]("...")

    case class KernelSpec(
        argv: List[String],
        display_name: String,
        language: String,
        codemirror_mode: Option[SyntaxMode] = None,
        env: Map[String, String] = Map.empty,
        help_links: List[HelpLink] = Nil)

    case class SyntaxMode(name: String, mode: String)
    case class HelpLink(text: String, url: String /*java.net.URL*/)

    implicit val HelpLinkJSON   = Json.format[HelpLink]
    implicit val SyntaxModeJSON = Json.format[SyntaxMode]
    implicit val KernelSpecJSON = Json.format[KernelSpec]

    lazy val ideaSettings = SbtIdeaPlugin.settings

    lazy val assemblySettings = SbtAssembly.assemblySettings ++ {
        import SbtAssembly.AssemblyKeys._
        Seq(test in assembly := {},
            jarName in assembly := s"${name.value}.jar",
            target in assembly := crossTarget.value / "lib",
            assembly <<= assembly dependsOn userScripts)
    }

    lazy val packagerSettings = SbtNativePackager.packagerSettings ++ {
        import SbtNativePackager.NativePackagerKeys._
        import SbtNativePackager.Universal
        import SbtAssembly.AssemblyKeys.assembly
        Seq(packageName in Universal := {
                s"${name.value}-${scalaBinaryVersion.value}-${version.value}"
            },
            mappings in Universal ++= {
                assembly.value pair relativeTo(crossTarget.value)
            },
            mappings in Universal ++= {
                userScripts.value pair relativeTo(crossTarget.value)
            },
            mappings in Universal ++= {
                val paths = Seq("README.md", "LICENSE")
                paths.map(path => (file(path), path))
            },
            release <<= packageZipTarball in Universal)
    }

    lazy val pluginSettings = ideaSettings ++ assemblySettings ++ packagerSettings

    lazy val iscalaSettings = Defaults.coreDefaultSettings ++ pluginSettings ++ {
        Seq(resolvers ++= {
                val github = url("https://raw.githubusercontent.com/mattpap/mvn-repo/master/releases")
                Seq(Resolver.url("github-releases", github)(Resolver.ivyStylePatterns), Resolver.mavenLocal)
            },
            libraryDependencies ++= {
                import Dependencies._
                scalaio ++ Seq(ivy.value, scopt, jeromq, play_json, slick, sqlite, slf4j, specs2, compiler.value, spark)
            },
            unmanagedSourceDirectories in Compile += {
                (sourceDirectory in Compile).value / s"scala_${scalaBinaryVersion.value}"
            },
            fork := true,
            initialCommands in Compile := """
                import scala.reflect.runtime.{universe=>u}
                import scala.tools.nsc.interpreter._
                import scalax.io.JavaConverters._
                import scalax.file.Path
                import scala.slick.driver.SQLiteDriver.simple._
                import Database.dynamicSession
                import org.refptr.iscala._
                """,
            kernelArgs := {
                val classpath = (fullClasspath in Compile).value.files.mkString(java.io.File.pathSeparator)
                val main = (mainClass in Compile).value getOrElse sys.error("unknown main class")
                List("java", "-cp", classpath, main, "--profile", "{connection_file}", "--parent")
            },
            kernelSpec := {
                KernelSpec(
                    argv = kernelArgs.value,
                    display_name = s"IScala (Scala ${scalaBinaryVersion.value})",
                    language = "scala",
                    codemirror_mode = Some(SyntaxMode("text/x-scala", "clike")))
            },
            writeKernelSpec := {
                val dir = Path.userHome / ".ipython" / "kernels" / s"IScala-${scalaBinaryVersion.value}"
                val spec = Json.stringify(Json.toJson(kernelSpec.value))
                IO.createDirectory(dir)
                IO.write(dir / "kernel.json", spec)
            },
            ipyCommands := List(
                "console"   -> "--no-banner",
                "qtconsole" -> "",
                "notebook"  -> ""),
            debugPort := 5005,
            debugCommand := {
                Seq("-Xdebug", s"-Xrunjdwp:transport=dt_socket,server=y,suspend=n,address=127.0.0.1:${debugPort.value}")
            },
            develScripts := {
                val classpath = (fullClasspath in Compile).value.files.mkString(java.io.File.pathSeparator)
                val main = (mainClass in Compile).value getOrElse sys.error("unknown main class")

                val java_args = Seq("java", "-cp", classpath, main)
                val ipy_args = Seq("--profile", "{connection_file}", "--parent")
                val kernel_args = (java_args ++ ipy_args).map(arg => s"'$arg'").mkString(", ")
                val extra_args = """$(python -c "import shlex; print(shlex.split('$*'))")"""
                val kernel_cmd = s"[$kernel_args] + $extra_args"

                val binDirectory = baseDirectory.value / "bin" / scalaBinaryVersion.value
                IO.createDirectory(binDirectory)

                def shebang(script: String) = s"#!/bin/bash\n$script"

                val kernel = "kernel" -> (java_args :+ "$*").mkString(" ")

                val scripts = kernel :: (ipyCommands.value map { case (name, options) =>
                    name -> s"""ipython $name --profile scala $options --KernelManager.kernel_cmd="$kernel_cmd""""
                })

                scripts map { case (name, content) =>
                    val file = binDirectory / name
                    streams.value.log.info(s"Writing ${file.getPath}")
                    IO.write(file, shebang(content))
                    file.setExecutable(true)
                    file
                }
            },
            userScripts := {
                val baseDir = "$(dirname $(dirname $(readlink -f $0)))"
                val jarName = {
                    import SbtAssembly.AssemblyKeys.{assembly,jarName=>jar}
                    (jar in assembly).value
                }

                val args = Seq("java", "-jar", "$JAR", "--profile", "{connection_file}", "--parent")
                val kernel_args = args.map(arg => s"'$arg'").mkString(", ")
                val extra_args = """$(python -c "import shlex; print(shlex.split('$*'))")"""
                val kernel_cmd = s"[$kernel_args] + $extra_args"

                val binDirectory = crossTarget.value / "bin"
                IO.createDirectory(binDirectory)

                ipyCommands.value map { case (command, options) =>
                    val output = s"""
                        |#!/bin/bash
                        |JAR="$baseDir/lib/$jarName"
                        |ipython $command --profile scala $options --KernelManager.kernel_cmd="$kernel_cmd"
                        """.stripMargin.trim + "\n"
                    val file = binDirectory / command
                    streams.value.log.info(s"Writing ${file.getPath}")
                    IO.write(file, output)
                    file.setExecutable(true)
                    file
                }
            })
    }

    lazy val coreSettings = Defaults.coreDefaultSettings ++ Seq(
        libraryDependencies ++= {
            import Dependencies._
            quasiquotes.value ++ Seq(reflect.value, play_json, specs2)
        }
    )

    lazy val libSettings = Defaults.coreDefaultSettings ++ Seq(
        libraryDependencies ++= {
            import Dependencies._
            quasiquotes.value ++ xml.value ++ Seq(reflect.value, codec, specs2)
        }
    )

    lazy val IScala = project in file(".") settings(iscalaSettings: _*) dependsOn(core, lib) aggregate(core, lib)
    lazy val core   = project in file("core") settings(coreSettings: _*)
    lazy val lib    = project in file("lib") settings(libSettings: _*) dependsOn(core)

    override def projects = Seq(IScala, core, lib)
}
