package org.refptr.iscala

import db.DB

class Session {
    private val session: Int = DB.newSession()

    def endSession(num_cmds: Int) {
        DB.endSession(session)(num_cmds)
    }

    def addHistory(line: Int, source: String) {
        DB.addHistory(session)(line, source)
    }

    def addOutputHistory(line: Int, output: String) {
        DB.addOutputHistory(session)(line, output)
    }
}
