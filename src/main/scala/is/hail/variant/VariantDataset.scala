package is.hail.variant

import is.hail.annotations.{Annotation, _}
import is.hail.expr.{EvalContext, Parser, TAggregable, TString, TStruct, Type, _}
import is.hail.io.plink.ExportBedBimFam
import is.hail.keytable.KeyTable
import is.hail.methods._
import is.hail.sparkextras.OrderedRDD2
import is.hail.stats.ComputeRRM
import is.hail.utils._
import org.apache.spark.sql.Row
import org.apache.spark.storage.StorageLevel
import scala.collection.JavaConverters._

import scala.language.implicitConversions

object VariantDataset {

  def fromKeyTable(kt: KeyTable): VariantDataset = {
    val vType: Type = kt.keyFields.map(_.typ) match {
      case Array(t@TVariant(_, _)) => t
      case arr => fatal("Require one key column of type Variant to produce a variant dataset, " +
        s"but found [ ${ arr.mkString(", ") } ]")
    }

    val rdd = kt.keyedRDD()
      .map { case (k, v) => (k.asInstanceOf[Row].get(0), v) }
      .filter(_._1 != null)
      .mapValues(a => (a: Annotation, Iterable.empty[Annotation]))

    val metadata = VSMMetadata(
      saSignature = TStruct.empty(),
      vSignature = vType,
      vaSignature = kt.valueSignature,
      globalSignature = TStruct.empty())

    VariantSampleMatrix.fromLegacy(kt.hc, metadata,
      VSMLocalValue(Annotation.empty, Array.empty[Annotation], Array.empty[Annotation]), rdd)
  }
}

class VariantDatasetFunctions(private val vsm: VariantSampleMatrix) extends AnyVal {

  def annotateAllelesExpr(expr: String, propagateGQ: Boolean = false): VariantDataset = {

    val splitmulti = new SplitMulti(vsm,
      "va.aIndex = aIndex, va.wasSplit = wasSplit",
      s"""
g = let
    newgt = downcode(Call(g.gt), aIndex) and
    newad = if (isDefined(g.ad))
        let sum = g.ad.sum() and adi = g.ad[aIndex] in [sum - adi, adi]
      else
        NA: Array[Int] and
    newpl = if (isDefined(g.pl))
        range(3).map(i => range(g.pl.length).filter(j => downcode(Call(j), aIndex) == Call(i)).map(j => g.pl[j]).min())
      else
        NA: Array[Int] and
    newgq = ${ if (propagateGQ) "g.gq" else "gqFromPL(newpl)" }
  in Genotype(v, newgt, newad, g.dp, newgq, newpl)
    """, keepStar = true, leftAligned = false)

    val splitMatrixType = splitmulti.newMatrixType

    val aggregationST = Map(
      "global" -> (0, vsm.globalSignature),
      "v" -> (1, vsm.vSignature),
      "va" -> (2, splitMatrixType.vaType),
      "g" -> (3, TGenotype()),
      "s" -> (4, TString()),
      "sa" -> (5, vsm.saSignature))
    val ec = EvalContext(Map(
      "global" -> (0, vsm.globalSignature),
      "v" -> (1, vsm.vSignature),
      "va" -> (2, splitMatrixType.vaType),
      "gs" -> (3, TAggregable(TGenotype(), aggregationST))))

    val (paths, types, f) = Parser.parseAnnotationExprs(expr, ec, Some(Annotation.VARIANT_HEAD))

    val inserterBuilder = new ArrayBuilder[Inserter]()
    val newType = (paths, types).zipped.foldLeft(vsm.vaSignature) { case (vas, (ids, signature)) =>
      val (s, i) = vas.insert(TArray(signature), ids)
      inserterBuilder += i
      s
    }

    val finalType = newType match {
      case t: TStruct =>
        paths.foldLeft(t) { case (res, path) =>
          res.setFieldAttributes(path, Map("Number" -> "A"))
        }
      case _ => newType
    }

    val inserters = inserterBuilder.result()

    val aggregateOption = Aggregators.buildVariantAggregations(vsm.sparkContext, splitMatrixType, vsm.value.localValue, ec)

    val localNSamples = vsm.nSamples
    val localRowType = vsm.rowType

    val localGlobalAnnotation = vsm.globalAnnotation
    val localVAnnotator = splitmulti.vAnnotator
    val localGAnnotator = splitmulti.gAnnotator
    val splitRowType = splitMatrixType.rowType

    val newMatrixType = vsm.matrixType.copy(vaType = finalType)
    val newRowType = newMatrixType.rowType

    val newRDD2 = OrderedRDD2(
      newMatrixType.orderedRDD2Type,
      vsm.rdd2.orderedPartitioner,
      vsm.rdd2.mapPartitions { it =>
        val splitcontext = new SplitMultiPartitionContext(true, localNSamples, localGlobalAnnotation, localRowType,
          localVAnnotator, localGAnnotator, splitRowType)
        val rv2b = new RegionValueBuilder()
        val rv2 = RegionValue()
        it.map { rv =>
          val annotations = splitcontext.splitRow(rv,
            sortAlleles = false, removeLeftAligned = false, removeMoving = false, verifyLeftAligned = false)
            .map { splitrv =>
              val splitur = new UnsafeRow(splitRowType, splitrv)
              val v = splitur.getAs[Variant](1)
              val va = splitur.get(2)
              ec.setAll(localGlobalAnnotation, v, va)
              aggregateOption.foreach(f => f(splitrv))
              (f(), types).zipped.map { case (a, t) =>
                Annotation.copy(t, a)
              }
            }
            .toArray

          rv2b.set(rv.region)
          rv2b.start(newRowType)
          rv2b.startStruct()

          rv2b.addField(localRowType, rv, 0) // pk
          rv2b.addField(localRowType, rv, 1) // v

          val ur = new UnsafeRow(localRowType, rv.region, rv.offset)
          val va = ur.get(2)
          val newVA = inserters.zipWithIndex.foldLeft(va) { case (va, (inserter, i)) =>
              inserter(va, annotations.map(_ (i)): IndexedSeq[Any])
          }
          rv2b.addAnnotation(finalType, newVA)

          rv2b.addField(localRowType, rv, 3) // gs
          rv2b.endStruct()

          rv2.set(rv.region, rv2b.end())
          rv2
        }
      })

    vsm.copy2(rdd2 = newRDD2, vaSignature = finalType)
  }

