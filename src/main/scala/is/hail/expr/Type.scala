package is.hail.expr

import is.hail.annotations.{Annotation, AnnotationPathException, _}
import is.hail.asm4s.Code
import is.hail.asm4s._
import is.hail.check.Arbitrary._
import is.hail.check.{Gen, _}
import is.hail.sparkextras.OrderedKey
import is.hail.utils
import is.hail.utils.{Interval, StringEscapeUtils, _}
import is.hail.variant.{AltAllele, Call, Contig, GRBase, GenomeReference, Genotype, Locus, Variant}
import org.apache.spark.sql.Row
import org.apache.spark.sql.types.DataType
import org.json4s._
import org.json4s.jackson.JsonMethods

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.reflect.ClassTag
import scala.reflect.classTag

abstract class BaseType

object Type {
  def genScalar(required: Boolean) =
    Gen.oneOf(TBoolean(required), TInt32(required), TInt64(required), TFloat32(required),
      TFloat64(required), TString(required), TAltAllele(required), TGenotype(required), TCall(required))

  val genOptionalScalar = genScalar(false)

  val genRequiredScalar = genScalar(true)

  def genComplexType(required: Boolean) = {
    val grDependents = GenomeReference.references.values.toArray.flatMap(gr =>
      Array(TVariant(gr, required), TLocus(gr, required), TInterval(gr, required)))
    val others = Array(
      TAltAllele(required), TGenotype(required), TCall(required))
    Gen.oneOfSeq(grDependents ++ others)
  }

  val optionalComplex = genComplexType(false)

  val requiredComplex = genComplexType(true)

  def preGenStruct(required: Boolean, genFieldType: Gen[Type]): Gen[TStruct] =
    Gen.buildableOf[Array, (String, Type, Map[String, String])](
      Gen.zip(Gen.identifier,
        genFieldType,
        Gen.option(
          Gen.buildableOf2[Map, String, String](
            Gen.zip(arbitrary[String].filter(s => !s.isEmpty), arbitrary[String])), someFraction = 0.05)
          .map(o => o.getOrElse(Map.empty[String, String]))))
      .filter(fields => fields.map(_._1).areDistinct())
      .map(fields => TStruct(fields
        .iterator
        .zipWithIndex
        .map { case ((k, t, m), i) => Field(k, t, i, m) }
        .toIndexedSeq))
      .map(t => if (required) (!t).asInstanceOf[TStruct] else t)

  private val defaultRequiredGenRatio = 0.2

  def genStruct: Gen[TStruct] = Gen.coin(defaultRequiredGenRatio).flatMap(preGenStruct(_, genArb))

  val genOptionalStruct = preGenStruct(false, genArb)

  val genRequiredStruct = preGenStruct(true, genArb)

  val genInsertableStruct: Gen[TStruct] = Gen.coin(defaultRequiredGenRatio).flatMap(required =>
    if (required)
      preGenStruct(true, genArb)
    else
      preGenStruct(false, genOptional))

  def genSized(size: Int, required: Boolean, genTStruct: Gen[TStruct]): Gen[Type] =
    if (size < 1)
      Gen.const(TStruct.empty(required))
    else if (size < 2)
      genScalar(required)
    else {
      Gen.frequency(
        (4, genScalar(required)),
        (1, genComplexType(required)),
        (1, genArb.map { TArray(_) }),
        (1, genArb.map { TSet(_) }),
        (1, Gen.zip(genRequired, genArb).map { case (k, v) => TDict(k, v) }),
        (1, genTStruct.resize(size)))
    }

  def preGenArb(required: Boolean, genStruct: Gen[TStruct] = genStruct): Gen[Type] =
    Gen.sized(genSized(_, required, genStruct))

  def genArb: Gen[Type] = Gen.coin(0.2).flatMap(preGenArb(_))

  val genOptional: Gen[Type] = preGenArb(false)

  val genRequired: Gen[Type] = preGenArb(true)

  val genInsertable: Gen[Type] = Gen.coin(0.2).flatMap(preGenArb(_, genInsertableStruct))

  def genWithValue: Gen[(Type, Annotation)] = for {
    s <- Gen.size
    // prefer smaller type and bigger values
    fraction <- Gen.choose(0.1, 0.3)
    x = (fraction * s).toInt
    y = s - x
    t <- Type.genStruct.resize(x)
    v <- t.genValue.resize(y)
  } yield (t, v)

  implicit def arbType = Arbitrary(genArb)

  def parseMap(s: String): Map[String, Type] = Parser.parseAnnotationTypes(s)
}

sealed abstract class Type extends BaseType with Serializable {
  self =>

  def children: Seq[Type] = Seq()

  def clear(): Unit = children.foreach(_.clear())

  def desc: String = ""

  def unify(concrete: Type): Boolean = {
    this.isOfType(concrete)
  }

  def isBound: Boolean = children.forall(_.isBound)

  def subst(): Type = this

  def getAsOption[T](fields: String*)(implicit ct: ClassTag[T]): Option[T] = {
    getOption(fields: _*)
      .flatMap { t =>
        if (ct.runtimeClass.isInstance(t))
          Some(t.asInstanceOf[T])
        else
          None
      }
  }

  def unsafeOrdering(missingGreatest: Boolean = false): UnsafeOrdering = ???

  def getOption(fields: String*): Option[Type] = getOption(fields.toList)

  def getOption(path: List[String]): Option[Type] = {
    if (path.isEmpty)
      Some(this)
    else
      None
  }

  def delete(fields: String*): (Type, Deleter) = delete(fields.toList)

  def delete(path: List[String]): (Type, Deleter) = {
    if (path.nonEmpty)
      throw new AnnotationPathException(s"invalid path ${ path.mkString(".") } from type ${ this }")
    else
      (TStruct.empty(), a => null)
  }

  def unsafeInsert(typeToInsert: Type, path: List[String]): (Type, UnsafeInserter) =
    TStruct.empty().unsafeInsert(typeToInsert, path)

  def insert(signature: Type, fields: String*): (Type, Inserter) = insert(signature, fields.toList)

  def insert(signature: Type, path: List[String]): (Type, Inserter) = {
    if (path.nonEmpty)
      TStruct.empty().insert(signature, path)
    else
      (signature, (a, toIns) => toIns)
  }

  def query(fields: String*): Querier = query(fields.toList)

  def query(path: List[String]): Querier = {
    val (t, q) = queryTyped(path)
    q
  }

  def queryTyped(fields: String*): (Type, Querier) = queryTyped(fields.toList)

  def queryTyped(path: List[String]): (Type, Querier) = {
    if (path.nonEmpty)
      throw new AnnotationPathException(s"invalid path ${ path.mkString(".") } from type ${ this }")
    else
      (this, identity[Annotation])
  }

  def _toString: String

  final override def toString = {
    if (required) "!" else ""
  } + _toString

  def _pretty(sb: StringBuilder, indent: Int = 0, printAttrs: Boolean = false, compact: Boolean = false) {
    sb.append(_toString)
  }

  final def pretty(sb: StringBuilder, indent: Int = 0, printAttrs: Boolean = false, compact: Boolean = false) {
    if (required)
      sb.append("!")
    _pretty(sb, indent, printAttrs, compact)
  }

  def toPrettyString(indent: Int = 0, compact: Boolean = false, printAttrs: Boolean = false): String = {
    val sb = new StringBuilder
    pretty(sb, indent, compact = compact, printAttrs = printAttrs)
    sb.result()
  }

  def fieldOption(fields: String*): Option[Field] = fieldOption(fields.toList)

  def fieldOption(path: List[String]): Option[Field] =
    None

  def schema: DataType = SparkAnnotationImpex.exportType(this)

  def str(a: Annotation): String = if (a == null) "NA" else a.toString

  def toJSON(a: Annotation): JValue = JSONAnnotationImpex.exportAnnotation(a, this)

  def genNonmissingValue: Gen[Annotation] = ???

  def genValue: Gen[Annotation] =
    if (required)
      genNonmissingValue
    else
      Gen.nextCoin(0.05).flatMap(isEmpty => if (isEmpty) Gen.const(null) else genNonmissingValue)

  def isRealizable: Boolean = children.forall(_.isRealizable)

  /* compare values for equality, but compare Float and Double values using D_== */
  def valuesSimilar(a1: Annotation, a2: Annotation, tolerance: Double = utils.defaultTolerance): Boolean = a1 == a2

  def scalaClassTag: ClassTag[_ <: AnyRef]

  def canCompare(other: Type): Boolean = this == other

  def ordering(missingGreatest: Boolean): Ordering[Annotation]

  val partitionKey: Type = this

  def typedOrderedKey[PK, K] = new OrderedKey[PK, K] {
    def project(key: K): PK = key.asInstanceOf[PK]

    val kOrd: Ordering[K] = ordering(missingGreatest = true).asInstanceOf[Ordering[K]]

    val pkOrd: Ordering[PK] = ordering(missingGreatest = true).asInstanceOf[Ordering[PK]]

    val kct: ClassTag[K] = scalaClassTag.asInstanceOf[ClassTag[K]]

    val pkct: ClassTag[PK] = scalaClassTag.asInstanceOf[ClassTag[PK]]
  }

  def orderedKey: OrderedKey[Annotation, Annotation] = new OrderedKey[Annotation, Annotation] {
    def project(key: Annotation): Annotation = key

    val kOrd: Ordering[Annotation] = ordering(missingGreatest = true)

    val pkOrd: Ordering[Annotation] = ordering(missingGreatest = true)

    val kct: ClassTag[Annotation] = classTag[Annotation]

    val pkct: ClassTag[Annotation] = classTag[Annotation]
  }

  def jsonReader: JSONReader[Annotation] = new JSONReader[Annotation] {
    def fromJSON(a: JValue): Annotation = JSONAnnotationImpex.importAnnotation(a, self)
  }

  def jsonWriter: JSONWriter[Annotation] = new JSONWriter[Annotation] {
    def toJSON(pk: Annotation): JValue = JSONAnnotationImpex.exportAnnotation(pk, self)
  }

  def byteSize: Long = 1

  def alignment: Long = byteSize

  /*  Fundamental types are types that can be handled natively by RegionValueBuilder: primitive
      types, Array and Struct. */
  def fundamentalType: Type = this

  def required: Boolean

  def _typeCheck(a: Any): Boolean

  final def typeCheck(a: Any): Boolean = (!required && a == null) || _typeCheck(a)

  final def setRequired(required: Boolean): Type = if (this.required == required) this else !this

