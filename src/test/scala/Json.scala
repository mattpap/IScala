package org.refptr.iscala.json

import org.refptr.iscala.UUID

// for now only check if at least it compiles
object JsonTest {
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
