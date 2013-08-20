package org.refptr.iscala

import org.refptr.iscala.json.{Json,SealedJson,EnumJson,JsonImplicits}
import org.refptr.iscala.msg._
import JsonImplicits._

package object formats {
    implicit val MsgTypeFormat = EnumJson.format(MsgType)
    implicit val HeaderFormat = Json.format[Header]

    implicit val ExecutionStatusFormat = EnumJson.format(ExecutionStatus)
    implicit val ExecutionStateFormat = EnumJson.format(ExecutionState)
    implicit val HistAccessTypeFormat = EnumJson.format(HistAccessType)

    implicit val ArgSpecFormat = Json.format[ArgSpec]

    implicit val ExecuteRequestJSON = Json.format[execute_request]
    implicit val ExecuteReplyJSON = SealedJson.writes[execute_reply]

    implicit val ObjectInfoRequestJSON = Json.format[object_info_request]
    implicit val ObjectInfoReplyJSON = SealedJson.writes[object_info_reply]

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
