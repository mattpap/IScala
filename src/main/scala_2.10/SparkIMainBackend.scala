package org.refptr.iscala

// IMainWrapper generic imports.
import scala.tools.nsc.{ Settings => ISettings }
import scala.tools.nsc.interpreter.IR
import scala.tools.nsc.interpreter.NamedParam
import scala.tools.nsc.interpreter.Parsed
import scala.tools.nsc.reporters.ConsoleReporter
import java.io.PrintWriter

// Spark Specific imports.
import org.apache.spark.repl.{ SparkIMain => iMainBackend }
import org.apache.spark.repl.{ SparkJLineCompletion => Completion }
import org.refptr.iscala.{ SparkIMainBackendCompatibility => iMainBackendCompatibility }

/**
 * Spark IMainBackend implementation.
 */
class SparkIMainBackend(settings: ISettings, printer: PrintWriter) extends IMainBackend with iMainBackendCompatibility {
  type IMain = iMainBackend
  type Global = imain.global.type
  type Request = imain.Request
  type ReadEvalPrint = imain.ReadEvalPrint
  type MemberHandler = imain.memberHandlers.MemberHandler

  val imain: iMainBackend = {
    try {
      new iMainBackend(settings, printer)  
    }
    catch {
      // Hack for the everyone using Spark 1.2...
      case e: java.lang.NoSuchMethodError => {
        import java.lang.{Boolean => JBoolean}
        val constructor = classOf[iMainBackend].getDeclaredConstructor(classOf[ISettings], classOf[PrintWriter], classOf[Boolean])
        constructor.newInstance(settings, printer, JBoolean.FALSE)
      }
    }
  }
    
  imain.initializeSynchronous()

  val global: Global = imain.global
  val naming = imain.naming
  val completion = new Completion(imain)

  import global._
  def subject: IMain = imain
  def reset(): Unit = imain.reset()
  def isInitializeComplete: Boolean = imain.isInitializeComplete
  def classLoader: ClassLoader = imain.classLoader
  def executionWrapper: String = imain.executionWrapper
  def recordRequest(request: RequestWrapper): Unit = imain.recordRequest(request.subject)
  def definedTypes(): List[TypeName] = imain.definedTypes
  def definedTerms(): List[TermName] = imain.definedTerms
  def readEvalPrint(): ReadEvalPrintWrapper = new ReadEvalPrintWrapperImpl(new imain.ReadEvalPrint())
  def interpret(line: String): IR.Result = imain.interpret(line)
  def beSilentDuring[T](block: => T): T = imain.beQuietDuring(block)
  def symbolOfLine(code: String): Symbol = imain.symbolOfLine(code)
  def sessionWildcards: List[Type] = imain.sessionWildcards
  def bind(name: String, boundType: String, value: Any, modifiers: List[String] = Nil): IR.Result = imain.bind(name, boundType, value, modifiers)
  def rebind(param: NamedParam): IR.Result = imain.rebind(param)
  def unqualifiedIds: List[String] = imain.unqualifiedIds
  def typeOfExpression(expr: String, silent: Boolean = true): Type = imain.typeOfExpression(expr, silent)
  def reporter(): ConsoleReporter = imain.reporter
  def showDeconstructed(tpe: Type): String = imain.deconstruct.show(tpe)
  def collectCompletions(input: String): List[String] = completion.topLevelFor(Parsed.dotted(input, input.length)) 
  protected def requestWrapper(request: Request) = new RequestWrapperImpl(request)

  class RequestWrapperImpl(val request: Request) extends RequestWrapper with RequestWrapperImplCompatibility {
    def subject = request
    def lineRep = new ReadEvalPrintWrapperImpl(request.lineRep)
    def importsPreamble = request.importsPreamble
    def importsTrailer = request.importsTrailer
    def accessPath = request.accessPath
    def handlers: List[MemberHandlerWrapper] = request.handlers.map(new MemberHandlerWrapperImpl(_))
    def compile = request.compile
    def typeOf(handler: MemberHandlerWrapper): String = {
      import imain.memberHandlers.{ MemberDefHandler, AssignHandler }
      val symbolName = handler.subject match {
        case handler: MemberDefHandler => handler.name
        case handler: AssignHandler => handler.name
        case _ => global.nme.NO_NAME
      }
      request.lookupTypeOf(symbolName)
    }
  }

  class ReadEvalPrintWrapperImpl(val readEvalPrint: ReadEvalPrint) extends ReadEvalPrintWrapper {
    def subject = readEvalPrint
    def evalName: String = readEvalPrint.evalName
    def evalPath: String = readEvalPrint.evalPath
    def compile(source: String): Boolean = readEvalPrint.compile(source)
    def callEither(name: String, args: Any*): Either[Throwable, AnyRef] = readEvalPrint.callEither(name, args)
    def pathTo(name: String): String = readEvalPrint.pathTo(name)
    def bindError(t: Throwable): String = readEvalPrint.bindError(t)
    def call(name: String, args: Any*): AnyRef = readEvalPrint.call(name, args: _*)
  }

  class MemberHandlerWrapperImpl(val memberHandler: MemberHandler) extends MemberHandlerWrapper {
    def subject: MemberHandler = memberHandler
    def definesValue(): Boolean = {
      // MemberHandler.definesValue has slightly different meaning from what is
      // needed in loadAndRunReq. We don't want to eagerly evaluate lazy vals
      // or 0-arity defs, so we handle those cases here.
      if (!memberHandler.definesValue) {
        false
      } else {
        import imain.memberHandlers.{ ValHandler, DefHandler }
        memberHandler match {
          case handler: ValHandler if handler.mods.isLazy => false
          case handler: DefHandler => false
          case _ => true
        }
      }
    }
  }
}