package org.refptr.iscala

import java.util.concurrent.locks.ReentrantLock

import scala.collection.mutable

import scala.tools.nsc.interpreter.{IMain,CommandLine,IR}
import scala.tools.nsc.util.Exceptional.unwrap

import Util.{log,debug,newThread,timer}
import Compatibility._

object Results {
    final case class Value(value: AnyRef, tpe: String)

    sealed trait Result
    final case class Success(value: Option[Value]) extends Result
    final case class Failure(exception: Throwable) extends Result
    final case object Error extends Result
    final case object Incomplete extends Result
    final case object Cancelled extends Result
}

class Interpreter(args: Seq[String], usejavacp: Boolean=true) {
    val commandLine = new CommandLine(args.toList, println)
    commandLine.settings.embeddedDefaults[this.type]
    commandLine.settings.usejavacp.value = usejavacp

    val output = new java.io.StringWriter
    val printer = new java.io.PrintWriter(output)

    private var _intp: IMain = _
    private var _runner: Runner = _
    private var _session: Session = _
    private var _n: Int = _

    var In: mutable.Map[Int, String] = _
    var Out: mutable.Map[Int, Any] = _

    def intp = _intp
    def runner = _runner
    def session = _session
    def n = _n

    reset()

    def settings = commandLine.settings

    def ++ = _n += 1

    def reset() {
        synchronized {
            finish()
            _intp = new IMain(settings, printer)
            _runner = new Runner(_intp.classLoader)
            _session = new Session
            _n = 0
            In = mutable.Map()
            Out = mutable.Map()
        }
    }

    def finish() {
        if (_session != null)
            _session.endSession(_n)
    }

    def resetOutput() {
        output.getBuffer.setLength(0)
    }

    def completion = new IScalaCompletion(intp)

    def interpret(line: String): Results.Result = interpret(line, false)

    def interpret(line: String, synthetic: Boolean): Results.Result = {
        // IMain#Request possibly != instance.Request
        // intp0 is needed as a stable identifier
        val intp0 = intp
        import intp0.Request

        def requestFromLine(line: String, synthetic: Boolean): Either[IR.Result, Request] = {
            // Dirty hack to call a private method IMain.requestFromLine
            val method = classOf[IMain].getDeclaredMethod("requestFromLine", classOf[String], classOf[Boolean])
            val args = Array(line, synthetic).map(_.asInstanceOf[AnyRef])
            method.setAccessible(true)
            method.invoke(intp0, args: _*).asInstanceOf[Either[IR.Result, Request]]
        }

        import intp0.memberHandlers.{MemberHandler,ValHandler,DefHandler}

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
            import intp0.naming.sessionNames.{result,print}

            val hasValue = definesValue(req.handlers.last)
            val name = if (hasValue) result else print

            val outcome = try {
                runner.execute {
                    try {
                        val value = req.lineRep.call(name)
                        intp0.recordRequest(req)
                        val outcome =
                            if (hasValue && value != null) {
                                val tpe = intp0.typeOfTerm(intp0.mostRecentVar)
                                Some(Results.Value(value, intp0.global.exitingTyper { tpe.toString }))
                            } else
                                None
                        Results.Success(outcome)
                    } catch {
                        case exception: Throwable =>
                            req.lineRep.bindError(exception)
                            Results.Failure(unwrap(exception))
                    }
                } result()
            } finally {
                runner.clear()
            }

            outcome
        }

        if (intp0.global == null) Results.Error
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
        val intp0 = intp

        val imports = (intp0.definedTypes ++ intp0.definedTerms) match {
            case Nil => "/* imports */"
            case names => names.map(_.decode).map("import " + _).mkString("\n  ")
        }

        val bindRep = new intp0.ReadEvalPrint()
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
                intp0.interpret(line)
            case Left(_) =>
                IR.Error
        }
    }

    def cancel() = runner.cancel()

    def stringify(obj: Any): String = intp.naming.unmangle(obj.toString)

    def typeInfo(code: String, deconstruct: Boolean): Option[String] = {
        val intp0 = intp
        import intp0.global.{NullaryMethodType}

        val symbol = intp0.symbolOfLine(code)
        if (symbol.exists) {
            Some(intp0.global.exitingTyper {
                val info = symbol.info match {
                    case NullaryMethodType(restpe) if symbol.isAccessor => restpe
                    case info                                           => info
                }
                stringify(if (deconstruct) intp0.deconstruct.show(info) else info)
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
                    debug(s"Forcefully stopping ${execution}")
                    execution.stop()
                }
            }
        }
    }
}
