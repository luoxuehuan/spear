package scraper.expressions

import scala.util.{Success, Try}

import scraper.trees.TreeNode
import scraper.types.DataType
import scraper.{ExpressionUnevaluable, ExpressionUnresolved, Row}

trait Expression extends TreeNode[Expression] {
  def foldable: Boolean = children.forall(_.foldable)

  def nullable: Boolean = true

  def resolved: Boolean = children.forall(_.resolved)

  def references: Seq[Attribute] = children.flatMap(_.references)

  def dataType: DataType

  def evaluate(input: Row): Any

  def evaluated: Any = evaluate(null)

  def childrenTypes: Seq[DataType] = children.map(_.dataType)

  def strictlyTyped: Try[this.type]

  protected def whenStrictlyTyped[T](value: => T): T = (
    for (e <- strictlyTyped if e sameOrEqual this) yield value
  ).get

  def +(that: Expression): Expression = Add(this, that)

  def -(that: Expression): Expression = Minus(this, that)

  def *(that: Expression): Expression = Multiply(this, that)

  def ===(that: Expression): Eq = Eq(this, that)

  def !==(that: Expression): NotEq = NotEq(this, that)

  /** Equivalent to [[===]].  Useful for avoiding name collision with ScalaTest. */
  def =:=(that: Expression): Eq = this === that

  /** Equivalent to [[!==]].  Useful for avoiding name collision with ScalaTest. */
  def =/=(that: Expression): NotEq = this !== that

  def as(alias: String): Alias = Alias(alias, this)

  def as(alias: Symbol): Alias = Alias(alias.name, this)

  def cast(dataType: DataType): Cast = Cast(this, dataType)
}

trait LeafExpression extends Expression {
  override def children: Seq[Expression] = Seq.empty

  override lazy val strictlyTyped: Try[this.type] = Success(this)
}

trait UnaryExpression extends Expression {
  def child: Expression

  override def children: Seq[Expression] = Seq(child)
}

trait BinaryExpression extends Expression {
  def left: Expression

  def right: Expression

  override def children: Seq[Expression] = Seq(left, right)

  def nullSafeEvaluate(lhs: Any, rhs: Any): Any

  override def evaluate(input: Row): Any = {
    val maybeResult = for {
      lhs <- Option(left.evaluate(input))
      rhs <- Option(right.evaluate(input))
    } yield nullSafeEvaluate(lhs, rhs)

    maybeResult.orNull
  }
}

object BinaryExpression {
  def unapply(e: BinaryExpression): Option[(Expression, Expression)] = Some((e.left, e.right))
}

trait UnevaluableExpression extends Expression {
  override def evaluate(input: Row): Any = throw ExpressionUnevaluable(this)
}

trait UnresolvedExpression extends Expression with UnevaluableExpression {
  override def dataType: DataType = throw ExpressionUnresolved(this)

  override def resolved: Boolean = false
}