  final def unary_!(): Type = {
    this match {
      case TBinary(req) => TBinary(!req)
      case TBoolean(req) => TBoolean(!req)
      case TInt32(req) => TInt32(!req)
      case TInt64(req) => TInt64(!req)
      case TFloat32(req) => TFloat32(!req)
      case TFloat64(req) => TFloat64(!req)
      case TString(req) => TString(!req)
      case TGenotype(req) => TGenotype(!req)
      case TCall(req) => TCall(!req)
      case TAltAllele(req) => TAltAllele(!req)
      case t: TArray => t.copy(required = !t.required)
      case t: TSet => t.copy(required = !t.required)
      case t: TDict => t.copy(required = !t.required)
      case t: TVariant => t.copy(required = !t.required)
      case t: TLocus => t.copy(required = !t.required)
      case t: TInterval => t.copy(required = !t.required)
      case t: TStruct => t.copy(required = !t.required)
    }
  }

  final def isOfType(t: Type): Boolean = {
    this match {
      case TBinary(_) => t == TBinaryOptional || t == TBinaryRequired
      case TBoolean(_) => t == TBooleanOptional || t == TBooleanRequired
      case TInt32(_) => t == TInt32Optional || t == TInt32Required
      case TInt64(_) => t == TInt64Optional || t == TInt64Required
      case TFloat32(_) => t == TFloat32Optional || t == TFloat32Required
      case TFloat64(_) => t == TFloat64Optional || t == TFloat64Required
      case TString(_) => t == TStringOptional || t == TStringRequired
      case TGenotype(_) => t == TGenotypeOptional || t == TGenotypeRequired
      case TCall(_) => t == TCallOptional || t == TCallRequired
      case TAltAllele(_) => t == TAltAlleleOptional || t == TAltAlleleRequired
      case t2: TLocus => t == t2 || t == !t2
      case t2: TVariant => t == t2 || t == !t2
      case t2: TInterval => t == t2 || t == !t2
      case t2: TStruct => t.isInstanceOf[TStruct] &&
        t.asInstanceOf[TStruct].fields.zip(t2.fields).forall { case (f1: Field, f2: Field) => f1.typ.isOfType(f2.typ) && f1.name == f2.name }
      case t2: TArray => t.isInstanceOf[TArray] && t.asInstanceOf[TArray].elementType.isOfType(t2.elementType)
      case t2: TSet => t.isInstanceOf[TSet] && t.asInstanceOf[TSet].elementType.isOfType(t2.elementType)
      case t2: TDict => t.isInstanceOf[TDict] && t.asInstanceOf[TDict].keyType.isOfType(t2.keyType) && t.asInstanceOf[TDict].valueType.isOfType(t2.valueType)
    }
  }
}

case object TVoid extends Type {
  override val required = true
  override def _toString = "Void"
  override def ordering(missingGreatest: Boolean): Ordering[is.hail.annotations.Annotation] = throw new UnsupportedOperationException("No ordering on Void")
  override def scalaClassTag: scala.reflect.ClassTag[_ <: AnyRef] = throw new UnsupportedOperationException("No ClassTag for Void")
  override def _typeCheck(a: Any): Boolean = throw new UnsupportedOperationException("No elements of Void")
  override def isRealizable = false
}

abstract class ComplexType extends Type {
  val representation: Type

  override def byteSize: Long = representation.byteSize

  override def alignment: Long = representation.alignment

  override def unsafeOrdering(missingGreatest: Boolean): UnsafeOrdering = representation.unsafeOrdering(missingGreatest)

  override def fundamentalType: Type = representation.fundamentalType
}

sealed class TBinary(override val required: Boolean) extends Type {
  def _toString = "Binary"

  def _typeCheck(a: Any): Boolean = a.isInstanceOf[Array[Byte]]

  override def genNonmissingValue: Gen[Annotation] = Gen.buildableOf(arbitrary[Byte])

  override def scalaClassTag: ClassTag[Array[Byte]] = classTag[Array[Byte]]

  override def unsafeOrdering(missingGreatest: Boolean): UnsafeOrdering = new UnsafeOrdering {
    def compare(r1: MemoryBuffer, o1: Long, r2: MemoryBuffer, o2: Long): Int = {
      val l1 = TBinary.loadLength(r1, o1)
      val l2 = TBinary.loadLength(r2, o2)

      val bOff1 = TBinary.bytesOffset(o1)
      val bOff2 = TBinary.bytesOffset(o2)

      val lim = math.min(l1, l2)
      var i = 0

      while (i < lim) {
        val b1 = r1.loadByte(bOff1 + i)
        val b2 = r2.loadByte(bOff2 + i)
        if (b1 != b2)
          return java.lang.Byte.compare(b1, b2)

        i += 1
      }
      Integer.compare(l1, l2)
    }
  }

  def ordering(missingGreatest: Boolean): Ordering[Annotation] = {
    val ord = Ordering.Iterable[Byte]

    annotationOrdering(extendOrderingToNull(missingGreatest)(
      new Ordering[Array[Byte]] {
        def compare(a: Array[Byte], b: Array[Byte]): Int = ord.compare(a, b)
      }))
  }

  override def byteSize: Long = 8
}

object TBinary {
  def apply(required: Boolean = false): TBinary = if (required) TBinaryRequired else TBinaryOptional

  def unapply(t: TBinary): Option[Boolean] = Option(t.required)

  def contentAlignment: Long = 4

  def contentByteSize(length: Int): Long = 4 + length

  def loadLength(region: MemoryBuffer, boff: Long): Int =
    region.loadInt(boff)

  def bytesOffset(boff: Long): Long = boff + 4

  def allocate(region: MemoryBuffer, length: Int): Long = {
    region.align(contentAlignment)
    region.allocate(contentByteSize(length))
  }

}

case object TBinaryOptional extends TBinary(false)

case object TBinaryRequired extends TBinary(true)

sealed class TBoolean(override val required: Boolean) extends Type {
  def _toString = "Boolean"

  def _typeCheck(a: Any): Boolean = a.isInstanceOf[Boolean]

  def parse(s: String): Annotation = s.toBoolean

  override def genNonmissingValue: Gen[Annotation] = arbitrary[Boolean]

  override def scalaClassTag: ClassTag[java.lang.Boolean] = classTag[java.lang.Boolean]

  override def unsafeOrdering(missingGreatest: Boolean): UnsafeOrdering = new UnsafeOrdering {
    def compare(r1: MemoryBuffer, o1: Long, r2: MemoryBuffer, o2: Long): Int = {
      java.lang.Boolean.compare(r1.loadBoolean(o1), r2.loadBoolean(o2))
    }
  }

  def ordering(missingGreatest: Boolean): Ordering[Annotation] =
    annotationOrdering(
      extendOrderingToNull(missingGreatest)(implicitly[Ordering[Boolean]]))

  override def byteSize: Long = 1
}

object TBoolean {
  def apply(required: Boolean = false): TBoolean = if (required) TBooleanRequired else TBooleanOptional

  def unapply(t: TBoolean): Option[Boolean] = Option(t.required)
}

case object TBooleanOptional extends TBoolean(false)

case object TBooleanRequired extends TBoolean(true)

object TNumeric {
  def promoteNumeric(types: Set[TNumeric]): Type = {
    if (types.size == 1)
      types.head
    else if (types(TFloat64Required) || types(TFloat64Optional))
      TFloat64(types(TFloat64Required))
    else if (types(TFloat32Required) || types(TFloat32Optional))
      TFloat32(types(TFloat32Required))
    else {
      assert(types(TInt64Required) || types(TInt64Optional))
      TInt64(types(TInt64Required))
    }
  }
}

abstract class TNumeric extends Type {
  def conv: NumericConversion[_, _]

  override def canCompare(other: Type): Boolean = other.isInstanceOf[TNumeric]
}

abstract class TIntegral extends TNumeric

sealed class TInt32(override val required: Boolean) extends TIntegral {
  def _toString = "Int32"

  val conv = IntNumericConversion

  def _typeCheck(a: Any): Boolean = a.isInstanceOf[Int]

  override def genNonmissingValue: Gen[Annotation] = arbitrary[Int]

  override def scalaClassTag: ClassTag[java.lang.Integer] = classTag[java.lang.Integer]

  override def unsafeOrdering(missingGreatest: Boolean): UnsafeOrdering = new UnsafeOrdering {
    def compare(r1: MemoryBuffer, o1: Long, r2: MemoryBuffer, o2: Long): Int = {
      Integer.compare(r1.loadInt(o1), r2.loadInt(o2))
    }
  }

  def ordering(missingGreatest: Boolean): Ordering[Annotation] =
    annotationOrdering(
      extendOrderingToNull(missingGreatest)(implicitly[Ordering[Int]]))

  override def byteSize: Long = 4
}

object TInt32 {
  def apply(required: Boolean = false) = if (required) TInt32Required else TInt32Optional

  def unapply(t: TInt32): Option[Boolean] = Option(t.required)
}

case object TInt32Optional extends TInt32(false)

case object TInt32Required extends TInt32(true)

sealed class TInt64(override val required: Boolean) extends TIntegral {
  def _toString = "Int64"

  val conv = LongNumericConversion

  def _typeCheck(a: Any): Boolean = a.isInstanceOf[Long]

  override def genNonmissingValue: Gen[Annotation] = arbitrary[Long]

  override def scalaClassTag: ClassTag[java.lang.Long] = classTag[java.lang.Long]

  override def unsafeOrdering(missingGreatest: Boolean): UnsafeOrdering = new UnsafeOrdering {
    def compare(r1: MemoryBuffer, o1: Long, r2: MemoryBuffer, o2: Long): Int = {
      java.lang.Long.compare(r1.loadLong(o1), r2.loadLong(o2))
    }
  }

  def ordering(missingGreatest: Boolean): Ordering[Annotation] =
    annotationOrdering(
      extendOrderingToNull(missingGreatest)(implicitly[Ordering[Long]]))

  override def byteSize: Long = 8
}

object TInt64 {
  def apply(required: Boolean = false): TInt64 = if (required) TInt64Required else TInt64Optional

  def unapply(t: TInt64): Option[Boolean] = Option(t.required)
}

case object TInt64Optional extends TInt64(false)

case object TInt64Required extends TInt64(true)

sealed class TFloat32(override val required: Boolean) extends TNumeric {
  def _toString = "Float32"

  val conv = FloatNumericConversion

  def _typeCheck(a: Any): Boolean = a.isInstanceOf[Float]

  override def str(a: Annotation): String = if (a == null) "NA" else a.asInstanceOf[Float].formatted("%.5e")

  override def genNonmissingValue: Gen[Annotation] = arbitrary[Double].map(_.toFloat)

