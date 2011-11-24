package silAST.expressions.program.terms

import scala.collection.Seq
import silAST.source.SourceLocation
import silAST.symbols.Field
import silAST.expressions.assertion.terms.GTerm

class FieldReadTerm[+T <: GProgramTerm[T]](
    sl : SourceLocation, 
    val location : T, 
    val field : Field) 
  extends GTerm[T](sl) with GProgramTerm[T] 
{

  override def toString(): String = { return location.toString() + "." + field.name }

  override def subNodes(): Seq[T] = { return location :: Nil }
  override def subTerms(): Seq[T] = { return location :: Nil }

}