  def concordance(other: VariantDataset): (IndexedSeq[IndexedSeq[Long]], KeyTable, KeyTable) = {
    require(vsm.wasSplit)
    require(other.wasSplit)

    CalculateConcordance(vsm, other)
  }

  def summarize(): SummaryResult = {
    vsm.typedRDD[Locus, Variant, Genotype]
      .aggregate(new SummaryCombiner[Genotype](_.hardCallIterator.countNonNegative()))(_.merge(_), _.merge(_))
      .result(vsm.nSamples)
  }

  def exportPlink(path: String, famExpr: String = "id = s") {
    require(vsm.wasSplit)
    vsm.requireColKeyString("export plink")

    val ec = EvalContext(Map(
      "s" -> (0, TString()),
      "sa" -> (1, vsm.saSignature),
      "global" -> (2, vsm.globalSignature)))

    ec.set(2, vsm.globalAnnotation)

    type Formatter = (Option[Any]) => String

    val formatID: Formatter = _.map(_.asInstanceOf[String]).getOrElse("0")
    val formatIsFemale: Formatter = _.map { a =>
      if (a.asInstanceOf[Boolean])
        "2"
      else
        "1"
    }.getOrElse("0")
    val formatIsCase: Formatter = _.map { a =>
      if (a.asInstanceOf[Boolean])
        "2"
      else
        "1"
    }.getOrElse("-9")
    val formatQPheno: Formatter = a => a.map(_.toString).getOrElse("-9")

    val famColumns: Map[String, (Type, Int, Formatter)] = Map(
      "famID" -> (TString(), 0, formatID),
      "id" -> (TString(), 1, formatID),
      "patID" -> (TString(), 2, formatID),
      "matID" -> (TString(), 3, formatID),
      "isFemale" -> (TBoolean(), 4, formatIsFemale),
      "qPheno" -> (TFloat64(), 5, formatQPheno),
      "isCase" -> (TBoolean(), 5, formatIsCase))

    val (names, types, f) = Parser.parseNamedExprs(famExpr, ec)

    val famFns: Array[(Array[Option[Any]]) => String] = Array(
      _ => "0", _ => "0", _ => "0", _ => "0", _ => "-9", _ => "-9")

    (names.zipWithIndex, types).zipped.foreach { case ((name, i), t) =>
      famColumns.get(name) match {
        case Some((colt, j, formatter)) =>
          if (colt != t)
            fatal(s"invalid type for .fam file column $i: expected $colt, got $t")
          famFns(j) = (a: Array[Option[Any]]) => formatter(a(i))

        case None =>
          fatal(s"no .fam file column $name")
      }
    }

    val spaceRegex = """\s+""".r
    val badSampleIds = vsm.stringSampleIds.filter(id => spaceRegex.findFirstIn(id).isDefined)
    if (badSampleIds.nonEmpty) {
      fatal(
        s"""Found ${ badSampleIds.length } sample IDs with whitespace
           |  Please run `renamesamples' to fix this problem before exporting to plink format
           |  Bad sample IDs: @1 """.stripMargin, badSampleIds)
    }

    val bedHeader = Array[Byte](108, 27, 1)

    vsm.rdd2.mapPartitions(
      ExportBedBimFam.bedRowTransformer(vsm.nSamples, vsm.rdd2.typ.rowType)
    ).saveFromByteArrays(path + ".bed", vsm.hc.tmpDir, header = Some(bedHeader))

    vsm.rdd2.mapPartitions(
      ExportBedBimFam.bimRowTransformer(vsm.rdd2.typ.rowType)
    ).writeTable(path + ".bim", vsm.hc.tmpDir)

    val famRows = vsm
      .sampleIdsAndAnnotations
      .map { case (s, sa) =>
        ec.setAll(s, sa)
        val a = f().map(Option(_))
        famFns.map(_ (a)).mkString("\t")
      }

    vsm.hc.hadoopConf.writeTextFile(path + ".fam")(out =>
      famRows.foreach(line => {
        out.write(line)
        out.write("\n")
      }))
  }

