package nl.tudelft.ewi.abs.nonnenmacher

import org.apache.spark.sql.functions._
import org.apache.spark.sql.util.ArrowUtils
import org.apache.spark.sql.{ArrowColumnarExtension, Row, SparkSessionExtensions}
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfterEach, FunSuite}


@RunWith(classOf[JUnitRunner])
class GandivaFilterSuite extends FunSuite with SparkSessionBuilder{

  override def withExtensions: Seq[SparkSessionExtensions => Unit] = Seq(ProjectionOnGandivaExtension(), ArrowColumnarExtension())

  test("that simple filter query can be executed on Gandiva") {

    // Deactivates whole stage codegen, helpful for debugging
    // spark.conf.set("spark.sql.codegen.wholeStage", false)
    import spark.implicits._

    val df = spark.range(10L).rdd.map(x => x)
      .toDF("value")

    val res = df.filter(col("value") < 3 || col("value") > 5)

    println("Logical Plan:")
    println(res.queryExecution.optimizedPlan)
    println("Spark Plan:")
    println(res.queryExecution.sparkPlan)
    println("Executed Plan:")
    println(res.queryExecution.executedPlan)

    //Assert Gandiva filter has been executed
    assert(res.queryExecution.executedPlan.find(_ .isInstanceOf[GandivaFilterExec]).isDefined)

    //Verify the expected results are correct
    val results: Array[Row] = res.collect();
    assert(results.length == 7)
  }
}
