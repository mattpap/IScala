package org.refptr.iscala

import java.io.File
import java.io.StringWriter
import java.io.PrintWriter
import scala.collection.mutable

import scala.tools.nsc.{ Settings => ISettings }
import scala.tools.nsc.interpreter.{ IMain, IR }
import scala.tools.nsc.util.Exceptional.unwrap

class IScalaInterpreter(val settings:ISettings, backendInit:(ISettings, PrintWriter) => IMainBackend) extends Interpreter with Compatibility {
    
    val initialClassPath = settings.classpath.value

    val output = new java.io.StringWriter
    val printer = new java.io.PrintWriter(output)

    // TODO the initialization of the backend is delayed because the we cannot add dependencies as 
    // soon as the IMain instance is created (the problem is caused by the backing compiler 
    // instance). The SI-6502 ticket describes this problem for both 2.10 and 2.11. A fix should 
    // be ready for 2.10.5/2.11.5.
    //
    // By making the Backend a var and recreating it when we add dependencies we could work arround
    // the issue. This would also require us to fixate the interpreter in a number of places 
    // because of the imports we are doing.
    lazy val intp = {
        val intp = backendInit(settings, printer)
        intp.initialize()
        intp
    }
    lazy val runner = new Runner(intp.classLoader)

    private var _session = new Session
    private var _n: Int = 0

    def session = _session
    def n = _n

    val In = mutable.Map.empty[Int, String]
    val Out = mutable.Map.empty[Int, Any]

    def reset() {
        finish()
        _session = new Session
        _n = 0
        In.clear()
        Out.clear()
        intp.reset()
    }

    def finish() {
        _session.endSession(_n)
    }

    def isInitialized = intp.isInitializeComplete

    def resetOutput() { // TODO: this shouldn't be maintained externally
        output.getBuffer.setLength(0)
    }

    def nextInput(): Int = { _n += 1; _n }

    def storeInput(input: String) {
        In(n) = input
        session.addHistory(n, input)
    }

    def storeOutput(result: Results.Value, output: String) {
        Out(n) = result.value
        session.addOutputHistory(n, output)
        bind("_" + n, result.tpe, result.value)
    }

    // TODO - A magic thing - not relevant for SPARK
    def classpath_=(cp: ClassPath): Unit = {
        settings.classpath.value = ClassPath.join(initialClassPath, cp.classpath)
    }
      
    def classpath: ClassPath = ClassPath(settings.classpath.value.split(File.pathSeparator).map(new File(_)))

    def completions(input: String): List[String] = intp.collectCompletions(input)

    def withRunner(block: => Results.Result): Results.Result = {
        try {
            runner.execute { block } result()
        } finally {
            runner.clear()
        }
    }

    def withOutput[T](block: => T): (T, String) = {
        resetOutput()
        try {
            (block, output.toString)
        } finally {
            resetOutput()
        }
    }

    def withException[T](req: intp.RequestWrapper)(block: => T): Either[T, Results.Result] = {
        try {
            Left(block)
        } catch {
            case original: Throwable =>
                val exception = unwrap(original)
                req.lineRep.bindError(original)

                val name = unmangle(exception.getClass.getName)
                val msg = Option(exception.getMessage).map(unmangle _) getOrElse ""
                val stacktrace = exception
                     .getStackTrace()
                     .takeWhile(_.getFileName != "<console>")
                     .map(stringify _)
                     .toList

                Right(Results.Exception(name, msg, stacktrace, exception))
        }
    }

    def runCode(moduleName: String, fieldName: String): Any = {
        import scala.reflect.runtime.{universe=>u}
        val mirror = u.runtimeMirror(intp.classLoader)
        val module = mirror.staticModule(moduleName)
        val instance = mirror.reflectModule(module).instance
        val im = mirror.reflect(instance)
        val fieldTerm = u.TermName(fieldName)
        val field = im.symbol.typeSignature.member(fieldTerm).asTerm
        im.reflectField(field).get
    }