  def filterAlleles(filterExpr: String, variantExpr: String = "", filterAlteredGenotypes: Boolean = false,
    keep: Boolean = true, subset: Boolean = true, leftAligned: Boolean = false, keepStar: Boolean = false): VariantDataset = {
    def filterGT(gtexpr: String): String = {
      if (filterAlteredGenotypes)
      // FIXME this can't possibly be right
        s"let newrawgt = $gtexpr in if (newrawgt == g.gt) newrawgt else NA: Int"
      else
        gtexpr
    }

    val genotypeExpr =
      if (subset) {
        """
g = let newpl = if (isDefined(g.pl))
        let unnorm = range(newV.nGenotypes).map(newi =>
            let oldi = gtIndex(newToOld[gtj(newi)], newToOld[gtk(newi)])
             in g.pl[oldi]) and
            minpl = unnorm.min()
         in unnorm - minpl
      else
        NA: Array[Int] and
    newgt = gtFromPL(newpl) and
    newad = if (isDefined(g.ad))
        range(newV.nAlleles).map(newi => g.ad[newToOld[newi]])
      else
        NA: Array[Int] and
    newgq = gqFromPL(newpl) and
    newdp = g.dp
 in Genotype(newV, Call(newgt), newad, newdp, newgq, newpl)
        """
      } else {
        // downcode
        s"""
g = let newgt = ${ filterGT("gtIndex(oldToNew[gtj(g.gt)], oldToNew[gtk(g.gt)])") } and
    newad = if (isDefined(g.ad))
        range(newV.nAlleles).map(i => range(v.nAlleles).filter(j => oldToNew[j] == i).map(j => g.ad[j]).sum())
      else
        NA: Array[Int] and
    newdp = g.dp and
    newpl = if (isDefined(g.pl))
        range(newV.nGenotypes).map(gi => range(v.nGenotypes).filter(gj => gtIndex(oldToNew[gtj(gj)], oldToNew[gtk(gj)]) == gi).map(gj => g.pl[gj]).min())
      else
        NA: Array[Int] and
    newgq = gqFromPL(newpl)
 in Genotype(newV, Call(newgt), newad, newdp, newgq, newpl)
        """
      }

    FilterAlleles(vsm, filterExpr, variantExpr, genotypeExpr,
      keep = keep, leftAligned = leftAligned, keepStar = keepStar)
  }

