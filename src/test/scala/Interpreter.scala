package org.refptr.iscala
package tests

import org.specs2.mutable.Specification

class InterpreterSpec extends Specification with InterpreterUtil {
    sequential

    "Interpreter" should {
        import Results._

        val Obj = "^([^@]+)@([0-9a-fA-F]+)$".r

        "support primitive values" in {
            interpret("1") must beLike { case NoOutput(Value(1, "Int", Plain("1"))) => ok }
            interpret("1.0") must beLike { case NoOutput(Value(1.0, "Double", Plain("1.0"))) => ok }
            interpret("true") must beLike { case NoOutput(Value(true, "Boolean", Plain("true"))) => ok }
            interpret("false") must beLike { case NoOutput(Value(false, "Boolean", Plain("false"))) => ok }
            interpret("\"XXX\"") must beLike { case NoOutput(Value("XXX", "String", Plain("XXX"))) => ok }

        }

        "support function values" in {
            interpret("() => 1") must beLike { case NoOutput(Value(_, "() => Int", Plain("<function0>"))) => ok }
            interpret("(x: Int) => x + 1") must beLike { case NoOutput(Value(_, "Int => Int", Plain("<function1>"))) => ok }
            interpret("(x: Int, y: Int) => x*y + 1") must beLike { case NoOutput(Value(_, "(Int, Int) => Int", Plain("<function2>"))) => ok }
        }

        "support container values" in {
            interpret("List(1, 2, 3)") must beLike { case NoOutput(Value(_, "List[Int]", Plain("List(1, 2, 3)"))) => ok }
            interpret("Array(1, 2, 3)") must beLike { case NoOutput(Value(_, "Array[Int]", Plain("Array(1, 2, 3)"))) => ok }
        }

        "support null values" in {
            interpret("null") must beLike { case NoOutput(Value(null, "Null", Plain("null"))) => ok }
            interpret("null: String") must beLike { case NoOutput(Value(null, "String", Plain("null"))) => ok }
        }

        "support unit value" in {
            interpret("()") must beLike { case NoOutput(NoValue) => ok }
        }

        "support imports" in {
            interpret("import scala.xml") must beLike { case NoOutput(NoValue) => ok }
        }

        "support printing" in {
            interpret("println(\"XXX\")") must beLike {
                case Output(NoValue, _, "") => ok     // TODO: "XXX\n"
            }
            interpret("print(\"XXX\")") must beLike {
                case Output(NoValue, _, "") => ok     // TODO: "XXX"
            }

            interpret("System.out.println(\"XXX\")") must beLike {
                case Output(NoValue, _, "") => ok     // TODO: "XXX\n"
            }
            interpret("System.out.print(\"XXX\")") must beLike {
                case Output(NoValue, _, "") => ok     // TODO: "XXX"
            }
        }

        "support long running code" in {
            interpret("(1 to 5).foreach { i => println(i); Thread.sleep(1000) }") must beLike {
                case Output(NoValue, _, "") => ok     // TODO: "1\n2\n3\n4\n5\n"
            }
        }

        "support arithmetics" in {
            interpret("1 + 2 + 3") must beLike { case NoOutput(Value(_, "Int", Plain("6"))) => ok }
        }

        "support defining values" in {
            interpret("val x = 1")  must beLike { case NoOutput(Value(1, "Int", Plain("1"))) => ok }
            interpret("x")          must beLike { case NoOutput(Value(1, "Int", Plain("1"))) => ok }
            interpret("100*x + 17") must beLike { case NoOutput(Value(117, "Int", Plain("117"))) => ok }
        }

        "support defining variables" in {
            interpret("var y = 1")  must beLike { case NoOutput(Value(1, "Int", Plain("1"))) => ok }
            interpret("y")          must beLike { case NoOutput(Value(1, "Int", Plain("1"))) => ok }
            interpret("100*y + 17") must beLike { case NoOutput(Value(117, "Int", Plain("117"))) => ok }
            interpret("y = 2")      must beLike { case NoOutput(Value(2, "Int", Plain("2"))) => ok }
            interpret("100*y + 17") must beLike { case NoOutput(Value(217, "Int", Plain("217"))) => ok }
        }

        "support defining lazy values" in {
            interpret("var initialized = false") must beLike { case NoOutput(Value(false, "Boolean", _)) => ok }
            interpret("lazy val z = { initialized = true; 1 }") must beLike { case NoOutput(NoValue) => ok }
            interpret("initialized") must beLike { case NoOutput(Value(false, "Boolean", _)) => ok }
            interpret("z + 1") must beLike { case NoOutput(Value(_, "Int", _)) => ok }
            interpret("initialized") must beLike { case NoOutput(Value(true, "Boolean", _)) => ok }
        }

        "support defining classes" in {
            interpret("class Foo(a: Int) { def bar(b: String) = b*a }") must beLike {
                case NoOutput(NoValue) => ok
            }
            interpret("val foo = new Foo(5)") must beLike {
                case NoOutput(Value(_, "Foo", _)) => ok
            }
            interpret("foo.bar(\"xyz\")") must beLike {
                case NoOutput(Value(_, "String", Plain("xyzxyzxyzxyzxyz"))) => ok
            }
        }

        "support exceptions" in {
            interpret("1/0") must beLike {
                case NoOutput(Exception("java.lang.ArithmeticException", "/ by zero", _, exc: ArithmeticException)) => ok
            }

            interpret("java.util.UUID.fromString(\"xyz\")") must beLike {
                case NoOutput(Exception("java.lang.IllegalArgumentException", "Invalid UUID string: xyz", _, exc: IllegalArgumentException)) => ok
            }

            interpret("throw new java.lang.IllegalArgumentException") must beLike {
                case NoOutput(Exception("java.lang.IllegalArgumentException", "", _, exc: IllegalArgumentException)) => ok
            }

            interpret("throw new java.lang.IllegalArgumentException(\"custom message\")") must beLike {
                case NoOutput(Exception("java.lang.IllegalArgumentException", "custom message", _, exc: IllegalArgumentException)) => ok
            }

            interpret("throw new java.lang.IllegalArgumentException(foo.getClass.getName)") must beLike {
                case NoOutput(Exception("java.lang.IllegalArgumentException", "Foo", _, exc: IllegalArgumentException)) => ok
            }
        }

        "support custom exceptions" in {
            interpret("class MyException(x: Int) extends Exception(s\"failed with $x\")") must beLike {
                case NoOutput(NoValue) => ok
            }

            interpret("throw new MyException(117)") must beLike {
                case NoOutput(Exception("MyException", "failed with 117", _, _)) => ok
            }
        }

        "support value patterns" in {
            interpret("""val obj = "^([^@]+)@([0-9a-fA-F]+)$".r""") must beLike {
                case NoOutput(Value(_, "scala.util.matching.Regex", Plain("^([^@]+)@([0-9a-fA-F]+)$"))) => ok
            }

            interpret("""val obj(name, hash) = "Macros$@88a4ee1"""") must beLike {
                case NoOutput(Value(_, "String", Plain("88a4ee1"))) => ok
            }

            interpret("name") must beLike {
                case NoOutput(Value(_, "String", Plain("Macros$"))) => ok
            }


            interpret("hash") must beLike {
                case NoOutput(Value(_, "String", Plain("88a4ee1"))) => ok
            }

            interpret("""val obj(name, hash) = "Macros$@88a4ee1x"""") must beLike {
                case NoOutput(Exception("scala.MatchError", "Macros$@88a4ee1x (of class java.lang.String)", _, exc: scala.MatchError)) => ok
            }
        }

        "support macros" in {
            interpret("""
                import scala.language.experimental.macros
                import scala.reflect.macros.Context

                object Macros {
                    def membersImpl[A: c.WeakTypeTag](c: Context): c.Expr[List[String]] = {
                        import c.universe._
                        val tpe = weakTypeOf[A]
                        val members = tpe.declarations.map(_.name.decoded).toList.distinct
                        val literals = members.map(member => Literal(Constant(member)))
                        c.Expr[List[String]](Apply(reify(List).tree, literals))
                    }

                    def members[A] = macro membersImpl[A]
                }
                """) must beLike {
                case NoOutput(Value(_, "Macros.type", Plain(Obj("Macros$", _)))) => ok
            }

            val plain = "List(<init>, toByte, toShort, toChar, toInt, toLong, toFloat, toDouble, unary_~, unary_+, unary_-, +, <<, >>>, >>, ==, !=, <, <=, >, >=, |, &, ^, -, *, /, %, getClass)"

            interpret("Macros.members[Int]") must beLike {
                case NoOutput(Value(_, "List[String]", Plain(plain))) => ok
            }
        }

        "support display framework" in {
            interpret("Nil") must beLike {
                case NoOutput(Value(_, "scala.collection.immutable.Nil.type", Plain("List()"))) => ok
            }
            interpret("implicit val PlainNil = org.refptr.iscala.display.PlainDisplay[Nil.type](_ => \"Nil\")") must beLike {
                case NoOutput(Value(_, "org.refptr.iscala.display.PlainDisplay[scala.collection.immutable.Nil.type]", _)) => ok
            }
            interpret("Nil") must beLike {
                case NoOutput(Value(_, "scala.collection.immutable.Nil.type", Plain("Nil"))) => ok
            }
            interpret("implicit val PlainNil = org.refptr.iscala.display.PlainDisplay[Nil.type](_ => \"NIL\")") must beLike {
                case NoOutput(Value(_, "org.refptr.iscala.display.PlainDisplay[scala.collection.immutable.Nil.type]", _)) => ok
            }
            interpret("Nil") must beLike {
                case NoOutput(Value(_, "scala.collection.immutable.Nil.type", Plain("NIL"))) => ok
            }
            interpret("implicit val PlainNil = org.refptr.iscala.display.PlainDisplay[Nothing](obj => ???)") must beLike {
                case NoOutput(Value(_, "org.refptr.iscala.display.PlainDisplay[Nothing]", _)) => ok
            }
            interpret("Nil") must beLike {
                case NoOutput(Value(_, "scala.collection.immutable.Nil.type", Plain("List()"))) => ok
            }
        }

        "support empty input" in {
            interpret("") must beLike {
                case NoOutput(NoValue) => ok
            }

            interpret("       ") must beLike {
                case NoOutput(Incomplete) => ok
            }
        }

        "support typeInfo()" in {
            intp.typeInfo("val a =") === None
            intp.typeInfo("val a = 5") === Some("Int")
            intp.typeInfo("val a = 5; val b = \"foo\"") === Some("Int <and> String")
        }

        "support completions()" in {
            intp.completions("1.") === List(
                "%", "&", "*", "+", "-", "/", ">", ">=", ">>", ">>>", "^",
                "asInstanceOf", "isInstanceOf",
                "toByte", "toChar", "toDouble", "toFloat", "toInt", "toLong", "toShort", "toString",
                "unary_+", "unary_-", "unary_~",
                "|")
            intp.completions("1.to") === List("toByte", "toChar", "toDouble", "toFloat", "toInt", "toLong", "toShort", "toString")
            intp.completions("1.toC") === List("toChar")
            intp.completions("1.toCx") === Nil
            intp.completions("List(1).") === Nil
        }
    }
}
