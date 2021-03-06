package org.jetbrains.plugins.scala.compiler

object CompilerEventType
  extends Enumeration {

  type CompilerEventType = Value
  
  final val CompilationStarted = Value("compilation-started")
  final val MessageEmitted = Value("message-emitted")
  final val ProgressEmitted = Value("progress-emitted")
  final val CompilationFinished = Value("compilation-finished")
}
