package org.refptr.iscala
package tests

import org.specs2.mutable.Specification

class InterpreterSpec extends Specification {
    sequential

    val intp = new Interpreter(Sbt.resolveCompiler(), Nil)

    "IScala's interpreter" should {
        import intp.{interpretWithOutput=>interpret}
        import Results._

        "support primitive values" in {
            interpret("1") === Output(Value(1: java.lang.Integer, "Int"))
            interpret("1.0") === Output(Value(1.0: java.lang.Double, "Double"))
            interpret("\"XXX\"") === Output(Value("XXX": java.lang.String, "String"))
        }

        "support function values" in {
            interpret("() => 1") must beLike { case Output(Value(_, "() => Int"), "", "") => ok }
            interpret("(x: Int) => x + 1") must beLike { case Output(Value(_, "Int => Int"), "", "") => ok }
            interpret("(x: Int, y: Int) => x*y + 1") must beLike { case Output(Value(_, "(Int, Int) => Int"), "", "") => ok }
        }

        "support printing" in {
            interpret("println(\"XXX\")") === Output(NoValue, "XXX\n", "")
            interpret("print(\"XXX\")") === Output(NoValue, "XXX", "")
        }
    }
}
