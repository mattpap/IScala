package org.refptr.iscala
package tests

import org.specs2.mutable.Specification

object Plain {
    def unapply(data: Data): Option[String] = data match {
        case Data((MIME.`text/plain`, output)) => Some(output)
        case _ => None
    }
}

class InterpreterSpec extends Specification {
    sequential

    val classpath = Sbt.resolveCompiler().classpath
    val intp = new Interpreter(classpath, Nil)

    "IScala's interpreter" should {
        import intp.{interpretWithOutput=>interpret}
        import Results._

        "support primitive values" in {
            interpret("1") must beLike { case Output(Value(_, "Int", Plain("1")), "", "") => ok }
            interpret("1.0") must beLike { case Output(Value(_, "Double", Plain("1.0")), "", "") => ok }
            interpret("\"XXX\"") must beLike { case Output(Value(_, "String", Plain("XXX")), "", "") => ok }
        }

        "support function values" in {
            interpret("() => 1") must beLike { case Output(Value(_, "() => Int", Plain("<function0>")), "", "") => ok }
            interpret("(x: Int) => x + 1") must beLike { case Output(Value(_, "Int => Int", Plain("<function1>")), "", "") => ok }
            interpret("(x: Int, y: Int) => x*y + 1") must beLike { case Output(Value(_, "(Int, Int) => Int", Plain("<function2>")), "", "") => ok }
        }

        "support printing" in {
            interpret("println(\"XXX\")") === Output(NoValue, "XXX\n", "")
            interpret("print(\"XXX\")") === Output(NoValue, "XXX", "")
        }
    }
}
