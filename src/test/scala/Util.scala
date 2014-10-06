package org.refptr.iscala
package tests

trait InterpreterUtil {
    object Plain {
        def unapply(data: Data): Option[String] = data match {
            case Data((display.MIME.`text/plain`, output)) => Some(output)
            case _ => None
        }
    }

    object NoOutput {
        def unapply[T](output: Output[T]): Option[T] = output match {
            case Output(value, "", "") => Some(value)
            case _ => None
        }
    }

    protected val intp = new Interpreter("", Nil, true)

    def interpret(code: String): Output[Results.Result] = {
        intp.interpretWithOutput(code)
    }
}