  override def valuesSimilar(a1: Annotation, a2: Annotation, tolerance: Double): Boolean =
    a1 == a2 || (a1 != null && a2 != null &&
      (D_==(a1.asInstanceOf[Float], a2.asInstanceOf[Float], tolerance) ||
        (a1.asInstanceOf[Double].isNaN && a2.asInstanceOf[Double].isNaN)))

  override def scalaClassTag: ClassTag[java.lang.Float] = classTag[java.lang.Float]

  override def unsafeOrdering(missingGreatest: Boolean): UnsafeOrdering = new UnsafeOrdering {
    def compare(r1: MemoryBuffer, o1: Long, r2: MemoryBuffer, o2: Long): Int = {
      java.lang.Float.compare(r1.loadFloat(o1), r2.loadFloat(o2))
    }
  }

  def ordering(missingGreatest: Boolean): Ordering[Annotation] =
    annotationOrdering(
      extendOrderingToNull(missingGreatest)(implicitly[Ordering[Float]]))

  override def byteSize: Long = 4
}

object TFloat32 {
  def apply(required: Boolean = false): TFloat32 = if (required) TFloat32Required else TFloat32Optional

  def unapply(t: TFloat32): Option[Boolean] = Option(t.required)
}

case object TFloat32Optional extends TFloat32(false)

case object TFloat32Required extends TFloat32(true)

sealed class TFloat64(override val required: Boolean) extends TNumeric {
  override def _toString = "Float64"

  val conv = DoubleNumericConversion

  def _typeCheck(a: Any): Boolean = a.isInstanceOf[Double]

  override def str(a: Annotation): String = if (a == null) "NA" else a.asInstanceOf[Double].formatted("%.5e")

  override def genNonmissingValue: Gen[Annotation] = arbitrary[Double]

  override def valuesSimilar(a1: Annotation, a2: Annotation, tolerance: Double): Boolean =
    a1 == a2 || (a1 != null && a2 != null &&
      (D_==(a1.asInstanceOf[Double], a2.asInstanceOf[Double], tolerance) ||
        (a1.asInstanceOf[Double].isNaN && a2.asInstanceOf[Double].isNaN)))

  override def scalaClassTag: ClassTag[java.lang.Double] = classTag[java.lang.Double]

  override def unsafeOrdering(missingGreatest: Boolean): UnsafeOrdering = new UnsafeOrdering {
    def compare(r1: MemoryBuffer, o1: Long, r2: MemoryBuffer, o2: Long): Int = {
      java.lang.Double.compare(r1.loadDouble(o1), r2.loadDouble(o2))
    }
  }

  def ordering(missingGreatest: Boolean): Ordering[Annotation] =
    annotationOrdering(
      extendOrderingToNull(missingGreatest)(implicitly[Ordering[Double]]))

  override def byteSize: Long = 8
}

object TFloat64 {
  def apply(required: Boolean = false): TFloat64 = if (required) TFloat64Required else TFloat64Optional

  def unapply(t: TFloat64): Option[Boolean] = Option(t.required)
}

case object TFloat64Optional extends TFloat64(false)

case object TFloat64Required extends TFloat64(true)

sealed class TString(override val required: Boolean) extends Type {
  def _toString = "String"

  def _typeCheck(a: Any): Boolean = a.isInstanceOf[String]

  override def genNonmissingValue: Gen[Annotation] = arbitrary[String]

  override def scalaClassTag: ClassTag[String] = classTag[String]

  override def unsafeOrdering(missingGreatest: Boolean): UnsafeOrdering = TBinary(required).unsafeOrdering(missingGreatest)

  def ordering(missingGreatest: Boolean): Ordering[Annotation] =
    annotationOrdering(
      extendOrderingToNull(missingGreatest)(implicitly[Ordering[String]]))

  override def byteSize: Long = 8

  override def fundamentalType: Type = TBinary(required)

}

object TString {
  def apply(required: Boolean = false): TString = if (required) TStringRequired else TStringOptional

  def unapply(t: TString): Option[Boolean] = Option(t.required)

  def loadString(region: MemoryBuffer, boff: Long): String = {
    val length = TBinary.loadLength(region, boff)
    new String(region.loadBytes(TBinary.bytesOffset(boff), length))
  }
}

case object TStringOptional extends TString(false)

case object TStringRequired extends TString(true)

final case class TFunction(paramTypes: Seq[Type], returnType: Type) extends Type {
  override val required = true

  def _toString = s"(${ paramTypes.mkString(",") }) => $returnType"

  override def isRealizable = false

  override def unify(concrete: Type): Boolean = {
    concrete match {
      case TFunction(cparamTypes, creturnType) =>
        paramTypes.length == cparamTypes.length &&
          (paramTypes, cparamTypes).zipped.forall { case (pt, cpt) =>
            pt.unify(cpt)
          } &&
          returnType.unify(creturnType)

      case _ => false
    }
  }

  override def subst() = TFunction(paramTypes.map(_.subst()), returnType.subst())

  def _typeCheck(a: Any): Boolean =
    throw new RuntimeException("TFunction is not realizable")

  override def children: Seq[Type] = paramTypes :+ returnType

  override def scalaClassTag: ClassTag[AnyRef] = throw new RuntimeException("TFunction is not realizable")

  override def ordering(missingGreatest: Boolean): Ordering[Annotation] =
    throw new RuntimeException("TFunction is not realizable")
}

final case class Box[T](var b: Option[T] = None) {
  def unify(t: T): Boolean = b match {
    case Some(bt) => t == bt
    case None =>
      b = Some(t)
      true
  }

  def clear() {
    b = None
  }

  def get: T = b.get
}

final case class TAggregableVariable(elementType: Type, st: Box[SymbolTable]) extends Type {
  override val required = true

  def _toString = s"?Aggregable[$elementType]"

  override def isRealizable = false

  override def children = Seq(elementType)

  def _typeCheck(a: Any): Boolean =
    throw new RuntimeException("TAggregableVariable is not realizable")

  override def unify(concrete: Type): Boolean = concrete match {
    case cagg: TAggregable =>
      elementType.unify(cagg.elementType) && st.unify(cagg.symTab)
    case _ => false
  }

  override def isBound: Boolean = elementType.isBound & st.b.nonEmpty

  override def clear() {
    st.clear()
  }

  override def subst(): Type = {
    assert(st != null)
    TAggregable(elementType.subst(), st.get)
  }

  override def desc: String = TAggregable.desc

  override def canCompare(other: Type): Boolean = false

  override def scalaClassTag: ClassTag[AnyRef] = throw new RuntimeException("TAggregableVariable is not realizable")

  override def ordering(missingGreatest: Boolean): Ordering[Annotation] =
    throw new RuntimeException("TAggregableVariable is not realizable")
}

final case class TVariable(name: String, var t: Type = null) extends Type {
  override val required = true

  override def _toString: String = s"?$name"

  override def isRealizable = false

  def _typeCheck(a: Any): Boolean =
    throw new RuntimeException("TVariable is not realizable")

  override def unify(concrete: Type): Boolean = {
    if (t == null) {
      if (concrete.isRealizable) {
        t = concrete
        true
      } else
        false
    } else
      t.isOfType(concrete)
  }

  override def isBound: Boolean = t != null

  override def clear() {
    t = null
  }

  override def subst(): Type = {
    assert(t != null)
    t
  }

  override def scalaClassTag: ClassTag[AnyRef] = throw new RuntimeException("TVariable is not realizable")

  override def ordering(missingGreatest: Boolean): Ordering[Annotation] =
    throw new RuntimeException("TVariable is not realizable")
}

object TAggregable {
  val desc = """An ``Aggregable`` is a Hail data type representing a distributed row or column of a matrix. Hail exposes a number of methods to compute on aggregables depending on the data type."""

  def apply(elementType: Type, symTab: SymbolTable): TAggregable = {
    val agg = TAggregable(elementType)
    agg.symTab = symTab
    agg
  }
}

final case class TAggregable(elementType: Type, override val required: Boolean = false) extends TContainer {
  val elementByteSize: Long = UnsafeUtils.arrayElementSize(elementType)

  val contentsAlignment: Long = elementType.alignment.max(4)

  override val fundamentalType: TArray = TArray(elementType.fundamentalType, required)

  // FIXME does symTab belong here?
  // not used for equality
  var symTab: SymbolTable = _

  override def unify(concrete: Type): Boolean = {
    concrete match {
      case TAggregable(celementType, _) => elementType.unify(celementType)
      case _ => false
    }
  }

  // FIXME symTab == null
  override def subst() = TAggregable(elementType.subst())

  override def isRealizable = false

  def _typeCheck(a: Any): Boolean =
    throw new RuntimeException("TAggregable is not realizable")

  override def _toString: String = s"Aggregable[${ elementType.toString }]"

  override def desc: String = TAggregable.desc

  override def scalaClassTag: ClassTag[_ <: AnyRef] = elementType.scalaClassTag

  override def ordering(missingGreatest: Boolean): Ordering[Annotation] =
    throw new RuntimeException("TAggregable is not realizable")
}

object TContainer {
  def loadLength(region: MemoryBuffer, aoff: Long): Int =
    region.loadInt(aoff)

  def loadLength(region: Code[MemoryBuffer], aoff: Code[Long]): Code[Int] =
    region.loadInt(aoff)
}

abstract class TContainer extends Type {
  def elementType: Type

  def elementByteSize: Long

  override def byteSize: Long = 8

  def contentsAlignment: Long

  override def children = Seq(elementType)

  final def loadLength(region: MemoryBuffer, aoff: Long): Int =
    TContainer.loadLength(region, aoff)

  final def loadLength(region: Code[MemoryBuffer], aoff: Code[Long]): Code[Int] =
    TContainer.loadLength(region, aoff)

  def _elementsOffset(length: Int): Long =
    if (elementType.required)
      UnsafeUtils.roundUpAlignment(4, elementType.alignment)
    else
      UnsafeUtils.roundUpAlignment(4 + ((length + 7) >>> 3), elementType.alignment)

  def _elementsOffset(length: Code[Int]): Code[Long] =
    if (elementType.required)
      UnsafeUtils.roundUpAlignment(4, elementType.alignment)
    else
      UnsafeUtils.roundUpAlignment(((length.toL + 7L) >>> 3) + 4L, elementType.alignment)

  var elementsOffsetTable: Array[Long] = _

  def elementsOffset(length: Int): Long = {
    if (elementsOffsetTable == null)
      elementsOffsetTable = Array.tabulate[Long](10)(i => _elementsOffset(i))

    if (length < 10)
      elementsOffsetTable(length)
    else
      _elementsOffset(length)
  }

