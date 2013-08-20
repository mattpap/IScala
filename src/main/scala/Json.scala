package org.refptr.iscala.json

import java.util.UUID
import scala.reflect.ClassTag

import play.api.libs.json.{Json=>PlayJson,Reads,Writes,OWrites,Format,JsPath}
import play.api.libs.json.{JsResult,JsSuccess,JsError}
import play.api.libs.json.{JsValue,JsString,JsArray,JsObject}

object JsonUtil {
    def toJSON[T:Writes](obj: T): String =
        PlayJson.stringify(PlayJson.toJson(obj))

    def fromJSON[T:Reads](json: String): T =
        PlayJson.parse(json).as[T]

    implicit class JsonString(json: String) {
        def as[T:Reads] = fromJSON[T](json)
    }
}

object Json {
    // overrides PlayJson.reads
    def reads[A] = macro JsMacroImpl.readsImpl[A]
    // overrides PlayJson.writes
    def writes[A] = macro JsMacroImpl.writesImpl[A]
    // overrides PlayJson.format
    def format[A] = macro JsMacroImpl.formatImpl[A]

    def noFields[A:ClassTag] = NoFields.format
}

object NoFields {
    def reads[T:ClassTag]: Reads[T] = new Reads[T] {
        def reads(json: JsValue) = json match {
            case JsObject(seq) if seq.isEmpty =>
                JsSuccess(implicitly[ClassTag[T]].runtimeClass.newInstance.asInstanceOf[T])
            case _ =>
                JsError("Not an empty object")
        }
    }

    def writes[T]: OWrites[T] = new OWrites[T] {
        def writes(t: T) = JsObject(Nil)
    }

    def format[T:ClassTag]: Format[T] = {
        Format(reads, writes)
    }
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

trait EitherJson {
    implicit def EitherReads[T1:Reads, T2:Reads]: Reads[Either[T1, T2]] = new Reads[Either[T1, T2]] {
        def reads(json: JsValue) = {
            implicitly[Reads[T1]].reads(json) match {
                case JsSuccess(left, _) => JsSuccess(Left(left))
                case _ =>
                    implicitly[Reads[T2]].reads(json) match {
                        case JsSuccess(right, _) => JsSuccess(Right(right))
                        case _ => JsError("Either[T1, T2] failed")
                    }
            }
        }
    }

    implicit def EitherWrites[T1:Writes, T2:Writes]: Writes[Either[T1, T2]] = new Writes[Either[T1, T2]] {
        def writes(t: Either[T1, T2]) = t match {
            case Left(left) => implicitly[Writes[T1]].writes(left)
            case Right(right) => implicitly[Writes[T2]].writes(right)
        }
    }
}

trait TupleJson {
    implicit def Tuple1Reads[T1:Reads]: Reads[Tuple1[T1]] = new Reads[Tuple1[T1]] {
        def reads(json: JsValue) = json match {
            case JsArray(List(j1)) =>
                (implicitly[Reads[T1]].reads(j1)) match {
                    case JsSuccess(v1, _) => JsSuccess(new Tuple1(v1))
                    case e1: JsError => e1
            }
            case _ => JsError("Not an array")
        }
    }

    implicit def Tuple1Writes[T1:Writes]: Writes[Tuple1[T1]] = new Writes[Tuple1[T1]] {
        def writes(t: Tuple1[T1]) = JsArray(Seq(implicitly[Writes[T1]].writes(t._1)))
    }

    implicit def Tuple2Reads[T1:Reads, T2:Reads]: Reads[(T1, T2)] = new Reads[(T1, T2)] {
        def reads(json: JsValue) = json match {
            case JsArray(List(j1, j2)) =>
                (implicitly[Reads[T1]].reads(j1),
                 implicitly[Reads[T2]].reads(j2)) match {
                    case (JsSuccess(v1, _), JsSuccess(v2, _)) => JsSuccess((v1, v2))
                    case (e1: JsError, _) => e1
                    case (_, e2: JsError) => e2
            }
            case _ => JsError("Not an array")
        }
    }

    implicit def Tuple2Writes[T1:Writes, T2:Writes]: Writes[(T1, T2)] = new Writes[(T1, T2)] {
        def writes(t: (T1, T2)) = JsArray(Seq(implicitly[Writes[T1]].writes(t._1),
                                              implicitly[Writes[T2]].writes(t._2)))
    }

