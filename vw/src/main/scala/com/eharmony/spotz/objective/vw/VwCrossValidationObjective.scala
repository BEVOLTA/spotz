package com.eharmony.spotz.objective.vw

import java.io.File

import com.eharmony.spotz.Preamble.Point
import com.eharmony.spotz.objective.Objective
import com.eharmony.spotz.objective.vw.util.VwCrossValidation
import com.eharmony.spotz.util._
import org.apache.spark.SparkContext
import org.apache.spark.rdd.RDD

/**
  * Perform K Fold cross validation given a dataset formatted for Vowpal Wabbit.
  *
  * @param numFolds
  * @param vwDatasetPath
  * @param vwTrainParamsString
  * @param vwTestParamsString
  */
abstract class AbstractVwCrossValidationObjective(
    val numFolds: Int,
    val vwDatasetPath: String,
    vwTrainParamsString: Option[String],
    vwTestParamsString: Option[String])
  extends Objective[Point, Double]
  with VwFunctions
  with VwCrossValidation
  with Logging {

  val localMode: Boolean

  val vwTrainParamsMap = parseVwArgs(vwTrainParamsString)
  val vwTestParamsMap = parseVwArgs(vwTestParamsString)

  // Gzip VW dataset
  info(s"Gzip $vwDatasetPath")
  val gzipVwDatasetFilename = FileUtil.gzip(vwDatasetPath)

  // Save VW dataset to executor or locally
  info(s"Saving gzipped VW dataset to executors $gzipVwDatasetFilename")
  val gzippedVwDatasetFilenameOnExecutor = save(gzipVwDatasetFilename)

  // Lazily initialize K-fold if utilizing cluster
  lazy val lazyFoldToVwCacheFiles = Option(initKFold())

  // Initialize K-fold non-lazily in local mode
  val nonLazyFoldToVwCacheFiles = {
    if (localMode) {
      info("Operating in local mode")
      val kFoldMap = initKFold()
      Option(kFoldMap)
    } else {
      info("Operating in non-local mode")
      None
    }
  }

  def initKFold(): Map[Int, (String, String)] = {
    info(s"Retrieving gzipped VW dataset on executor $gzippedVwDatasetFilenameOnExecutor")
    val file = get(gzippedVwDatasetFilenameOnExecutor)

    val unzippedFilename = FileUtil.gunzip(file.getAbsolutePath)
    info(s"Unzipped ${file.getAbsolutePath} to $unzippedFilename")

    info(s"Creating K Fold cache files from $unzippedFilename")
    kFold(unzippedFilename, numFolds, vwTrainParamsMap)
  }

  def save(filename: String): String
  def get(filename: String): File

  /**
    * This method can run on the driver and/or the executor.  It performs a k-fold cross validation
    * over the vw input dataset passed through the class constructor.  The dataset has been split in
    * such a way that every fold has its own training and test set in the form of VW cache files.
    *
    * @param point a point object representing the hyper parameters to evaluate upon
    * @return Double the cross validated average loss
    */
  override def apply(point: Point): Double = {
    val vwTrainParams = getTrainVwParams(vwTrainParamsMap, point)
    val vwTestParams = getTestVwParams(vwTestParamsMap, point)

    info(s"Vw Training Params: $vwTrainParams")
    info(s"Vw Testing Params: $vwTestParams")

    val foldToVwCacheFiles = if (localMode) {
      nonLazyFoldToVwCacheFiles
    } else {
      lazyFoldToVwCacheFiles
    }

    assert(foldToVwCacheFiles.isDefined, "Unable to initialize K Fold cross validation")

    val avgLosses = (0 until numFolds).map { fold =>
      // Retrieve the training and test set cache for this fold.
      val (vwTrainFilename, vwTestFilename) = foldToVwCacheFiles.get(fold)
      val vwTrainFile = getCache(vwTrainFilename)
      val vwTestFile = getCache(vwTestFilename)

      // Initialize the model file on the filesystem.  Just reserve a unique filename.
      val modelFile = FileUtil.tempFile(s"model-fold-$fold.vw")

      // Train
      val vwTrainingProcess = VwProcess(s"-f ${modelFile.getAbsolutePath} --cache_file ${vwTrainFile.getAbsolutePath} $vwTrainParams")
      info(s"Executing training: ${vwTrainingProcess.toString}")
      val vwTrainResult = vwTrainingProcess()
      info(s"Train stderr ${vwTrainResult.stderr}")
      assert(vwTrainResult.exitCode == 0, s"VW Training exited with non-zero exit code s${vwTrainResult.exitCode}")

      // Test
      val vwTestProcess = VwProcess(s"-t -i ${modelFile.getAbsolutePath} --cache_file ${vwTestFile.getAbsolutePath} $vwTestParams")
      info(s"Executing testing: ${vwTestProcess.toString}")
      val vwTestResult = vwTestProcess()
      assert(vwTestResult.exitCode == 0, s"VW Testing exited with non-zero exit code s${vwTestResult.exitCode}")
      info(s"Test stderr ${vwTestResult.stderr}")
      val loss = vwTestResult.loss.getOrElse(throw new RuntimeException("Unable to obtain avg loss from test result"))

      // Delete the model.  We don't need these sitting around on the executor's filesystem.
      modelFile.delete()

      loss
    }

    info(s"Avg losses for all folds: $avgLosses")
    val crossValidatedAvgLoss = avgLosses.sum / numFolds
    info(s"Cross validated avg loss: $crossValidatedAvgLoss")

    crossValidatedAvgLoss
  }
}

class SparkVwCrossValidationObjective(
    @transient val sc: SparkContext,
    numFolds: Int,
    vwDatasetPath: String,
    vwTrainParamsString: Option[String],
    vwTestParamsString: Option[String])
  extends AbstractVwCrossValidationObjective(numFolds, vwDatasetPath, vwTrainParamsString, vwTestParamsString)
  with SparkFileFunctions {

  override lazy val localMode = sc.isLocal

  def this(sc: SparkContext,
           numFolds: Int,
           vwDatasetIterator: Iterator[String],
           vwTrainParamsString: Option[String],
           vwTestParamsString: Option[String]) = {
    this(sc, numFolds, FileUtil.tempFile(vwDatasetIterator).getAbsolutePath, vwTrainParamsString, vwTestParamsString)
  }


  def this(sc: SparkContext,
           numFolds: Int,
           @transient vwDataset: RDD[String],
           vwTrainParamsString: Option[String],
           vwTestParamsString: Option[String]) = {
    this(sc, numFolds, SparkFileUtil.saveToLocalFile(sc, vwDataset), vwTrainParamsString, vwTestParamsString)
  }

  override def save(filename: String): String = {
    saveToSparkFiles(filename)
  }

  override def get(filename: String): File = {
    getFromSparkFiles(filename)
  }
}

class VwCrossValidationObjective(
    numFolds: Int,
    vwDatasetPath: String,
    vwTrainParamsString: Option[String],
    vwTestParamsString: Option[String])
  extends AbstractVwCrossValidationObjective(numFolds, vwDatasetPath, vwTrainParamsString, vwTestParamsString)
  with LocalFileSystemFunctions {

  override lazy val localMode = true

  def this(numFolds: Int,
           vwDatasetIterator: Iterator[String],
           vwTrainParamsString: Option[String],
           vwTestParamsString: Option[String]) = {
    this(numFolds, FileUtil.tempFile(vwDatasetIterator).getAbsolutePath, vwTrainParamsString, vwTestParamsString)
  }

  override def save(filename: String): String = {
    saveLocally(filename)
  }

  override def get(filename: String): File = {
    getLocally(filename)
  }
}