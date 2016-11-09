# spotz [![Build Status](https://travis-ci.org/eHarmony/spotz.svg?branch=master)](https://travis-ci.org/eHarmony/spotz) [![Stories in Ready](https://badge.waffle.io/eHarmony/spotz.png?label=ready&title=Ready)](https://waffle.io/eHarmony/spotz) #
# Spark Parameter Optimization

## Vowpal Wabbit
At [eHarmony](http://www.eharmony.com), we make heavy use of
[Vowpal Wabbit](https://github.com/JohnLangford/vowpal_wabbit/wiki).
We use this learner so much that we feel strong integration with VW is very
important.  Considering that Vowpal Wabbit does not support hyperparameter
optimization out of the box, we've taken steps to support it.

Support for K-Fold cross validation has been included by implementing
an objective function that searches over your defined hyperparameter
space.  Spark acts as the distributed computation framework responsible
for parallelizing VW execution on nodes.

The hyperparameter space that is searched over in VW includes but is not
limited to the namespaces, the learning rate, l1, l2, etc.  In fact, you
can search over any parameter of your choice, as long as the space
of the parameter can be properly defined within Spotz.  The parameter
names are the same as the command line arguments passed to VW.

For example to search within the learning rate space between 0 and 1, 'l'
specifies the learning rate as if you had passed that same parameter name
to VW on the command line.  

```scala
val space = Map(
  ("l",  UniformDouble(0, 1))
  ("q",  Combinations(Seq("a", "b", "c"), k = 2, replacement = true))
)
```

## Maven dependency

To use this as part of a maven build

```xml
<dependency>
    <groupId>com.eharmony</groupId>
    <artifactId>spotz-vw</artifactId>
    <version>1.0.1</version>
<dependency>
```

## Example

```scala
val space = Map(
  ("l",  UniformDouble(0, 1)),
  ("l2", UniformDouble(0, 0.0005)),
  ("q",  Combinations(Seq("a", "b", "c"), k = 2, replacement = true))
)

val stop = StopStrategy.stopAfterMaxTrials(1000)
val optimizer = new SparkRandomSearch[Point, Double](sc, stop)

val rdd = sc.textFile("file:///path to vw dataset")
val objective = new SparkVwCrossValidationObjective(
  sc = sc,
  numFolds = 10,
  vwDataset = rdd.toLocalIterator,
  Option("--passes 20 --cache_file /tmp/vw.cache -k -b 22")
  None
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
arguments to VW and there are K training and test runs of VW using the same
same sampled hyperparameters, one training and test run for each fold.
The test losses for all folds are averaged to compute a single
loss for the entire K fold cross validation run.  Spotz will keep track of
this loss and its respective sampled hyperparameters.

Repeat this K fold cross validation process with newly sampled
hyperparameter values and a newly computed loss for as many trials 
as necessary until the stop strategy criteria has been fulfilled,
and finally return the best loss and its respective hyperparameters.

