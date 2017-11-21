package is.hail.io.plink

import is.hail.sparkextras._
import is.hail.expr._
import is.hail.annotations._
import is.hail.variant._

object ExportBedBimFam {

  val gtMap = Array(3, 2, 0)

  def bedRowTransformer(nSamples: Int, rowType: TStruct): Iterator[RegionValue] => Iterator[Array[Byte]] = { it =>
    val hcv = HardCallView(rowType)
    val rvb = new RegionValueBuilder()
    val rv2 = RegionValue()
    val gtMap = Array(3, 2, 0)
    val nBytes = (nSamples + 3) / 4
    val a = new Array[Byte](nBytes)

    it.map { rv =>
      hcv.setRegion(rv)
      rvb.set(rv.region)

      var b = 0
      var k = 0
      while (k < nSamples) {
        hcv.setGenotype(k)
        val gt = if (hcv.hasGT) gtMap(hcv.getGT) else 1
        b |= gt << ((k & 3) * 2)
        if ((k & 3) == 3) {
          a(k >> 2) = b.toByte
          b = 0
        }
        k += 1
      }
      if ((k & 3) > 0)
        a(nBytes - 1) = b.toByte

      // FIXME: NO BYTE ARRAYS, go directly through writePartitions
      a
    }
  }

  def bimRowTransformer(rowType: TStruct): Iterator[RegionValue] => Iterator[String] = { it =>
    val vIdx = rowType.fieldIdx("v")
    val psuedoVariantType = rowType.fieldType(vIdx).asInstanceOf[TVariant]
    val view = new VariantView(psuedoVariantType)

    it.map { rv =>
      val region = rv.region
      assert(rowType.isFieldDefined(rv, vIdx))
      view.setRegion(region, rowType.loadField(rv, vIdx))
      val contig = view.getContig()
      val start = view.getStart()
      val ref = view.getRef()
      val alt = view.getAlt()
      // FIXME: NO STRINGS, go directly through writePartitions
      val id = s"${contig}:${start}:${ref}:${alt}"
      s"""${contig}\t$id\t0\t${start}\t${alt}\t${ref}"""
    }
  }
}
