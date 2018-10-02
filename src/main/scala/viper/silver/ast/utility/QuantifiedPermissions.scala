/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package viper.silver.ast.utility

import scala.collection.mutable
import scala.language.postfixOps
import viper.silver.ast._

/** Utility methods for quantified predicate permissions. */
object QuantifiedPermissions {
  /** Utility methods for matching on quantified permission assertions.
    * Decomposes a quantifier of the shape (with quantified variables xs)
    *   `forall xs :: c(xs) ==> acc(...)``
    * into a tuple with the following elements:
    *   1. matched forall node
    *   2. condition `c(xs)``
    *   3. accessibility predicate `acc(...)`
    *
    * The implication is optional: if the quantifier body consists of an accessibility
    * predicate only, then the returned condition will be a [[TrueLit]].
    */
  object QuantifiedPermissionAssertion {
    def unapply(forall: Forall): Option[(Forall, Exp, ResourceAccess)] = {
      forall match {
        case SourceQuantifiedPermissionAssertion(`forall`, condition, res: ResourceAccess) =>
          Some((forall, condition, res))
        case _ =>
          None
      }
    }
  }

  object SourceQuantifiedPermissionAssertion {
    def unapply(forall: Forall): Option[(Forall, Exp, Exp)] = {
      forall match {
        case Forall(_, _, Implies(condition, rhs)) =>
          Some((forall, condition, rhs))
        case Forall(_, _, expr) =>
          Some(forall, BoolLit(true)(forall.pos, forall.info), expr)
        case _ =>
          None
      }
    }
  }

  /* TODO: Computing the set of quantified fields would probably benefit from caching
   *       the (sub)results per member. Ideally, it would be possible to retrieve the
   *       (cached) results from the members themselves,
   *       e.g. someMethod.quantifiedFields.
   */

  def quantifiedFields(root: Node, program: Program): collection.Set[Field] = {
    val collected = mutable.LinkedHashSet[Field]()
    val visited = mutable.Set[Member]()
    val toVisit = mutable.Queue[Member]()

    root match {
      case m: Member => toVisit += m
      case _ =>
    }

    toVisit ++= Nodes.referencedMembers(root, program)

    quantifiedFields(toVisit, collected, visited, program)

    collected
  }

  /* TODO: See comment above about caching
   * TODO: Unify with corresponding code for fields
   */
  def quantifiedPredicates(root: Node, program: Program): collection.Set[Predicate] = {
    val collected = mutable.LinkedHashSet[Predicate]()
    val visited = mutable.Set[Member]()
    val toVisit = mutable.Queue[Member]()

    root match {
      case m: Member => toVisit += m
      case _ =>
    }

    toVisit ++= Nodes.referencedMembers(root, program)

    quantifiedPredicates(toVisit, collected, visited, program)

    collected
  }

  def quantifiedMagicWands(root: Node, program: Program): collection.Set[MagicWandStructure.MagicWandStructure] = {
    (root collect {
      case QuantifiedPermissionAssertion(_, _, wand: MagicWand) => Seq(wand.structure(program))
      case Forall(_,triggers,_) => triggers flatMap (_.exps) collect {case wand: MagicWand => wand.structure(program)}
    } toSet) flatten
  }

  private def quantifiedFields(toVisit: mutable.Queue[Member],
                               collected: mutable.LinkedHashSet[Field],
                               visited: mutable.Set[Member],
                               program: Program) {

    while (toVisit.nonEmpty) {
      val root = toVisit.dequeue()

      root visit {
        case QuantifiedPermissionAssertion(_, _, acc: FieldAccessPredicate) =>
          collected += acc.loc.field
        case Forall(_,triggers,_) => collected ++= triggers flatMap (_.exps) collect {case fa: FieldAccess => fa.field}
      }

      visited += root

      utility.Nodes.referencedMembers(root, program) foreach (m =>
        if (!visited.contains(m)) toVisit += m)
    }
  }

  private def quantifiedPredicates(toVisit: mutable.Queue[Member],
                                   collected: mutable.LinkedHashSet[Predicate],
                                   visited: mutable.Set[Member],
                                   program: Program) {

    while (toVisit.nonEmpty) {
      val root = toVisit.dequeue()

      root visit {
        case QuantifiedPermissionAssertion(_, _, acc: PredicateAccessPredicate) =>
          collected += program.findPredicate(acc.loc.predicateName)
        case Forall(_,triggers,_) => collected ++= triggers flatMap (_.exps) collect {case pa: PredicateAccess => pa.loc(program)}
      }

      visited += root

      utility.Nodes.referencedMembers(root, program) foreach (m =>
        if (!visited.contains(m)) toVisit += m)
    }
  }

  def desugareSourceSyntax(source: Forall): Seq[Forall] = {
    val vars = source.variables
    val triggers = source.triggers

    source match {
      case SourceQuantifiedPermissionAssertion(_, cond, rhs) =>
        /* Source forall denotes a quantified permission assertion that potentially
         * needs to be desugared
         */

        rhs match {
          case And(e0, e1) =>
            /* RHS is a conjunction; split into two foralls and recursively rewrite these */

            val foralls0 =
              desugareSourceSyntax(
                Forall(
                  vars,
                  triggers,
                  Implies(cond, e0)(rhs.pos, rhs.info)
                )(source.pos, source.info))

            val foralls1 =
              desugareSourceSyntax(
                Forall(
                  vars,
                  triggers,
                  Implies(cond, e1)(rhs.pos, rhs.info)
                )(source.pos, source.info))

            foralls0 ++ foralls1

          case Implies(e0, e1) =>
            /* RHS is an implication; pull its LHS out and rewrite the resulting forall */

            val newCond = And(cond, e0)(cond.pos, cond.info)

            val newRhs =
              Forall(
                vars,
                triggers,
                Implies(newCond, e1)(rhs.pos, rhs.info)
              )(source.pos, source.info)

            desugareSourceSyntax(newRhs)

          case _ =>
            /* RHS does not need to be desugared (any further) */
            Seq(source)
        }

      case _ =>
        /* Source forall does not denote a quantified permission assertion;
         * no desugaring necessary
         */
        Seq(source)
    }
  }
}
