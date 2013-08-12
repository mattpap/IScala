package org.refptr.iscala.json

import play.api.libs.json.{Json,Reads,Writes,Format}
import play.api.libs.json.{JsResult,JsSuccess,JsError,JsValue,JsString}

object PlayJsonUtil {
    def toJSON[T:Writes](obj: T): String =
        Json.stringify(Json.toJson(obj))

    def fromJSON[T:Reads](json: String): T =
        Json.parse(json).as[T]

    implicit class JsonString(json: String) {
        def as[T:Reads] = fromJSON[T](json)
    }
}

object PlayJson {
    // overrides Json.reads
    def reads[A] = macro JsMacroImpl.readsImpl[A]
    // overrides Json.writes
    def writes[A] = macro JsMacroImpl.writesImpl[A]
    // overrides Json.format
    def format[A] = macro JsMacroImpl.formatImpl[A]
}

object EnumJson {
    def reads[E <: Enumeration](enum: E): Reads[E#Value] = new Reads[E#Value] {
        def reads(json: JsValue): JsResult[E#Value] = json match {
            case JsString(string) =>
                try {
                    JsSuccess(enum.withName(string))
                } catch {
                    case _: NoSuchElementException =>
                        JsError(s"Enumeration expected of type: ${enum.getClass}, but it does not appear to contain the value: $string")
                }
            case _ =>
                JsError("Value of type String expected")
        }
    }

    def writes[E <: Enumeration]: Writes[E#Value] = new Writes[E#Value] {
        def writes(value: E#Value): JsValue = JsString(value.toString)
    }

    def format[E <: Enumeration](enum: E): Format[E#Value] = {
        Format(reads(enum), writes)
    }
}

object TestJson {
    type MyType = Int

    object FooBarBaz extends Enumeration {
        type FooBarBaz = Value
        val Foo = Value
        val Bar = Value
        val Baz = Value
    }

    case class Embedded(string: String)

    case class OptionCaseClass(
        optionBoolean: Option[Boolean],
        optionString: Option[String],
        optionInt: Option[Int],
        optionDouble: Option[Double],
        optionEnum: Option[FooBarBaz.Value],
        optionEmbedded: Option[Embedded],
        optionMyType: Option[MyType],
        optionOptionString: Option[Option[String]],
        optionListString: Option[List[String]],
        optionMapString: Option[Map[String, String]])

    case class ListCaseClass(
        listBoolean: List[Boolean],
        listString: List[String],
        listInt: List[Int],
        listDouble: List[Double],
        listEnum: List[FooBarBaz.Value],
        listEmbedded: List[Embedded],
        listMyType: List[MyType],
        listOptionString: List[Option[String]],
        listListString: List[List[String]],
        listMapString: Map[String, String])

    case class MapCaseClass(
        mapBoolean: Map[String, Boolean],
        mapString: Map[String, String],
        mapInt: Map[String, Int],
        mapDouble: Map[String, Double],
        mapEnum: Map[String, FooBarBaz.Value],
        mapEmbedded: Map[String, Embedded],
        mapMyType: Map[String, MyType],
        mapOptionString: Map[String, Option[String]],
        mapListString: Map[String, List[String]],
        mapMapString: Map[String, Map[String, String]])

    case class CaseClass(
        boolean: Boolean,
        string: String,
        int: Int,
        double: Double,
        enum: FooBarBaz.Value,
        embedded: Embedded,
        myType: MyType,
        option: OptionCaseClass,
        list: ListCaseClass,
        map: MapCaseClass)

    implicit val FooBarBazJSON = EnumJson.format(FooBarBaz)
    implicit val EmbeddedJSON = PlayJson.format[Embedded]
    implicit val OptionCaseClassJSON = PlayJson.format[OptionCaseClass]
    implicit val ListCaseClassJSON = PlayJson.format[ListCaseClass]
    implicit val MapCaseClassJSON = PlayJson.format[MapCaseClass]
    implicit val CaseClassJSON = PlayJson.format[CaseClass]
}