  def grm(): KinshipMatrix = {
    require(vsm.wasSplit)
    info("Computing GRM...")
    GRM(vsm)
  }

  def hardCalls(): VariantDataset = {
    vsm.mapValues(TGenotype(), { g =>
      if (g == null)
        g
      else
        Genotype(g.asInstanceOf[Genotype]._unboxedGT): Annotation
    })
  }

  /**
    *
    * @param mafThreshold     Minimum minor allele frequency threshold
    * @param includePAR       Include pseudoautosomal regions
    * @param fFemaleThreshold Samples are called females if F < femaleThreshold
    * @param fMaleThreshold   Samples are called males if F > maleThreshold
    * @param popFreqExpr      Use an annotation expression for estimate of MAF rather than computing from the data
    */
  def imputeSex(mafThreshold: Double = 0.0, includePAR: Boolean = false, fFemaleThreshold: Double = 0.2,
    fMaleThreshold: Double = 0.8, popFreqExpr: Option[String] = None): VariantDataset = {
    require(vsm.wasSplit)

    ImputeSexPlink(vsm,
      mafThreshold,
      includePAR,
      fMaleThreshold,
      fFemaleThreshold,
      popFreqExpr)
  }

  def ldMatrix(forceLocal: Boolean = false): LDMatrix = {
    require(vsm.wasSplit)
    LDMatrix(vsm, Some(forceLocal))
  }

  def ldPrune(nCores: Int, r2Threshold: Double = 0.2, windowSize: Int = 1000000, memoryPerCore: Int = 256): VariantDataset = {
    require(vsm.wasSplit)
    LDPrune(vsm, nCores, r2Threshold, windowSize, memoryPerCore * 1024L * 1024L)
  }

  def mendelErrors(ped: Pedigree): (KeyTable, KeyTable, KeyTable, KeyTable) = {
    require(vsm.wasSplit)
    vsm.requireColKeyString("mendel errors")

    val men = MendelErrors(vsm, ped.filterTo(vsm.stringSampleIdSet).completeTrios)

    (men.mendelKT(), men.fMendelKT(), men.iMendelKT(), men.lMendelKT())
  }

  def nirvana(config: String, blockSize: Int = 500000, root: String): VariantDataset = {
    Nirvana.annotate(vsm, config, blockSize, root)
  }

  /**
    *
    * @param k          the number of principal components to use to distinguish
    *                   ancestries
    * @param maf        the minimum individual-specific allele frequency for an
    *                   allele used to measure relatedness
    * @param blockSize  the side length of the blocks of the block-distributed
    *                   matrices; this should be set such that atleast three of
    *                   these matrices fit in memory (in addition to all other
    *                   objects necessary for Spark and Hail).
    * @param statistics which subset of the four statistics to compute
    */
  def pcRelate(k: Int, maf: Double, blockSize: Int, minKinship: Double = PCRelate.defaultMinKinship, statistics: PCRelate.StatisticSubset = PCRelate.defaultStatisticSubset): KeyTable = {
    require(vsm.wasSplit)
    val pcs = SamplePCA(vsm, k, false, false)._1
    PCRelate.toKeyTable(vsm, pcs, maf, blockSize, minKinship, statistics)
  }

  def rrm(forceBlock: Boolean = false, forceGramian: Boolean = false): KinshipMatrix = {
    require(vsm.wasSplit)
    info(s"rrm: Computing Realized Relationship Matrix...")
    val (rrm, m) = ComputeRRM(vsm, forceBlock, forceGramian)
    info(s"rrm: RRM computed using $m variants.")
    KinshipMatrix(vsm.hc, vsm.sSignature, rrm, vsm.sampleIds.toArray, m)
  }

  def tdt(ped: Pedigree, tdtRoot: String = "va.tdt"): VariantDataset = {
    require(vsm.wasSplit)
    vsm.requireColKeyString("TDT")
    TDT(vsm, ped.filterTo(vsm.stringSampleIdSet).completeTrios,
      Parser.parseAnnotationRoot(tdtRoot, Annotation.VARIANT_HEAD))
  }

