package com.eharmony.spotz.optimizer.random

import com.eharmony.spotz.backend.{BackendFunctions, ParallelFunctions, SparkFunctions}
import com.eharmony.spotz.objective.Objective
import com.eharmony.spotz.optimizer._
import com.eharmony.spotz.optimizer.hyperparam.RandomSampler
import com.eharmony.spotz.util.{DurationUtils, Logging}
import org.apache.spark.SparkContext
import org.joda.time.DateTime

import scala.annotation.tailrec
import scala.math.Ordering
import scala.reflect.ClassTag
import scala.util.Random

/**
  * Random search implementation.
  *
  * This search algorithm samples from a predefined Map of RandomSamplers to materialize hyper parameter
  * values.  This hyper parameter values are represented through a type parameter P, the point representation.
  * These points are then evaluated with the supplied objective function.  A StopStrategy object must be
  * supplied by the caller to specify search algorithm stopping criteria.
  *
  * {{{
  *   import com.eharmony.spotz.Preamble._
  *   import com.eharmony.spotz.optimizer.NormalDistribution
  *
  *   val rs = new RandomSearch[Point, Double](sc, StopStrategy.stopAfterMaxTrials(1000000L))
  *   val result = rs.minimize(new MyObjective(), Map(
  *     ("x1", NormalDistribution(0, 0.1))
  *   ))
  * }}}
  *
  * @author vsuthichai
  */
abstract class RandomSearch[P, L](
    stopStrategy: StopStrategy,
    trialBatchSize: Int,
    seed: Int = 0)
    (implicit val ord: Ordering[(P, L)], factory: Map[String, _] => P)
  extends AbstractOptimizer[P, L, Map[String, RandomSampler[_]], RandomSearchResult[P, L]]
  with BackendFunctions
  with Logging {

  /**
    * Sample from the user defined hyper parameter constraints.
    *
    * @param params the user defined hyper parameters
    * @param theSeed random number generator seed
    * @return the point P of the sampled hyper parameters
    */
  private def sample(params: Map[String, RandomSampler[_]], theSeed: Long): P = {
    val rng = new Random(theSeed)
    // Get rid of the first sample due to low entropy
    rng.nextDouble()
    factory(params.map { case (label, sampler) => (label, sampler(rng)) } )
  }

  override protected def optimize(objective: Objective[P, L],
                                  paramSpace: Map[String, RandomSampler[_]],
                                  reducer: Reducer[(P, L)])
                                 (implicit c: ClassTag[P], p: ClassTag[L]): RandomSearchResult[P, L] = {
    val startTime = DateTime.now()

    val randomSearchContext = RandomSearchContext[P, L](
      bestPointSoFar = None,
      bestLossSoFar = None,
      startTime = startTime,
      currentTime = startTime,
      trialsSoFar = 0,
      optimizerFinished = false)

    randomSearch(objective, reducer, paramSpace, randomSearchContext)
  }

  /**
    * A tail recursive method that performs the random search.  We keep track of the best point and the best loss
    * represented by P and L.  This function delegates to the BackendFunctions trait to parallelize the objective
    * function evaluations.  The method completes when the stop strategy specified by the user evaluted to true.
    *
    * @param objective the objective function
    * @param reducer the function used to reduce points
    * @param rsc the RandomSearchContext object which maintains the updated state of the search
    * @param c ClassTag for P
    * @param p ClassTag for C
    * @return a <code>RandomSearchResult</code>
    */
  @tailrec
  private[this] def randomSearch(objective: Objective[P, L],
                                 reducer: Reducer[(P, L)],
                                 paramSpace: Map[String, RandomSampler[_]],
                                 rsc: RandomSearchContext[P, L])
                                (implicit c: ClassTag[P], p: ClassTag[L]): RandomSearchResult[P, L] = {

    info(s"Best point and loss after ${rsc.trialsSoFar} trials and ${DurationUtils.format(rsc.elapsedTime)} : ${rsc.bestPointSoFar} loss: ${rsc.bestLossSoFar}")

    if (stopStrategy.shouldStop(rsc) && rsc.bestPointSoFar.isDefined && rsc.bestLossSoFar.isDefined) {
      // Base case, end recursion, return the result
      new RandomSearchResult[P, L](
        bestPoint = rsc.bestPointSoFar.get,
        bestLoss = rsc.bestLossSoFar.get,
        startTime = rsc.startTime,
        endTime = rsc.currentTime,
        elapsedTime = rsc.elapsedTime,
        totalTrials = rsc.trialsSoFar)
    } else {
      val batchSize = scala.math.min(stopStrategy.getMaxTrials - rsc.trialsSoFar, trialBatchSize)
      // TODO: Adaptive batch sizing
      //val batchSize = nextBatchSize(None, elapsedTime, currentBatchSize, trialsSoFar, null, stopStrategy.getMaxTrials)

      val (point, loss) = bestRandomPointAndLoss(rsc.trialsSoFar, batchSize, objective, reducer, paramSpace, sample, seed)

      val (bestPoint, bestLoss) =
        if (rsc.bestPointSoFar.isDefined && rsc.bestLossSoFar.isDefined)
          reducer((rsc.bestPointSoFar.get, rsc.bestLossSoFar.get), (point, loss))
        else
          (point, loss)

      val currentTime = DateTime.now()

      val randomSearchContext = RandomSearchContext(
        bestPointSoFar = Option(bestPoint),
        bestLossSoFar = Option(bestLoss),
        startTime = rsc.startTime,
        currentTime = currentTime,
        trialsSoFar = rsc.trialsSoFar + batchSize,
        optimizerFinished = false)

        // Last argument maintain the state
      randomSearch(objective, reducer, paramSpace, randomSearchContext)
    }
  }
}

