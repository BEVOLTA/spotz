# spotz [![Build Status](https://travis-ci.org/eHarmony/spotz.svg?branch=master)](https://travis-ci.org/eHarmony/spotz) [![Stories in Ready](https://badge.waffle.io/eHarmony/spotz.png?label=ready&title=Ready)](https://waffle.io/eHarmony/spotz) #
# Spark Hyper Parameter Optimization for Vowpal Wabbit

At [eHarmony](http://www.eharmony.com), we make heavy use of
[Vowpal Wabbit](https://github.com/JohnLangford/vowpal_wabbit/wiki).
We use this learner so much that we feel strong Spark integration with VW
is very important.  Considering that Vowpal Wabbit does not support 
hyperparameter optimization out of the box, we've taken steps to support it.

A prerequisite is that VW is of course installed on all Spark worker
nodes.

Support for K-Fold cross validation has been included by implementing
an objective function that searches over your defined hyperparameter
space.  Spark acts as the distributed computation framework responsible
for parallelizing VW execution on nodes.

The hyperparameter space that is searched over in VW includes but is not
limited to the namespaces, the learning rate, l1, l2, etc.  In fact, you
can search over any parameter of your choice, as long as the space
of the parameter can be properly defined within Spotz.  The parameter
names are the same as the command line arguments passed to VW.

## Maven dependency

To use this as part of a maven build

```xml
<dependency>
    <groupId>com.eharmony</groupId>
    <artifactId>spotz-core</artifactId>
    <version>1.0.1</version>
<dependency>
<dependency>
    <groupId>com.eharmony</groupId>
    <artifactId>spotz-vw</artifactId>
    <version>1.0.1</version>
<dependency>
```


## Example

```scala
import com.eharmony.spotz.Preamble._
import com.eharmony.spotz.optimizer.StopStrategy
import com.eharmony.spotz.optimizer.random.SparkRandomSearch
import com.eharmony.spotz.optimizer.hyperparam.UniformDouble
import com.eharmony.spotz.optimizer.hyperparam.Combinations
import com.eharmony.spotz.objective.vw.SparkVwCrossValidationObjective
import org.apache.spark.{SparkConf, SparkContext}

val space = Map(
  ("l",  UniformDouble(0, 1)),
  ("l2", UniformDouble(0, 0.0005)),
  // Assume your VW dataset has three namespaces, 'a', 'b', 'c',  for
  // which you want to generate quadratic features with two randomly
  // chosen namespaces
  ("q",  Combinations(Seq("a", "b", "c"), k = 2, replacement = true))
)

val stop = StopStrategy.stopAfterMaxTrials(1000)
val optimizer = new SparkRandomSearch[Point, Double](sc, stop)

val rdd = sc.textFile("file:///path to vw dataset")
val objective = new SparkVwCrossValidationObjective(
  sc = sc,
  numFolds = 10,
  vwDataset = rdd.toLocalIterator,
  vwTrainParamsString = Option("--passes 20 -b 22"),
  vwTestParamsString = None
)
val searchResult = optimizer.minimize(objective, space)
val bestPoint = searchResult.bestPoint
val bestPoint = searchResult.bestLoss
```

All the cross validation logic resides in the objective function.
Internally, what happens is that the dataset is split into
K parts.  For every i'th part, a VW cache file is generated for the
remaining K - 1 parts and that i'th part.  The VW cache file
for the K - 1 parts is used for training and the i'th part cache
file is used for testing.  These cache files are distributed
through Spark onto the worker nodes participating in
the Spark job.  For a single K fold cross validation run,
sampled hyperparameters from the defined space are used as
arguments to VW.  On the training cache file, a model is trained with the
parameters specified by the caller and the sampled hyperparameters.  
Subsequently, this model is used with the test set cache file to compute a
loss for this fold.  There are K training and test runs of VW using the 
same sampled hyperparameters, one training and test run for each fold.  
The test losses for all folds are averaged to compute a single loss for 
the entire K fold cross validation run.  Spotz will keep track of this 
loss and its respective sampled hyperparameters.

Repeat this K fold cross validation process with newly sampled
hyperparameter values and a newly computed loss for as many trials 
as necessary until the stop strategy criteria has been fulfilled,
and finally return the best loss and its respective hyperparameters.

During cache generation, specifying "```-b```" in the objective's
training param string is important.  If it is not set,
it will default to 18.  Additionally, Spotz will control any VW
arguments related to caching and cleanup on the distributed Spark
workers.  The caller need only be concerned about the bit precision.
Specifying any arguments in the training parameter string related to
caching will be manually filtered out by Spotz except for "```-b```".
Cache generation and distribution to Spark nodes can affect the
time to complete the Spark job depending on hardware quality and 
dataset size.

To search over namespaces, using ```Combinations``` and ```Subsets```
sampling functions with VW arguments ```q```,```cubic```, and
```interactions``` is important.

## Random Search Space

For example to define a random search with the learning rate range
between 0 and 1, define a Map where the key "l" specifies the learning
rate as if you had passed that same parameter name to VW on the command line.  
The value is a Spotz sampling function.

```scala
val space = Map(
  ("l",  UniformDouble(0, 1))
)
```

This will define a space to allow sampling namespace combinations of 2
from the sequence of namespaces ```a```,```b```,```c```.  The combination
is generated randomly.  Two namespaces will then be passed to VW with
```-q``` argument.  VW can then interact the features of the two 
namespaces in the combination.

```scala
val space = Map( 
  ("q",  Combinations(Seq("a", "b", "c"), k = 2))
)
```

This requires using the ```SparkRandomSearch``` or ```ParRandomSearch```
optimizer.

## Grid Search Space

The same space searched in an ordered manner with grid search is
defined as follows.  The value of the Map is an iterable and not a
sampling function.

```scala
val space = Map(
  ("l", Range.Double(0, 1, 0.01)),
  ("q", Seq("a", "b", "c").combinations(2).toIterable)
)
```

This requires using a ```SparkGridSearch``` or ```ParGridSearch```
optimizer.

