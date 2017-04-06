package com.eharmony.spotz.objective.vw.util

import java.io._

import com.eharmony.spotz.objective.vw.VwProcess
import com.eharmony.spotz.util.{FileUtil, LocalFileSystemFunctions, Logging}

import scala.collection.mutable

/**
  * Create and save a cache file given a VW dataset.  The implication of saving means that
  * the cache file is added to the SparkContext such that it can be retrieved on worker nodes.
  * If Spark is not being used, ie. parallel collections are being used, then the cache file
  * is just saved locally to the file system.
  */
trait VwDatasetFunctions extends LocalFileSystemFunctions with Logging {
  def saveAsCache(vwDatasetInputStream: InputStream, vwCacheFilename: String, vwParamsMap: Map[String, _]): String = {
    val vwCacheFile = FileUtil.tempFile(vwCacheFilename)
    val vwResult = VwProcess.generateCache(vwDatasetInputStream, vwCacheFile.getAbsolutePath, cacheParams(vwParamsMap))
    info(s"VW cache generation stderr ${vwResult.stderr}")
    saveLocally(vwCacheFile)
    vwCacheFile.getName
  }

  def saveAsCache(vwDatasetIterator: Iterator[String], vwCacheFilename: String, vwParamsMap: Map[String, _]): String = {
    val vwCacheFile = FileUtil.tempFile(vwCacheFilename)
    val vwResult = VwProcess.generateCache(vwDatasetIterator, vwCacheFile.getAbsolutePath, cacheParams(vwParamsMap))
    info(s"VW cache generation stderr ${vwResult.stderr}")

    saveLocally(vwCacheFile)
    vwCacheFile.getName
  }

  def saveAsCache(vwDatasetPath: String, vwCacheFilename: String, vwParamsMap: Map[String, _]): String = {
    val vwCacheFile = FileUtil.tempFile(vwCacheFilename)
    val vwResult = VwProcess.generateCache(vwDatasetPath, vwCacheFile.getAbsolutePath, cacheParams(vwParamsMap))
    info(s"VW cache generation stderr ${vwResult.stderr}")
    saveLocally(vwCacheFile)
    vwCacheFile.getName
  }

  private def cacheParams(vwParamsMap: Map[String, _]): String = {
    vwParamsMap.foldLeft("") {
      case (paramString, ("cb", paramValue)) => paramString + s"--cb $paramValue "
      case (paramString, ("cb_adf", paramValue)) => paramString + s"--cb_adf $paramValue "
      case (paramString, ("cb_explore", paramValue)) => paramString + s"--cb_explore $paramValue "
      case (paramString, ("cb_explore_adf", paramValue)) => paramString + s"--cb_explore_adf $paramValue "
      case (paramString, ("b", paramValue)) => paramString + s"-b $paramValue "
      case (paramString, ("bit_precision", paramValue)) => paramString + s"--bit_precision $paramValue "
      case (paramString, _) => paramString
    }
  }

  def getCache(name: String): File = getLocally(name)
}

/**
  * Perform kFold CrossValidation on VW Dataset.
  */
trait VwCrossValidation extends VwDatasetFunctions {
  def kFold(inputPath: String, folds: Int, vwParamsMap: Map[String, _]): Map[Int, (String, String)] = {
    val enumeratedVwInput = FileUtil.fileLinesIterator(inputPath)
    println(s"kFold input path $inputPath")
    kFold(enumeratedVwInput.toIterable, folds, vwParamsMap)
  }

  /**
    * This method takes the VW input file specified in the class constructor and
    * partitions the file into a training set and test set for every fold.  The train
    * and test set for every fold are then input into VW to generate cache files.
    * These cache files are added to the SparkContext so that they'll
    * be accessible on the executors. To keep track of the train and test set caches
    * for every fold, a Map is used where the key is the fold number and the value is
    * (trainingSetCachePath, testSetCachePath).  These file names do NEED to be unique so that
    * they do not collide with other file names.  The entirety of this method
    * runs on the driver.  All VW input training / test set files as well as cache
    * files are deleted upon JVM exit.
    *
    * This strategy has the downside of duplicating the dataset across every node K times.
    * An alternative approach is to train K cache files and train the regressor K - 1 times and
    * test on the last test cache file.
    *
    * @param vwDataset
    * @param folds
    * @return a map representation where key is the fold number and value is
    *         (trainingSetFilename, testSetFilename)
    */
  //def kFold(vwDataset: Iterator[String], folds: Int, cacheBitSize: Int, cb: Option[Int]): Map[Int, (String, String)] = {
  def kFold(vwDataset: Iterable[String], folds: Int, vwParamsMap: Map[String, _]): Map[Int, (String, String)] = {
    val enumeratedVwDataset = vwDataset.zipWithIndex.toList

    // For every fold iteration, partition the vw input such that one fold is the test set and the
    // remaining K-1 folds comprise the training set
    (0 until folds).foldLeft(mutable.Map[Int, (String, String)]()) { (map, fold) =>
      val (trainWithLineNumber, testWithLineNumber) = enumeratedVwDataset.partition {
        // train
        case (line, lineNumber) if lineNumber % folds != fold => true
        // test
        case (line, lineNumber) if lineNumber % folds == fold => false
      }

      val train = trainWithLineNumber.map { case (line, lineNumber) => line }.toIterator
      val vwTrainingCacheFilename = saveAsCache(train, s"train-fold-$fold.cache", vwParamsMap)

      val test = testWithLineNumber.map { case (line, lineNumber) => line }.toIterator
      val vwTestCacheFilename = saveAsCache(test, s"test-fold-$fold.cache", vwParamsMap)

      // Add it to the map which will be referenced later on the executor
      map + ((fold, (vwTrainingCacheFilename, vwTestCacheFilename)))
    }.toMap
  }
}
