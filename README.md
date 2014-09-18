# IScala

**IScala** is a [Scala-language](http://scala-lang.org) backend for [IPython](http://ipython.org).

[![Build Status][travis]](https://travis-ci.org/mattpap/IScala)

## Requirements

* [IPython](http://ipython.org/ipython-doc/stable/install/install.html) 1.0+
* [Java](http://wwww.java.com) JRE 1.6+

## Usage

First obtain a copy of IScala from [here](https://github.com/mattpap/IScala/releases). The
package comes with pre-compiled `IScala.jar` and collection of scripts for running IPython's
console, qtconsole and notebook. `IScala.jar` contains all project dependencies and resources,
so you can move IScala easily around. To start IPython's console, simply issue `bin/console`
in a terminal. This will start `ipython console` and setup it to use IScala backend instead
of the default Python one. Issue `bin/qtconsole` or `bin/notebook` to start IPython's Qt
console or notebook, respectively.

To start IPython with IScala backend manually, issue:
```
ipython console --KernelManager.kernel_cmd='["java", "-jar", "lib/IScala.jar", "--profile", "{connection_file}", "--parent"]'
```
The same works for `qtconsole` and `notebook`, and is, in principle, what scripts in `bin/`
do. Note that you may have to provide a full path to `IScala.jar`. Option `--parent` is
important and tells IScala that it was started by IPython and is not a standalone kernel.
If not provided, double `^C` (`INT` signal) within 500 ms terminates IScala. Otherwise,
`TERM` signal or shutdown message is needed to terminate IScala gracefully. As a safety
measure, IScala also watches connection file that IPython provided. If the file is removed,
the respective kernel is terminated.

You can also create a `scala` profile for IPython. To do this, issue:
```
$ ipython profile create scala
[ProfileCreate] WARNING | Generating default config file: u'~/.config/ipython/profile_scala/ipython_config.py'
[ProfileCreate] WARNING | Generating default config file: u'~/.config/ipython/profile_scala/ipython_qtconsole_config.py'
[ProfileCreate] WARNING | Generating default config file: u'~/.config/ipython/profile_scala/ipython_notebook_config.py'
```
Then add the following line:
```
c.KernelManager.kernel_cmd = ["java", "-jar", "$ISCALA_PATH/lib/IScala.jar", "--profile", "{connection_file}", "--parent"]"
```
to `~/.config/ipython/profile_scala/ipython_config.py`. Replace `$ISCALA_PATH` with the actual
location of `IScala.jar`. Then you can run IPython with `ipython console --profile scala`.

To start a standalone kernel simply issue:
```
$ java -jar lib/IScala.jar
connect ipython with --existing profile-18271.json
Welcome to Scala 2.10.2 (OpenJDK 64-Bit Server VM, Java 1.6.0_27)
```
This creates a connection file `profile-PID.json`, where `PID` is the process ID of IScala
kernel. You can connect IPython using `--existing profile-PID.json`. You can provide an
existing connection file with `--profile` option.

IScala supports other options as well. See `java -jar IScala.jar -h` for details. Note
that you can also pass options directly to Scala compiler after `--` delimiter:
```
$ java -jar IScala.jar --profile profile.json -- -Xprint:typer
```
This will start standalone IScala with preexisting connection file and make Scala compiler
print Scala syntax trees after _typer_ compiler phase.

## Example

```
$ bin/console
Welcome to Scala 2.10.2 (OpenJDK 64-Bit Server VM, Java 1.6.0_27)

In [1]: 1
Out[1]: 1

In [2]: 1 + 2 + 3
Out[2]: 6

In [3]: (1 to 5).foreach { i => println(i); Thread.sleep(1000) }
1
2
3
4
5

In [4]: val x = 1
Out[4]: 1

In [5]: x
Out[5]: 1

In [6]: 100*x + 17
Out[6]: 117

In [7]: x.<TAB>
x.%             x.-             x.>>            x.isInstanceOf  x.toFloat       x.toString      x.|
x.&             x./             x.>>>           x.toByte        x.toInt         x.unary_+
x.*             x.>             x.^             x.toChar        x.toLong        x.unary_-
x.+             x.>=            x.asInstanceOf  x.toDouble      x.toShort       x.unary_~

In [7]: x.to<TAB>
x.toByte    x.toChar    x.toDouble  x.toFloat   x.toInt     x.toLong    x.toShort   x.toString

In [7]: x.toS<TAB>
x.toShort   x.toString

In [7]: 1/0
java.lang.ArithmeticException: / by zero

In [8]: java.util.UUID.fromString("abc")
java.lang.IllegalArgumentException: Invalid UUID string: abc
    java.util.UUID.fromString(UUID.java:226)

In [9]: class Foo(a: Int) { def bar(b: String) = b*a }

In [10]: new Foo(5)
Out[10]: Foo@70f4d063

In [11]: _10.bar("xyz")
Out[11]: xyzxyzxyzxyzxyz

In [12]: import scala.language.experimental.macros

In [13]: import scala.reflect.macros.Context

In [14]: object Macros {
    ...:     def membersImpl[A: c.WeakTypeTag](c: Context): c.Expr[List[String]] = {
    ...:         import c.universe._
    ...:         val tpe = weakTypeOf[A]
    ...:         val members = tpe.declarations.map(_.name.decoded).toList.distinct
    ...:         val literals = members.map(member => Literal(Constant(member)))
    ...:         c.Expr[List[String]](Apply(reify(List).tree, literals))
    ...:     }
    ...:
    ...:     def members[A] = macro membersImpl[A]
    ...: }
    ...:

In [15]: Macros.members[Int]
Out[15]: List(<init>, toByte, toShort, toChar, toInt, toLong, toFloat, toDouble, unary_~,
unary_+, unary_-, +, <<, >>>, >>, ==, !=, <, <=, >, >=, |, &, ^, -, *, /, %, getClass)
```

## Magics

IScala supports magic commands similarly to IPython, but the set of magics is
different to match the specifics of Scala and JVM. Magic commands consist of
percent sign `%` followed by an identifier and optional input to a magic. Magic
command's syntax may resemble valid Scala, but every magic implements its own
domain specific parser.

### Type information

To infer the type of an expression use `%type expr`. This doesn't require
evaluation of `expr`, only compilation up to _typer_ phase. You can also
get compiler's internal type trees with `%type -v` or `%type --verbose`.

```
In [1]: %type 1
Int

In [2]: %type -v 1
TypeRef(TypeSymbol(final abstract class Int extends AnyVal))

In [3]: val x = "" + 1
Out[3]: 1

In [4]: %type x
String

In [5]: %type List(1, 2, 3)
List[Int]

In [6]: %type List("x" -> 1, "y" -> 2, "z" -> 3)
List[(String, Int)]

In [7]: %type List("x" -> 1, "y" -> 2, "z" -> 3.0)
List[(String, AnyVal)]
```

### Library management

Library management is done by [sbt](http://www.scala-sbt.org/). There is no
need for a build file, because settings are managed by IScala. To add a
dependency use `%libraryDependencies += moduleID`, where `moduleID` follows
`organization % name % revision` syntax. You can also use `%%` to track
dependencies that have binary dependency on Scala. Scala version used is
the same that IScala was compiled against.

To resolve dependencies issue `%update`. If successful this will restart
the interpreter to allow it to use the new classpath. Note that this
will erase the state of the interpreter, so you will have to recompute
all values from scratch. Restarts don't affect interpreter's settings.

```
In [1]: import scalaj.collection.Imports._
<console>:7: error: not found: value scalaj
       import scalaj.collection.Imports._
              ^

In [2]: %libraryDependencies += "org.scalaj" %% "scalaj-collection" % "1.5"

In [3]: %update
[info] Resolving org.scalaj#scalaj-collection_2.10;1.5 ...
[info] Resolving org.scala-lang#scala-library;2.10.2 ...

In [1]: import scalaj.collection.Imports._

In [2]: List(1, 2, 3)
Out[2]: List(1, 2, 3)

In [3]: _2.asJava
Out[3]: [1, 2, 3]

In [4]: _3.isInstanceOf[List[_]]
Out[4]: false

In [5]: _3.isInstanceOf[java.util.List[_]]
Out[5]: true

In [6]: %libraryDependencies
List(org.scalaj:scalaj-collection:1.5)
```

If a dependency can't be resolved, `%update` will fail gracefully. For example,
if we use `com.scalaj` organization instead of `org.scalaj`, then we will get
the following error:

```
In [1]: %libraryDependencies += "com.scalaj" %% "scalaj-collection" % "1.5"

In [2]: %update
[info] Resolving com.scalaj#scalaj-collection_2.10;1.5 ...
[warn] 	module not found: com.scalaj#scalaj-collection_2.10;1.5
[warn] ==== sonatype-releases: tried
[warn]   https://oss.sonatype.org/content/repositories/releases/com/scalaj/scalaj-collection_2.10/1.5/scalaj-collection_2.10-1.5.pom
[warn] 	::::::::::::::::::::::::::::::::::::::::::::::
[warn] 	::          UNRESOLVED DEPENDENCIES         ::
[warn] 	::::::::::::::::::::::::::::::::::::::::::::::
[warn] 	:: com.scalaj#scalaj-collection_2.10;1.5: not found
[warn] 	::::::::::::::::::::::::::::::::::::::::::::::
[error] unresolved dependency: com.scalaj#scalaj-collection_2.10;1.5: not found
```

By default IScala uses Sonatype's releases maven repository. To add more
repositories use `%resolvers += "Repo Name" at "https://path/to/repository"`
and run `%update` again.

## Development

Obtain a copy of IScala either by cloning [this](git@github.com:mattpap/IScala.git)
repository or download it from [here](https://github.com/mattpap/IScala/archive/master.zip).
We use [SBT](http://www.scala-sbt.org/) for dependency management, compilation and deployment.
In a terminal issue:
```
$ cd IScala
$ ./sbt
```
This will start SBT console (which will be indicated by `>` prefix). On first run
SBT will download itself, its dependencies and plugins, and compile project build
file. From here you can compile the project by issuing `compile` command:
```
> compile
```
It implicitly run `update` task, so on first run it will download all project
dependencies (including Scala standard library and compiler), so it may take a
while. Note that dependencies are cached in `~/.ivy2` directory, so they will be
picked up next time SBT is run (also in other projects compiled from the same
account).

Ignore any (deprecation) warnings you will get. To start IScala issue:
```
> run
[info] Running org.refptr.iscala.IScala
[info] connect ipython with --existing profile-18271.json
[info] Welcome to Scala 2.10.2 (OpenJDK 64-Bit Server VM, Java 1.6.0_27)
```
This is an equivalent of starting a standalone IScala kernel from a terminal. To
terminate a kernel press `Ctrl+C` (SBT my signal an error). Finally to generate
a JAR file with IScala's class files, resources and dependencies, issue `assembly`.
You can run it with:
```
$ java -jar IScala.jar
```
Unless you made any changes, this is exactly the JAR you can download from IScala's
releases page.

## Status

This is an early work in progress. Main features and majority of IPython's message
specification were implemented, however certain features are not yet available
(e.g. introspection) or are limited in functionality and subject to major changes.
Report any problems and submit enhancement proposals [here](https://github.com/mattpap/IScala/issues).

## Acknowledgment

This work is substantially based on [IJulia](https://github.com/JuliaLang/IJulia.jl),
a [Julia-language](http://julialang.org/) backend for IPython.

## License

Copyright &copy; 2013-2014 by Mateusz Paprocki and contributors.

Published under The MIT License, see LICENSE.

[travis]: https://api.travis-ci.org/mattpap/IScala.png?branch=master
