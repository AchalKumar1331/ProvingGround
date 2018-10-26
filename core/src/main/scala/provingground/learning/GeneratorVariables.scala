package provingground.learning
import provingground.{FiniteDistribution => FD}
import shapeless._
import HList._
import provingground.learning.GeneratorNode._

import scala.language.higherKinds

/**
  * resolving a general specification of a recursive generative model as finite distributions, depending on truncation;
  * the coefficients of the various generator nodes should be `Double`
  *
  * @param nodeCoeffSeq the various generator nodes with coefficients for various random variables and families
  * @param sd finite distributions from the initial state corresponding to random variables and families
  * @tparam State scala type of the initial state
  */
case class GeneratorVariables[State, Boat](
    nodeCoeffSeq: NodeCoeffSeq[State, Boat, Double],
    state: State)(implicit sd: StateDistribution[State, FD]) {

  def varSupport[Y](rv: RandomVar[Y]): Set[Y] =
    StateDistribution.value(state)(rv).support

  def varListSupport[Dom <: HList](rvs: RandomVarList[Dom]): Set[Dom] =
    rvs match {
      case RandomVarList.Nil => Set(HNil)
      case RandomVarList.Cons(head, tail) =>
        val headSet = varSupport(head)
        val tailSet = varListSupport(tail)
        for {
          x <- headSet
          y <- tailSet
        } yield x :: y
    }
  import GeneratorVariables._

  def varFamilyVars[Dom <: HList, Y](
      rvF: RandomVarFamily[Dom, Y]): Set[GeneratorVariables.Variable[_]] =
    rvF match {
      case rv: RandomVar[u] =>
        for {
        y <- StateDistribution.value(state)(rv).support
        } yield Elem(y, rv)
      case _ =>
        for {
          x <- varListSupport(rvF.polyDomain)
          dist : FD[Y] =  StateDistribution.valueAt(state)(rvF, x)
          y <- dist.support
        } yield Elem(y, rvF.at(x))
    }

  lazy val outputVars: Set[Variable[_]] =
    nodeCoeffSeq.outputs.toSet.flatMap(
      (rvF: RandomVarFamily[_ <: HList, _]) => varFamilyVars(rvF)
    )

  def generatorVars[Y](
      generatorNode: GeneratorNode[Y]): Set[GeneratorVariables.Variable[_]] =
    generatorNode match {
      case tc: ThenCondition[o, Y] =>
        Set(GeneratorVariables.Event(tc.gen.output, tc.condition))
      case MapOpt(f, input, _) =>
        Set(GeneratorVariables.Event(input, Sort.Restrict(f)))
      case zm: ZipMapOpt[o1, o2, Y] =>
        Set(
          GeneratorVariables
            .PairEvent(zm.input1, zm.input2, Sort.Restrict[(o1, o2), Y] {
              case (x, y) => zm.f(x, y)
            }))
      case fm: FlatMap[o, Y] =>
//        pprint.log(fm)
        varSupport(fm.baseInput).flatMap((x) => generatorVars(fm.fiberNode(x)))
      case fm: FlatMapOpt[o, Y] =>
//        pprint.log(fm)
        for {
          x    <- varSupport(fm.baseInput)
          node <- fm.fiberNodeOpt(x).toVector
          v    <- generatorVars(node)
        } yield v
      // case isle: Island[Y, State, o, b] =>
      //   pprint.log(isle)
      //   import isle._
      //   val (isleInit, boat) = initMap(state)
      //   pprint.log(isleInit)
      //   val isleVars: Set[Variable[_]] =
      //     GeneratorVariables(nodeCoeffSeq, isleInit).allVars
      //   isleVars.map((x) => GeneratorVariables.InIsle(x, boat))
      case _ => Set()
    }

  def generatorFamilyVars[Dom <: HList, O](
      generatorNodeFamily: GeneratorNodeFamily[Dom, O],
      output: RandomVarFamily[Dom, O]): Set[Variable[_]] =
    generatorNodeFamily match {
      case p: GeneratorNodeFamily.Pi[Dom, O] =>
        varListSupport(output.polyDomain).flatMap((x) =>
          generatorVars(p.nodes(x)))
      case p: GeneratorNodeFamily.PiOpt[Dom, O] =>
        varListSupport(output.polyDomain).flatMap(
          (x) =>
            p.nodesOpt(x)
              .toSet
              .flatMap((node: GeneratorNode[O]) => generatorVars(node)))
      case node: GeneratorNode[O] => generatorVars(node)
    }

  def nodeSeqVars(nc: NodeCoeffSeq[State, Boat, Double]): Set[Variable[_]] =
    nc match {
      case _: NodeCoeffSeq.Empty[State, Boat, Double] => Set()
      case ncs: NodeCoeffSeq.Cons[State, Boat, Double, rd, y] =>
        nodeSeqVars(ncs.tail) union {
          ncs.head.nodeFamilies.flatMap(nf =>
            generatorFamilyVars[rd, y](nf, ncs.head.output))
        }
    }

  lazy val allVars: Set[Variable[_]] = outputVars union nodeSeqVars(
    nodeCoeffSeq)

}