  def elementsOffset(length: Code[Int]): Code[Long] = {
    // FIXME: incorporate table, maybe?
    _elementsOffset(length)
  }

  def contentsByteSize(length: Int): Long =
    elementsOffset(length) + length * elementByteSize

  def contentsByteSize(length: Code[Int]): Code[Long] = {
    elementsOffset(length) + length.toL * elementByteSize
  }

  def isElementDefined(region: MemoryBuffer, aoff: Long, i: Int): Boolean =
    elementType.required || !region.loadBit(aoff + 4, i)

  def isElementDefined(region: Code[MemoryBuffer], aoff: Code[Long], i: Code[Int]): Code[Boolean] =
    if (elementType.required)
      true
    else
      !region.loadBit(aoff + 4, i.toL)

  def setElementMissing(region: MemoryBuffer, aoff: Long, i: Int) {
    assert(!elementType.required)
    region.setBit(aoff + 4, i)
  }

  def setElementMissing(region: Code[MemoryBuffer], aoff: Code[Long], i: Code[Int]): Code[Unit] = {
    region.setBit(aoff + 4L, i.toL)
  }

  def elementOffset(aoff: Long, length: Int, i: Int): Long =
    aoff + elementsOffset(length) + i * elementByteSize

  def elementOffsetInRegion(region: MemoryBuffer, aoff: Long, i: Int): Long =
    elementOffset(aoff, loadLength(region, aoff), i)

  def elementOffset(aoff: Code[Long], length: Code[Int], i: Code[Int]): Code[Long] =
    aoff + elementsOffset(length) + i.toL * const(elementByteSize)

  def elementOffsetInRegion(region: Code[MemoryBuffer], aoff: Code[Long], i: Code[Int]): Code[Long] =
    elementOffset(aoff, loadLength(region, aoff), i)

  def loadElement(region: MemoryBuffer, aoff: Long, length: Int, i: Int): Long = {
    val off = elementOffset(aoff, length, i)
    elementType.fundamentalType match {
      case _: TArray | _: TBinary => region.loadAddress(off)
      case _ => off
    }
  }

  def loadElement(region: Code[MemoryBuffer], aoff: Code[Long], length: Code[Int], i: Code[Int]): Code[Long] = {
    val off = elementOffset(aoff, length, i)
    elementType.fundamentalType match {
      case _: TArray | _: TBinary => region.loadAddress(off)
      case _ => off
    }
  }

  def loadElement(region: MemoryBuffer, aoff: Long, i: Int): Long =
    loadElement(region, aoff, region.loadInt(aoff), i)

  def loadElement(region: Code[MemoryBuffer], aoff: Code[Long], i: Code[Int]): Code[Long] =
    loadElement(region, aoff, region.loadInt(aoff), i)

  def allocate(region: MemoryBuffer, length: Int): Long = {
    region.align(contentsAlignment)
    region.allocate(contentsByteSize(length))
  }

  def clearMissingBits(region: MemoryBuffer, aoff: Long, length: Int) {
    if (elementType.required)
      return
    val nMissingBytes = (length + 7) / 8
    var i = 0
    while (i < nMissingBytes) {
      region.storeByte(aoff + 4 + i, 0)
      i += 1
    }
  }

  def initialize(region: MemoryBuffer, aoff: Long, length: Int) {
    region.storeInt(aoff, length)
    clearMissingBits(region, aoff, length)
  }

  def initialize(region: Code[MemoryBuffer], aoff: Code[Long], length: Code[Int], a: LocalRef[Int]): Code[Unit] = {
    var c = region.storeInt32(aoff, length)
    if (elementType.required)
      return c
    Code(
      c,
      a.store((length + 7) >>> 3),
      Code.whileLoop(a > 0,
        Code(
          a.store(a - 1),
          region.storeByte(aoff + 4L + a.toL, const(0))
        )
      )
    )
  }

  override def unsafeOrdering(missingGreatest: Boolean): UnsafeOrdering = {
    val eltOrd = elementType.unsafeOrdering(missingGreatest)

    new UnsafeOrdering {
      override def compare(r1: MemoryBuffer, o1: Long, r2: MemoryBuffer, o2: Long): Int = {
        val length1 = loadLength(r1, o1)
        val length2 = loadLength(r2, o2)

        var i = 0
        while (i < math.min(length1, length2)) {
          val leftDefined = isElementDefined(r1, o1, i)
          val rightDefined = isElementDefined(r2, o2, i)

          if (leftDefined && rightDefined) {
            val eOff1 = loadElement(r1, o1, length1, i)
            val eOff2 = loadElement(r2, o2, length2, i)
            val c = eltOrd.compare(r1, eOff1, r2, eOff2)
            if (c != 0)
              return c
          } else if (leftDefined != rightDefined) {
            val c = if (leftDefined) -1 else 1
            if (missingGreatest)
              return c
            else
              return -c
          }
          i += 1
        }
        Integer.compare(length1, length2)
      }
    }
  }
}

abstract class TIterable extends TContainer {
  override def valuesSimilar(a1: Annotation, a2: Annotation, tolerance: Double): Boolean =
    a1 == a2 || (a1 != null && a2 != null
      && (a1.asInstanceOf[Iterable[_]].size == a2.asInstanceOf[Iterable[_]].size)
      && a1.asInstanceOf[Iterable[_]].zip(a2.asInstanceOf[Iterable[_]])
      .forall { case (e1, e2) => elementType.valuesSimilar(e1, e2, tolerance) })
}

final case class TArray(elementType: Type, override val required: Boolean = false) extends TIterable {
  val elementByteSize: Long = UnsafeUtils.arrayElementSize(elementType)

  val contentsAlignment: Long = elementType.alignment.max(4)

  override val fundamentalType: TArray = {
    if (elementType == elementType.fundamentalType)
      this
    else
      this.copy(elementType = elementType.fundamentalType)
  }

  def _toString = s"Array[$elementType]"

  override def canCompare(other: Type): Boolean = other match {
    case TArray(otherType, _) => elementType.canCompare(otherType)
    case _ => false
  }

  override def unify(concrete: Type): Boolean = {
    concrete match {
      case TArray(celementType, _) => elementType.unify(celementType)
      case _ => false
    }
  }

  override def subst() = TArray(elementType.subst())

  override def _pretty(sb: StringBuilder, indent: Int, printAttrs: Boolean, compact: Boolean = false) {
    sb.append("Array[")
    elementType.pretty(sb, indent, printAttrs, compact)
    sb.append("]")
  }

  def _typeCheck(a: Any): Boolean = a.isInstanceOf[IndexedSeq[_]] &&
    a.asInstanceOf[IndexedSeq[_]].forall(elementType.typeCheck)

  override def str(a: Annotation): String = JsonMethods.compact(toJSON(a))

  override def genNonmissingValue: Gen[Annotation] =
    Gen.buildableOf[Array, Annotation](elementType.genValue).map(x => x: IndexedSeq[Annotation])

  def ordering(missingGreatest: Boolean): Ordering[Annotation] =
    annotationOrdering(extendOrderingToNull(missingGreatest)(
      Ordering.Iterable(elementType.ordering(missingGreatest))))

  override def desc: String =
    """
    An ``Array`` is a collection of items that all have the same data type (ex: Int, String) and are indexed. Arrays can be constructed by specifying ``[item1, item2, ...]`` and they are 0-indexed.

    An example of constructing an array and accessing an element is:

    .. code-block:: text
        :emphasize-lines: 2

        let a = [1, 10, 3, 7] in a[1]
        result: 10

    They can also be nested such as Array[Array[Int]]:

    .. code-block:: text
        :emphasize-lines: 2

        let a = [[1, 2, 3], [4, 5], [], [6, 7]] in a[1]
        result: [4, 5]
    """

  override def scalaClassTag: ClassTag[IndexedSeq[AnyRef]] = classTag[IndexedSeq[AnyRef]]
}

final case class TSet(elementType: Type, override val required: Boolean = false) extends TIterable {
  val elementByteSize: Long = UnsafeUtils.arrayElementSize(elementType)

  val contentsAlignment: Long = elementType.alignment.max(4)

  override val fundamentalType: TArray = TArray(elementType.fundamentalType, required)

  def _toString = s"Set[$elementType]"

  override def canCompare(other: Type): Boolean = other match {
    case TSet(otherType, _) => elementType.canCompare(otherType)
    case _ => false
  }

  override def unify(concrete: Type): Boolean = concrete match {
    case TSet(celementType, _) => elementType.unify(celementType)
    case _ => false
  }

  override def subst() = TSet(elementType.subst())

  def _typeCheck(a: Any): Boolean =
    a.isInstanceOf[Set[_]] && a.asInstanceOf[Set[_]].forall(elementType.typeCheck)

  override def _pretty(sb: StringBuilder, indent: Int, printAttrs: Boolean, compact: Boolean = false) {
    sb.append("Set[")
    elementType.pretty(sb, indent, printAttrs, compact)
    sb.append("]")
  }

  def ordering(missingGreatest: Boolean): Ordering[Annotation] = {
    val elementSortOrd = elementType.ordering(true)
    val itOrd = Ordering.Iterable(elementType.ordering(missingGreatest))
    val setOrdering = new Ordering[Set[Annotation]] {
      def compare(x: Set[Annotation], y: Set[Annotation]): Int = {
        val s1 = x.toArray.sorted(elementSortOrd)
        val s2 = y.toArray.sorted(elementSortOrd)

        itOrd.compare(s1, s2)
      }
    }

    annotationOrdering(extendOrderingToNull(missingGreatest)(setOrdering))
  }

  override def str(a: Annotation): String = JsonMethods.compact(toJSON(a))

  override def genNonmissingValue: Gen[Annotation] = Gen.buildableOf[Set, Annotation](elementType.genValue)

  override def desc: String =
    """
    A ``Set`` is an unordered collection with no repeated values of a given data type (ex: Int, String). Sets can be constructed by specifying ``[item1, item2, ...].toSet()``.

    .. code-block:: text
        :emphasize-lines: 2

        let s = ["rabbit", "cat", "dog", "dog"].toSet()
        result: Set("cat", "dog", "rabbit")

    They can also be nested such as Set[Set[Int]]:

    .. code-block:: text
        :emphasize-lines: 2

        let s = [[1, 2, 3].toSet(), [4, 5, 5].toSet()].toSet()
        result: Set(Set(1, 2, 3), Set(4, 5))
    """

  override def scalaClassTag: ClassTag[Set[AnyRef]] = classTag[Set[AnyRef]]
}