/**
  * Context object used to maintain and pass state within RandomSearch.  This is used in lieu of
  * using mutable variables.  It is also inspected by stop strategies to determine when to end
  * a search.
  *
  * @param bestPointSoFar the best point so far in the search
  * @param bestLossSoFar the best loss so far in the search
  * @param startTime the start time the search begin
  * @param currentTime the current time
  * @param trialsSoFar the number of trials executed so far
  * @param optimizerFinished boolean value indicating when a search has stopped
  * @tparam P point type
  * @tparam L loss type
  */
case class RandomSearchContext[P, L](
    bestPointSoFar: Option[P],
    bestLossSoFar: Option[L],
    startTime: DateTime,
    currentTime: DateTime,
    trialsSoFar: Long,
    optimizerFinished: Boolean) extends OptimizerState[P, L]

/**
  * This implementation uses parallel collections to evaluate the objective function.  Internally,
  * threads are used.
  *
  * @param stopStrategy the stop strategy criteria specifying when the search should end
  * @param trialBatchSize batch size specifying the number of points to process per epoch
  * @param ord an implicit Ordering for the point representation type parameter P
  * @param factory an implicit function specifying how to instantiate P given a Map of sampled hyper parameter values
  * @tparam P point type representation
  * @tparam L loss type representation
  */
class ParRandomSearch[P, L](
    stopStrategy: StopStrategy,
    trialBatchSize: Int = 1000000)
    (implicit ord: Ordering[(P, L)], factory: Map[String, _] => P)
  extends RandomSearch[P, L](stopStrategy, trialBatchSize)(ord, factory)
  with ParallelFunctions

/**
  * Random search on top of Spark.
  *
  * @param stopStrategy the stop strategy criteria specifying when the search should end
  * @param trialBatchSize batch size specifying the number of points to process per epoch
  * @param ord an implicit Ordering for the point representation type parameter P
  * @param factory an implicit function specifying how to instantiate P given a Map of sampled hyper parameter values
  * @tparam P point type representation
  * @tparam L loss type representation
  */
class SparkRandomSearch[P, L](
    @transient val sc: SparkContext,
    stopStrategy: StopStrategy,
    trialBatchSize: Int = 1000000)
    (implicit ord: Ordering[(P, L)], factory: Map[String, _] => P)
  extends RandomSearch[P, L](stopStrategy, trialBatchSize)(ord, factory)
  with SparkFunctions
