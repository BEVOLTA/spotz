package com.eharmony.spotz.objective.vw

import java.io.{InputStream, PipedInputStream, PipedOutputStream, PrintWriter}

import com.eharmony.spotz.util.RegexUtil.floatingPointRegex
import com.eharmony.spotz.util.{CommandLineProcess, Logging}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

/**
  * A VW Process run.  The parameters are passed as a string in the exact same
  * way they are passed as parameters on the vw command line.
  **
  * @param params vw args
  */
case class VwProcess(params: String, stdin: Option[InputStream] = None) extends CommandLineProcess(s"vw $params", stdin) with Logging {

  /**
    * Execute the VW process and return a VWResult object with the VW exit code,
    * stdout, and stderr, and average loss.
    *
    * @return a VWResult object
    */
  def apply(): VwResult = {
    val processResult = super.apply()

    info(s"Executing VW Process: vw $params")

    VwResult(processResult.exitCode,
             processResult.stdout,
             processResult.stderr,
             averageLoss(processResult.stderr))
  }

  /**
    * Parse the average loss using a regex.
    *
    * @param stderr
    * @return
    */
  private def averageLoss(stderr: String): Option[Double] = {
    VwProcess.avgLossRegex.findFirstMatchIn(stderr).map(_.group(1).toDouble)
  }

  // TODO: What else is important that should be returned as part of the VwResult?
  override def toString = s"vw $params"
}

case class VwResult(
    exitCode: Int,
    stdout: String,
    stderr: String,
    loss: Option[Double])

object VwProcess {
  val avgLossRegex = s"average\\s+loss\\s+=\\s+($floatingPointRegex)".r

  def generateCache(inputStream: InputStream, cachePath: String, cacheParams: String): VwResult = {
    val vwCacheProcess = VwProcess(s"-k --cache_file $cachePath $cacheParams", Option(inputStream))
    val vwCacheResult = vwCacheProcess()

    assert(vwCacheResult.exitCode == 0,
      s"VW Training cache exited with non-zero exit code ${vwCacheResult.exitCode}")

    vwCacheResult
  }

  def generateCache(vwDatasetIterator: Iterator[String], cachePath: String, cacheParams: String): VwResult = {
    val pos = new PipedOutputStream
    val pis = new PipedInputStream(pos)
    val pw = new PrintWriter(pos, true)

    Future {
      vwDatasetIterator.foreach(line => pw.println(line))
      pw.close()
    }

    generateCache(pis, cachePath, cacheParams)
  }

  def generateCache(vwDatasetPath: String, cachePath: String, cacheParams: String): VwResult = {
    val vwCacheProcess = VwProcess(s"-k --cache_file $cachePath -d $vwDatasetPath $cacheParams", None)
    val vwCacheResult = vwCacheProcess()

    assert(vwCacheResult.exitCode == 0,
      s"VW Training cache exited with non-zero exit code ${vwCacheResult.exitCode}")

    vwCacheResult
  }
}

/**
  * This is a builder class to make it more convenient to construct the VW command line
  * with all its various arguments.  Using this is not necessary at all as one could simply
  * specify their own VW parameters if they're familiar with them and pass them
  * as a string to the class constructor of VwProcess.
  */
class VwProcessBuilder {
  private val sb = new StringBuilder

  private def append(param: String): this.type = {
    sb.append(param)
    this
  }

  def params(params: String) = append(s"$params ")

  // TODO: Finish this builder up.  There are many parameters not here.
  def contextualBandits(cb: Int) = append(s"--cb $cb")
  def test() = append(s"-t")
  def binary() = append(s"--binary")
  def dataset(path: String) = append(s"-d $path")
  def learningRate(learningRate: Double) = append(s"-l $learningRate")
  def passes(passes: Int) = append(s"--passes $passes")
  def cache(cacheFile: String) = append(s"--cache_file $cacheFile")
  def quiet() = append("--quiet")
  def finalRegressor(path: String) = append(s"-f $path")
  def initialRegressor(path: String) = append(s"-i $path")

  def build = VwProcess(sb.toString())
  def execute = build()

  override def toString = s"vw ${sb.toString()}"
}