final case class TDict(keyType: Type, valueType: Type, override val required: Boolean = false) extends TContainer {
  val elementType: Type = !TStruct("key" -> keyType, "value" -> valueType)

  val elementByteSize: Long = UnsafeUtils.arrayElementSize(elementType)

  val contentsAlignment: Long = elementType.alignment.max(4)

  override val fundamentalType: TArray = TArray(elementType.fundamentalType, required)

  override def canCompare(other: Type): Boolean = other match {
    case TDict(okt, ovt, _) => keyType.canCompare(okt) && valueType.canCompare(ovt)
    case _ => false
  }

  override def children = Seq(keyType, valueType)

  override def unify(concrete: Type): Boolean = {
    concrete match {
      case TDict(kt, vt, _) => keyType.unify(kt) && valueType.unify(vt)
      case _ => false
    }
  }

  override def subst() = TDict(keyType.subst(), valueType.subst())

  def _toString = s"Dict[$keyType, $valueType]"

  override def _pretty(sb: StringBuilder, indent: Int, printAttrs: Boolean, compact: Boolean = false) {
    sb.append("Dict[")
    keyType.pretty(sb, indent, printAttrs, compact)
    if (compact)
      sb += ','
    else
      sb.append(", ")
    valueType.pretty(sb, indent, printAttrs, compact)
    sb.append("]")
  }

  def _typeCheck(a: Any): Boolean = a == null || (a.isInstanceOf[Map[_, _]] &&
    a.asInstanceOf[Map[_, _]].forall { case (k, v) => keyType.typeCheck(k) && valueType.typeCheck(v) })

  override def str(a: Annotation): String = JsonMethods.compact(toJSON(a))

  override def genNonmissingValue: Gen[Annotation] =
    Gen.buildableOf2[Map, Annotation, Annotation](Gen.zip(keyType.genValue, valueType.genValue))

  override def valuesSimilar(a1: Annotation, a2: Annotation, tolerance: Double): Boolean =
    a1 == a2 || (a1 != null && a2 != null &&
      a1.asInstanceOf[Map[Any, _]].outerJoin(a2.asInstanceOf[Map[Any, _]])
        .forall { case (_, (o1, o2)) =>
          o1.liftedZip(o2).exists { case (v1, v2) => valueType.valuesSimilar(v1, v2, tolerance) }
        })

  override def desc: String =
    """
    A ``Dict`` is an unordered collection of key-value pairs. Each key can only appear once in the collection.
    """

  override def scalaClassTag: ClassTag[Map[_, _]] = classTag[Map[_, _]]

  def ordering(missingGreatest: Boolean): Ordering[Annotation] = {
    val elementSortOrd = elementType.ordering(true)
    val itOrd = Ordering.Iterable(elementType.ordering(missingGreatest))
    val dict = new Ordering[Map[Annotation, Annotation]] {
      def compare(x: Map[Annotation, Annotation], y: Map[Annotation, Annotation]): Int = {
        val s1 = x.toArray.map { case (k, v) => Row(k, v) }.sorted(elementSortOrd)
        val s2 = y.toArray.map { case (k, v) => Row(k, v) }.sorted(elementSortOrd)

        itOrd.compare(s1, s2)
      }
    }

    annotationOrdering(extendOrderingToNull(missingGreatest)(dict))
  }
}

sealed class TGenotype(override val required: Boolean) extends ComplexType {
  def _toString = "Genotype"

  val representation: TStruct = TGenotype.representation(required)

  def _typeCheck(a: Any): Boolean = a.isInstanceOf[Genotype]

  override def genNonmissingValue: Gen[Annotation] = Genotype.genNonmissingValue

  override def desc: String = "A ``Genotype`` is a Hail data type representing a genotype in the Variant Dataset. It is referred to as ``g`` in the expression language."

  override def scalaClassTag: ClassTag[Genotype] = classTag[Genotype]

  override def ordering(missingGreatest: Boolean): Ordering[Annotation] = {
    val rowOrd = fundamentalType.ordering(missingGreatest)
    val ord = new Ordering[Annotation] {
      def compare(x: Annotation, y: Annotation): Int = rowOrd.compare(
        Genotype.toRow(x.asInstanceOf[Genotype]),
        Genotype.toRow(y.asInstanceOf[Genotype]))
    }
    annotationOrdering(extendOrderingToNull(missingGreatest)(ord))
  }
}

object TGenotype {
  def apply(required: Boolean = false): TGenotype = if (required) TGenotypeRequired else TGenotypeOptional

  def unapply(t: TGenotype): Option[Boolean] = Option(t.required)

  def representation(required: Boolean = false): TStruct = {
    val t = TStruct(
      "gt" -> TInt32(),
      "ad" -> TArray(!TInt32()),
      "dp" -> TInt32(),
      "gq" -> TInt32(),
      "pl" -> TArray(!TInt32()))
    
    t.setRequired(required).asInstanceOf[TStruct]
  }
  
  def representationWithVCFAttributes(required: Boolean = false): TStruct = {
    val t = TStruct(IndexedSeq(
      Field("GT", TCall(), 0,
        Map("Number" -> "1", "Type" -> "String", "Description" -> "Genotype")),
      Field("AD", TArray(TInt32()), 1,
        Map("Number" -> "R", "Type" -> "Integer",
          "Description" -> "Allelic depths for the ref and alt alleles in the order listed")),
      Field("DP", TInt32(), 2,
        Map("Number" -> "1", "Type" -> "Integer", "Description" -> "Read Depth")),
      Field("GQ", TInt32(), 3,
        Map("Number" -> "1", "Type" -> "Integer", "Description" -> "Genotype Quality")),
      Field("PL", TArray(TInt32()), 4,
        Map("Number" -> "G", "Type" -> "Integer",
          "Description" -> "Normalized, Phred-scaled likelihoods for genotypes as defined in the VCF specification"))))
    
    t.setRequired(required).asInstanceOf[TStruct]
  }
}

case object TGenotypeOptional extends TGenotype(false)

case object TGenotypeRequired extends TGenotype(true)

sealed class TCall(override val required: Boolean) extends ComplexType {
  def _toString = "Call"

  val representation: Type = TCall.representation(required)

  def _typeCheck(a: Any): Boolean = a.isInstanceOf[Int]

  override def genNonmissingValue: Gen[Annotation] = Call.genNonmissingValue

  override def desc: String = "A ``Call`` is a Hail data type representing a genotype call (ex: 0/0) in the Variant Dataset."

  override def scalaClassTag: ClassTag[java.lang.Integer] = classTag[java.lang.Integer]

  override def ordering(missingGreatest: Boolean): Ordering[Annotation] =
    annotationOrdering(
      extendOrderingToNull(missingGreatest)(implicitly[Ordering[Int]]))
}

object TCall {
  def apply(required: Boolean = false): TCall = if (required) TCallRequired else TCallOptional

  def unapply(t: TCall): Option[Boolean] = Option(t.required)

  def representation(required: Boolean = false): Type = if (required) !TInt32() else TInt32()
}

case object TCallOptional extends TCall(false)

case object TCallRequired extends TCall(true)

sealed class TAltAllele(override val required: Boolean) extends ComplexType {
  def _toString = "AltAllele"

  def _typeCheck(a: Any): Boolean = a.isInstanceOf[AltAllele]

  override def genNonmissingValue: Gen[Annotation] = AltAllele.gen

  override def desc: String = "An ``AltAllele`` is a Hail data type representing an alternate allele in the Variant Dataset."

  override def scalaClassTag: ClassTag[AltAllele] = classTag[AltAllele]

  override def ordering(missingGreatest: Boolean): Ordering[Annotation] =
    annotationOrdering(
      extendOrderingToNull(missingGreatest)(implicitly[Ordering[AltAllele]]))

  val representation: TStruct = TAltAllele.representation(required)
}

object TAltAllele {
  def apply(required: Boolean = false): TAltAllele = if (required) TAltAlleleRequired else TAltAlleleOptional
  def unapply(t: TAltAllele): Option[Boolean] = Option(t.required)

  def representation(required: Boolean = false): TStruct = {
    val t = TStruct(
      "ref" -> !TString(),
      "alt" -> !TString())
    if (required) (!t).asInstanceOf[TStruct] else t
  }

}

case object TAltAlleleOptional extends TAltAllele(false)

case object TAltAlleleRequired extends TAltAllele(true)

object TVariant {
  def representation(required: Boolean = false): TStruct = {
  	val rep = TStruct(
    "contig" -> !TString(),
    "start" -> !TInt32(),
    "ref" -> !TString(),
    "altAlleles" -> !TArray(!TAltAllele().representation))
    if (required) (!rep).asInstanceOf[TStruct] else rep
  }
}

