package org.refptr.iscala

import scala.collection.mutable

import scala.tools.nsc.interpreter.{IMain,CommandLine,IR}
import scala.tools.nsc.util.Exceptional.unwrap

object Results {
    sealed trait Result
    final case class Success(value: Option[AnyRef]) extends Result
    final case class Failure(exception: Throwable) extends Result
    final case object Error extends Result
    final case object Incomplete extends Result
}

class Interpreter(args: Seq[String], usejavacp: Boolean=true) {
    val commandLine = new CommandLine(args.toList, println)
    commandLine.settings.embeddedDefaults[this.type]
    commandLine.settings.usejavacp.value = usejavacp

    val output = new java.io.StringWriter
    val printer = new java.io.PrintWriter(output)

    private var _intp: IMain = _
    private var _n: Int = _

    var In: mutable.Map[Int, String] = _
    var Out: mutable.Map[Int, Any] = _

    def intp = _intp
    def n = _n

    reset()

    def settings = commandLine.settings

    def increment = _n += 1

    def reset() {
        synchronized {
            _intp = new IMain(settings, printer)
            _n = 0
            In = mutable.Map()
            Out = mutable.Map()
        }
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

        def loadAndRunReq(req: Request): Results.Result = {
            intp0.classLoader.setAsContext()

            import intp0.naming.sessionNames.{result,print}
            val definesValue = req.handlers.last.definesValue
            val name = if (definesValue) result else print

            try {
                val value = req.lineRep.call(name)
                intp0.recordRequest(req)
                Results.Success(if (definesValue && value != null) Some(value) else None)
            } catch {
                case exception: Throwable =>
                    req.lineRep.bindError(exception)
                    Results.Failure(unwrap(exception))
            }
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

    def stringify(obj: Any): String = intp.naming.unmangle(obj.toString)

    def typeInfo(code: String, deconstruct: Boolean): Option[String] = {
        val intp0 = intp
        import intp0.global._

        val symbol = intp0.symbolOfLine(code)
        if (symbol.exists) {
            Some(afterTyper {
                val info = symbol.info match {
                    case NullaryMethodType(restpe) if symbol.isAccessor => restpe
                    case info                                           => info
                }
                stringify(if (deconstruct) intp0.deconstruct.show(info) else info)
            })
        } else None
    }
}
