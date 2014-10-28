package org.refptr.iscala

import scala.tools.nsc.interpreter.{IMain,Parsed,JLineCompletion}

class IScalaCompletion(intp: IMain) extends JLineCompletion(intp) {
    def collectCompletions(input: String): List[String] = {
        topLevelFor(Parsed.dotted(input, input.length))
    }
}
