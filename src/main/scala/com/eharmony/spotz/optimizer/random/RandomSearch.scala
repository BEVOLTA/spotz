package com.eharmony.spotz.optimizer.random

import com.eharmony.spotz.objective.Objective
import com.eharmony.spotz.optimizer._
import com.eharmony.spotz.space.Space
import com.eharmony.spotz.spark.SparkFunctions
import org.apache.spark.SparkContext
import org.joda.time.format.PeriodFormatterBuilder
import org.joda.time.{DateTime, Duration}

import scala.annotation.tailrec
import scala.math.Ordering

/**
 * @author vsuthichai
 */
class RandomSearch[P, L](
    @transient sc: SparkContext,
    stopStrategy: StopStrategy,
    trialBatchSize: Int = 100000)
    (implicit pointLossOrdering: Ordering[(P, L)])
  extends SparkBaseOptimizer[P, L](sc)(pointLossOrdering)
    with SparkFunctions[P, L] {

  override def optimize(objective: Objective[P, L], space: Space[P], reducer: Reducer[(P, L)]): RandomSearchResult[P, L] = {
    val startTime = DateTime.now()
    val rngModifiedSpace = space.seed(0)
    val firstPoint = rngModifiedSpace.sample
    val firstLoss = objective(firstPoint)

    // Last three arguments maintain the best point and loss and the trial count
    randomSearch(objective, space, reducer, startTime, firstPoint, firstLoss, 1)
  }

  @tailrec
  private[this] def randomSearch(objective: Objective[P, L],
                                 space: Space[P],
                                 reducer: Reducer[(P, L)],
                                 startTime: DateTime,
                                 bestPointSoFar: P,
                                 bestLossSoFar: L,
                                 trialsSoFar: Long): RandomSearchResult[P, L] = {

    val endTime = DateTime.now()
    val elapsedTime = new Duration(startTime, endTime)

    stopStrategy.shouldStop(trialsSoFar, elapsedTime) match {
      case true  =>
        // Base case, End recursion
        new RandomSearchResult[P, L](bestPointSoFar, bestLossSoFar, startTime, endTime, trialsSoFar, elapsedTime)

      case false =>
        val batchSize = scala.math.min(stopStrategy.getMaxTrials - trialsSoFar, trialBatchSize).toInt
        val (bestPoint, bestLoss) = reducer((bestPointSoFar, bestLossSoFar),
                                            bestRandomPoint(trialsSoFar, batchSize, objective, space, reducer))
        // Last 3 args maintain the state
        randomSearch(objective, space, reducer, startTime, bestPoint, bestLoss, trialsSoFar + batchSize)
    }
  }
}