    implicit def Tuple3Reads[T1:Reads, T2:Reads, T3:Reads]: Reads[(T1, T2, T3)] = new Reads[(T1, T2, T3)] {
        def reads(json: JsValue) = json match {
            case JsArray(List(j1, j2, j3)) =>
                (implicitly[Reads[T1]].reads(j1),
                 implicitly[Reads[T2]].reads(j2),
                 implicitly[Reads[T3]].reads(j3)) match {
                    case (JsSuccess(v1, _), JsSuccess(v2, _), JsSuccess(v3, _)) => JsSuccess((v1, v2, v3))
                    case (e1: JsError, _, _) => e1
                    case (_, e2: JsError, _) => e2
                    case (_, _, e3: JsError) => e3
            }
            case _ => JsError("Not an array")
        }
    }

    implicit def Tuple3Writes[T1:Writes, T2:Writes, T3:Writes]: Writes[(T1, T2, T3)] = new Writes[(T1, T2, T3)] {
        def writes(t: (T1, T2, T3)) = JsArray(Seq(implicitly[Writes[T1]].writes(t._1),
                                                  implicitly[Writes[T2]].writes(t._2),
                                                  implicitly[Writes[T3]].writes(t._3)))
    }

    implicit def Tuple4Reads[T1:Reads, T2:Reads, T3:Reads, T4:Reads]: Reads[(T1, T2, T3, T4)] = new Reads[(T1, T2, T3, T4)] {
        def reads(json: JsValue) = json match {
            case JsArray(List(j1, j2, j3, j4)) =>
                (implicitly[Reads[T1]].reads(j1),
                 implicitly[Reads[T2]].reads(j2),
                 implicitly[Reads[T3]].reads(j3),
                 implicitly[Reads[T4]].reads(j4)) match {
                    case (JsSuccess(v1, _), JsSuccess(v2, _), JsSuccess(v3, _), JsSuccess(v4, _)) => JsSuccess((v1, v2, v3, v4))
                    case (e1: JsError, _, _, _) => e1
                    case (_, e2: JsError, _, _) => e2
                    case (_, _, e3: JsError, _) => e3
                    case (_, _, _, e4: JsError) => e4
            }
            case _ => JsError("Not an array")
        }
    }

    implicit def Tuple4Writes[T1:Writes, T2:Writes, T3:Writes, T4:Writes]: Writes[(T1, T2, T3, T4)] = new Writes[(T1, T2, T3, T4)] {
        def writes(t: (T1, T2, T3, T4)) = JsArray(Seq(implicitly[Writes[T1]].writes(t._1),
                                                      implicitly[Writes[T2]].writes(t._2),
                                                      implicitly[Writes[T3]].writes(t._3),
                                                      implicitly[Writes[T4]].writes(t._4)))
    }
}

trait MapJson {
    implicit def MapReads[V:Reads]: Reads[Map[String, V]] = new Reads[Map[String, V]] {
        def reads(json: JsValue) = json match {
            case JsObject(obj) =>
                var hasErrors = false

                val r = obj.map { case (key, value) =>
                    implicitly[Reads[V]].reads(value) match {
                        case JsSuccess(v, _) => Right(key -> v)
                        case JsError(e) =>
                            hasErrors = true
                            Left(e.map { case (p, valerr) => (JsPath \ key) ++ p -> valerr })
                    }
                }

                if (hasErrors) {
                    JsError(r.collect { case Left(t) => t }.reduceLeft((acc, v) => acc ++ v))
                } else {
                    JsSuccess(r.collect { case Right(t) => t }.toMap)
                }
            case _ => JsError("Not an object")
        }
    }

    implicit def MapWrites[V:Writes]: OWrites[Map[String, V]] = new OWrites[Map[String, V]] {
        def writes(t: Map[String, V]) =
            JsObject(t.map { case (k, v) => (k, implicitly[Writes[V]].writes(v)) }.toList)
    }
}

trait UUIDJson {
    implicit val UUIDReads: Reads[UUID] = new Reads[UUID] {
        def reads(json: JsValue) = json match {
            case JsString(uuid) =>
                try {
                    JsSuccess(UUID.fromString(uuid))
                } catch {
                    case e: java.lang.IllegalArgumentException =>
                        JsError(e.getMessage)
                }
            case _ => JsError("Not a string")
        }
    }

    implicit val UUIDWrites: Writes[UUID] = new Writes[UUID] {
        def writes(t: UUID) = JsString(t.toString)
    }
}

trait JsonImplicits extends EitherJson with TupleJson with MapJson with UUIDJson
object JsonImplicits extends JsonImplicits

object TestJson {
    import JsonImplicits._

    type MyType = Int

    object FooBarBaz extends Enumeration {
        type FooBarBaz = Value
        val Foo = Value
        val Bar = Value
        val Baz = Value
    }

    case class Embedded(string: String)