object GeneratorVariables {
  def variableValue[Y, State, Boat](boatMap: (Boat, State) => State,
                                    state: State)(
      implicit sd: StateDistribution[State, FD]): Variable[Y] => Double = {
    case rv @ Elem(element, randomVar) =>
      val fd = StateDistribution.value(state)(randomVar)
      fd(element)
    case NodeCoeff(_) => 1
    case Event(base, sort) =>
      val fd = StateDistribution.value(state)(base)
      fd.filter(sort.pred).total
    case PairEvent(base1, base2, sort) =>
      val fd1 = StateDistribution.value(state)(base1)
      val fd2 = StateDistribution.value(state)(base2)
      fd1.zip(fd2).filter(sort.pred).total
    case isle: InIsle[y, Boat] =>
      val x = variableValue(boatMap, boatMap(isle.boat, state))
      x(isle.isleVar)
  }

  /**
    * a variable representing a probability
    * @tparam Y objects in the distribution
    */
  sealed trait Variable[+Y]

  case class Elem[Y](element: Y, randomVar: RandomVar[Y]) extends Variable[Y]

  trait ElemList[Dom <: HList]

  object ElemList{
    case object Empty extends ElemList[HNil]

    case class Cons[Y, Z <: HList](head: Elem[Y], tail: ElemList[Z]) extends ElemList[Y :: Z]
  }

  case class Event[X, Y](base: RandomVar[X], sort: Sort[X, Y])
      extends Variable[Y]

  case class PairEvent[X1, X2, Y](base1: RandomVar[X1],
                                  base2: RandomVar[X2],
                                  sort: Sort[(X1, X2), Y])
      extends Variable[Y]

  case class InIsle[Y, Boat](isleVar: Variable[Y], boat: Boat)
      extends Variable[Y]

  case class NodeCoeff[RDom <: HList, Y](
      nodeFamily: GeneratorNodeFamily[RDom, Y])
      extends Variable[Unit]

  sealed trait Expression {
    def mapVars(f: Variable[_] => Variable[_]): Expression

    def useBoat[Boat](boat: Boat): Expression = mapVars(InIsle(_, boat))

    def +(that: Expression): Sum = Sum(this, that)

    def *(that: Expression): Product = Product(this, that)

    def /(that: Expression): Quotient = Quotient(this, that)

    def -(that: Expression): Sum = this + (that * Literal(-1))

    def unary_- : Expression = this * Literal(-1)
  }

  sealed trait VarVal[+Y] extends Expression {
    val variable: Variable[Y]
  }

  case class FinalVal[+Y](variable: Variable[Y]) extends VarVal[Y] {
    def mapVars(f: Variable[_] => Variable[_]): Expression =
      FinalVal(f(variable))
  }

  case class InitialVal[+Y](variable: Variable[Y]) extends VarVal[Y] {
    def mapVars(f: Variable[_] => Variable[_]): Expression =
      InitialVal(f(variable))
  }

  case class Expectation[Y](rv: RandomVar[Y], f: Y => Expression)
      extends Expression {
    def mapVars(f: Variable[_] => Variable[_]): Expectation[Y] = this
  }

  case class Log(exp: Expression) extends Expression {
    def mapVars(f: Variable[_] => Variable[_]): Expression = Log(exp.mapVars(f))
  }

  case class Sum(x: Expression, y: Expression) extends Expression {
    def mapVars(f: Variable[_] => Variable[_]): Sum =
      Sum(x.mapVars(f), y.mapVars(f))
  }

  case class Product(x: Expression, y: Expression) extends Expression {
    def mapVars(f: Variable[_] => Variable[_]): Product =
      Product(x.mapVars(f), y.mapVars(f))
  }

  case class Literal(value: Double) extends Expression {
    def mapVars(f: Variable[_] => Variable[_]): Literal = this
  }

  case class Quotient(x: Expression, y: Expression) extends Expression {
    def mapVars(f: Variable[_] => Variable[_]): Quotient =
      Quotient(x.mapVars(f), y.mapVars(f))
  }

  case class Equation(lhs: Expression, rhs: Expression) {
    def mapVars(f: Variable[_] => Variable[_]) =
      Equation(lhs.mapVars(f), rhs.mapVars(f))

    def useBoat[Boat](boat: Boat): Equation = mapVars(InIsle(_, boat))
  }

  case class EquationTerm(lhs: Expression, rhs: Expression) {
    def *(sc: Expression) = EquationTerm(lhs, rhs * sc)

    def *(x: Double)              = EquationTerm(lhs, rhs * Literal(x))
    def useBoat[Boat](boat: Boat) = EquationTerm(lhs, rhs.useBoat(boat))
  }

  def groupEquations(ts: Set[EquationTerm]): Set[Equation] =
    ts.groupBy(_.lhs)
      .map { case (lhs, rhss) => Equation(lhs, rhss.map(_.rhs).reduce(_ + _)) }
      .toSet
}