    def display(req: intp.RequestWrapper): Either[Data, Results.Result] = {

        val displayName = "$display"
        val displayPath = req.lineRep.pathTo(displayName)

        import intp.global.NoSymbol

        val NS = "org.refptr.iscala"

        val displayResult = req.value match {
            case NoSymbol => s"$NS.Data()"
            case symbol   => s"$NS.display.Repr.stringify(${intp.originalPath(symbol)})"
        }

        val handlerCode = "" // TODO why is this empty? val generate = (handler: MemberHandler) => ""

        val code = s"""
                    |object $displayName {
                    |  ${req.importsPreamble}
                    |  val $displayName: $NS.Data = ${intp.executionWrapper} {
                    |    $displayResult
                    |    $handlerCode
                    |  }
                    |  ${req.importsTrailer}
                    |  val $displayName = this${req.accessPath}.$displayName
                    |}
                    """.stripMargin

        if (!req.lineRep.compile(code)) Right(Results.Error)
        else withException(req) { runCode(displayPath, displayName) }.left.map {
            case Data(items @ _*) => Data(items map { case (mime, string) => (mime, unmangle(string)) }: _*)
        }
    }

    def loadAndRunReq(req: intp.RequestWrapper): Results.Result = {
        import intp.naming.sessionNames

        val handler = req.handlers.last
        val hasValue = handler.definesValue()

        val evalName = if (hasValue) sessionNames.result else sessionNames.print
        val evalResult = withException(req) { req.lineRep.call(evalName) }

        intp.recordRequest(req)

        evalResult match {
            case Left(value) =>
                lazy val valueType = req.typeOf(handler)

                if (hasValue && valueType != "Unit") {
                    display(req) match {
                        case Left(repr)    => Results.Value(value, valueType, repr)
                        case Right(result) => result
                    }
                } else
                    Results.NoValue
            case Right(result) => result
        }
    }

    def interpret(line: String, synthetic: Boolean = false): Results.Result = {
        import intp.RequestWrapper
        println(line)
        intp.requestFromLine(line, synthetic) match {
            case Left(IR.Incomplete) => Results.Incomplete
            case Left(_)             => Results.Error      // parse error
            case Right(req)          =>
                // null indicates a disallowed statement type; otherwise compile
                // and fail if false (implying e.g. a type error)
                if (req == null || !req.compile) Results.Error
                else withRunner { loadAndRunReq(req) }
        }
    }

    def bind(name: String, boundType: String, value: Any, modifiers: List[String] = Nil, quiet:Boolean = false): IR.Result = {
        val imports = (intp.definedTypes ++ intp.definedTerms) match {
            case Nil   => "/* imports */"
            case names => names.map(intp.originalPath(_)).map("import " + _).mkString("\n  ")
        }

        val bindRep = intp.readEvalPrint()
        val source = s"""
            |object ${bindRep.evalName} {
            |  $imports
            |  var value: ${boundType} = _
            |  def set(x: Any) = value = x.asInstanceOf[${boundType}]
            |}
            """.stripMargin

        def bind0() = {
            bindRep.compile(source)
            bindRep.callEither("set", value) match {
                case Right(_) =>
                    val line = "%sval %s = %s.value".format(modifiers map (_ + " ") mkString, name, bindRep.evalPath)
                    intp.interpret(line)
                case Left(_) =>
                    IR.Error
            }
        }

        if (quiet) intp.beSilentDuring(bind0) 
        else bind0
    }

    def cancel() = runner.cancel()

    def stringify(obj: Any): String = unmangle(obj.toString)

    def unmangle(string: String): String = intp.naming.unmangle(string)

    def typeInfo(code: String, deconstruct: Boolean = false): Option[String] = {
        typeInfo(intp.symbolOfLine(code), deconstruct)
    }

    def typeInfo(symbol: intp.global.Symbol, deconstruct: Boolean): Option[String] = {
        import intp.global.{Symbol,Type,NullaryMethodType,OverloadedType}

        def removeNullaryMethod(symbol: Symbol): Type = {
            symbol.typeSignature match {
                case tpe @ OverloadedType(_, alt) =>
                    val alternatives = alt.map(removeNullaryMethod _).map(_.typeSymbol)
                    tpe.copy(alternatives=alternatives)
                case NullaryMethodType(resultType) if symbol.isAccessor => resultType
                case tpe                                                => tpe
            }
        }

        if (symbol.exists) {
            Some(intp.global.exitingTyper {
                val tpe = removeNullaryMethod(symbol)
                stringify(if (deconstruct) intp.showDeconstructed(tpe) else tpe)
            })
        } else None
    }
}