case class TVariant(gr: GRBase, override val required: Boolean = false) extends ComplexType {
  def _toString = s"""Variant($gr)"""

  def _typeCheck(a: Any): Boolean = a.isInstanceOf[Variant]

  override def genNonmissingValue: Gen[Annotation] = Variant.gen

  override def desc: String =
    """
    A ``Variant(GR)`` is a Hail data type representing a variant in the dataset. It is parameterized by a genome reference (GR) such as GRCh37 or GRCh38. It is referred to as ``v`` in the expression language.

    The `pseudoautosomal region <https://en.wikipedia.org/wiki/Pseudoautosomal_region>`_ (PAR) is currently defined with respect to reference `GRCh37 <http://www.ncbi.nlm.nih.gov/projects/genome/assembly/grc/human/>`_:

    - X: 60001 - 2699520, 154931044 - 155260560
    - Y: 10001 - 2649520, 59034050 - 59363566

    Most callers assign variants in PAR to X.
    """

  override def scalaClassTag: ClassTag[Variant] = classTag[Variant]

  override def ordering(missingGreatest: Boolean): Ordering[Annotation] =
    annotationOrdering(
      extendOrderingToNull(missingGreatest)(implicitly[Ordering[Variant]]))

  override val partitionKey: Type = TLocus(gr)

  override def typedOrderedKey[PK, K]: OrderedKey[PK, K] = new OrderedKey[PK, K] {
    def project(key: K): PK = key.asInstanceOf[Variant].locus.asInstanceOf[PK]

    val kOrd: Ordering[K] = ordering(missingGreatest = true).asInstanceOf[Ordering[K]]

    val pkOrd: Ordering[PK] = TLocus(gr).ordering(missingGreatest = true).asInstanceOf[Ordering[PK]]

    val kct: ClassTag[K] = classTag[Variant].asInstanceOf[ClassTag[K]]

    val pkct: ClassTag[PK] = classTag[Locus].asInstanceOf[ClassTag[PK]]
  }

  override def orderedKey: OrderedKey[Annotation, Annotation] = new OrderedKey[Annotation, Annotation] {
    def project(key: Annotation): Annotation = key.asInstanceOf[Variant].locus

    val kOrd: Ordering[Annotation] = ordering(missingGreatest = true)

    val pkOrd: Ordering[Annotation] = TLocus(gr).ordering(missingGreatest = true)

    val kct: ClassTag[Annotation] = classTag[Annotation]

    val pkct: ClassTag[Annotation] = classTag[Annotation]
  }

  // FIXME: Remove when representation of contig/position is a naturally-ordered Long
  override def unsafeOrdering(missingGreatest: Boolean): UnsafeOrdering = {
    val fundamentalComparators = representation.fields.map(_.typ.unsafeOrdering(missingGreatest)).toArray
    val repr = representation.fundamentalType
    new UnsafeOrdering {
      def compare(r1: MemoryBuffer, o1: Long, r2: MemoryBuffer, o2: Long): Int = {
        val cOff1 = repr.loadField(r1, o1, 0)
        val cOff2 = repr.loadField(r2, o2, 0)

        val contig1 = TString.loadString(r1, cOff1)
        val contig2 = TString.loadString(r2, cOff2)

        val c = Contig.compare(contig1, contig2)
        if (c != 0)
          return c

        var i = 1
        while (i < representation.size) {
          val fOff1 = repr.loadField(r1, o1, i)
          val fOff2 = repr.loadField(r2, o2, i)

          val c = fundamentalComparators(i).compare(r1, fOff1, r2, fOff2)
          if (c != 0)
            return c

          i += 1
        }
        0
      }
    }
  }

  val representation: TStruct = TVariant.representation(required)

  override def unify(concrete: Type): Boolean = concrete match {
    case TVariant(cgr, _) => gr.unify(cgr)
    case _ => false
  }

  override def clear(): Unit = gr.clear()

  override def subst() = gr.subst().variant
}

object TLocus {
  def representation(required: Boolean = false): TStruct = {
    val rep = TStruct(
      "contig" -> !TString(),
      "position" -> !TInt32())
    if (required) (!rep).asInstanceOf[TStruct] else rep
  }
}

case class TLocus(gr: GRBase, override val required: Boolean = false) extends ComplexType {
  def _toString = s"Locus($gr)"

  def _typeCheck(a: Any): Boolean = a.isInstanceOf[Locus]

  override def genNonmissingValue: Gen[Annotation] = Locus.gen

  override def desc: String = "A ``Locus(GR)`` is a Hail data type representing a specific genomic location in the Variant Dataset. It is parameterized by a genome reference (GR) such as GRCh37 or GRCh38."

  override def scalaClassTag: ClassTag[Locus] = classTag[Locus]

  override def ordering(missingGreatest: Boolean): Ordering[Annotation] =
    annotationOrdering(
      extendOrderingToNull(missingGreatest)(implicitly[Ordering[Locus]]))

  // FIXME: Remove when representation of contig/position is a naturally-ordered Long
  override def unsafeOrdering(missingGreatest: Boolean): UnsafeOrdering = {
    val repr = representation.fundamentalType

    new UnsafeOrdering {
      def compare(r1: MemoryBuffer, o1: Long, r2: MemoryBuffer, o2: Long): Int = {
        val cOff1 = repr.loadField(r1, o1, 0)
        val cOff2 = repr.loadField(r2, o2, 0)

        val contig1 = TString.loadString(r1, cOff1)
        val contig2 = TString.loadString(r2, cOff2)

        val c = Contig.compare(contig1, contig2)
        if (c != 0)
          return c

        val posOff1 = repr.loadField(r1, o1, 1)
        val posOff2 = repr.loadField(r2, o2, 1)
        java.lang.Integer.compare(r1.loadInt(posOff1), r2.loadInt(posOff2))
      }
    }
  }

  val representation: TStruct = TLocus.representation(required)

  override def unify(concrete: Type): Boolean = concrete match {
    case TLocus(cgr, _) => gr.unify(cgr)
    case _ => false
  }

  override def clear(): Unit = gr.clear()

  override def subst() = gr.subst().locus
}

object TInterval {
  def representation(required: Boolean = false): TStruct = {
    val rep = TStruct(
      "start" -> !TLocus.representation(),
      "end" -> !TLocus.representation())
    if (required) (!rep).asInstanceOf[TStruct] else rep
  }
}

case class TInterval(gr: GRBase, override val required: Boolean = false) extends ComplexType {
  def _toString = s"""Interval($gr)"""

  def _typeCheck(a: Any): Boolean = a.isInstanceOf[Interval[_]] && a.asInstanceOf[Interval[_]].end.isInstanceOf[Locus]

  override def genNonmissingValue: Gen[Annotation] = Interval.gen(Locus.gen)

  override def desc: String = "An ``Interval(GR)`` is a Hail data type representing a range of genomic locations in the dataset. It is parameterized by a genome reference (GR) such as GRCh37 or GRCh38."

  override def scalaClassTag: ClassTag[Interval[Locus]] = classTag[Interval[Locus]]

  override def ordering(missingGreatest: Boolean): Ordering[Annotation] =
    annotationOrdering(
      extendOrderingToNull(missingGreatest)(implicitly[Ordering[Interval[Locus]]]))

  // FIXME: Remove when representation of contig/position is a naturally-ordered Long
  override def unsafeOrdering(missingGreatest: Boolean): UnsafeOrdering = {
    val locusOrd = TLocus(gr).unsafeOrdering(missingGreatest)
    new UnsafeOrdering {
      def compare(r1: MemoryBuffer, o1: Long, r2: MemoryBuffer, o2: Long): Int = {
        val sOff1 = representation.loadField(r1, o1, 0)
        val sOff2 = representation.loadField(r2, o2, 0)

        val c1 = locusOrd.compare(r1, sOff1, r2, sOff2)
        if (c1 != 0)
          return c1

        val eOff1 = representation.loadField(r1, o1, 1)
        val eOff2 = representation.loadField(r2, o2, 1)

        locusOrd.compare(r1, eOff1, r2, eOff2)
      }
    }
  }

  val representation: TStruct = TInterval.representation(required)

  override def unify(concrete: Type): Boolean = concrete match {
    case TInterval(cgr, _) => gr.unify(cgr)
    case _ => false
  }

  override def clear(): Unit = gr.clear()

  override def subst() = gr.subst().interval
}

final case class Field(name: String, typ: Type,
  index: Int,
  attrs: Map[String, String] = Map.empty) {
  def attr(s: String): Option[String] = attrs.get(s)

  def attrsJava(): java.util.Map[String, String] = attrs.asJava

  def unify(cf: Field): Boolean =
    name == cf.name &&
      typ.unify(cf.typ) &&
      index == cf.index &&
      attrs == cf.attrs

  def pretty(sb: StringBuilder, indent: Int, printAttrs: Boolean, compact: Boolean) {
    if (compact) {
      sb.append(prettyIdentifier(name))
      sb.append(":")
    } else {
      sb.append(" " * indent)
      sb.append(prettyIdentifier(name))
      sb.append(": ")
    }
    typ.pretty(sb, indent, printAttrs, compact)
    if (printAttrs) {
      attrs.foreach { case (k, v) =>
        if (!compact) {
          sb += '\n'
          sb.append(" " * (indent + 2))
        }
        sb += '@'
        sb.append(prettyIdentifier(k))
        sb.append("=\"")
        sb.append(StringEscapeUtils.escapeString(v))
        sb += '"'
      }
    }
  }
}

object TStruct {
  private val requiredEmpty = TStruct(Array.empty[Field], true)
  private val optionalEmpty = TStruct(Array.empty[Field], false)

  def empty(required: Boolean = false): TStruct = if (required) requiredEmpty else optionalEmpty

  def apply(args: (String, Type)*): TStruct =
    TStruct(args
      .iterator
      .zipWithIndex
      .map { case ((n, t), i) => Field(n, t, i) }
      .toArray)

  def apply(names: java.util.ArrayList[String], types: java.util.ArrayList[Type], required: Boolean): TStruct = {
    val sNames = names.asScala.toArray
    val sTypes = types.asScala.toArray
    if (sNames.length != sTypes.length)
      fatal(s"number of names does not match number of types: found ${ sNames.length } names and ${ sTypes.length } types")

    val t = TStruct(sNames.zip(sTypes): _*)
    if (required)
      (!t).asInstanceOf[TStruct]
    else t
  }
}

