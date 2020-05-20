package nl.tudelft.ewi.abs.nonnenmacher

import nl.tudelft.ewi.abs.nonnenmacher.util.AutoCloseProcessing._
import nl.tudelft.ewi.abs.nonnenmacher.util.ClosableFunction
import org.apache.arrow.gandiva.evaluator.Projector
import org.apache.arrow.gandiva.expression.TreeBuilder
import org.apache.arrow.memory.BufferAllocator
import org.apache.arrow.vector.types.pojo.Field
import org.apache.arrow.vector.{ValueVector, VectorSchemaRoot}
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.ColumnarBatchWithSelectionVector
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.{Attribute, AttributeReference, NamedExpression}
import org.apache.spark.sql.execution.{SparkPlan, UnaryExecNode}
import org.apache.spark.sql.util.ArrowUtils
import org.apache.spark.sql.vectorized.ColumnarBatch

import scala.collection.JavaConverters._

case class GandivaProjectExec(child: SparkPlan, projectionList: Seq[NamedExpression]) extends UnaryExecNode {

  override def supportsColumnar: Boolean = true

  lazy val outputs: Seq[Attribute] = {
    projectionList.map(expr => AttributeReference(expr.name, expr.dataType)())
  }

  override protected def doExecute(): RDD[InternalRow] = {
    throw new IllegalAccessException(s"${getClass.getSimpleName} does only support columnar data processing.")
  }

  private def toField(attribute: Attribute): Field = {
    ArrowUtils.toArrowField(attribute.name, attribute.dataType, attribute.nullable, conf.sessionLocalTimeZone);
  }

  override protected def doExecuteColumnar(): RDD[ColumnarBatch] = {

    child.executeColumnar().mapPartitions { batchIter =>

      batchIter
        .map(ColumnarBatchWithSelectionVector.from)
        .mapAndAutoClose(new GandivaProjection)
        .map(_.toColumnarBatch)
    }
  }

  private class GandivaProjection extends ClosableFunction[ColumnarBatchWithSelectionVector, ColumnarBatchWithSelectionVector] {

    private val allocator: BufferAllocator = ArrowUtils.rootAllocator.newChildAllocator(s"${this.getClass.getSimpleName}", 0, Long.MaxValue)
    private val treeNodes = projectionList.map(GandivaExpressionConverter.transform)
    private val expressionTrees = treeNodes.zip(outputs).map { case (node, attr) => TreeBuilder.makeExpression(node, toField(attr)) }
    private val gandivaProjector: Projector = Projector.make(ArrowUtils.toArrowSchema(child.schema, conf.sessionLocalTimeZone), expressionTrees.asJava)
    private val rootOut = VectorSchemaRoot.create(ArrowUtils.toArrowSchema(schema, conf.sessionLocalTimeZone), allocator)

    override def apply(batchIn: ColumnarBatchWithSelectionVector): ColumnarBatchWithSelectionVector = {
      val buffers = batchIn.fieldVectors.flatMap(f => f.getFieldBuffers.asScala).asJava

      //allocate memory for  all field vectors!
      rootOut.clear()
      rootOut.setRowCount(batchIn.getRecordCount.toInt)
      val vectors = rootOut.getFieldVectors.asScala.map(_.asInstanceOf[ValueVector])

      if (batchIn.selectionVector == null) {
        gandivaProjector.evaluate(batchIn.fieldVectorRows, buffers, vectors.asJava)
      } else {
        gandivaProjector.evaluate(batchIn.fieldVectorRows, buffers, batchIn.selectionVector, vectors.asJava)
      }


      new ColumnarBatchWithSelectionVector(rootOut.getFieldVectors.asScala, batchIn.getRecordCount.toInt, null)
    }

    override def close(): Unit = {
      gandivaProjector.close()
      rootOut.close()
      allocator.close()
    }
  }

  override def output: Seq[Attribute] = outputs
}

