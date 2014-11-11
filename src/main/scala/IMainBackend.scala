package org.refptr.iscala

import scala.tools.nsc.Global
import scala.tools.nsc.{ Settings => ISettings}
import scala.tools.nsc.interpreter.IR
import scala.tools.nsc.interpreter.Naming
import scala.tools.nsc.interpreter.NamedParam
import scala.tools.nsc.reporters.ConsoleReporter

/**
 * Currently the Scala's and Sparks do not share a common interface. Spark's version is an adapted
 * version of the Scala version. This class creates the common interface to both versions.
 */
trait IMainBackend {
  type IMain
  type Request
  type ReadEvalPrint
  type MemberHandler

  /**
   * The backing Scala/Spark interpreter.
   */
  val imain: IMain

  /**
   * The compiler used by the interpreter.
   *
   * Global Needs to lazy: we cannot add jars to the class path as soon as we touch it.
   */
  val global: Global

  /**
   * The naming logic used by the interpreter.
   */
  val naming: Naming

  import global._

  /**
   * Get the Backing interpreter instance.
   */
  def subject: IMain

  /**
   * Reset this interpreter, forgetting all user-specified requests.
   */
  def reset(): Unit

  /**
   *  Check if the interpreters compiler is initialized.
   */
  def isInitializeComplete: Boolean

  /**
   * The ClassLoader used by the interpreter
   */
  def classLoader: ClassLoader

  /**
   * The code to be wrapped around all lines.
   */
  def executionWrapper: String

  /**
   * TODO documentation??? 2.11 method...
   */
  def originalPath(symbol: Symbol): String

  /**
   * TODO documentation??? 2.11 method...
   */
  def originalPath(name: Name): String

  /**
   * Records a request.
   */
  def recordRequest(request: RequestWrapper): Unit

  /**
   * Get all the types defined in the current Interpreter session.
   */
  def definedTypes(): List[TypeName]

  /**
   * Get all the terms defined in the current Interpreter session.
   */
  def definedTerms(): List[TermName]

  /**
   * Create a Read-Eval-Print proxy.
   */
  def readEvalPrint(): ReadEvalPrintWrapper

  /**
   *  Interpret one line of input. All feedback, including parse errors
   *  and evaluation results, are printed via the supplied compiler's
   *  reporter. Values defined are available for future interpreted strings.
   *
   *  The return value is whether the line was interpreter successfully,
   *  e.g. that there were no parse errors.
   */
  def interpret(line: String): IR.Result

  /**
   * Let the interpreter perform a operation silently (without output).
   */
  def beSilentDuring[T](operation: => T): T

  /**
   * TODO documentation???
   */
  def symbolOfLine(code: String): Symbol

  /**
   * Types which have been wildcard imported, such as:
   *    val x = "abc" ; import x._  // type java.lang.String
   *    import java.lang.String._   // object java.lang.String
   */
  def sessionWildcards: List[Type]

  /** Bind a specified name to a specified value.  The name may
   *  later be used by expressions passed to interpret.
   *
   *  @param name      the variable name to bind
   *  @param boundType the type of the variable, as a string
   *  @param value     the object value to bind to it
   *  @return          an indication of whether the binding succeeded
   */
  def bind(name: String, boundType: String, value: Any, modifiers: List[String] = Nil): IR.Result
  
  /**
   * Rebind an existing variable to a different type.
   */
  def rebind(param: NamedParam): IR.Result

  /**
   * Id's in scope of the interpreter.
   */
  def unqualifiedIds: List[String]

  /**
   * TODO documentation???
   */
  def typeOfExpression(expr: String, silent: Boolean = true): Type
  
  /**
   * Get the reporter used by the interpreter.
   */
  def reporter(): ConsoleReporter
  
  /**
   * Show a deconstructed type. This method would normally use 
   */
  def showDeconstructed(tpe: Type): String

  /**
   * Collect completions for the given input.
   */
  def collectCompletions(input: String): List[String]

  /**
   * Create a proxy for an Interpreter request.
   */
  protected def requestWrapper(request: Request): RequestWrapper

  /**
   * Construct a request from a 'line' of code. 
   * 
   * RequestFromLine is a private method within the IMain class. We are using reflection to get to
   * this method (a HACK).
   */
  def requestFromLine(line: String, synthetic: Boolean): Either[IR.Result, RequestWrapper] = {
    // XXX: Dirty hack to call a private method IMain.requestFromLine
    val method = imain.getClass().getDeclaredMethod("requestFromLine", classOf[String], classOf[Boolean])
    val args = Array(line, synthetic).map(_.asInstanceOf[AnyRef])
    method.setAccessible(true)
    method.invoke(imain, args: _*).asInstanceOf[Either[IR.Result, Request]] match {
      case Left(result: IR.Result) => Left(result)
      case Right(request: Request @unchecked) => Right(requestWrapper(request))
    }
  }
 
  /**
   * Proxy for a Request instance.
   */
  trait RequestWrapper {
    def value: Symbol
    def lineRep: ReadEvalPrintWrapper
    def importsPreamble: String
    def importsTrailer: String
    def accessPath: String
    def handlers: List[MemberHandlerWrapper]
    def compile: Boolean
    def subject: Request
    def typeOf(handler: MemberHandlerWrapper): String
  }

  /**
   * Proxy for a Read-Eval-Print instance.
   *
   * Here is where we:
   *
   *  1) Read some source code, and put it in the "read" object.
   *  2) Evaluate the read object, and put the result in the "eval" object.
   *  3) Create a String for human consumption, and put it in the "print" object.
   *
   *  Read! Eval! Print!
   */
  trait ReadEvalPrintWrapper {
    def subject: ReadEvalPrint
    def evalName: String
    def evalPath: String
    def compile(source: String): Boolean
    def callEither(name: String, args: Any*): Either[Throwable, AnyRef]
    def pathTo(name: String): String
    def bindError(t: Throwable): String
    def call(name: String, args: Any*): AnyRef
  }

  /**
   * Proxy for a MemberHandler instance.
   *
   * TODO only one method defined; move it to the toplevel?
   */
  trait MemberHandlerWrapper {
    def subject: MemberHandler
    def definesValue(): Boolean
  }
}