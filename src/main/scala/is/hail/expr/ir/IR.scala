package is.hail.expr.ir

import is.hail.expr.{BaseIR, TBoolean, TFloat32, TFloat64, TInt32, TInt64, TStruct, TVoid, Type, TArray}

sealed trait IR extends BaseIR {
  def typ: Type

  override def children: IndexedSeq[BaseIR] =
    Children(this)

  override def copy(newChildren: IndexedSeq[BaseIR]): BaseIR =
    Copy(this, newChildren)
}
case class I32(x: Int) extends IR { val typ = TInt32() }
case class I64(x: Long) extends IR { val typ = TInt64() }
case class F32(x: Float) extends IR { val typ = TFloat32() }
case class F64(x: Double) extends IR { val typ = TFloat64() }
case class True() extends IR { val typ = TBoolean() }
case class False() extends IR { val typ = TBoolean() }

case class Cast(v: IR, typ: Type) extends IR

case class NA(typ: Type) extends IR
case class MapNA(name: String, value: IR, body: IR, var typ: Type = null) extends IR
case class IsNA(value: IR) extends IR { val typ = TBoolean() }

case class If(cond: IR, cnsq: IR, altr: IR, var typ: Type = null) extends IR

case class Let(name: String, value: IR, body: IR, var typ: Type = null) extends IR
case class Ref(name: String, var typ: Type = null) extends IR

case class ApplyBinaryPrimOp(op: BinaryOp, l: IR, r: IR, var typ: Type = null) extends IR
case class ApplyUnaryPrimOp(op: UnaryOp, x: IR, var typ: Type = null) extends IR

case class MakeArray(args: Array[IR], var typ: TArray = null) extends IR {
  override def toString(): String = s"MakeArray(${args: IndexedSeq[IR]}, $typ)"
}
case class MakeArrayN(len: IR, elementType: Type) extends IR { def typ: TArray = TArray(elementType) }
case class ArrayRef(a: IR, i: IR, var typ: Type = null) extends IR
case class ArrayMissingnessRef(a: IR, i: IR) extends IR { val typ: Type = TBoolean() }
case class ArrayLen(a: IR) extends IR { val typ = TInt32() }
case class ArrayMap(a: IR, name: String, body: IR, var elementTyp: Type = null) extends IR { def typ: TArray = TArray(elementTyp) }
case class ArrayFold(a: IR, zero: IR, accumName: String, valueName: String, body: IR, var typ: Type = null) extends IR

case class MakeStruct(fields: Array[(String, Type, IR)]) extends IR {
  val typ: TStruct = TStruct(fields.map(x => x._1 -> x._2):_*)
  override def toString(): String =
    s"MakeStruct(${fields: IndexedSeq[(String, Type, IR)]})"
}
case class GetField(o: IR, name: String, var typ: Type = null) extends IR
case class GetFieldMissingness(o: IR, name: String) extends IR { val typ: Type = TBoolean() }

case class In(i: Int, val typ: Type) extends IR
case class InMissingness(i: Int) extends IR { val typ: Type = TBoolean() }
// FIXME: should be type any
case class Die(message: String) extends IR { val typ = TVoid }
