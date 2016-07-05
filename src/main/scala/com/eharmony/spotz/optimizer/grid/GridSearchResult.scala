package com.eharmony.spotz.optimizer.grid

import com.eharmony.spotz.optimizer.OptimizerResult
import org.joda.time.format.PeriodFormatterBuilder
import org.joda.time.{DateTime, Duration}

/**
  * @author vsuthichai
  */
class GridSearchResult[P, L](
    bestPoint: P,
    bestLoss: L,
    startTime: DateTime,
    endTime: DateTime,
    totalTrials: Long,
    elapsedTime: Duration)
  extends OptimizerResult[P, L](bestPoint, bestLoss) {

  override def toString = {
    val formatter = new PeriodFormatterBuilder()
      .appendDays().appendSuffix("d")
      .appendHours().appendSuffix("h")
      .appendMinutes().appendSuffix("m")
      .appendSeconds().appendSuffix("s")
      .appendMillis().appendSuffix("ms")
      .toFormatter

    s"GridSearchResult(bestPoint=$bestPoint, bestLoss=$bestLoss, " +
      s"totalTrials=$totalTrials, duration=${formatter.print(elapsedTime.toPeriod)}"
  }
}