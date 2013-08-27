package org.refptr.iscala.db

import java.sql.Timestamp

import scala.slick.driver.SQLiteDriver.simple._
import Database.threadLocalSession

object Sessions extends Table[(Int, Timestamp, Timestamp, Int, String)]("sessions") {
    def session = column[Int]("session", O.PrimaryKey, O.AutoInc)
    def start = column[Timestamp]("start")
    def end = column[Timestamp]("end")
    def num_cmds = column[Int]("num_cmds")
    def remark = column[String]("remark")

    def * = session ~ start ~ end ~ num_cmds ~ remark
    def forInsert = start ~ end ~ num_cmds ~ remark
}

object History extends Table[(Int, Int, String, String)]("history") {
    def session = column[Int]("session", O.PrimaryKey)
    def line = column[Int]("line", O.PrimaryKey)
    def source = column[String]("source")
    def source_raw = column[String]("source_raw")

    def * = session ~ line ~ source ~ source_raw
}

object OutputHistory extends Table[(Int, Int, String)]("output_history") {
    def session = column[Int]("session", O.PrimaryKey)
    def line = column[Int]("line", O.PrimaryKey)
    def output = column[String]("output")

    def * = session ~ line ~ output
}

object DB {
    lazy val db = Database.forURL("jdbc:sqlite:history.sqlite", driver="org.sqlite.JDBC")

    import db.withSession

    def createTable[T](table: Table[T]) = withSession {
        try {
            table.ddl.create
        } catch {
            case e: java.sql.SQLException if e.getMessage contains s"""table "${table.tableName}" already exists""" =>
        }
    }

    def createTables {
        createTable(Sessions)
        createTable(History)
        createTable(OutputHistory)
    }

    def now = new Timestamp(System.currentTimeMillis)

    def newSession(): Int = withSession {
        Sessions.forInsert returning Sessions.session insert (now, now, 0, "")
    }

    def endSession(session: Int)(num_cmds: Int): Unit = withSession {
        val q = for { s <- Sessions if s.session === session } yield s.end ~ s.num_cmds
        q.update(now, num_cmds)
    }

    def addHistory(session: Int)(line: Int, source: String): Unit = withSession {
        History.insert(session, line, source, source)
    }
}
