package org.refptr.iscala.db

import java.sql.Timestamp

import scalax.io.JavaConverters._
import scalax.file.Path

import scala.slick.driver.SQLiteDriver.simple._
import Database.threadLocalSession

object Sessions extends Table[(Int, Timestamp, Option[Timestamp], Option[Int], String)]("sessions") {
    def session = column[Int]("session", O.PrimaryKey, O.AutoInc)
    def start = column[Timestamp]("start")
    def end = column[Option[Timestamp]]("end")
    def num_cmds = column[Option[Int]]("num_cmds")
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
    private lazy val dbPath = {
        val home = Path.fromString(System.getProperty("user.home"))
        val profile = home / ".config" / "ipython" / "profile_scala"
        if (!profile.exists) profile.createDirectory()
        profile / "history.sqlite" path
    }

    private def createTable[T](table: Table[T]) = {
        try {
            table.ddl.create
        } catch {
            case e: java.sql.SQLException if e.getMessage contains s"""table "${table.tableName}" already exists""" =>
        }
    }

    private def createTables() {
        createTable(Sessions)
        createTable(History)
        createTable(OutputHistory)
    }


    lazy val db = {
        val db = Database.forURL(s"jdbc:sqlite:$dbPath", driver="org.sqlite.JDBC")
        db.withSession { createTables() }
        db
    }

    import db.withSession

    private def now = new Timestamp(System.currentTimeMillis)

    def newSession(): Int = withSession {
        Sessions.forInsert returning Sessions.session insert (now, None, None, "")
    }

    def endSession(session: Int)(num_cmds: Int): Unit = withSession {
        val q = for { s <- Sessions if s.session === session } yield s.end ~ s.num_cmds
        q.update(Some(now), Some(num_cmds))
    }

    def addHistory(session: Int)(line: Int, source: String): Unit = withSession {
        History.insert(session, line, source, source)
    }

    def addOutputHistory(session: Int)(line: Int, output: String): Unit = withSession {
        OutputHistory.insert(session, line, output)
    }
}
