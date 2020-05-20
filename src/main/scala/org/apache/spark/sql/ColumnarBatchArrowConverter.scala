package org.apache.spark.sql

import nl.tudelft.ewi.abs.nonnenmacher.util.ClosableFunction
import org.apache.arrow.vector.VectorSchemaRoot
import org.apache.spark.sql.vectorized.{ColumnVector, ColumnarBatch}

import scala.collection.JavaConverters._

object ColumnarBatchArrowConverter {

  def ColumnarBatchToVectorRoot: ClosableFunction[ColumnarBatch, VectorSchemaRoot] = new ClosableFunction[ColumnarBatch, VectorSchemaRoot] {

    var root: VectorSchemaRoot = _

    override def apply(batch: ColumnarBatch): VectorSchemaRoot = {
      //TODO
      val fieldVectors = (1 until batch.numCols).map(batch.column).map {
        case ArrowColumnVectorWithFieldVector(fieldVector) => fieldVector
        case _ => throw new IllegalStateException(s"${getClass.getSimpleName} does only support columnar data in arrow format.")
      }

      if (root != null) root.close()
      root = new VectorSchemaRoot(fieldVectors.asJava)
      root
    }

    override def close(): Unit = if (root != null) root.close()
  }

  def VectorRootToColumnarBatch: Function[VectorSchemaRoot, ColumnarBatch] = new Function[VectorSchemaRoot, ColumnarBatch] {
    override def apply(root: VectorSchemaRoot): ColumnarBatch = {

      val arrowVectors = root.getFieldVectors.asScala.map(x => new vectorized.ArrowColumnVector(x)).toArray[ColumnVector]

      //combine all column vectors in ColumnarBatch
      new ColumnarBatch(arrowVectors, root.getRowCount)
    }
  }

}
