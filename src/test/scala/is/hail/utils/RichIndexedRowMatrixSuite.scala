package is.hail.utils

import breeze.linalg.{DenseMatrix => BDM, _}
import is.hail.SparkSuite
import is.hail.distributedmatrix.BlockMatrix.ops._
import org.apache.spark.mllib.linalg.Vectors
import org.apache.spark.mllib.linalg.distributed.{DistributedMatrix, IndexedRow, IndexedRowMatrix}
import org.apache.spark.rdd.RDD
import org.testng.annotations.Test

/**
  * Testing RichIndexedRowMatrix.
  */
class RichIndexedRowMatrixSuite extends SparkSuite {

  private def convertDistributedMatrixToBreeze(sparkMatrix: DistributedMatrix): Matrix[Double] = {
    val breezeConverter = sparkMatrix.getClass.getMethod("toBreeze")
    breezeConverter.invoke(sparkMatrix).asInstanceOf[Matrix[Double]]
  }

  @Test def testToBlockMatrixDense() {
    val rows = 9L
    val cols = 6L
    val data = Seq(
      (0L, Vectors.dense(0.0, 1.0, 2.0, 1.0, 3.0, 4.0)),
      (1L, Vectors.dense(3.0, 4.0, 5.0, 1.0, 1.0, 1.0)),
      (3L, Vectors.dense(9.0, 0.0, 1.0, 1.0, 1.0, 1.0)),
      (4L, Vectors.dense(9.0, 0.0, 1.0, 1.0, 1.0, 1.0)),
      (5L, Vectors.dense(9.0, 0.0, 1.0, 1.0, 1.0, 1.0)),
      (6L, Vectors.dense(1.0, 2.0, 3.0, 1.0, 1.0, 1.0)),
      (7L, Vectors.dense(4.0, 5.0, 6.0, 1.0, 1.0, 1.0)),
      (8L, Vectors.dense(7.0, 8.0, 9.0, 1.0, 1.0, 1.0))
    ).map(IndexedRow.tupled)
    val indexedRows: RDD[IndexedRow] = sc.parallelize(data)

    val idxRowMat = new IndexedRowMatrix(indexedRows)

    for {
      blockSize <- Seq(1,2,3,4,6,7,9,10)
    } {
      val blockMat = idxRowMat.toHailBlockMatrix(blockSize)
      assert(blockMat.rows === rows)
      assert(blockMat.cols === cols)
      assert(blockMat.toLocalMatrix() === convertDistributedMatrixToBreeze(idxRowMat))
    }

    intercept[IllegalArgumentException] {
      idxRowMat.toHailBlockMatrix(-1)
    }
    intercept[IllegalArgumentException] {
      idxRowMat.toHailBlockMatrix(0)
    }
  }

  @Test def emptyBlocks() {
    val rows = 9
    val cols = 2
    val data = Seq(
      (3L, Vectors.dense(1.0, 2.0)),
      (4L, Vectors.dense(1.0, 2.0)),
      (5L, Vectors.dense(1.0, 2.0)),
      (8L, Vectors.dense(1.0, 2.0))
    ).map(IndexedRow.tupled)

    val idxRowMat = new IndexedRowMatrix(sc.parallelize(data))

    val m = idxRowMat.toHailBlockMatrix(2)
    assert(m.rows == rows)
    assert(m.cols == cols)
    assert(m.toLocalMatrix() == convertDistributedMatrixToBreeze(idxRowMat))
    assert(m.blocks.count() == 5)

    (m * m.t).toLocalMatrix() // assert no exception

    assert(m.mapWithIndex { case (i, j, v) => i + 10 * j + v }.toLocalMatrix() ===
      new BDM[Double](rows, cols, Array[Double](
        0.0, 1.0, 2.0, 4.0, 5.0, 6.0, 6.0, 7.0, 9.0,
        10.0, 11.0, 12.0, 15.0, 16.0, 17.0, 16.0, 17.0, 20.0
      )))
  }

}
