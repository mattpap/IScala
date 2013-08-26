package org.refptr.iscala;

// XXX: This is a hack to silence deperection warning on Thread.stop()
// in src/main/scala/Interpreter.scala. javac will still complain, but
// we aren't going to change this any more and scalc will remain silent.
class Threading {
    final static void stop(Thread thread) {
        thread.stop();
    }
}
