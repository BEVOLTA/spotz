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

Support for K-Fold cross validation has also been included by implementing
an objective function that 

## Maven dependency

To use this as part of a maven build

```xml
<dependency>
    <groupId>com.eharmony</groupId>
    <artifactId>spotz-vw</artifactId>
    <version>1.0.1</version>
<dependency>
```

## Usage

Using this framework consists of writing the following boilerplate code:

1. Import the default definitions from the spotz ```Preamble``` object.
Importing from a library Preamble is a Scala convention to bring in default
definitions into the current scope.
2. Define the objective function.
3. Define the space of hyperparameter values that you wish to search.
4. Select the solver.

## Imports

Import the default definitions from the spotz preamble object

```scala
import com.eharmony.spotz.Preamble._
```

## Objective Function Trait

Define your objective function by implementing the ```Objective[P, L]```
trait.

## Hyperparameter Space


## Choose Solver


### Stop Strategies


## Full Example

