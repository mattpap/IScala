# IScala

**IScala** is a [Scala-language](http://scala-lang.org) backend for [IPython](http://ipython.org).

## Requirements

* [IPython](http://ipython.org/ipython-doc/stable/install/install.html) 1.0+
* [Java](http://wwww.java.com) JRE 1.6+

## Usage

First obtain a copy of IScala either by cloning this repository or download it from
[here](https://github.com/mattpap/IScala/archive/master.zip). We use [SBT](http://www.scala-sbt.org/)
for dependency management, compilation and deployment. In a terminal issue:
```
$ cd IScala
$ ./sbt
```
This will start SBT console (which will be indicated by `>` prefix). On first run
SBT will download itself and build dependencies (plugins), and compile build file.
From here you can compile the project by issuing `compile` command:
```
> compile
```
On first run it will download all project dependencies (including Scala standard
library and compiler), so it may take a while. Note that dependencies are cached
in `~/.ivy2` directory, so they will be picked up next time SBT is run.

Ignore any (deprecation) warnings you will get. To start IScala issue:
```
> run
[info] Running org.refptr.iscala.IScala
[info] connect ipython with --existing profile-18271.json
[info] Starting kernel event loops.
```
To terminate a kernel press `Ctrl+C`. Finally to generate a JAR file with IScala's
class files, data files and all dependencies, issue `assembly`. You can run it with:
```
$ java -jar target/scala-2.10/IScala.jar
```
You can pass command line argument to IScala after `run` command or after JAR file. e.g.:
```
$ java -jar target/scala-2.10/IScala.jar --profile my_profile.json
```
It is also possible to customize Scala compiler by passing option directly to the
compiler after `--` delimiter:
```
$ java -jar target/scala-2.10/IScala.jar --profile my_profile.json -- -Xprint:typer
```
This will print Scala syntax trees after _typer_ compiler phase.

## Example

```
In [1]: 1
Out[1]: 1

In [2]: (1 to 10).foreach { i => println(i); Thread.sleep(1000) }
1
2
3
4
5
6
7
8
9
10

In [3]: 1/0
java.lang.ArithmeticException: / by zero
	at .<init>(<console>:8)
	at .<clinit>(<console>)
	at .<init>(<console>:7)
	at .<clinit>(<console>)
	at $print(<console>)
	at sun.reflect.NativeMethodAccessorImpl.invoke0(Native Method)
	at sun.reflect.NativeMethodAccessorImpl.invoke(NativeMethodAccessorImpl.java:57)
	at sun.reflect.DelegatingMethodAccessorImpl.invoke(DelegatingMethodAccessorImpl.java:43)
	at java.lang.reflect.Method.invoke(Method.java:616)
	at scala.tools.nsc.interpreter.IMain$ReadEvalPrint.call(IMain.scala:734)
	at scala.tools.nsc.interpreter.IMain$Request.loadAndRun(IMain.scala:983)
	at scala.tools.nsc.interpreter.IMain.loadAndRunReq$1(IMain.scala:573)
	at scala.tools.nsc.interpreter.IMain.interpret(IMain.scala:604)
	at scala.tools.nsc.interpreter.IMain.interpret(IMain.scala:568)
	at org.refptr.iscala.IScala$$anonfun$8.apply(IScala.scala:353)
	at org.refptr.iscala.IScala$$anonfun$8.apply(IScala.scala:353)
	at scala.util.DynamicVariable.withValue(DynamicVariable.scala:57)
	at scala.Console$.withErr(Console.scala:148)
	at org.refptr.iscala.IScala$$anonfun$capture$1.apply(IScala.scala:303)
	at scala.util.DynamicVariable.withValue(DynamicVariable.scala:57)
	at scala.Console$.withOut(Console.scala:107)
	at org.refptr.iscala.IScala$.capture(IScala.scala:302)
	at org.refptr.iscala.IScala$.handle_execute_request(IScala.scala:352)
	at org.refptr.iscala.IScala$EventLoop.run(IScala.scala:477)
```

## Status

This is a very early work in progress. It works but many features are not implemented
(e.g. introspection, interrupting kernel) or are very limited/buggy (e.g. completion).
All this will be improved in (hopefully) near future.

## Acknowledgment

This work is substantially based on [IJulia](https://github.com/JuliaLang/IJulia.jl),
a [Julia-language](http://julialang.org/) backend for IPython.

## License

Copyright &copy; 2013 by Mateusz Paprocki and contributors.

Published under The MIT License, see LICENSE.
