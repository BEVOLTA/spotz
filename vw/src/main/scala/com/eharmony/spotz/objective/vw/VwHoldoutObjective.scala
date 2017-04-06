package com.eharmony.spotz.objective.vw

import com.eharmony.spotz.Preamble.Point
import com.eharmony.spotz.objective.Objective
import com.eharmony.spotz.objective.vw.util.VwDatasetFunctions
import com.eharmony.spotz.util.{FileUtil, Logging, SparkFileUtil}
import org.apache.spark.SparkContext

/**
  * @author vsuthichai
  */
abstract class AbstractVwHoldoutObjective(
    vwTrainSetIterator: Iterator[String],
    vwTrainParamsString: Option[String],
    vwTestSetIterator: Iterator[String],
    vwTestParamsString: Option[String])
  extends Objective[Point, Double]
  with VwFunctions
  with VwDatasetFunctions with Logging {

  private val vwTrainParamMap = parseVwArgs(vwTrainParamsString)
  private val vwTestParamMap = parseVwArgs(vwTestParamsString)

  private val vwTrainCacheFilename = saveAsCache(vwTrainSetIterator, "train-dataset.cache", vwTrainParamMap)
  private val vwTestCacheFilename = saveAsCache(vwTestSetIterator, "test-dataset.cache", vwTrainParamMap)

  override def apply(point: Point): Double = {
    // Initialize the model file on the filesystem.  Reserve a unique filename.
    val modelFile = FileUtil.tempFile(s"model.vw")

    // Train
    val vwTrainFile = getCache(vwTrainCacheFilename)
    val vwTrainParams = getTrainVwParams(vwTrainParamMap, point)
    val vwTrainingProcess = VwProcess(s"-f ${modelFile.getAbsolutePath} --cache_file ${vwTrainFile.getAbsolutePath} $vwTrainParams")
    info(s"Executing training: ${vwTrainingProcess.toString}")
    val vwTrainResult = vwTrainingProcess()
    info(s"Train stderr\n${vwTrainResult.stderr}")
    assert(vwTrainResult.exitCode == 0, s"VW Training exited with non-zero exit code s${vwTrainResult.exitCode}")

    // Test
    val vwTestFile = getCache(vwTestCacheFilename)
    val vwTestParams = getTestVwParams(vwTestParamMap, point)
    val vwTestProcess = VwProcess(s"-t -i ${modelFile.getAbsolutePath} --cache_file $vwTestFile $vwTestParams")
    info(s"Executing testing: ${vwTestProcess.toString}")
    val vwTestResult = vwTestProcess()
    assert(vwTestResult.exitCode == 0, s"VW Testing exited with non-zero exit code s${vwTestResult.exitCode}")
    info(s"Test stderr\n${vwTestResult.stderr}")
    val loss = vwTestResult.loss.getOrElse(throw new RuntimeException("Unable to obtain avg loss from test result"))

    // Delete the model.  We don't need these sitting around on the executor's filesystem.
    modelFile.delete()

    loss
  }
}

class SparkVwHoldoutObjective(
    @transient val sc: SparkContext,
    vwTrainSetIterator: Iterator[String],
    vwTrainParamsString: Option[String],
    vwTestSetIterator: Iterator[String],
    vwTestParamsString: Option[String])
  extends AbstractVwHoldoutObjective(vwTrainSetIterator, vwTrainParamsString, vwTestSetIterator, vwTestParamsString)
  with Logging {

  def this(sc: SparkContext,
           vwTrainSetIterable: Iterable[String],
           vwTrainParamsString: Option[String],
           vwTestSetIterable: Iterable[String],
           vwTestParamsString: Option[String]) = {
    this(sc, vwTrainSetIterable.toIterator, vwTrainParamsString, vwTestSetIterable.toIterator, vwTestParamsString)
  }

  def this(sc: SparkContext,
           vwTrainSetPath: String,
           vwTrainParamsString: Option[String],
           vwTestSetPath: String,
           vwTestParamsString: Option[String]) = {
    this(sc, SparkFileUtil.loadFile(sc, vwTrainSetPath), vwTrainParamsString, SparkFileUtil.loadFile(sc, vwTestSetPath), vwTestParamsString)
  }
}

class VwHoldoutObjective(
    vwTrainSetIterator: Iterator[String],
    vwTrainParamsString: Option[String],
    vwTestSetIterator: Iterator[String],
    vwTestParamsString: Option[String])
  extends AbstractVwHoldoutObjective(vwTrainSetIterator, vwTrainParamsString, vwTestSetIterator, vwTestParamsString) {

  def this(vwTrainSetIterable: Iterable[String],
           vwTrainParamsString: Option[String],
           vwTestSetIterable: Iterable[String],
           vwTestParamsString: Option[String]) = {
    this(vwTrainSetIterable.toIterator, vwTrainParamsString, vwTestSetIterable.toIterator, vwTestParamsString)
  }

  def this(vwTrainSetPath: String,
           vwTrainParamsString: Option[String],
           vwTestSetPath: String,
           vwTestParamsString: Option[String]) = {
    this(FileUtil.fileLinesIterator(vwTrainSetPath), vwTrainParamsString, FileUtil.fileLinesIterator(vwTestSetPath), vwTestParamsString)
  }
}

