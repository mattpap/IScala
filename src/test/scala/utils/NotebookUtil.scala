package org.refptr.iscala
package tests

import java.io.File
import scala.io.Source

import play.api.libs.json.Json
import org.specs2.matcher.{Matcher,Expectable}

trait NotebookUtil extends InterpreterUtil {
    case class Notebook(nbformat: Int, nbformat_minor: Int, worksheets: List[Worksheet])
    case class Worksheet(cells: List[CodeCell])
    case class CodeCell(input: List[String], language: String, collapsed: Boolean) {
        def code: String = input.mkString
    }

    implicit val CodeCellReads  = Json.reads[CodeCell]
    implicit val WorksheetReads = Json.reads[Worksheet]
    implicit val NotebookReads  = Json.reads[Notebook]

    def loadNotebook(file: File): Notebook = {
        Json.parse(Source.fromFile(file).mkString).as[Notebook]
    }

    object beInterpretable extends Matcher[File] {
        def apply[S <: File](s: Expectable[S]) = {
            val notebook = loadNotebook(s.value)

            val outcome = notebook.worksheets.flatMap(_.cells.map(_.code)).collectFirst { code =>
                interpret(code) match {
                    case Output(result: Results.Failure, _, _) => (code, result)
                }
            }

            val (ok, code) = outcome match {
                case Some((code, _)) => (false, code)
                case None            => (true,  "")
            }

            result(ok, s"${s.description} is interpretable", s"${s.description} is not interpretable, fails at:\n\n$code", s)
        }
    }
}
