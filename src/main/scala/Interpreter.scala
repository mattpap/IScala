package org.refptr.iscala

import java.io.File
import java.util.concurrent.locks.ReentrantLock

import scala.collection.mutable

import scala.tools.nsc.interpreter.{IMain,CommandLine,IR}
import scala.tools.nsc.util.Exceptional.unwrap
import scala.tools.nsc.util.ClassPath

import Util.{newThread,timer}

object Results {
    sealed trait Result
    sealed trait Success extends Result
    sealed trait Failure extends Result

    final case class Value(value: AnyRef, tpe: String) extends Success
    final case object NoValue extends Success

    final case class Exception(exception: Throwable) extends Failure
    final case object Error extends Failure
    final case object Incomplete extends Failure
    final case object Cancelled extends Failure
}

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

    def classpath(paths: Seq[File]) {
        val cp = ClassPath.join(_classpath, Util.classpath(paths))
        settings.classpath.value = cp
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

        import intp.memberHandlers.{MemberHandler,ValHandler,DefHandler}

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
            import intp.naming.sessionNames.{result,print}

            val hasValue = definesValue(req.handlers.last)
            val name = if (hasValue) result else print

            val outcome = try {
                runner.execute {
                    try {
                        val value = req.lineRep.call(name)
                        intp.recordRequest(req)

                        if (hasValue && value != null) {
                            val tpe = intp.typeOfTerm(intp.mostRecentVar)
                            Results.Value(value, intp.global.exitingTyper { tpe.toString })
                        } else
                            Results.NoValue
                    } catch {
                        case exception: Throwable =>
                            req.lineRep.bindError(exception)
                            Results.Exception(unwrap(exception))
                    }
                } result()
            } finally {
                runner.clear()
            }

            outcome
        }

        if (intp.global == null) Results.Error
        else requestFromLine(line, synthetic) match {
            case Left(IR.Incomplete) => Results.Incomplete
            case Left(_) => Results.Error
            case Right(req)   =>
                // null indicates a disallowed statement type; otherwise compile
                // and fail if false (implying e.g. a type error)
                if (req == null || !req.compile) Results.Error
                else loadAndRunReq(req)
        }
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

    def stringify(obj: Any): String = intp.naming.unmangle(obj.toString)

    def typeInfo(code: String, deconstruct: Boolean): Option[String] = {
        import intp.global.NullaryMethodType

        val symbol = intp.symbolOfLine(code)
        if (symbol.exists) {
            Some(intp.global.exitingTyper {
                val info = symbol.info match {
                    case NullaryMethodType(restpe) if symbol.isAccessor => restpe
                    case info                                           => info
                }
                stringify(if (deconstruct) intp.deconstruct.show(info) else info)
            })
        } else None
    }
}

class Runner(classLoader: ClassLoader) {
    class Execution(body: => Results.Result) {
        private var _result: Option[Results.Result] = None

        private val lock     = new ReentrantLock()
        private val finished = lock.newCondition()

        private def withLock[T](body: => T) = {
            lock.lock()
            try body
            finally lock.unlock()
        }

        private def setResult(result: Results.Result) = withLock {
            _result = Some(result)
            finished.signal()
        }

        private val _thread = newThread {
            _.setContextClassLoader(classLoader)
        } {
            setResult(body)
        }

        private[Runner] def cancel() = if (running) setResult(Results.Cancelled)

        private[Runner] def interrupt() = _thread.interrupt()
        private[Runner] def stop()      = Threading.stop(_thread)

        def alive   = _thread.isAlive
        def running = !_result.isDefined

        def await()  = withLock { while (running) finished.await() }
        def result() = { await(); _result.getOrElse(sys.exit) }

        override def toString = s"Execution(thread=${_thread})"
    }

    private var _current: Option[Execution] = None
    def current = _current

    def execute(body: => Results.Result): Execution = {
        val execution = new Execution(body)
        _current = Some(execution)
        execution
    }

    def clear() {
        _current.foreach(_.cancel())
        _current = None
    }

    def cancel() {
        current.foreach { execution =>
            execution.interrupt()
            execution.cancel()
            timer(5) {
                if (execution.alive) {
                    logger.debug(s"Forcefully stopping ${execution}")
                    execution.stop()
                }
            }
        }
    }
}