    case class TupleCaseClass(
        boolean: Tuple1[Boolean],
        string: Tuple1[String],
        int: Tuple1[Int],
        double: Tuple1[Double],
        enum: Tuple1[FooBarBaz.Value],
        embedded: Tuple1[Embedded],
        myType: Tuple1[MyType],
        uuid: Tuple1[UUID],
        tuple: Tuple1[(String, Int)],
        either: Tuple1[Either[String, Int]],
        option: Tuple1[Option[String]],
        array: Tuple1[Array[String]],
        list: Tuple1[List[String]],
        map: Tuple1[Map[String, String]],
        stringInt: (String, Int),
        stringIntDouble: (String, Int, Double),
        stringIntDoubleEmbedded: (String, Int, Double, Embedded))

    case class EitherCaseClass(
        eitherBooleanString: Either[Boolean, String],
        eitherIntDouble: Either[Int, Double],
        eitherEnumEmbedded: Either[FooBarBaz.Value, Embedded],
        eitherMyTypeEither: Either[MyType, Either[Boolean, String]],
        eitherOptionArray: Either[Option[Boolean], Array[String]],
        eitherListMap: Either[List[Boolean], Map[String, String]])

    case class OptionCaseClass(
        optionBoolean: Option[Boolean],
        optionString: Option[String],
        optionInt: Option[Int],
        optionDouble: Option[Double],
        optionEnum: Option[FooBarBaz.Value],
        optionEmbedded: Option[Embedded],
        optionMyType: Option[MyType],
        optionUUID: Option[UUID],
        optionTuple: Option[(Boolean, String)],
        optionEither: Option[Either[Boolean, String]],
        optionOption: Option[Option[String]],
        optionArray: Option[Array[String]],
        optionList: Option[List[String]],
        optionMap: Option[Map[String, String]])

    case class ArrayCaseClass(
        arrayBoolean: Array[Boolean],
        arrayString: Array[String],
        arrayInt: Array[Int],
        arrayDouble: Array[Double],
        arrayEnum: Array[FooBarBaz.Value],
        arrayEmbedded: Array[Embedded],
        arrayMyType: Array[MyType],
        arrayUUID: Array[UUID],
        arrayTuple: Array[(Boolean, String)],
        arrayEither: Array[Either[Boolean, String]],
        arrayOption: Array[Option[String]],
        arrayArray: Array[Array[String]],
        arrayList: Array[List[String]],
        arrayMap: Array[Map[String, String]])

    case class ListCaseClass(
        listBoolean: List[Boolean],
        listString: List[String],
        listInt: List[Int],
        listDouble: List[Double],
        listEnum: List[FooBarBaz.Value],
        listEmbedded: List[Embedded],
        listMyType: List[MyType],
        listUUID: List[UUID],
        listTuple: List[(Boolean, String)],
        listEither: List[Either[Boolean, String]],
        listOption: List[Option[String]],
        listArray: List[Array[String]],
        listList: List[List[String]],
        listMap: List[Map[String, String]])

    case class MapCaseClass(
        mapBoolean: Map[String, Boolean],
        mapString: Map[String, String],
        mapInt: Map[String, Int],
        mapDouble: Map[String, Double],
        mapEnum: Map[String, FooBarBaz.Value],
        mapEmbedded: Map[String, Embedded],
        mapMyType: Map[String, MyType],
        mapUUID: Map[String, UUID],
        mapTuple: Map[String, (Boolean, String)],
        mapEither: Map[String, Either[Boolean, String]],
        mapOption: Map[String, Option[String]],
        mapArray: Map[String, Array[String]],
        mapList: Map[String, List[String]],
        mapMap: Map[String, Map[String, String]])

    case class CaseClass(
        boolean: Boolean,
        string: String,
        int: Int,
        double: Double,
        enum: FooBarBaz.Value,
        embedded: Embedded,
        myType: MyType,
        uuid: UUID,
        tuple: TupleCaseClass,
        either: EitherCaseClass,
        option: OptionCaseClass,
        array: ArrayCaseClass,
        list: ListCaseClass,
        map: MapCaseClass)

    case class NoFields()
    case class OneField(field: String)

    implicit val FooBarBazJSON = EnumJson.format(FooBarBaz)
    implicit val EmbeddedJSON = Json.format[Embedded]
    implicit val TupleCaseClassJSON = Json.format[TupleCaseClass]
    implicit val EitherCaseClassJSON = Json.format[EitherCaseClass]
    implicit val OptionCaseClassJSON = Json.format[OptionCaseClass]
    implicit val ArrayCaseClassJSON = Json.format[ArrayCaseClass]
    implicit val ListCaseClassJSON = Json.format[ListCaseClass]
    implicit val MapCaseClassJSON = Json.format[MapCaseClass]
    implicit val CaseClassJSON = Json.format[CaseClass]
    implicit val NoFieldsJSON = Json.noFields[NoFields]
    implicit val OneFieldJSON = Json.format[OneField]
}
