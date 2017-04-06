package com.eharmony.spotz.optimizer.grid

import com.eharmony.spotz.backend.{BackendFunctions, ParallelFunctions, SparkFunctions}
import com.eharmony.spotz.objective.Objective
import com.eharmony.spotz.optimizer.{AbstractOptimizer, OptimizerState, StopStrategy}
import com.eharmony.spotz.util.{DurationUtils, Logging}
import org.apache.spark.SparkContext
import org.joda.time.{DateTime, Duration}

import scala.annotation.tailrec
import scala.math.Ordering
import scala.reflect.ClassTag

/**
  * Grid search implementation.
  *
  * This class accepts a hyper parameter space on which to search using a grid search algorithm.
  * The parameter space must be specified through a Map where the key is a String that
  * identifies the hyper parameter label and the value is an Iterable type.  Grid search will iteratively
  * and exhaustively search over all possible combinations of values specified by the Iterable's in the Map.
  *
  * The implementation specifies two type parameters, P, the point representation of hyper parameters
  * and L, the resulting loss from evaluating the objective function.  The best point is kept track of while
  * iterating over the grid values.  Once the grid values have been exhausted, the search algorithm ends.
  *
  * Internally, points are evaluted in batches to allow intermediate updates from whatever distributed
  * computation framework is being used.
  *
  * @param stopStrategy stopping criteria specifying when to stop the search
  * @param trialBatchSize the batch size specifying the number of trials to execute in a batch
  * @param ord implicit Ordering of (P, L)
  * @param factory implicit function definition to convert a Map of hyperparameters to point type P
  * @tparam P point type passed to objective function
  * @tparam L loss returned from objective function evaluation
  */
abstract class GridSearch[P, L](
    stopStrategy: StopStrategy = StopStrategy.stopWhenOptimizerFinishes,
    trialBatchSize: Int)
    (implicit ord: Ordering[(P, L)], factory: Map[String, _] => P)
  extends AbstractOptimizer[P, L, Map[String, Iterable[Any]], GridSearchResult[P, L]]
  with BackendFunctions
  with Logging {

  override protected def optimize(objective: Objective[P, L],
                                  paramSpace: Map[String, Iterable[Any]],
                                  reducer: Reducer[(P, L)])
                                 (implicit c: ClassTag[P], p: ClassTag[L]): GridSearchResult[P, L] = {
    val space = new Grid[P](paramSpace)
    val startTime = DateTime.now()
    /*
    val firstPoint = space(0)
    val firstLoss = objective(firstPoint)
    */

    val gridSearchContext = GridSearchContext[P, L](
      bestPointSoFar = None,
      bestLossSoFar = None,
      startTime = startTime,
      currentTime = startTime,
      trialsSoFar = 0L,
      optimizerFinished = 0L >= space.size)

    // Last three arguments maintain the best point and loss and the trial count
    gridSearch(objective, space, reducer, gridSearchContext)
  }

  @tailrec
  private def gridSearch(objective: Objective[P, L],
                         space: Grid[P],
                         reducer: Reducer[(P, L)],
                         gsc: GridSearchContext[P, L])
                        (implicit c: ClassTag[P], p: ClassTag[L]): GridSearchResult[P, L] = {

    info(s"Best point and loss after ${gsc.trialsSoFar} trials and ${DurationUtils.format(gsc.elapsedTime)} : ${gsc.bestPointSoFar} loss: ${gsc.bestLossSoFar}")

    if (stopStrategy.shouldStop(gsc) && gsc.bestLossSoFar.isDefined && gsc.bestPointSoFar.isDefined) {
      // Base case, end recursion, return the result
      GridSearchResult[P, L](
        bestPoint = gsc.bestPointSoFar.get,
        bestLoss = gsc.bestLossSoFar.get,
        startTime = gsc.startTime,
        endTime = gsc.currentTime,
        elapsedTime = gsc.elapsedTime,
        totalTrials = gsc.trialsSoFar)
    } else {
      val currentTime = DateTime.now()
      val elapsedTime = new Duration(gsc.startTime, currentTime)

      val batchSize = scala.math.min(space.size - gsc.trialsSoFar, trialBatchSize)

      val (point, loss) = bestGridPointAndLoss(gsc.trialsSoFar, batchSize, objective, space, reducer)

      val (bestPoint, bestLoss) =
        if (gsc.bestPointSoFar.isDefined && gsc.bestLossSoFar.isDefined)
          reducer((gsc.bestPointSoFar.get, gsc.bestLossSoFar.get), (point, loss))
        else
          (point, loss)

      val trialsSoFar = gsc.trialsSoFar + batchSize

      val gridSearchContext = GridSearchContext(
        bestPointSoFar = Option(bestPoint),
        bestLossSoFar = Option(bestLoss),
        startTime = gsc.startTime,
        currentTime = currentTime,
        trialsSoFar = trialsSoFar,
        optimizerFinished = trialsSoFar >= space.size)

      gridSearch(objective, space, reducer, gridSearchContext)
    }
  }
}

case class GridSearchContext[P, L](
    bestPointSoFar: Option[P],
    bestLossSoFar: Option[L],
    startTime: DateTime,
    currentTime: DateTime,
    trialsSoFar: Long,
    optimizerFinished: Boolean) extends OptimizerState[P, L]

/**
  * Grid search backed by parallel collections.
  *
  * @param trialBatchSize default batch size specifying number of points to batch process
  * @param ord Ordering for point type P
  * @param factory a function that takes a Map of sampled hyper parameter values and instanties the point P
  * @tparam P point type representation
  * @tparam L loss type representation
  */
class ParGridSearch[P, L](
    stopStrategy: StopStrategy = StopStrategy.stopWhenOptimizerFinishes,
    trialBatchSize: Int = 1000000)
    (implicit val ord: Ordering[(P, L)], factory: Map[String, _] => P)
  extends GridSearch[P, L](stopStrategy, trialBatchSize)(ord, factory)
  with ParallelFunctions

/**
  * Grid search backed by Spark.
  *
  * @param sc SparkContext
  * @param trialBatchSize default batch size specifying number of points to batch process
  * @param ord Ordering for point type P
  * @param factory a function that takes a Map of sampled hyper parameter values and instanties the point P
  * @tparam P point type representation
  * @tparam L loss type representation
  */
class SparkGridSearch[P, L](
    @transient val sc: SparkContext,
    stopStrategy: StopStrategy = StopStrategy.stopWhenOptimizerFinishes,
    trialBatchSize: Int = 1000000)
    (implicit val ord: Ordering[(P, L)], factory: Map[String, _] => P)
  extends GridSearch[P, L](stopStrategy, trialBatchSize)(ord, factory)
  with SparkFunctions