  def deNovo(ped: Pedigree,
    referenceAF: String,
    minGQ: Int = 20,
    minPDeNovo: Double = 0.05,
    maxParentAB: Double = 0.05,
    minChildAB: Double = 0.20,
    minDepthRatio: Double = 0.10): KeyTable = {
    require(vsm.wasSplit)
    vsm.requireColKeyString("de novo")

    DeNovo(vsm, ped, referenceAF, minGQ, minPDeNovo, maxParentAB, minChildAB, minDepthRatio)
  }

  /**
    *
    * @param config    VEP configuration file
    * @param root      Variant annotation path to store VEP output
    * @param csq       Annotates with the VCF CSQ field as a string, rather than the full nested struct schema
    * @param blockSize Variants per VEP invocation
    */
  def vep(config: String, root: String = "va.vep", csq: Boolean = false,
    blockSize: Int = 1000): VariantSampleMatrix = {
    VEP.annotate(vsm, config, root, csq, blockSize)
  }

  def filterIntervals(intervals: java.util.ArrayList[Interval[Locus]], keep: Boolean): VariantSampleMatrix = {
    val iList = IntervalTree[Locus](intervals.asScala.toArray)
    filterIntervals(iList, keep)
  }

  def filterIntervals[T](iList: IntervalTree[Locus, _], keep: Boolean): VariantSampleMatrix = {
    implicit val locusOrd = vsm.matrixType.locusType.ordering(missingGreatest = true)

    val ab = new ArrayBuilder[(Interval[Annotation], Annotation)]()
    iList.foreach { case (i, v) =>
      ab += (Interval[Annotation](i.start, i.end), v)
    }

    val iList2 = IntervalTree.annotationTree(ab.result())

    if (keep)
      vsm.copy(rdd = vsm.rdd.filterIntervals(iList2))
    else {
      val iListBc = vsm.sparkContext.broadcast(iList)
      vsm.filterVariants { (v, va, gs) => !iListBc.value.contains(v.asInstanceOf[Variant].locus) }
    }
  }

  /**
    * Remove multiallelic variants from this dataset.
    *
    * Useful for running methods that require biallelic variants without calling the more expensive split_multi step.
    */
  def filterMulti(): VariantSampleMatrix = {
    if (vsm.wasSplit) {
      warn("called redundant `filter_multi' on an already split or multiallelic-filtered VDS")
      vsm
    } else {
      vsm.filterVariants {
        case (v, va, gs) => v.asInstanceOf[Variant].isBiallelic
      }.copy2(wasSplit = true)
    }
  }

  def verifyBiallelic(): VariantSampleMatrix =
    verifyBiallelic("verifyBiallelic")

  def verifyBiallelic(method: String): VariantSampleMatrix = {
    if (vsm.wasSplit) {
      warn("called redundant `$method' on biallelic VDS")
      vsm
    } else {
      val localRowType = vsm.rowType
      vsm.copy2(
        rdd2 = vsm.rdd2.mapPreservesPartitioning { rv =>
          val ur = new UnsafeRow(localRowType, rv.region, rv.offset)
          val v = ur.getAs[Variant](1)
          if (!v.isBiallelic)
            fatal("in $method: found non-biallelic variant: $v")
          rv
        },
        wasSplit = true)
    }
  }

