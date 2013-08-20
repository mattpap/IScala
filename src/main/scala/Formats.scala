package org.refptr.iscala

import org.refptr.iscala.json.{Json,EnumJson,JsonImplicits}
import org.refptr.iscala.msg._
import JsonImplicits._

import play.api.libs.json.Writes

package object formats {
    implicit val MsgTypeFormat = EnumJson.format(MsgType)
    implicit val HeaderFormat = Json.format[Header]

    implicit val ExecutionStatusFormat = EnumJson.format(ExecutionStatus)
    implicit val ExecutionStateFormat = EnumJson.format(ExecutionState)
    implicit val HistAccessTypeFormat = EnumJson.format(HistAccessType)

    implicit val ArgSpecFormat = Json.format[ArgSpec]

    implicit val ExecuteRequestJSON = Json.format[execute_request]
    implicit val ExecuteReplyJSON = new Writes[execute_reply] {
        val execute_ok_reply_fmt = Json.writes[execute_ok_reply]
        val execute_error_reply_fmt = Json.writes[execute_error_reply]
        val execute_abort_reply_fmt = Json.writes[execute_abort_reply]

        def writes(obj: execute_reply) = (obj match {
            case obj: execute_ok_reply => execute_ok_reply_fmt.writes(obj)
            case obj: execute_error_reply => execute_error_reply_fmt.writes(obj)
            case obj: execute_abort_reply => execute_abort_reply_fmt.writes(obj)
        }) + ("status" -> Json.toJson(obj.status))
    }

    // implicit val ExecuteOkReplyJSON = Json.format[execute_ok_reply]
    // implicit val ExecuteErrorReplyJSON = Json.format[execute_error_reply]
    // implicit val ExecuteAbortReplyJSON = Json.format[execute_abort_reply]

    implicit val ObjectInfoRequestJSON = Json.format[object_info_request]
    implicit val ObjectInfoReplyJSON = new Writes[object_info_reply] {
        val object_info_notfound_reply_fmt = Json.writes[object_info_notfound_reply]
        val object_info_found_reply_fmt = Json.writes[object_info_found_reply]

        def writes(obj: object_info_reply) = (obj match {
            case obj: object_info_notfound_reply => object_info_notfound_reply_fmt.writes(obj)
            case obj: object_info_found_reply => object_info_found_reply_fmt.writes(obj)
        }) + ("found" -> Json.toJson(obj.found))
    }

    // implicit val ObjectInfoNotfoundReplyJSON = Json.format[object_info_notfound_reply]
    // implicit val ObjectInfoFoundReplyJSON = Json.format[object_info_found_reply]

    implicit val CompleteRequestJSON = Json.format[complete_request]
    implicit val CompleteReplyJSON = Json.format[complete_reply]

    implicit val HistoryRequestJSON = Json.format[history_request]
    implicit val HistoryReplyJSON = Json.format[history_reply]

    implicit val ConnectRequestJSON = Json.noFields[connect_request]
    implicit val ConnectReplyJSON = Json.format[connect_reply]

    implicit val KernelInfoRequestJSON = Json.noFields[kernel_info_request]
    implicit val KernelInfoReplyJSON = Json.format[kernel_info_reply]

    implicit val ShutdownRequestJSON = Json.format[shutdown_request]
    implicit val ShutdownReplyJSON = Json.format[shutdown_reply]

    implicit val StreamJSON = Json.format[stream]
    implicit val DisplayDataJSON = Json.format[display_data]
    implicit val PyinJSON = Json.format[pyin]
    implicit val PyoutJSON = Json.format[pyout]
    implicit val PyerrJSON = Json.format[pyerr]
    implicit val StatusJSON = Json.format[status]

    implicit val InputRequestJSON = Json.format[input_request]
    implicit val InputReplyJSON = Json.format[input_reply]
}
