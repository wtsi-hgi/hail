package is.hail.variant

import is.hail.annotations.Annotation
import is.hail.expr._

object VSMLocalValue {
  def apply(sampleIds: IndexedSeq[Annotation]): VSMLocalValue =
    VSMLocalValue(Annotation.empty,
      sampleIds,
      Annotation.emptyIndexedSeq(sampleIds.length))
}

case class VSMLocalValue(
  globalAnnotation: Annotation,
  sampleIds: IndexedSeq[Annotation],
  sampleAnnotations: IndexedSeq[Annotation]) {
  assert(sampleIds.length == sampleAnnotations.length)

  def nSamples: Int = sampleIds.length

  def dropSamples(): VSMLocalValue = VSMLocalValue(globalAnnotation,
    IndexedSeq.empty[Annotation],
    IndexedSeq.empty[Annotation])
}

object VSMFileMetadata {
  def apply(sampleIds: IndexedSeq[String],
    sampleAnnotations: IndexedSeq[Annotation] = null,
    globalAnnotation: Annotation = Annotation.empty,
    sSignature: Type = TString(),
    saSignature: Type = TStruct.empty(),
    vSignature: Type = TVariant(GenomeReference.defaultReference),
    vaSignature: Type = TStruct.empty(),
    globalSignature: Type = TStruct.empty(),
    genotypeSignature: Type = TGenotype(),
    wasSplit: Boolean = false): VSMFileMetadata = {
    VSMFileMetadata(
      VSMMetadata(sSignature, saSignature, vSignature, vaSignature, globalSignature, genotypeSignature, wasSplit),
      VSMLocalValue(globalAnnotation, sampleIds,
        if (sampleAnnotations == null)
          Annotation.emptyIndexedSeq(sampleIds.length)
        else
          sampleAnnotations))
  }
}

case class VSMFileMetadata(
  metadata: VSMMetadata,
  localValue: VSMLocalValue)

case class VSMMetadata(
  sSignature: Type = TString(),
  saSignature: Type = TStruct.empty(),
  vSignature: Type = TVariant(GenomeReference.defaultReference),
  vaSignature: Type = TStruct.empty(),
  globalSignature: Type = TStruct.empty(),
  genotypeSignature: Type = TGenotype(),
  wasSplit: Boolean = false)
