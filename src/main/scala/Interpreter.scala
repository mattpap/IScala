package org.refptr.iscala

import java.io.StringWriter
import scala.tools.nsc.interpreter.IR

trait InterpreterFactory extends Function1[Options#Config, Interpreter]

trait Interpreter {
  def reset(): Unit
  def finish(): Unit
  def cancel(): Unit
  def isInitialized(): Boolean

  def session():Session
  
  def nextInput(): Int
  def storeInput(input: String): Unit
  def n(): Int
  

  def resetOutput(): Unit
  def storeOutput(result: Results.Value, output: String): Unit
  def output(): StringWriter

  def interpret(line: String, synthetic: Boolean = false): Results.Result
  def completions(input: String): List[String]
  def typeInfo(code: String, deconstruct: Boolean = false): Option[String]
  def bind(name: String, boundType: String, value: Any, modifiers: List[String] = Nil, quiet: Boolean = false): IR.Result
  def classpath_=(cp: ClassPath): Unit
  def classpath: ClassPath
}