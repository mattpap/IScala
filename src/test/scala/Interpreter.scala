package org.refptr.iscala
package tests

import org.specs2.mutable.Specification

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

class InterpreterSpec extends Specification {
    sequential

    val intp = new Interpreter("", Nil, true)

    "IScala's interpreter" should {
        import intp.{interpretWithOutput=>interpret}
        import Results._

        "support primitive values" in {
            interpret("1") must beLike { case NoOutput(Value(_, "Int", Plain("1"))) => ok }
            interpret("1.0") must beLike { case NoOutput(Value(_, "Double", Plain("1.0"))) => ok }
            interpret("\"XXX\"") must beLike { case NoOutput(Value(_, "String", Plain("XXX"))) => ok }
        }

        "support function values" in {
            interpret("() => 1") must beLike { case NoOutput(Value(_, "() => Int", Plain("<function0>"))) => ok }
            interpret("(x: Int) => x + 1") must beLike { case NoOutput(Value(_, "Int => Int", Plain("<function1>"))) => ok }
            interpret("(x: Int, y: Int) => x*y + 1") must beLike { case NoOutput(Value(_, "(Int, Int) => Int", Plain("<function2>"))) => ok }
        }

        "support printing" in {
            interpret("println(\"XXX\")") === Output(NoValue, "XXX\n", "")
            interpret("print(\"XXX\")") === Output(NoValue, "XXX", "")
        }
    }
}
