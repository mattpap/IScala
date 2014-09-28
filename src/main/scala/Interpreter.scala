package org.refptr.iscala

import java.io.File
import scala.collection.mutable

import scala.tools.nsc.interpreter.{IMain,CommandLine,IR}
import scala.tools.nsc.util.Exceptional.unwrap

class Interpreter(classpath: String, args: Seq[String]) extends InterpreterCompatibility {
    protected val commandLine = new CommandLine(args.toList, println)

    private val _classpath: String = {
        val cp = commandLine.settings.classpath
        cp.value = ClassPath.join(cp.value, classpath)
        logger.debug(s"classpath: ${cp.value}")
        cp.value
    }

    val output = new java.io.StringWriter
    val printer = new java.io.PrintWriter(output)

    val intp: IMain = new IMain(settings, printer)

    private var _runner: Runner = _
    private var _session: Session = _

    private var _n: Int = _

    private var In: mutable.Map[Int, String] = _
    private var Out: mutable.Map[Int, Any] = _

    initVars()

    def runner = _runner
    def session = _session

    def n = _n

    private def initVars() {
        _runner = new Runner(intp.classLoader)
        _session = new Session
        _n = 0
        In = mutable.Map()
        Out = mutable.Map()
    }

    def reset() {
        finish()
        intp.reset()
        initVars()
    }

    def finish() {
        if (_session != null)
            _session.endSession(_n)
    }

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

    def settings = commandLine.settings

    def classpath(cp: ClassPath) {
        settings.classpath.value = ClassPath.join(_classpath, cp.classpath)
    }

    def completion = new IScalaCompletion(intp)

    def interpret(line: String): Results.Result = interpret(line, false)

    def interpret(line: String, synthetic: Boolean): Results.Result = {
        import intp.Request

        def requestFromLine(line: String, synthetic: Boolean): Either[IR.Result, Request] = {
            // Dirty hack to call a private method IMain.requestFromLine
            val method = classOf[IMain].getDeclaredMethod("requestFromLine", classOf[String], classOf[Boolean])
            val args = Array(line, synthetic).map(_.asInstanceOf[AnyRef])
            method.setAccessible(true)
            method.invoke(intp, args: _*).asInstanceOf[Either[IR.Result, Request]]
        }

        import intp.memberHandlers.{MemberHandler,MemberDefHandler,ValHandler,DefHandler}

        def definesValue(handler: MemberHandler): Boolean = {
            // MemberHandler.definesValue has slightly different meaning from what is
            // needed in loadAndRunReq. We don't want to eagerly evaluate lazy vals
            // or 0-arity defs, so we handle those cases here.
            if (!handler.definesValue) {
                false
            } else {
                handler match {
                    case handler: ValHandler if handler.mods.isLazy => false
                    case handler: DefHandler                        => false
                    case _ => true
                }
            }
        }

        def loadAndRunReq(req: Request): Results.Result = {
            try {
                val handler = req.handlers.last
                val hasValue = definesValue(handler)

                val value = req.lineRep.call {
                    import intp.naming.{sessionNames=>names}
                    if (hasValue) names.result else names.print
                }

                intp.recordRequest(req)

                if (hasValue && value != null) {
                    val name = handler match {
                        case handler: MemberDefHandler => handler.name
                        case _                         => intp.global.nme.NO_NAME
                    }

                    val tpe = req.lookupTypeOf(name)
                    val repr = Data(MIME.`text/plain` -> stringify(value))

                    Results.Value(value, tpe, repr)
                } else
                    Results.NoValue
            } catch {
                case exception: Throwable =>
                    req.lineRep.bindError(exception)
                    Results.Exception(unwrap(exception))
            }
        }

        def withRunner(block: => Results.Result): Results.Result = {
            try {
                runner.execute { block } result()
            } finally {
                runner.clear()
            }
        }

        requestFromLine(line, synthetic) match {
            case Left(IR.Incomplete) => Results.Incomplete
            case Left(_)             => Results.Error      // parse error
            case Right(req)          =>
                // null indicates a disallowed statement type; otherwise compile
                // and fail if false (implying e.g. a type error)
                if (req == null || !req.compile) Results.Error
                else withRunner { loadAndRunReq(req) }
        }
    }

    def interpretWithOutput(line: String): Output[Results.Result] = {
        Capture.captureOutput { interpret(line) }
    }

    def bind(name: String, boundType: String, value: Any, modifiers: List[String] = Nil): IR.Result = {
        val imports = (intp.definedTypes ++ intp.definedTerms) match {
            case Nil   => "/* imports */"
            case names => names.map(intp.originalPath(_)).map("import " + _).mkString("\n  ")
        }

        val bindRep = new intp.ReadEvalPrint()
        val source = s"""
            |object ${bindRep.evalName} {
            |  $imports
            |  var value: ${boundType} = _
            |  def set(x: Any) = value = x.asInstanceOf[${boundType}]
            |}
            """.stripMargin

        bindRep.compile(source)
        bindRep.callEither("set", value) match {
            case Right(_) =>
                val line = "%sval %s = %s.value".format(modifiers map (_ + " ") mkString, name, bindRep.evalPath)
                intp.interpret(line)
            case Left(_) =>
                IR.Error
        }
    }

    def cancel() = runner.cancel()

    def stringify(obj: Any): String = unmangle(obj.toString)

    def unmangle(string: String): String = intp.naming.unmangle(string)

    def typeInfo(code: String, deconstruct: Boolean): Option[String] = {
        typeInfo(intp.symbolOfLine(code), deconstruct)
    }

    def typeInfo(symbol: intp.global.Symbol, deconstruct: Boolean): Option[String] = {
        if (symbol.exists) {
            Some(intp.global.exitingTyper {
                val info = symbol.info match {
                    case intp.global.NullaryMethodType(restpe) if symbol.isAccessor => restpe
                    case info                                                       => info
                }
                stringify(if (deconstruct) intp.deconstruct.show(info) else info)
            })
        } else None
    }
}
