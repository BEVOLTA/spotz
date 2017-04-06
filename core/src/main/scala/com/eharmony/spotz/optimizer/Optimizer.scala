package com.eharmony.spotz.optimizer

import com.eharmony.spotz.objective.Objective
import org.joda.time.{DateTime, Duration}

import scala.math.Ordering
import scala.reflect.ClassTag

/**
  * Optimizer trait.
  *
  * @tparam P point type passed to objective function
  * @tparam L loss returned from objective function evaluation
  * @tparam R optimizer result containing the best point and minimized or maximized loss
  */
trait Optimizer[P, L, S, +R] extends Serializable {
  def minimize(objective: Objective[P, L], space: S)(implicit c: ClassTag[P], p: ClassTag[L]): R
  def maximize(objective: Objective[P, L], space: S)(implicit c: ClassTag[P], p: ClassTag[L]): R
}

/**
  * All Spotz optimizers inherit from this trait.  An implicit Ordering is declared.  This Ordering
  * object is used during the reduce operation within <code>min</code> and <code>max</code>
  *
  * @tparam P point type passed to objective function
  * @tparam L loss returned from objective function evaluation
  * @tparam S the hyperparameter space to search
  * @tparam R optimizer result containing the best point and minimized or maximized loss
  */
trait AbstractOptimizer[P, L, S, R <: OptimizerResult[P, L]] extends Optimizer[P, L, S, R] {
  type Reducer[T] = (T, T) => T

  /** The ordering of a point, loss tuple used by the reducer */
  implicit val ord: Ordering[(P, L)]

  /**
    * Min reducer.
    *
    * @param p1 point and loss #1
    * @param p2 point and loss #2
    * @param ord an Ordering describing the strategy to sort a point and loss 2-Tuple
    * @return the point and loss that ranks lower
    */
  protected def min(p1: (P,L), p2: (P,L))(implicit ord: Ordering[(P,L)]): (P,L) = ord.min(p1, p2)

  /**
    * Max reducer.
    *
    * @param p1 point and loss #1
    * @param p2 point and loss #2
    * @param ord an Ordering describing the strategy to sort a point and loss 2-Tuple
    * @return the point and loss that ranks higher in the
    */
  protected def max(p1: (P,L), p2: (P,L))(implicit ord: Ordering[(P,L)]): (P,L) = ord.max(p1, p2)

  /**
    * Given an objective function, this method starts searching over the defined hyperparameter space
    * for the minimum loss returned by the objective function.
    *
    * @param objective an objective function
    * @param space a hyperparameter space defined by the caller
    * @param c ClassTag of point type P
    * @param p ClassTag of loss type L
    * @return an OptimizerResult[P, L] object
    */
  override def minimize(objective: Objective[P, L], space: S)(implicit c: ClassTag[P], p: ClassTag[L]): R = {
    optimize(objective, space, min)
  }

  /**
    * Given an objective function, this method starts searching over the defined hyperparameter space
    * for the maximunm loss returned by the objective function.
    *
    * @param objective an objective function
    * @param space a hyperparameter space defined by the caller
    * @param c ClassTag of point type P
    * @param p ClassTag of loss type L
    * @return an OptimizerResult[P, L] object
    */
  override def maximize(objective: Objective[P, L], space: S)(implicit c: ClassTag[P], p: ClassTag[L]): R = {
    optimize(objective, space, max)
  }

  /**
    * Concrete subclasses of this trait should override this method to provide an optimization
    * implementation.
    *
    * @param objective the objective function
    * @param space the hyperparameter space to search
    * @param reducer function to reduce (P, L) tuples
    * @param c point type P class tag
    * @param p loss type L class tag
    * @return an OptimizerResult[P, L]
    */
  protected def optimize(objective: Objective[P, L], space: S, reducer: Reducer[(P, L)])
                        (implicit c: ClassTag[P], p: ClassTag[L]): R
}

/**
  * Tracks state of an optimizer.  This is intended to be implemented by subclasses.
  *
  * @tparam P
  * @tparam L
  */
trait OptimizerState[P, L] {
  val bestPointSoFar: Option[P]
  val bestLossSoFar: Option[L]
  val startTime: DateTime
  val currentTime: DateTime
  val trialsSoFar: Long
  val optimizerFinished: Boolean

  def elapsedTime = new Duration(startTime, currentTime)
}

/**
  * Result of an optimizer.  All other optimization algorithms' results should inherit from this.
  * Minimally, this result contains the best point and the best loss.
  *
  * @param bestPoint the best point
  * @param bestLoss the best loss
  * @tparam P the point type
  * @tparam L the loss type
  */
abstract class OptimizerResult[P, L](bestPoint: P, bestLoss: L)