final case class TStruct(fields: IndexedSeq[Field], override val required: Boolean = false) extends Type {
  assert(fields.zipWithIndex.forall { case (f, i) => f.index == i })

  override def children: Seq[Type] = fields.map(_.typ)

  override def canCompare(other: Type): Boolean = other match {
    case t: TStruct => size == t.size && fields.zip(t.fields).forall { case (f1, f2) =>
      f1.name == f2.name && f1.typ.canCompare(f2.typ)
    }
    case _ => false
  }

  override def unify(concrete: Type): Boolean = concrete match {
    case TStruct(cfields, _) =>
      fields.length == cfields.length &&
        (fields, cfields).zipped.forall { case (f, cf) =>
          f.unify(cf)
        }
    case _ => false
  }

  override def subst() = TStruct(fields.map(f => f.copy(typ = f.typ.subst().asInstanceOf[Type])))

  val fieldIdx: Map[String, Int] =
    fields.map(f => (f.name, f.index)).toMap

  val fieldNames: Array[String] = fields.map(_.name).toArray

  def index(str: String): Option[Int] = fieldIdx.get(str)

  def selfField(name: String): Option[Field] = fieldIdx.get(name).map(i => fields(i))

  def hasField(name: String): Boolean = fieldIdx.contains(name)

  def field(name: String): Field = fields(fieldIdx(name))

  def fieldType(i: Int): Type = fields(i).typ

  def size: Int = fields.length

  override def getOption(path: List[String]): Option[Type] =
    if (path.isEmpty)
      Some(this)
    else
      selfField(path.head).map(_.typ).flatMap(t => t.getOption(path.tail))

  override def fieldOption(path: List[String]): Option[Field] =
    if (path.isEmpty)
      None
    else {
      val f = selfField(path.head)
      if (path.length == 1)
        f
      else
        f.flatMap(_.typ.fieldOption(path.tail))
    }

  def updateFieldAttributes(path: List[String], f: Map[String, String] => Map[String, String]): TStruct = {

    if (path.isEmpty)
      throw new AnnotationPathException(s"Empty path for attribute annotation is not allowed.")

    if (!hasField(path.head))
      throw new AnnotationPathException(s"struct has no field ${ path.head }")

    copy(fields.map {
      field =>
        if (field.name == path.head) {
          if (path.length == 1)
            field.copy(attrs = f(field.attrs))
          else {
            field.typ match {
              case struct: TStruct => field.copy(typ = struct.updateFieldAttributes(path.tail, f))
              case t => fatal(s"Field ${ field.name } is not a Struct and cannot contain field ${ path.tail.mkString(".") }")
            }
          }
        }
        else
          field
    })
  }

  def setFieldAttributes(path: List[String], kv: Map[String, String]): TStruct = {
    updateFieldAttributes(path, attributes => attributes ++ kv)
  }

  def deleteFieldAttribute(path: List[String], attr: String): TStruct = {
    updateFieldAttributes(path, attributes => attributes - attr)
  }

  override def queryTyped(p: List[String]): (Type, Querier) = {
    if (p.isEmpty)
      (this, identity[Annotation])
    else {
      selfField(p.head) match {
        case Some(f) =>
          val (t, q) = f.typ.queryTyped(p.tail)
          val localIndex = f.index
          (t, (a: Any) =>
            if (a == null)
              null
            else
              q(a.asInstanceOf[Row].get(localIndex)))
        case None => throw new AnnotationPathException(s"struct has no field ${ p.head }")
      }
    }
  }

  override def delete(p: List[String]): (Type, Deleter) = {
    if (p.isEmpty)
      (TStruct.empty(), a => null)
    else {
      val key = p.head
      val f = selfField(key) match {
        case Some(f) => f
        case None => throw new AnnotationPathException(s"$key not found")
      }
      val index = f.index
      val (newFieldType, d) = f.typ.delete(p.tail)
      val newType: Type =
        if (newFieldType == TStruct.empty())
          deleteKey(key, f.index)
        else
          updateKey(key, f.index, newFieldType)

      val localDeleteFromRow = newFieldType == TStruct.empty()

      val deleter: Deleter = { a =>
        if (a == null)
          null
        else {
          val r = a.asInstanceOf[Row]

          if (localDeleteFromRow)
            r.delete(index)
          else
            r.update(index, d(r.get(index)))
        }
      }
      (newType, deleter)
    }
  }

  override def unsafeInsert(typeToInsert: Type, path: List[String]): (Type, UnsafeInserter) = {
    if (path.isEmpty) {
      (typeToInsert, (region, offset, rvb, inserter) => inserter())
    } else {
      val localSize = size
      val key = path.head
      selfField(key) match {
        case Some(f) =>
          val j = f.index
          val (insertedFieldType, fieldInserter) = f.typ.unsafeInsert(typeToInsert, path.tail)

          (updateKey(key, j, insertedFieldType), { (region, offset, rvb, inserter) =>
            rvb.startStruct()
            var i = 0
            while (i < j) {
              rvb.addField(this, region, offset, i)
              i += 1
            }
            fieldInserter(region, loadField(region, offset, j), rvb, inserter)
            i += 1
            while (i < localSize) {
              rvb.addField(this, region, offset, i)
              i += 1
            }
            rvb.endStruct()
          })

        case None =>
          val (insertedFieldType, fieldInserter) = TStruct.empty().unsafeInsert(typeToInsert, path.tail)

          (appendKey(key, insertedFieldType), { (region, offset, rvb, inserter) =>
            rvb.startStruct()
            var i = 0
            while (i < localSize) {
              rvb.addField(this, region, offset, i)
              i += 1
            }
            fieldInserter(null, 0, rvb, inserter)
            rvb.endStruct()
          })
      }
    }
  }

  override def insert(signature: Type, p: List[String]): (Type, Inserter) = {
    if (p.isEmpty)
      (signature, (a, toIns) => toIns)
    else {
      val key = p.head
      val f = selfField(key)
      val keyIndex = f.map(_.index)
      val (newKeyType, keyF) = f
        .map(_.typ)
        .getOrElse(TStruct.empty())
        .insert(signature, p.tail)

      val newSignature = keyIndex match {
        case Some(i) => updateKey(key, i, newKeyType)
        case None => appendKey(key, newKeyType)
      }

      val localSize = fields.size

      val inserter: Inserter = (a, toIns) => {
        val r = if (a == null || localSize == 0) // localsize == 0 catches cases where we overwrite a path
          Row.fromSeq(Array.fill[Any](localSize)(null))
        else
          a.asInstanceOf[Row]
        keyIndex match {
          case Some(i) => r.update(i, keyF(r.get(i), toIns))
          case None => r.append(keyF(null, toIns))
        }
      }
      (newSignature, inserter)
    }
  }

  def updateKey(key: String, i: Int, sig: Type): Type = {
    assert(fieldIdx.contains(key))

    val newFields = Array.fill[Field](fields.length)(null)
    for (i <- fields.indices)
      newFields(i) = fields(i)
    newFields(i) = Field(key, sig, i)
    TStruct(newFields)
  }

  def deleteKey(key: String, index: Int): Type = {
    assert(fieldIdx.contains(key))
    if (fields.length == 1)
      TStruct.empty()
    else {
      val newFields = Array.fill[Field](fields.length - 1)(null)
      for (i <- 0 until index)
        newFields(i) = fields(i)
      for (i <- index + 1 until fields.length)
        newFields(i - 1) = fields(i).copy(index = i - 1)
      TStruct(newFields)
    }
  }

  def appendKey(key: String, sig: Type): TStruct = {
    assert(!fieldIdx.contains(key))
    val newFields = Array.fill[Field](fields.length + 1)(null)
    for (i <- fields.indices)
      newFields(i) = fields(i)
    newFields(fields.length) = Field(key, sig, fields.length)
    TStruct(newFields)
  }

  def merge(other: TStruct): (TStruct, Merger) = {
    val intersect = fields.map(_.name).toSet
      .intersect(other.fields.map(_.name).toSet)

    if (intersect.nonEmpty)
      fatal(
        s"""Invalid merge operation: cannot merge structs with same-name ${ plural(intersect.size, "field") }
           |  Found these fields in both structs: [ ${
          intersect.map(s => prettyIdentifier(s)).mkString(", ")
        } ]
           |  Hint: use `drop' or `select' to remove these fields from one side""".stripMargin)

    val newStruct = TStruct(fields ++ other.fields.map(f => f.copy(index = f.index + size)))

    val size1 = size
    val size2 = other.size
    val targetSize = newStruct.size

    val merger = (a1: Annotation, a2: Annotation) => {
      if (a1 == null && a2 == null)
        null
      else {
        val s1 = Option(a1).map(_.asInstanceOf[Row].toSeq)
          .getOrElse(Seq.fill[Any](size1)(null))
        val s2 = Option(a2).map(_.asInstanceOf[Row].toSeq)
          .getOrElse(Seq.fill[Any](size2)(null))
        val newValues = s1 ++ s2
        assert(newValues.size == targetSize)
        Annotation.fromSeq(newValues)
      }
    }

    (newStruct, merger)
  }

  def filter(set: Set[String], include: Boolean = true): (TStruct, Deleter) = {
    val notFound = set.filter(name => selfField(name).isEmpty).map(prettyIdentifier)
    if (notFound.nonEmpty)
      fatal(
        s"""invalid struct filter operation: ${
          plural(notFound.size, s"field ${ notFound.head }", s"fields [ ${ notFound.mkString(", ") } ]")
        } not found
           |  Existing struct fields: [ ${ fields.map(f => prettyIdentifier(f.name)).mkString(", ") } ]""".stripMargin)

    val fn = (f: Field) =>
      if (include)
        set.contains(f.name)
      else
        !set.contains(f.name)
    filter(fn)
  }

  def ungroup(identifier: String, mangle: Boolean = false): (TStruct, (Row) => Row) = {
    val overlappingNames = mutable.Set[String]()
    val fieldNamesSet = fieldNames.toSet

    val ungroupedFields = fieldOption(identifier) match {
      case None => fatal(s"Struct does not have field with name `$identifier'.")
      case Some(x) => x.typ match {
        case s: TStruct =>
          s.fields.flatMap { fd =>
            (fieldNamesSet.contains(fd.name), mangle) match {
              case (_, true) => Some((identifier + "." + fd.name, fd.typ))
              case (false, false) => Some((fd.name, fd.typ))
              case (true, false) =>
                overlappingNames += fd.name
                None
            }
          }.toArray
        case other => fatal(s"Can only ungroup fields of type Struct, but found type ${ other.toPrettyString(compact = true) } for identifier $identifier.")
      }
    }

    if (overlappingNames.nonEmpty)
      fatal(s"Found ${ overlappingNames.size } ${ plural(overlappingNames.size, "ungrouped field name") } overlapping existing struct field names.\n" +
        "Either rename manually or use the 'mangle' option to handle duplicates.\n Overlapping fields:\n  " +
        s"@1", overlappingNames.truncatable("\n  "))

    val fdIndexToUngroup = fieldIdx(identifier)

    val newSignature = TStruct(fields.filterNot(_.index == fdIndexToUngroup).map { fd => (fd.name, fd.typ) } ++ ungroupedFields: _*)

    val origSize = size
    val newSize = newSignature.size

    val ungrouper = (r: Row) => {
      val result = Array.fill[Any](newSize)(null)

      if (r != null) {
        var localIdx = 0

        var i = 0
        while (i < origSize) {
          if (i != fdIndexToUngroup) {
            result(localIdx) = r.get(i)
            localIdx += 1
          }
          i += 1
        }

        val ugr = r.getAs[Row](fdIndexToUngroup)

        if (ugr != null) {
          var j = 0
          while (j < ungroupedFields.length) {
            result(localIdx) = ugr.get(j)
            j += 1
            localIdx += 1
          }
        }
      }

      Row.fromSeq(result)
    }

    (newSignature, ungrouper)
  }

  def group(dest: String, names: Array[String]): (TStruct, (Row) => Row) = {
    val fieldsToGroup = names.zipWithIndex.map { case (name, i) =>
      fieldOption(name) match {
        case None => fatal(s"Struct does not have field with name `$name'.")
        case Some(fd) => fd
      }
    }

    val keepFields = fields.filterNot(fd => names.contains(fd.name) || fd.name == dest)
    val keepIndices = keepFields.map(_.index)

    val groupedTyp = TStruct(fieldsToGroup.map(fd => (fd.name, fd.typ)): _*)
    val finalSignature = TStruct(keepFields.map(fd => (fd.name, fd.typ)) :+ (dest, groupedTyp): _*)

    val newSize = finalSignature.size

    val grouper = (r: Row) => {
      val result = Array.fill[Any](newSize)(null)

      if (r != null) {
        var localIdx = 0
        keepIndices.foreach { i =>
          result(localIdx) = r.get(i)
          localIdx += 1
        }

        assert(localIdx == newSize - 1)
        result(localIdx) = Row.fromSeq(fieldsToGroup.map(fd => r.get(fd.index)))
      }

      Row.fromSeq(result)
    }

    (finalSignature, grouper)
  }

  def parseInStructScope[T >: Null](code: String)(implicit hr: HailRep[T]): (Annotation) => T = {
    val ec = EvalContext(fields.map(f => (f.name, f.typ)): _*)
    val f = Parser.parseTypedExpr[T](code, ec)

    (a: Annotation) => {
      if (a == null)
        null
      else {
        ec.setAllFromRow(a.asInstanceOf[Row])
        f()
      }
    }
  }

  def ++(that: TStruct): TStruct = {
    val overlapping = fields.map(_.name).toSet.intersect(
      that.fields.map(_.name).toSet)
    if (overlapping.nonEmpty)
      fatal(s"overlapping fields in struct concatenation: ${ overlapping.mkString(", ") }")

    TStruct(fields.map(f => (f.name, f.typ)) ++ that.fields.map(f => (f.name, f.typ)): _*)
  }

  def filter(f: (Field) => Boolean): (TStruct, (Annotation) => Annotation) = {
    val included = fields.map(f)

    val newFields = fields.zip(included)
      .flatMap { case (field, incl) =>
        if (incl)
          Some(field)
        else
          None
      }

    val newSize = newFields.size

    val filterer = (a: Annotation) =>
      if (a == null)
        a
      else if (newSize == 0)
        null
      else {
        val r = a.asInstanceOf[Row]
        val newValues = included.zipWithIndex
          .flatMap {
            case (incl, i) =>
              if (incl)
                Some(r.get(i))
              else None
          }
        assert(newValues.length == newSize)
        Annotation.fromSeq(newValues)
      }

    (TStruct(newFields.zipWithIndex.map { case (f, i) => f.copy(index = i) }), filterer)
  }

  def _toString: String = if (size == 0) "Empty" else toPrettyString(compact = true)

  override def _pretty(sb: StringBuilder, indent: Int, printAttrs: Boolean, compact: Boolean) {
    if (size == 0)
      sb.append("Empty")
    else {
      if (compact) {
        sb.append("Struct{")
        fields.foreachBetween(_.pretty(sb, indent, printAttrs, compact))(sb += ',')
        sb += '}'
      } else {
        sb.append("Struct{")
        sb += '\n'
        fields.foreachBetween(_.pretty(sb, indent + 4, printAttrs, compact))(sb.append(",\n"))
        sb += '\n'
        sb.append(" " * indent)
        sb += '}'
      }
    }
  }

  override def _typeCheck(a: Any): Boolean =
    a.isInstanceOf[Row] && {
      val r = a.asInstanceOf[Row]
      r.length == fields.length &&
        r.toSeq.zip(fields).forall {
          case (v, f) => f.typ.typeCheck(v)
        }
    }

  override def str(a: Annotation): String = JsonMethods.compact(toJSON(a))

  override def genNonmissingValue: Gen[Annotation] = {
    if (size == 0) {
      Gen.const(Annotation.empty)
    } else
      Gen.size.flatMap(fuel =>
        if (size > fuel)
          Gen.uniformSequence(fields.map(f => if (isFieldRequired(f.index)) f.typ.genValue else Gen.const(null))).map(a => Annotation(a: _*))
        else
          Gen.uniformSequence(fields.map(f => f.typ.genValue)).map(a => Annotation(a: _*)))
  }

  override def valuesSimilar(a1: Annotation, a2: Annotation, tolerance: Double): Boolean =
    a1 == a2 || (a1 != null && a2 != null
      && fields.zip(a1.asInstanceOf[Row].toSeq).zip(a2.asInstanceOf[Row].toSeq)
      .forall {
        case ((f, x1), x2) =>
          f.typ.valuesSimilar(x1, x2, tolerance)
      })

  override def desc: String =
    """
    A ``Struct`` is like a Python tuple where the fields are named and the set of fields is fixed.

    An example of constructing and accessing the fields in a ``Struct`` is

    .. code-block:: text
        :emphasize-lines: 2

        let s = {gene: "ACBD", function: "LOF", nHet: 12} in s.gene
        result: "ACBD"

    A field of the ``Struct`` can also be another ``Struct``. For example, ``va.info.AC`` selects the struct ``info`` from the struct ``va``, and then selects the array ``AC`` from the struct ``info``.
    """

  override def scalaClassTag: ClassTag[Row] = classTag[Row]

  override def ordering(missingGreatest: Boolean): Ordering[Annotation] = {
    val fieldOrderings = fields.map(f => f.typ.ordering(missingGreatest))

    annotationOrdering(
      extendOrderingToNull(missingGreatest)(new Ordering[Row] {
        def compare(a: Row, b: Row): Int = {
          var i = 0
          while (i < a.size) {
            val c = fieldOrderings(i).compare(a.get(i), b.get(i))
            if (c != 0)
              return c

            i += 1
          }

          // equal
          0
        }
      }))
  }

  override def unsafeOrdering(missingGreatest: Boolean): UnsafeOrdering = {
    val fieldOrderings = fields.map(_.typ.unsafeOrdering(missingGreatest)).toArray

    new UnsafeOrdering {
      def compare(r1: MemoryBuffer, o1: Long, r2: MemoryBuffer, o2: Long): Int = {
        var i = 0
        while (i < size) {
          val leftDefined = isFieldDefined(r1, o1, i)
          val rightDefined = isFieldDefined(r2, o2, i)

          if (leftDefined && rightDefined) {
            val c = fieldOrderings(i).compare(r1, loadField(r1, o1, i), r2, loadField(r2, o2, i))
            if (c != 0)
              return c
          } else if (leftDefined != rightDefined) {
            val c = if (leftDefined) -1 else 1
            if (missingGreatest)
              return c
            else
              return -c
          }
          i += 1
        }
        0
      }
    }
  }

  def select(keep: Array[String]): (TStruct, (Row) => Row) = {
    val t = TStruct(keep.map { n =>
      n -> field(n).typ
    }: _*)

    val keepIdx = keep.map(fieldIdx)
    val selectF: Row => Row = { r =>
      Row.fromSeq(keepIdx.map(r.get))
    }
    (t, selectF)
  }

  val (missingIdx, nMissing) = {
    var i = 0
    val a = new Array[Int](size)
    fields.foreach { f =>
      a(f.index) = i
      if (!isFieldRequired(f.index))
        i += 1
    }
    (a, i)
  }
  def nMissingBytes: Int = (nMissing + 7) >>> 3

  var byteOffsets: Array[Long] = _
  override val byteSize: Long = {
    val a = new Array[Long](size)

    val bp = new BytePacker()

    var offset: Long = nMissingBytes
    fields.foreach { f =>
      val fSize = f.typ.byteSize
      val fAlignment = f.typ.alignment

      bp.getSpace(fSize, fAlignment) match {
        case Some(start) =>
          a(f.index) = start
        case None =>
          val mod = offset % fAlignment
          if (mod != 0) {
            val shift = fAlignment - mod
            bp.insertSpace(shift, offset)
            offset += (fAlignment - mod)
          }
          a(f.index) = offset
          offset += fSize
      }
    }
    byteOffsets = a
    offset
  }

  override val alignment: Long = {
    if (fields.isEmpty)
      1
    else
      fields.map(_.typ.alignment).max
  }

  override val fundamentalType: TStruct = {
    val fundamentalFieldTypes = fields.map(f => f.typ.fundamentalType)
    if ((fields, fundamentalFieldTypes).zipped
      .forall { case (f, ft) => f.typ == ft })
      this
    else {
      val t = TStruct((fields, fundamentalFieldTypes).zipped.map { case (f, ft) => (f.name, ft) }: _*)
      if (required) (!t).asInstanceOf[TStruct] else t

    }
  }

  def allocate(region: MemoryBuffer): Long = {
    region.align(alignment)
    region.allocate(byteSize)
  }

  def clearMissingBits(region: MemoryBuffer, off: Long) {
    var i = 0
    while (i < nMissingBytes) {
      region.storeByte(off + i, 0)
      i += 1
    }
  }

  def clearMissingBits(region: Code[MemoryBuffer], off: Code[Long]): Code[Unit] = {
    var c: Code[Unit] = Code._empty
    var i = 0
    while (i < nMissingBytes) {
      c = Code(c, region.storeByte(off + i.toLong, const(0)))
      i += 1
    }
    c
  }

  def isFieldRequired(fieldIdx: Int): Boolean = fieldType(fieldIdx).required

  def isFieldDefined(rv: RegionValue, fieldIdx: Int): Boolean =
    isFieldDefined(rv.region, rv.offset, fieldIdx)

  def isFieldDefined(region: MemoryBuffer, offset: Long, fieldIdx: Int): Boolean =
     isFieldRequired(fieldIdx) || !region.loadBit(offset, missingIdx(fieldIdx))

  def isFieldDefined(region: Code[MemoryBuffer], offset: Code[Long], fieldIdx: Int): Code[Boolean] =
    if (isFieldRequired(fieldIdx))
      true
    else
      !region.loadBit(offset, missingIdx(fieldIdx))

  def setFieldMissing(region: MemoryBuffer, offset: Long, fieldIdx: Int) {
    assert(!isFieldRequired(fieldIdx))
    region.setBit(offset, missingIdx(fieldIdx))
  }

  def setFieldMissing(region: Code[MemoryBuffer], offset: Code[Long], fieldIdx: Int): Code[Unit] = {
    assert(!isFieldRequired(fieldIdx))
    region.setBit(offset, missingIdx(fieldIdx))
  }

  def fieldOffset(offset: Long, fieldIdx: Int): Long =
    offset + byteOffsets(fieldIdx)

  def fieldOffset(offset: Code[Long], fieldIdx: Int): Code[Long] =
    offset + byteOffsets(fieldIdx)

  def loadField(rv: RegionValue, fieldIdx: Int): Long = loadField(rv.region, rv.offset, fieldIdx)

  def loadField(region: MemoryBuffer, offset: Long, fieldIdx: Int): Long = {
    val off = fieldOffset(offset, fieldIdx)
    fields(fieldIdx).typ.fundamentalType match {
      case _: TArray | _: TBinary => region.loadAddress(off)
      case _ => off
    }
  }
}
