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

    // XXX: if (fork) ("", true) else (sys.props("java.class.path"), false)
    protected val intp = IScalaInterpreter(sys.props("java.class.path"))

    def interpret(code: String): Output[Results.Result] = {
        Capture.captureOutput {
            code match {
                case Magic(name, input, Some(magic)) =>
                    magic(intp, input)
                case Magic(name, _, None) =>
                    Results.Error // s"ERROR: Line magic function `%$name` not found."
                case "" =>
                    Results.NoValue
                case _ => 
                    intp.interpret(code)
            }
        }
    }
}