  def exportGen(path: String, precision: Int = 4) {
    require(vsm.wasSplit)

    def writeSampleFile() {
      // FIXME: should output all relevant sample annotations such as phenotype, gender, ...
      vsm.hc.hadoopConf.writeTable(path + ".sample",
        "ID_1 ID_2 missing" :: "0 0 0" :: vsm.sampleIds.map(s => s"$s $s 0").toList)
    }

    def writeGenFile() {
      val varidSignature = vsm.vaSignature.getOption("varid")
      val varidQuery: Querier = varidSignature match {
        case Some(_) =>
          val (t, q) = vsm.queryVA("va.varid")
          t match {
            case _: TString => q
            case _ => a => null
          }
        case None => a => null
      }

      val rsidSignature = vsm.vaSignature.getOption("rsid")
      val rsidQuery: Querier = rsidSignature match {
        case Some(_) =>
          val (t, q) = vsm.queryVA("va.rsid")
          t match {
            case _: TString => q
            case _ => a => null
          }
        case None => a => null
      }

      val localNSamples = vsm.nSamples
      val localRowType = vsm.rowType
      vsm.rdd2.mapPartitions { it =>
        val sb = new StringBuilder
        val view = new ArrayGenotypeView(localRowType)
        it.map { rv =>
          view.setRegion(rv)
          val ur = new UnsafeRow(localRowType, rv)

          val v = ur.getAs[Variant](1)
          val va = ur.get(2)

          sb.clear()
          sb.append(v.contig)
          sb += ' '
          sb.append(Option(varidQuery(va)).getOrElse(v.toString))
          sb += ' '
          sb.append(Option(rsidQuery(va)).getOrElse("."))
          sb += ' '
          sb.append(v.start)
          sb += ' '
          sb.append(v.ref)
          sb += ' '
          sb.append(v.alt)

          var i = 0
          while (i < localNSamples) {
            view.setGenotype(i)
            if (view.hasGP) {
              sb += ' '
              sb.append(formatDouble(view.getGP(0), precision))
              sb += ' '
              sb.append(formatDouble(view.getGP(1), precision))
              sb += ' '
              sb.append(formatDouble(view.getGP(2), precision))
            } else
              sb.append(" 0 0 0")
            i += 1
          }
          sb.result()
        }
      }.writeTable(path + ".gen", vsm.hc.tmpDir, None)
    }

    writeSampleFile()
    writeGenFile()
  }

  def splitMulti(propagateGQ: Boolean = false, keepStar: Boolean = false, leftAligned: Boolean = false): VariantSampleMatrix = {
    if (vsm.genotypeSignature.isOfType(TGenotype())) {
      vsm.splitMulti("va.aIndex = aIndex, va.wasSplit = wasSplit", s"""
g = let
    newgt = downcode(Call(g.gt), aIndex) and
    newad = if (isDefined(g.ad))
        let sum = g.ad.sum() and adi = g.ad[aIndex] in [sum - adi, adi]
      else
        NA: Array[Int] and
    newpl = if (isDefined(g.pl))
        range(3).map(i => range(g.pl.length).filter(j => downcode(Call(j), aIndex) == Call(i)).map(j => g.pl[j]).min())
      else
        NA: Array[Int] and
    newgq = ${ if (propagateGQ) "g.gq" else "gqFromPL(newpl)" }
  in Genotype(v, newgt, newad, g.dp, newgq, newpl)
    """,
        keepStar, leftAligned)
    } else {
      def hasGenotypeFieldOfType(name: String, expected: Type): Boolean = {
        vsm.genotypeSignature match {
          case t: TStruct =>
            t.selfField(name) match {
              case Some(f) => f.typ.isOfType(expected)
              case None => false
            }
          case _ => false
        }
      }

      val b = new ArrayBuilder[String]()
      if (hasGenotypeFieldOfType("GT", TCall()))
        b += "g.GT = downcode(g.GT, aIndex)"
      if (hasGenotypeFieldOfType("AD", TArray(!TInt32())))
        b += """
g.AD = if (isDefined(g.AD))
  let sum = g.AD.sum() and adi = g.AD[aIndex] in [sum - adi, adi]
else
  NA: Array[Int]
        """
      if (hasGenotypeFieldOfType("PL", TArray(!TInt32())))
        b += """
g.PL = if (isDefined(g.PL))
  range(3).map(i => range(g.PL.length).filter(j => downcode(Call(j), aIndex) == Call(i)).map(j => g.PL[j]).min())
else
  NA: Array[Int]
        """

      var vsm2 = vsm.splitMulti("va.aIndex = aIndex, va.wasSplit = wasSplit", b.result().mkString(","), keepStar, leftAligned)

      if (!propagateGQ && hasGenotypeFieldOfType("GQ", TInt32())) {
        vsm2.annotateGenotypesExpr("g.GQ = gqFromPL(g.PL)")
      } else
        vsm2
    }
  }

  def splitMulti(variantExpr: String, genotypeExpr: String, keepStar: Boolean, leftAligned: Boolean): VariantSampleMatrix = {
    val splitmulti = new SplitMulti(vsm, variantExpr, genotypeExpr, keepStar, leftAligned)
    splitmulti.split()
  }
}
