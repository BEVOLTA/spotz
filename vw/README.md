# spotz [![Build Status](https://travis-ci.org/eHarmony/spotz.svg?branch=master)](https://travis-ci.org/eHarmony/spotz) [![Stories in Ready](https://badge.waffle.io/eHarmony/spotz.png?label=ready&title=Ready)](https://waffle.io/eHarmony/spotz) #
# Spark Parameter Optimization

## Vowpal Wabbit
At [eHarmony](http://www.eharmony.com), we make heavy use of
[Vowpal Wabbit](https://github.com/JohnLangford/vowpal_wabbit/wiki).
We use this learner so much that we feel strong integration with VW is very
important.  Considering that Vowpal Wabbit does not support hyperparameter
optimization out of the box, we've taken steps to support it.

The hyperparameter space that is searched over in VW includes but is not
limited to the namespaces, the learning rate, l1, l2.

Support for K-Fold cross validation has been included by implementing
an objective function that searches over your defined hyperparameter
space.  Spark acts as the distributed computation framework responsible
for parallelizing VW execution on nodes.

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
println(bestPoint)
```
