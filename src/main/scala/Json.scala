package org.refptr.iscala.json

import play.api.libs.json.{Json,Reads,Writes}

object PlayJson {
    // overrides Json.reads
    def reads[A] = macro JsMacroImpl.readsImpl[A]
    // overrides Json.writes
    def writes[A] = macro JsMacroImpl.writesImpl[A]
    // overrides Json.format
    def format[A] = macro JsMacroImpl.formatImpl[A]
}

object PlayJsonUtil {
    def toJSON[T:Writes](obj: T): String =
        Json.stringify(Json.toJson(obj))

    def fromJSON[T:Reads](json: String): T =
        Json.parse(json).as[T]

    implicit class JsonString(json: String) {
        def as[T:Reads] = fromJSON[T](json)
    }
}
