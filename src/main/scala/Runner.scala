package org.refptr.iscala

import java.util.concurrent.locks.ReentrantLock
import Util.{newThread,timer}

class Runner(classLoader: => ClassLoader) {

    class Execution(body: => Results.Result) {
        private var _result: Option[Results.Result] = None

        private val lock     = new ReentrantLock()
        private val finished = lock.newCondition()

        private def withLock[T](body: => T) = {
            lock.lock()
            try body
            finally lock.unlock()
        }

        private def setResult(result: Results.Result) = withLock {
            _result = Some(result)
            finished.signal()
        }

        private val _thread = newThread {
            _.setContextClassLoader(classLoader)
        } {
            setResult(body)
        }

        private[Runner] def cancel() = if (running) setResult(Results.Cancelled)

        private[Runner] def interrupt() = _thread.interrupt()
        private[Runner] def stop()      = Threading.stop(_thread)

        def alive   = _thread.isAlive
        def running = !_result.isDefined

        def await()  = withLock { while (running) finished.await() }
        def result() = { await(); _result.getOrElse(sys.exit) }

        override def toString = s"Execution(thread=${_thread})"
    }

    private var _current: Option[Execution] = None
    def current = _current

    def execute(body: => Results.Result): Execution = {
        val execution = new Execution(body)
        _current = Some(execution)
        execution
    }

    def clear() {
        _current.foreach(_.cancel())
        _current = None
    }

    def cancel() {
        current.foreach { execution =>
            execution.interrupt()
            execution.cancel()
            timer(5) {
                if (execution.alive) {
                    logger.debug(s"Forcefully stopping ${execution}")
                    execution.stop()
                }
            }
        }
    }
}
