package com.eharmony.spotz.util

import java.io._
import java.util.zip.{GZIPInputStream, GZIPOutputStream}

import org.apache.commons.io.FilenameUtils
import org.apache.commons.lang3.StringUtils
import org.apache.commons.vfs2.{FileNotFoundException, FileSystemManager, VFS}
import org.apache.spark.rdd.RDD
import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormat
import resource.managed

import scala.io.Codec
import scala.io.Source
import scala.util.{Success, Try}
import sys.process._

/**
  * @author vsuthichai
  */
object FileUtil {
  private val vfs2: FileSystemManager = VFS.getManager
  private val pwd = new File(System.getProperty("user.dir"))

  /**
    *
    * @param absolutePath
    * @return
    */
  def gzip(absolutePath: String): String = {
    val is = loadFileInputStream(absolutePath)
    val fos = new FileOutputStream(absolutePath + ".gz")
    val gzipfos = new GZIPOutputStream(fos)

    val buffer = new Array[Byte](4096)
    var bytes_read = is.read(buffer)
    while (bytes_read > 0) {
      gzipfos.write(buffer, 0, bytes_read)
      bytes_read = is.read(buffer)
    }

    is.close()
    gzipfos.finish()
    gzipfos.close()

    absolutePath + ".gz"
  }

  /**
    *
    * @param absolutePath
    * @return
    */
  def gunzip(absolutePath: String): String = {
    val gzis = new GZIPInputStream(new FileInputStream(absolutePath))

    val outputFilename = if (absolutePath.endsWith(".gz")) {
      val filename = absolutePath.substring(0, absolutePath.length - 3)
      val ext = FilenameUtils.getExtension(filename)
      if (StringUtils.isEmpty(ext))
        tempFile(filename, "txt", true).getAbsolutePath
      else {
        val filenameWithoutExt = filename.substring(0, filename.indexOf('.'))
        tempFile(filenameWithoutExt, ext, true).getAbsolutePath
      }
    } else {
      tempFile(absolutePath, "txt", true).getAbsolutePath
    }

    val out = new FileOutputStream(outputFilename)

    val buffer = new Array[Byte](4096)
    var bytes_read = gzis.read(buffer)
    while (bytes_read > 0) {
      out.write(buffer, 0, bytes_read)
      bytes_read = gzis.read(buffer)
    }

    gzis.close()
    out.close()

    outputFilename
  }

  /**
    * Return a file with a filename guaranteed not to be used on the file system.  This is
    * mainly used for files with a lifetime of a jvm run.
    *
    * @param prefix
    * @param suffix
    * @param deleteOnExit boolean indicating if this file should be deleted when the jvm exists
    * @return
    */
  def tempFile(prefix: String, suffix: String, deleteOnExit: Boolean): File = {
    val f = File.createTempFile(s"$prefix-", s".$suffix")
    if (deleteOnExit)
      f.deleteOnExit()
    f
  }

  /**
    * Create a temp file.
    *
    * @param filename name of temp file that will be enhanced with more characters to ensure it doesn't exist on
    *                 the file system
    * @param deleteOnExit boolean indicating if this file should be deleted when the jvm exists
    * @return
    */
  def tempFile(filename: String, deleteOnExit: Boolean = true): File = {
    tempFile(FilenameUtils.getBaseName(filename), FilenameUtils.getExtension(filename), deleteOnExit)
  }

  def tempFile(inputIterator: Iterator[String]): File = {
    val tempFile = FileUtil.tempFile("file.temp")
    val printWriter = new PrintWriter(tempFile)
    inputIterator.foreach(line => printWriter.println(line))
    printWriter.close()
    tempFile
  }

  /**
    * Load the lines of a file as an iterator.
    *
    * @param path input path
    * @return lines of the file as an Iterator[String]
    */
  def fileLinesIterator(path: String): Iterator[String] = {
    val is = loadFileInputStream(path)
    Source.fromInputStream(is)(Codec("UTF-8")).getLines()
  }

  /**
    *
    * @param path
    * @return
    */
  def loadFileInputStream(path: String): InputStream = {
    val vfsFile = vfs2.resolveFile(pwd, path)
    vfsFile.getContent.getInputStream
  }
}

object SparkFileUtil {
  import org.apache.spark.SparkContext
  import org.apache.hadoop.mapred.InvalidInputException

  /**
    * Load the lines of a file as an iterator.  Also attempt to load the file from HDFS
    * since the SparkContext is available.
    *
    * @param path input path
    * @return lines of the file as an Iterator[String]
    */
  def loadFile(sc: SparkContext, path: String): Iterator[String] = {
    try {
      FileUtil.fileLinesIterator(path)
    } catch {
      case e: FileNotFoundException =>
        try {
          sc.textFile(path).toLocalIterator
        } catch {
          case e: InvalidInputException => Source.fromInputStream(this.getClass.getResourceAsStream(path)).getLines()
        }
    }
  }

  /**
    * TODO Fix this.  It's too inefficient to save an RDD to hdfs and merge it to a local file.
    * @param sc
    * @param rdd
    * @return
    */
  def saveToLocalFile(sc: SparkContext, rdd: RDD[String]): String = {
    val dtf = DateTimeFormat.forPattern("yyyyMMdd-HHmmss")
    val dt = DateTime.now()
    val hdfsPath = s"hdfs:///tmp/spotz-vw-dataset-${dtf.print(dt)}"
    val tempFile = FileUtil.tempFile(s"spotz-vw-dataset-${dtf.print(dt)}", "txt", deleteOnExit = true)
    rdd.saveAsTextFile(hdfsPath)
    s"hdfs dfs -getmerge $hdfsPath ${tempFile.getAbsolutePath}".!
    s"hdfs dfs -rm -r $hdfsPath".!
    tempFile.getAbsolutePath
  }
}
