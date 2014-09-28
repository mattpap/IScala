package org.refptr.iscala
package tests

import org.specs2.mutable.Specification

class InterpreterSpec extends Specification {
    sequential

    val classpath = Sbt.resolveCompiler().classpath
    val intp = new Interpreter(classpath, Nil)

    "IScala's interpreter" should {
        import intp.{interpretWithOutput=>interpret}
        import Results._

        "support primitive values" in {
            interpret("1") must beLike { case Output(Value(_, "Int", _), "", "") => ok }
            interpret("1.0") must beLike { case Output(Value(_, "Double", _), "", "") => ok }
            interpret("\"XXX\"") must beLike { case Output(Value(_, "String", _), "", "") => ok }
        }

        "support function values" in {
            interpret("() => 1") must beLike { case Output(Value(_, "() => Int", _), "", "") => ok }
            interpret("(x: Int) => x + 1") must beLike { case Output(Value(_, "Int => Int", _), "", "") => ok }
            interpret("(x: Int, y: Int) => x*y + 1") must beLike { case Output(Value(_, "(Int, Int) => Int", _), "", "") => ok }
        }

        "support printing" in {
            interpret("println(\"XXX\")") === Output(NoValue, "XXX\n", "")
            interpret("print(\"XXX\")") === Output(NoValue, "XXX", "")
        }
    }
}
